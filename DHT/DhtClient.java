/** Client to cooperate with the server with dynamic hash table. 
 * 
 * Usage: DhtClient client_IP server command key value 
 * 
 * Function: The client sends a command to a remote DhtServer in a UDP and waits for reply packet. 
 * 
 * The first argument is client_IP address which is supposed to be bound to this client's port.
 * The second argument is a DhtServer configuration file containing the IP address and port number 
 * used by DhtServer. 
 * The third argument is the command to operate the client, which should be one of "get" or "put". 
 * The remaining arguments are optional arguments which are offered by user. 
 * 
 * The DhtClient uses the send and receive method of the Packet class
 * with the debug flag set to true, which will send all the packets and print the received ones.
 */
import java.io.*;
import java.net.*;
import java.util.*;
public class DhtClient{
	public static void main(String[] args){
		//if number of arguments <= 3
		//print error message and quit
		if(args.length < 3){
			System.out.println("ERROR: Incorrect number of arugments");
			System.out.println("Correct Usage: java SimpleDhtClient myAddr server option [key] [val]");
			System.exit(1);
		}

		//create socket and read server's information
		InetAddress myAddr = null;
		InetSocketAddress serverAddr = null;
		DatagramSocket sock = null;
		try{
			//read my address
			myAddr = InetAddress.getByName(args[0]);
			//create socket using myAddr
			sock = new DatagramSocket(0,myAddr);
			//read configuration file
			BufferedReader readS = new BufferedReader(new InputStreamReader(new FileInputStream(args[1]),"US-ASCII"));
			String serverInfo = readS.readLine();
			//split[0] is server's ip and split[1] is port number
			String[] split = serverInfo.split(" ");
			String serverIp = split[0];
			Integer serverPort = Integer.parseInt(split[1]);
			//create server address
			serverAddr = new InetSocketAddress(serverIp, serverPort);
		}catch(Exception e){
			System.out.println("ERROR: Problem in reading configuration file");
			System.exit(1);
		}

		//read option type
		String type = args[2];
        //read key and val if exists
        String key = null;
        String val = null;
        if(args.length >= 4){
        	key = args[3];
        	if(args.length >= 5){
        		val = args[4];
        	}
        }

        //create packet and set values
        Packet pkt = new Packet();
        pkt.type = type;
        pkt.key = key;
        pkt.val = val;
        pkt.tag = 12345;
        //send packet
        pkt.send(sock, serverAddr, true);

        //receive packet
        Packet rcvPkt = new Packet();
        rcvPkt.receive(sock, true);
	}
}
