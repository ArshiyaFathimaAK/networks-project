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

public class MathServer {

    private static final int DEFAULT_PORT = 5000;
    private static final BlockingQueue<Request> REQUEST_QUEUE = new LinkedBlockingQueue<>();
    private static final Object LOG_LOCK = new Object();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DecimalFormat RESULT_FORMAT = new DecimalFormat("0.##########");
    private static PrintWriter logWriter;

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        try {
            logWriter = new PrintWriter(new FileWriter("logging.txt", true), true);

            Thread requestWorker = new Thread(MathServer::processRequests, "request-worker");
            requestWorker.setDaemon(true);
            requestWorker.start();

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

    private static void handleClient(Socket socket) {
        ClientInfo client = null;
        boolean disconnectLogged = false;

        try (Socket clientSocket = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

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

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("REQ|")) {
                    String expr = line.substring(4).trim();
                    System.out.println("Queued request from " + client.name + " (" + client.ip + ":" + client.port + "): " + expr);
                    REQUEST_QUEUE.put(new Request(expr, client));
                } else if (line.equalsIgnoreCase("DISCONNECT")) {
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
            if (client != null && !disconnectLogged) {
                logDisconnect(client);
            }
        }
    }

    private static void processRequests() {
        while (true) {
            try {
                Request req = REQUEST_QUEUE.take();
                String result = evaluateExpression(req.expression);

                logEvent("REQUEST", req.client,
                        "Expression: " + req.expression + System.lineSeparator() + "Result: " + result,
                        null);

                PrintWriter out = new PrintWriter(req.client.socket.getOutputStream(), true);
                out.println("RES|" + result);
            } catch (Exception e) {
                System.out.println("Request processing error: " + e.getMessage());
            }
        }
    }

    private static String evaluateExpression(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return "ERROR|Empty expression";
        }

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

    private static void logDisconnect(ClientInfo client) {
        long durationMillis = System.currentTimeMillis() - client.connectTime;
        String durationText = formatDuration(durationMillis);
        logEvent("DISCONNECT", client, "Client disconnected from server.", durationText);
    }

    private static String formatDuration(long durationMillis) {
        Duration duration = Duration.ofMillis(durationMillis);
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).toSeconds();
        long milliseconds = duration.minusMinutes(minutes).minusSeconds(seconds).toMillis();
        return minutes + "m " + seconds + "s " + milliseconds + "ms";
    }

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

    private static class ExpressionParser {
        private final String input;
        private int position = -1;
        private int currentChar;

        ExpressionParser(String input) {
            this.input = input;
        }

        double parse() {
            nextChar();
            double value = parseExpression();
            skipWhitespace();
            if (position < input.length()) {
                throw new IllegalArgumentException("Unexpected character");
            }
            return value;
        }

        private void nextChar() {
            currentChar = (++position < input.length()) ? input.charAt(position) : -1;
        }

        private void skipWhitespace() {
            while (currentChar == ' ') {
                nextChar();
            }
        }

        private boolean eat(int charToEat) {
            skipWhitespace();
            if (currentChar == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (true) {
                if (eat('+')) {
                    value += parseTerm();
                } else if (eat('-')) {
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

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
                value = parseExpression();
                if (!eat(')')) {
                    throw new IllegalArgumentException("Missing closing parenthesis");
                }
            } else if ((currentChar >= '0' && currentChar <= '9') || currentChar == '.') {
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
