package multithreading;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

class ClientInfo { // class for collecting client request information
    String name; // client name
    String ip; // client ip address
    int port; // client port number 
    long connectTime; // time for which client connected
    Socket socket; // client socket

    public ClientInfo(String name, Socket socket) { // initializing attributes upon receiving client request
        this.name = name;
        this.socket = socket;
        this.ip = socket.getInetAddress().toString();
        this.port = socket.getPort();
        this.connectTime = System.currentTimeMillis();
    }
}

class Request { // request class
    String expression; // math expression to evaluate/termination request
    ClientInfo client; // client request information (object)

    public Request(String expression, ClientInfo client) { // initializing request attributes
        this.expression = expression;
        this.client = client;
    }
}

public class MathServer {

    private static BlockingQueue<Request> queue = new LinkedBlockingQueue<>(); // queue for requests
    private static PrintWriter logWriter; // for logging values (subject to change, untested)

    public static void main(String[] args) throws Exception { // main server setup function
    	
        ServerSocket serverSocket = new ServerSocket(5000); // server socket allocate, port 5000
        System.out.println("##### - MathServer - allocated socket...");

        logWriter = new PrintWriter(new FileWriter("server.log", true), true); // for logging values (subject to change, untested)
        System.out.println("created log writer...");

        // Worker thread (FIFO processing) - untested
        new Thread(() -> processRequests()).start();

        System.out.println("created thread + Server running on port 5000...");

        while (true) { // when client connects to socket, handleClient is called for that client in a new thread
            Socket socket = serverSocket.accept();
            new Thread(() -> handleClient(socket)).start();
            System.out.println("accepting client on port 5000...");
        }
    }

    private static void handleClient(Socket socket) { // queuing client for sequential processing
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream())); // untested
            System.out.println("##### - HandleClient - buffered reader");
            
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true); // untested
            System.out.println("printwriter...");

            // CONNECT
            String msg = in.readLine(); // read in the provided message of client
            System.out.println("read in message...");

            if (msg == null || !msg.startsWith("CONNECT")) { // if the message is empty/does not start with "CONNECT", terminate connection and return
                System.out.println("termination message...");
                socket.close();
                return;
            }

            String name = msg.split("\\|")[1]; // extract name from message and store
            System.out.println("store name");
            
            ClientInfo client = new ClientInfo(name, socket); // create ClientInfo object for this client
            System.out.println("create new client info log file...");

            log("[CONNECT]", client, ""); // log info for current client (untested)
            System.out.println("start to log client info");

            out.println("ACK|SUCCESS|Connected"); // send to client ACK of successful connection

            while (true) { // when math request/termination request sent
                String line = in.readLine(); // read in the line
                System.out.println("read in line");
                
                if (line == null) break; // if the line is empty, break out of loop

                if (line.startsWith("REQ")) { // if line is a math request
                    System.out.println("this is a request");
                    String expr = line.split("\\|")[1]; // extract expression
                    queue.put(new Request(expr, client)); // queue the expression as new request object
                    System.out.println("queued request");
                }
                else if (line.equals("DISCONNECT")) { // if line is a termination request
                    System.out.println("terminate request detected");
                    out.println("BYE"); // send BYE to client
                    logDisconnect(client); // disconnect file log
                    System.out.println("client disconnected");
                    socket.close(); // close socket
                    System.out.println("socket closed");
                    break;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void processRequests() {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("JavaScript"); // untested

        while (true) {
            try {
                Request req = queue.take(); // take request from queue
                System.out.println("#### - processRequests - process request");

                String result;
                if (!req.expression.matches("^[0-9+\\-*/(). ]+$")) { // error validation
                    System.out.println("ERROR - expression does not match given character bank");
                    result = "ERROR|Invalid expression";
                } else {
                    try {
                        Object eval = engine.eval(req.expression); // math algorithm here (does not work)
                        System.out.println("evaluating expression");
                        result = eval.toString(); // convert result to string
                    } catch (Exception e) {
                        result = "ERROR|Evaluation failed"; // if evaluation fails, save result as error message
                    }
                }

                PrintWriter out = new PrintWriter(req.client.socket.getOutputStream(), true);
                // untested

                if (result.startsWith("ERROR")) { // if result is an error, print
                    out.println("RES|" + result);
                } else {
                    out.println("RES|" + result); // if result is not an error print (<- same output for both branches, is this necessary?)
                }

                log("[REQUEST]", req.client, req.expression + " = " + result); // log request (untested)
                System.out.println("logging request");

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // LOGGING IS UNTESTED
    private static void log(String type, ClientInfo client, String extra) {
        logWriter.println(type);
        logWriter.println("Name: " + client.name);
        logWriter.println("IP: " + client.ip);
        logWriter.println("Port: " + client.port);
        logWriter.println("Time: " + System.currentTimeMillis());
        if (!extra.isEmpty()) logWriter.println("Data: " + extra);
        logWriter.println();
    }

    private static void logDisconnect(ClientInfo client) {
        long duration = System.currentTimeMillis() - client.connectTime;

        logWriter.println("[DISCONNECT]");
        logWriter.println("Name: " + client.name);
        logWriter.println("IP: " + client.ip);
        logWriter.println("Port: " + client.port);
        logWriter.println("Duration(ms): " + duration);
        logWriter.println();
    }
}
