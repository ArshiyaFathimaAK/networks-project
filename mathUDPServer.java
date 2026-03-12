package udp_Testing;
import java.io.*;
import java.net.*;

public class mathUDPServer {
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
           // output received message
           System.out.println("MESSAGE FROM CLIENT:" + sentence); 
           // get IP address of client
           InetAddress IPAddress = receivePacket.getAddress();
           // get port number
           int port = receivePacket.getPort();
           				// SERVER ACTION
				        /*
				         1. receive String e.g. "1*2"
				         2. Loop through string and find relevant characters
				         a) loop through UNTIL we reach an operator -> *
				         store length of first operand
				         find first operand substring
				         store index of operator
				         convert first operand into integer
				         b) Store operator character *;
				         c) start from index of operator (exclusive), loop through
				         string until \n
				         store length of second operand
				         find first operand substring
				         convert second operand into integer
				         d)  
				         (May have to do error checking + validation 
				         to ensure operands are both numbers)
				         (May have to do error checking + validation 
				         to ensure correct operators are being used)
				         3.
				         	if operator_char == "*", then perform multiplication
				         (switch statement for multiple operators)
				         4. store computation in int FinalAnswer.
				         
				         5. CANNOT DO THIS -> sendData = FinalAnswer.getBytes();
				         MUST turn output of operation back into a string
				         
				         */
           
           
                       String capitalizedSentence = sentence.toUpperCase();

           sendData = capitalizedSentence.getBytes();

           DatagramPacket sendPacket =
              new DatagramPacket(sendData, sendData.length, IPAddress,
                                port);

           serverSocket.send(sendPacket);
         }
     }

}
