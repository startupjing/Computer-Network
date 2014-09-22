import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Author: Jing Lu
 * Date: 09/06/2014
 *
 * A server that stores string pairs and waits for requests
 *
 * To use the MapServer, type java MapServer [portNumber]
 * Note: if [portNumber] is not provided, the server will use default value 30123
 *
 * The server waits for UDP requests to add or retrieve string pairs (str1, str2)
 * stored in the HashMap by the server
 *
 * The server will accept UPD packets with a payload of ASCII strings with command
 * (get, put, remove). The colon character is used as a delimiter.
 * The commands are formatted as follow:
 * get: the key string
 * put: key string : corresponding value
 * remove: key string
 *
 * The server will reponse with the following payloads
 * Command     If Found      Payload
 *  get          yes         ok:val     
 *               no          no match
 *  put          yes         updated: str
 *               no          ok
 * remove        yes         ok
 *               no          no match
 * If the server receives a packet that is not well-formed, it will reply
 * error: unrecognizable input: copy of the input's packet payload
 *
 **/
 
public class MapServer {
	public static void main(String args[]) throws Exception {
        //check if additional port number argument is given for the server
		int serverPort = 30123;
        if(args.length > 0){
            serverPort = Integer.parseInt(args[0]);
        }

		//open datagram socket on port
		DatagramSocket sock = new DatagramSocket(serverPort);

		//create a hashmap to store pairs
		HashMap<String, String> pairs = new HashMap<String, String>();

		//create two packets sharing common buffer
		byte[] buf = new byte[3000];
		DatagramPacket inPkt = new DatagramPacket(buf, buf.length);
        DatagramPacket outPkt = new DatagramPacket(buf, buf.length);
	
        
        //always waiting for requests
        while(true){
        	//wait for incoming packet
        	inPkt.setData(buf);
        	sock.receive(inPkt);

            //read command info and split
        	String command = new String(buf, 0, inPkt.getLength(), "US-ASCII");
        	String[] split = command.split(":", 2);
        	String reply = "";

        	//check if command is well-formed, build reply message
        	if(split.length != 2){
        		reply += "error:unrecognizable input:"+command;
        	}else{
        		String cmdName = split[0];
				//check command name and perform corresponding operation
        		if(cmdName.equals("get")){
				    String ans = pairs.get(split[1]);
        			if(ans != null){
        				reply += "ok:" + ans;
        			}else{
        				reply += "no match";
        			}
        		}else if(cmdName.equals("put")){
        			String[] splitParam = split[1].split(":",2);
					if(splitParam.length == 2){
					   //if the key is already in the hashmap, update the value
        			   if(pairs.containsKey(splitParam[0])){
        				   pairs.remove(splitParam[0]);
        				   pairs.put(splitParam[0], splitParam[1]);
        				   reply += "updated:" + splitParam[0];
        			    }else{
        				   pairs.put(splitParam[0], splitParam[1]);
        				   reply += "ok";
        			    }
					}else{
					    reply += "error:unrecognizable input:"+command;
					}
        		}else if(cmdName.equals("remove")){
        			if(pairs.containsKey(split[1])){
        				pairs.remove(split[1]);
        				reply += "ok";
        			}else{
        				reply += "no match";
        			}
        		}else{
        		    reply += "error:unrecognizable input:"+command;
        		}
        	}
            
            //send packet
            outPkt.setAddress(inPkt.getAddress());
            outPkt.setPort(inPkt.getPort());
        	outPkt.setData(reply.getBytes("US-ASCII"));
        	sock.send(outPkt);

        }

	}
}