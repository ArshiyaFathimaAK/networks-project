import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

/**
 * MathClient is a command-line client for connecting to the MathServer.
 *
 * Features:
 *   - Prompts the user for a name and connects to the server using a TCP socket.
 *   - Handles the handshake protocol (CONNECT|<name> and expects ACK|SUCCESS).
 *   - Allows the user to enter arithmetic expressions, sends them to the server,
 *     and displays the results.
 *   - Supports clean disconnects (DISCONNECT command) and handles server responses.
 *   - Retries connection up to MAX_CONNECT_ATTEMPTS if the server is unavailable.
 */
public class MathClient {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5000;
    private static final int MAX_CONNECT_ATTEMPTS = 3;

    /**
     * Entry point for the MathClient application.
     *
     * Steps:
     *   1. Reads the server host/port from command-line args (or uses defaults).
     *   2. Prompts the user for their name (required for handshake).
     *   3. Connects to the server, retrying up to MAX_CONNECT_ATTEMPTS times if needed.
     *   4. Performs handshake: sends CONNECT|<name> and expects ACK|SUCCESS.
     *   5. Enters a loop where the user can input math expressions to send to the server.
     *   6. Handles 'exit' command to disconnect cleanly.
     *   7. Prints results or error messages from the server.
     */
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.print("Enter your name: ");
            String name = scanner.nextLine().trim();

            if (name.isEmpty()) {
                System.out.println("Name cannot be empty.");
                return;
            }

            try (Socket socket = connectWithRetries(host, port);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                // --- Handshake: send CONNECT|<name> and expect ACK|SUCCESS ---
                out.println("CONNECT|" + name);
                String response = in.readLine();

                if (response == null || !response.startsWith("ACK|SUCCESS")) {
                    System.out.println("Server rejected the connection request.");
                    return;
                }

                System.out.println("Connected to " + host + ":" + port + ".");
                System.out.println("Type a math expression using +, -, *, /, and parentheses.");
                System.out.println("Type 'exit' to close the connection.");

                // --- Main input loop: send expressions, handle results ---
                while (true) {
                    System.out.print("Enter a math calculation: ");
                    String input = scanner.nextLine().trim();

                    if (input.isEmpty()) {
                        System.out.println("Please enter a valid expression.");
                        continue;
                    }

                    if (input.equalsIgnoreCase("exit")) {
                        // Clean disconnect: notify server and exit loop
                        out.println("DISCONNECT");
                        String byeMessage = in.readLine();
                        System.out.println(byeMessage == null ? "Connection closed." : byeMessage);
                        break;
                    }

                    // Send math request to server
                    out.println("REQ|" + input);
                    String result = in.readLine();

                    if (result == null) {
                        System.out.println("Server closed the connection.");
                        break;
                    }

                    if (result.startsWith("RES|")) {
                        System.out.println("Result: " + result.substring(4));
                    } else {
                        System.out.println("Server response: " + result);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Client error: " + e.getMessage());
        }
    }

    /**
     * Attempts to connect to the server, retrying up to MAX_CONNECT_ATTEMPTS times.
     *
     * @param host The server hostname or IP address
     * @param port The server port
     * @return A connected Socket
     * @throws IOException if all attempts fail
     */
    private static Socket connectWithRetries(String host, int port) throws IOException {
        IOException lastError = null;

        for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS; attempt++) {
            try {
                return new Socket(host, port);
            } catch (IOException e) {
                lastError = e;
                System.out.println("Connection attempt " + attempt + " failed.");
            }
        }

        throw new IOException("Unable to connect to the server after " + MAX_CONNECT_ATTEMPTS + " attempts.", lastError);
    }
}
