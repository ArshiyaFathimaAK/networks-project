import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Holds metadata about a connected client, including their name,
 * IP address, port, connection time, and the underlying socket.
 * Created when a client successfully completes the handshake.
 */
class ClientInfo {
    final String name;
    final String ip;
    final int port;
    final long connectTime;
    final Socket socket;

    ClientInfo(String name, Socket socket) {
        this.name = name;
        this.socket = socket;
        this.ip = socket.getInetAddress().getHostAddress();
        this.port = socket.getPort();
        this.connectTime = System.currentTimeMillis();
    }
}

/**
 * Represents a math calculation request submitted by a client.
 * Bundles the raw expression string with the client who sent it
 * and the time it was received, for logging and ordering purposes.
 */
class Request {
    final String expression;
    final ClientInfo client;
    final long receivedTime;

    Request(String expression, ClientInfo client) {
        this.expression = expression;
        this.client = client;
        this.receivedTime = System.currentTimeMillis();
    }
}

/**
 * MathServer — a multi-client TCP server that evaluates arithmetic expressions.
 *
 * Architecture overview:
 *  - The main thread listens for incoming TCP connections on the configured port.
 *  - Each accepted client gets its own dedicated thread (handleClient).
 *  - Client threads validate the connection handshake, then read incoming requests
 *    and drop them into a shared blocking queue.
 *  - A single background worker thread (processRequests) drains the queue in FIFO
 *    order, evaluates each expression, and sends the result back to the client.
 *    This ensures clients are served in the order their requests arrived, satisfying
 *    requirement #5 of the project spec.
 *  - All significant events (connect, request/result, disconnect) are written to
 *    "logging.txt" in a thread-safe manner.
 *
 * Protocol (plain text, newline-delimited):
 *  Client → Server  |  Server → Client
 *  CONNECT|<name>   |  ACK|SUCCESS|...  or  ACK|FAIL|...
 *  REQ|<expression> |  RES|<result>     or  RES|ERROR|...
 *  DISCONNECT       |  BYE|...
 */
public class MathServer {

    private static final int DEFAULT_PORT = 5000;

    // Shared FIFO queue that decouples client I/O threads from the single
    // request-processing worker. LinkedBlockingQueue is thread-safe and blocks
    // the worker when empty, avoiding busy-waiting.
    private static final BlockingQueue<Request> REQUEST_QUEUE = new LinkedBlockingQueue<>();
    
