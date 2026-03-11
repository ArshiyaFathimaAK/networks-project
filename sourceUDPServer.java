package udp_Testing;
import java.io.*;
import java.net.*;

public class sourceUDPServer {
	public static void main(String args[]) throws Exception
    {

      DatagramSocket serverSocket = new DatagramSocket(9876);

      byte[] receiveData = new byte[1024];
      byte[] sendData  = new byte[1024];
      System.out.println("SERVER is running:");

      while(true)
        {

          DatagramPacket receivePacket =
             new DatagramPacket(receiveData, receiveData.length);
           serverSocket.receive(receivePacket);

           String sentence = new String(receivePacket.getData());
           System.out.println("MESSAGE FROM CLIENT:" + sentence);

           InetAddress IPAddress = receivePacket.getAddress();

           int port = receivePacket.getPort();

                       String capitalizedSentence = sentence.toUpperCase();

           sendData = capitalizedSentence.getBytes();

           DatagramPacket sendPacket =
              new DatagramPacket(sendData, sendData.length, IPAddress,
                                port);

           serverSocket.send(sendPacket);
         }
     }

}
