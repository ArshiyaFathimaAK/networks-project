package multithreading;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class MathClient {

    public static void main(String[] args) {
        try {
            Socket socket = new Socket("localhost", 5000);

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Scanner scanner = new Scanner(System.in);

            // Send CONNECT
            System.out.print("Enter your name: ");
            String name = scanner.nextLine();
            out.println("CONNECT|" + name);

            String response = in.readLine();
            if (!response.startsWith("ACK|SUCCESS")) {
                System.out.println("Connection failed.");
                socket.close();
                return;
            }

            System.out.println("Connected to server.");

            while (true) {
                System.out.print("Enter a math calculation (or 'exit'): ");
                String input = scanner.nextLine();

                if (input.equalsIgnoreCase("exit")) {
                    out.println("DISCONNECT");
                    System.out.println(in.readLine()); // BYE
                    socket.close();
                    break;
                }

                out.println("REQ|" + input);

                String res = in.readLine();
                System.out.println("Server response: " + res);
            }

        } catch (Exception e) {
            System.out.println("Connection error.");
            e.printStackTrace();
        }
    }
}
