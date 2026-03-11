package udp_Testing;

import java.io.*;
import java.net.*;
class sourceTCPClient {

    public static void main(String argv[]) throws Exception
    {
        String sentence;
        String modifiedSentence;
        System.out.println("Client is running: " );

        Socket clientSocket = new Socket("127.0.0.1", 6789);

        BufferedReader inFromUser =
          new BufferedReader(new InputStreamReader(System.in));

        BufferedReader inFromServer =
                new BufferedReader(new
                InputStreamReader(clientSocket.getInputStream()));

        DataOutputStream outToServer =
          new DataOutputStream(clientSocket.getOutputStream());

            sentence = inFromUser.readLine();

            outToServer.writeBytes(sentence + '\n');

            modifiedSentence = inFromServer.readLine();

            System.out.println("FROM SERVER: " + modifiedSentence);

            clientSocket.close();

          }
      }
