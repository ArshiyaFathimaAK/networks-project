package udp_Testing;

import java.io.*;
import java.net.*;

class sourceTCPServer {

  public static void main(String argv[]) throws Exception
    {
      String clientSentence;
      String capitalizedSentence;

      ServerSocket welcomeSocket = new ServerSocket(6789);

      while(true) {

            System.out.println("Server is UP and running!");
            Socket connectionSocket = welcomeSocket.accept();

           BufferedReader inFromClient = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));

           DataOutputStream  outToClient =
             new DataOutputStream(connectionSocket.getOutputStream());

           clientSentence = inFromClient.readLine();

            System.out.println("Server received message!" + clientSentence);
           capitalizedSentence = clientSentence.toUpperCase() + '\n';

           outToClient.writeBytes(capitalizedSentence);
        }
    }
}
