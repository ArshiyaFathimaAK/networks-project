package udp_Testing;
import java.io.*;
import java.net.*;

public class sourceUDPClient {
	 public static void main(String args[]) throws Exception
	    {
	      System.out.println("Client is running!");
	      byte[] sendData = new byte[1024];
	      byte[] receiveData = new byte[1024];
	      DatagramSocket clientSocket = new DatagramSocket();


	      BufferedReader inFromUser =
	        new BufferedReader(new InputStreamReader(System.in));

	      String sentence = inFromUser.readLine();
	      sendData = sentence.getBytes();

	      InetAddress IPAddress = InetAddress.getByName("127.0.0.1");


	      DatagramPacket sendPacket =
	    	         new DatagramPacket(sendData, sendData.length, IPAddress, 9876);

	      clientSocket.send(sendPacket);

	      DatagramPacket receivePacket =
	    	         new DatagramPacket(receiveData, receiveData.length);

	      clientSocket.receive(receivePacket);

	    	      String modifiedSentence =
	    	          new String(receivePacket.getData());

	    	      System.out.println("FROM SERVER:" + modifiedSentence);
	    	      clientSocket.close();
	    	      }

}