    // Mutex used to serialise writes to the log file so entries from
    // concurrent client threads don't interleave.
    private static final Object LOG_LOCK = new Object();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Formats doubles cleanly: drops unnecessary trailing zeros but keeps up
    // to 10 decimal places of precision (e.g. 3.5 instead of 3.5000000000).
    private static final DecimalFormat RESULT_FORMAT = new DecimalFormat("0.##########");
    private static PrintWriter logWriter;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        // Open the log file in append mode so previous runs are preserved.
        try {
            logWriter = new PrintWriter(new FileWriter("logging.txt", true), true);

            // Start the single request-processing worker as a daemon thread so
            // it doesn't prevent JVM shutdown when the main thread exits.
            Thread requestWorker = new Thread(MathServer::processRequests, "request-worker");
            requestWorker.setDaemon(true);
            requestWorker.start();

            // Accept client connections indefinitely; each gets its own thread.
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("MathServer is listening on port " + port + ".");

                while (true) {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> handleClient(socket), "client-" + socket.getPort()).start();
                }
            }
        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }

    // Client handler — runs on a per-client thread

    /**
     * Manages the full lifecycle of one client connection:
     *   1. Handshake  — expects "CONNECT|<name>", replies ACK.
     *   2. Request loop — accepts "REQ|<expr>" messages and enqueues them,
     *                     or "DISCONNECT" to cleanly end the session.
     *   3. Cleanup    — ensures a disconnect event is always logged, even if
     *                   the client dropped unexpectedly (no DISCONNECT sent).
     */

    private static void handleClient(Socket socket) {
        ClientInfo client = null;
        boolean disconnectLogged = false;

        try (Socket clientSocket = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            // --- Handshake ---
            String msg = in.readLine();
            if (msg == null || !msg.startsWith("CONNECT|")) {
                out.println("ACK|FAIL|Invalid connection request");
                return;
            }

            String[] parts = msg.split("\\|", 2);
            String name = parts.length > 1 ? parts[1].trim() : "Unknown";

            if (name.isEmpty()) {
                out.println("ACK|FAIL|Name is required");
                return;
            }

            client = new ClientInfo(name, clientSocket);
            System.out.println("Client connected: " + client.name + " from " + client.ip + ":" + client.port);
            logEvent("CONNECT", client, "Successful connection established.", null);
            out.println("ACK|SUCCESS|Connected to MathServer");

            // --- Request loop ---
            // Reads lines from the client until the connection closes or a
            // DISCONNECT command is received.
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("REQ|")) {
                    // Strip the "REQ|" prefix and queue the raw expression.
                    // The worker thread will evaluate it and send back the result.
                    String expr = line.substring(4).trim();
                    System.out.println("Queued request from " + client.name + " (" + client.ip + ":" + client.port + "): " + expr);
                    REQUEST_QUEUE.put(new Request(expr, client));
                } else if (line.equalsIgnoreCase("DISCONNECT")) {
                    // Client requested a clean shutdown — reply, log, and exit the loop.
                    out.println("BYE|Connection with server terminated");
                    logDisconnect(client);
                    disconnectLogged = true;
                    System.out.println("Client disconnected: " + client.name + " from " + client.ip + ":" + client.port);
                    break;
                } else {
                    out.println("ERROR|Unknown request format");
                }
            }
        } catch (Exception e) {
            if (client != null) {
                System.out.println("Client session error for " + client.name + ": " + e.getMessage());
            }
        } finally {
            // Guarantee a disconnect log entry even when the client drops without
            // sending DISCONNECT (e.g. network failure or abrupt process kill).
            if (client != null && !disconnectLogged) {
                logDisconnect(client);
            }
        }
    }

     // -------------------------------------------------------------------------
    // Request worker — runs on a single background thread
    // -------------------------------------------------------------------------
 
    /**
     * Continuously drains the shared request queue.
     * Processes one request at a time to honour FIFO ordering across all clients
     * (project requirement #5). For each request it:
     *   1. Evaluates the arithmetic expression.
     *   2. Logs the expression and result.
     *   3. Sends "RES|<result>" back to the originating client's socket.
     */
    private static void processRequests() {
        while (true) {
            try {
                // Blocks here when the queue is empty — no CPU spin.
                Request req = REQUEST_QUEUE.take();
                String result = evaluateExpression(req.expression);

                logEvent("REQUEST", req.client,
                        "Expression: " + req.expression + System.lineSeparator() + "Result: " + result,
                        null);

                // Write the result directly to the client's socket output stream.
                PrintWriter out = new PrintWriter(req.client.socket.getOutputStream(), true);
                out.println("RES|" + result);
            } catch (Exception e) {
                System.out.println("Request processing error: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Expression evaluation
    // -------------------------------------------------------------------------
 
    /**
     * Validates and evaluates a plain-text arithmetic expression.
     *
     * Validation rejects null/empty input and anything containing characters
     * outside the whitelist [0-9 + - * / ( ) . space], preventing injection.
     * Evaluation is delegated to the recursive-descent ExpressionParser.
     *
     * @return The formatted numeric result, or an "ERROR|..." string on failure.
     */
    private static String evaluateExpression(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return "ERROR|Empty expression";
        }

        // Whitelist check — only allow digits, basic operators, parentheses,
        // decimal points, and spaces. Anything else is rejected outright.
        if (!expression.matches("^[0-9+\\-*/().\\s]+$")) {
            return "ERROR|Invalid characters in expression";
        }

        try {
            double value = new ExpressionParser(expression).parse();
            return RESULT_FORMAT.format(value);
        } catch (ArithmeticException e) {
            return "ERROR|" + e.getMessage();
        } catch (Exception e) {
            return "ERROR|Invalid math expression";
        }
    }

     // -------------------------------------------------------------------------
    // Logging helpers
    // -------------------------------------------------------------------------
 
    /**
     * Calculates how long the client was connected and writes a DISCONNECT entry.
     */
    private static void logDisconnect(ClientInfo client) {
        long durationMillis = System.currentTimeMillis() - client.connectTime;
        String durationText = formatDuration(durationMillis);
        logEvent("DISCONNECT", client, "Client disconnected from server.", durationText);
    }

    /** Converts a millisecond duration into a human-readable "Xm Ys Zms" string. */
    private static String formatDuration(long durationMillis) {
        Duration duration = Duration.ofMillis(durationMillis);
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).toSeconds();
        long milliseconds = duration.minusMinutes(minutes).minusSeconds(seconds).toMillis();
        return minutes + "m " + seconds + "s " + milliseconds + "ms";
    }

    /**
     * Writes a structured log entry to "logging.txt".
     * Synchronized on LOG_LOCK to prevent concurrent threads from interleaving
     * their log output.
     *
     * @param type     Event category: CONNECT | REQUEST | DISCONNECT
     * @param client   The client associated with this event
     * @param details  Additional free-text details (expression, result, etc.)
     * @param duration Session duration string — only included for DISCONNECT events
     */
    private static void logEvent(String type, ClientInfo client, String details, String duration) {
        synchronized (LOG_LOCK) {
            logWriter.println("==================================================");
            logWriter.println("Event: " + type);
            logWriter.println("Client Name: " + client.name);
            logWriter.println("IP Address: " + client.ip);
            logWriter.println("Port Number: " + client.port);
            logWriter.println("Timestamp: " + LocalDateTime.now().format(TIME_FORMAT));
            if (duration != null) {
                logWriter.println("Duration Connected: " + duration);
            }
            if (details != null && !details.isBlank()) {
                logWriter.println(details);
            }
            logWriter.println();
            logWriter.flush();
        }
    }

    
    // -------------------------------------------------------------------------
    // Recursive-descent expression parser
    // -------------------------------------------------------------------------
 
    /**
     * A hand-written recursive-descent parser for basic arithmetic expressions
     * supporting +, -, *, /, parentheses, and decimal numbers.
     *
     * Grammar (in order of increasing precedence):
     *   expression → term  (('+' | '-') term)*
     *   term       → factor (('*' | '/') factor)*
     *   factor     → ['+' | '-'] '(' expression ')' | number
     *
     * The three-level grammar naturally enforces operator precedence:
     * multiplication and division bind tighter than addition and subtraction,
     * and parentheses override everything.
     */
    private static class ExpressionParser {
        private final String input;
        private int position = -1;
        private int currentChar;

        ExpressionParser(String input) {
            this.input = input;
        }

        /** Kicks off parsing and asserts the entire input was consumed. */
        double parse() {
            nextChar();
            double value = parseExpression();
            skipWhitespace();
            if (position < input.length()) {
                throw new IllegalArgumentException("Unexpected character");
            }
            return value;
        }

        /** Advances the read cursor by one character. */
        private void nextChar() {
            currentChar = (++position < input.length()) ? input.charAt(position) : -1;
        }

        /** Skips any spaces at the current position. */
        private void skipWhitespace() {
            while (currentChar == ' ') {
                nextChar();
            }
        }

        /**
         * Consumes the given character if it is the current character (after
         * skipping whitespace). Returns true on a match, false otherwise.
         */
        private boolean eat(int charToEat) {
            skipWhitespace();
            if (currentChar == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        // Parses and evaluates addition and subtraction operations.
        // This is the entry point of the recursive descent parser and handles
        // the LOWEST precedence operations (+ and -), meaning they are evaluated last.
        private double parseExpression() {
            double value = parseTerm(); // Start by parsing the first term (handles higher-precedence * and / first)
            // Loop continuously to handle chained operations e.g. 1 + 2 + 3 - 4
            while (true) { 
                if (eat('+')) { // If the next character is '+', consume it and add the next term 
                    value += parseTerm(); // e.g. value = 1, then 1+2=3, then 3+3=6
                 // If the next character is '-', consume it and subtract the next term
                } else if (eat('-')) {
                    value -= parseTerm(); // e.g. 6-4 = 2
                // No more '+' or '-' found, so we're done at this level — return the result
                } else {
                    return value;
                }
            }
        }

        /**
         * Parses multiplication and division — higher precedence than + and -.
         * Delegates to parseFactor() for individual operands, then loops to
         * consume chained * or / operators. Division by zero throws ArithmeticException.
         */
        private double parseTerm() {
            double value = parseFactor();
            while (true) {
                if (eat('*')) {
                    value *= parseFactor();
                } else if (eat('/')) {
                    double divisor = parseFactor();
                    if (Math.abs(divisor) < 1e-12) {
                        throw new ArithmeticException("Division by zero");
                    }
                    value /= divisor;
                } else {
                    return value;
                }
            }
        }

        /**
         * Parses the highest-precedence elements: unary +/-, parenthesised
         * sub-expressions, and literal numbers.
         * Unary operators are handled via recursion (eat '-' → negate result).
         * Numbers are parsed by accumulating digit/dot characters and handing
         * them to Double.parseDouble.
         */
        private double parseFactor() {
            if (eat('+')) {
                return parseFactor();
            }
            if (eat('-')) {
                return -parseFactor();
            }

            double value;
            int start = position;

            if (eat('(')) {
                // Parenthesised sub-expression: recurse all the way back to
                // parseExpression so the inner expression is fully evaluated first.
                value = parseExpression();
                if (!eat(')')) {
                    throw new IllegalArgumentException("Missing closing parenthesis");
                }
            } else if ((currentChar >= '0' && currentChar <= '9') || currentChar == '.') {
                // Consume all consecutive digit/dot characters, then parse the
                // resulting substring as a double.
                while ((currentChar >= '0' && currentChar <= '9') || currentChar == '.') {
                    nextChar();
                }
                value = Double.parseDouble(input.substring(start, position));
            } else {
                throw new IllegalArgumentException("Unexpected token");
            }

            return value;
        }
    }
}
