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
 * ClientInfo holds all relevant information about a connected client.
 * This includes:
 *   - The client's chosen name (from handshake)
 *   - The client's IP address and port (for identification)
 *   - The time the client connected (for session duration)
 *   - The underlying Socket object (for communication)
 * This object is created after a successful handshake and is used
 * throughout the session for logging and request tracking.
 */
class ClientInfo {
    final String name;      // Client's chosen name
    final String ip;        // Client's IP address
    final int port;         // Client's port number
    final long connectTime; // Timestamp when client connected
    final Socket socket;    // Socket for communication

    ClientInfo(String name, Socket socket) {
        this.name = name;
        this.socket = socket;
        this.ip = socket.getInetAddress().getHostAddress();
        this.port = socket.getPort();
        this.connectTime = System.currentTimeMillis();
    }
}

/**
 * Request represents a single math calculation sent by a client.
 * It contains:
 *   - The raw arithmetic expression string
 *   - The ClientInfo object for the sender
 *   - The timestamp when the request was received (for logging and ordering)
 * Requests are enqueued and processed in FIFO order to ensure fairness.
 */
class Request {
    final String expression;   // The math expression to evaluate
    final ClientInfo client;   // The client who sent the request
    final long receivedTime;   // When the request was received

    Request(String expression, ClientInfo client) {
        this.expression = expression;
        this.client = client;
        this.receivedTime = System.currentTimeMillis();
    }
}

/**
 * MathServer is a multi-client TCP server for evaluating arithmetic expressions.
 *
 * High-level architecture:
 *  - The main thread listens for TCP connections on a configurable port.
 *  - Each client connection is handled by a dedicated thread (handleClient),
 *    which manages handshake, request reading, and disconnects.
 *  - All client requests are placed into a shared blocking queue (REQUEST_QUEUE).
 *  - A single background worker thread (processRequests) processes requests
 *    from the queue in FIFO order, evaluates the math, and sends results back.
 *    This guarantees that requests are handled in the order received, even
 *    across multiple clients (project fairness requirement).
 *  - All major events (connect, request/result, disconnect) are logged to
 *    "logging.txt" in a thread-safe way.
 *
 * Protocol (plain text, newline-delimited):
 *  Client → Server  |  Server → Client
 *  CONNECT|<name>   |  ACK|SUCCESS|...  or  ACK|FAIL|...
 *  REQ|<expression> |  RES|<result>     or  RES|ERROR|...
 *  DISCONNECT       |  BYE|...
 */
public class MathServer {

    private static final int DEFAULT_PORT = 5000;

    // Shared FIFO queue for all client requests.
    // Decouples client threads from the single request-processing worker.
    // LinkedBlockingQueue is thread-safe and blocks the worker when empty.
    private static final BlockingQueue<Request> REQUEST_QUEUE = new LinkedBlockingQueue<>();
    
    // Mutex for serializing log file writes, so log entries from different threads don't interleave.
    private static final Object LOG_LOCK = new Object();
    // Formatter for timestamps in logs.
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Formatter for results: trims trailing zeros, keeps up to 10 decimal places.
    private static final DecimalFormat RESULT_FORMAT = new DecimalFormat("0.##########");
    // Writer for the log file.
    private static PrintWriter logWriter;

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // Entry point: starts the server and worker thread
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
                    // Each client gets a new thread for handling handshake, requests, and disconnects.
                    new Thread(() -> handleClient(socket), "client-" + socket.getPort()).start();
                }
            }
        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Client handler — runs on a per-client thread
    // -------------------------------------------------------------------------

    /**
     * Handles the full lifecycle of a single client connection:
     *   1. Handshake: expects "CONNECT|<name>" from client, replies with ACK.
     *   2. Request loop: accepts "REQ|<expr>" messages (enqueues them for processing),
     *      or "DISCONNECT" to end the session cleanly.
     *   3. Cleanup: always logs disconnect, even if the client drops unexpectedly.
     *
     * @param socket The socket for the connected client
     */
    private static void handleClient(Socket socket) {
        ClientInfo client = null;
        boolean disconnectLogged = false;

        try (Socket clientSocket = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            // --- Handshake: expect CONNECT|<name> ---
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

            // --- Request loop: handle REQ|<expr> and DISCONNECT ---
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
                    // Any other message is considered an error.
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
     * Continuously processes requests from the shared queue.
     * For each request:
     *   1. Evaluates the arithmetic expression.
     *   2. Logs the expression and result.
     *   3. Sends "RES|<result>" back to the originating client.
     * This ensures strict FIFO ordering across all clients.
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
     * Validation:
     *   - Rejects null/empty input
     *   - Rejects any characters outside the whitelist [0-9 + - * / ( ) . space]
     *     (prevents code injection or invalid input)
     *
     * Evaluation:
     *   - Uses a recursive-descent ExpressionParser for correct operator precedence
     *
     * @param expression The arithmetic expression as a string
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
     * Calculates how long the client was connected and writes a DISCONNECT entry to the log.
     * @param client The client that disconnected
     */
    private static void logDisconnect(ClientInfo client) {
        long durationMillis = System.currentTimeMillis() - client.connectTime;
        String durationText = formatDuration(durationMillis);
        logEvent("DISCONNECT", client, "Client disconnected from server.", durationText);
    }

    /**
     * Converts a millisecond duration into a human-readable string (e.g. "2m 5s 123ms").
     * @param durationMillis Duration in milliseconds
     * @return Formatted duration string
     */
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
     * ExpressionParser is a hand-written recursive-descent parser for evaluating
     * basic arithmetic expressions. It supports +, -, *, /, parentheses, and decimals.
     *
     * Grammar (in order of increasing precedence):
     *   expression → term  (('+' | '-') term)*
     *   term       → factor (('*' | '/') factor)*
     *   factor     → ['+' | '-'] '(' expression ')' | number
     *
     * The grammar ensures correct operator precedence:
     *   - Multiplication/division bind tighter than addition/subtraction
     *   - Parentheses override precedence
     */
    private static class ExpressionParser {
        private final String input;
        private int position = -1;
        private int currentChar;

        ExpressionParser(String input) {
            this.input = input;
        }

        /**
         * Starts parsing and ensures the entire input is consumed.
         * @return The evaluated result as a double
         */
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
         * Consumes the given character if it matches the current character (after skipping whitespace).
         * @param charToEat The character to match
         * @return true if matched and consumed, false otherwise
         */
        private boolean eat(int charToEat) {
            skipWhitespace();
            if (currentChar == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        /**
         * Parses and evaluates addition and subtraction (lowest precedence).
         * This is the entry point for the parser.
         */
        private double parseExpression() {
            double value = parseTerm(); // Handles higher-precedence * and / first
            while (true) { 
                if (eat('+')) {
                    value += parseTerm(); // Add next term
                } else if (eat('-')) {
                    value -= parseTerm(); // Subtract next term
                } else {
                    return value; // No more + or -
                }
            }
        }

        /**
         * Parses multiplication and division (higher precedence than + and -).
         * Throws ArithmeticException for division by zero.
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
         * Parses the highest-precedence elements: unary +/-, parentheses, and numbers.
         * Handles unary operators recursively. Numbers are parsed as doubles.
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
                // Parenthesized sub-expression: recurse to parseExpression
                value = parseExpression();
                if (!eat(')')) {
                    throw new IllegalArgumentException("Missing closing parenthesis");
                }
            } else if ((currentChar >= '0' && currentChar <= '9') || currentChar == '.') {
                // Parse number (digits and decimal point)
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
