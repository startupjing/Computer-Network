/**
 * Author: Jing Lu
 * Date: 09/15/2014
 *
 * A server that stores string pairs and waits for TCP requests
 *
 * To use the TcpMapServer, type java MapServer [address] [portNumber]
 * Note: 
 * if [address] is not provided, the server will use wildcard address
 * if [portNumber] is not provided, the server will use default value 30123
 *
 * The server waits for TCP requests to add or retrieve string pairs (str1, str2)
 * stored in the HashMap by the server
 *
 * The server will accept TCP packets with a payload of ASCII strings with command
 * (get, get all, put, remove). The colon character is used as a delimiter.
 * The commands are formatted as follow:
 * get: the key string
 * get all
 * put: key string : corresponding value
 * remove: key string
 *
 * The server will reponse with the following payloads
 * Command     If Found      Payload
 *  get          yes         ok:val     
 *               no          no match
 *  get all      yes         key1:val1::key2:val2:: ... ::keyn:valn
 *               no          no match
 *  put          yes         updated: str
 *               no          ok
 * remove        yes         ok
 *               no          no match
 * If the server receives a packet that is not well-formed, it will reply
 * error: unrecognizable input: copy of the input's packet payload
 *
 **/


import java.io.*;
import java.net.*;
import java.util.*;

public class TcpMapServer{
	public static void main(String args[]) throws Exception {
		//default port number and wildcard adress
		int serverPort = 30123;
		InetAddress serverAddr = null;

		//create a hashmap to store pairs
		HashMap<String, String> pairs = new HashMap<String, String>();

		//if server address provided, use the given address
		if(args.length > 0){
			serverAddr = InetAddress.getByName(args[0]);
		}

		//if port number provided, use the given value
		if(args.length > 1){
			serverPort = Integer.parseInt(args[1]);
		}

		//open and bind the stream socket
		ServerSocket listenSock = new ServerSocket(serverPort, 0, serverAddr);

        //waiting for connections
		while(true){
			//create connection socket
			Socket connSock = listenSock.accept();

            //create buffers for input and output stream
			BufferedReader in = new BufferedReader(new InputStreamReader(connSock.getInputStream()));
			BufferedWriter out = new BufferedWriter(new OutputStreamWriter(connSock.getOutputStream()));
            
            String command;

            //if reading a nonempty line, process the command
			while((command = in.readLine()) != null){
				String reply = "";

                //split the command
				String[] split = command.split(":", 2);

                //if length is 2, either "get all" command or invalid input
				if(split.length != 2){
					if(split[0].equals("get all")){
						if(!pairs.isEmpty()){
							int count = 0;

							//building reply message
							for(String key: pairs.keySet()){
								count ++;
								if(count != pairs.size()){
									reply += key + ":" + pairs.get(key) + "::";
								}else{
									reply += key + ":" + pairs.get(key);
								}
							}
						}else{
							reply = "no match";
						}
					}else{
        		        reply = "error:unrecognizable input:"+command;
        		    }
        	    }else{
        		    String cmdName = split[0];
			        //check command name and perform corresponding operation
        		    if(cmdName.equals("get")){
			            String ans = pairs.get(split[1]);
        			    if(ans != null){
        				    reply = "ok:" + ans;
        			    }else{
        				    reply = "no match";
        			    }
        		    }else if(cmdName.equals("put")){
        		    	//split the arguments for put command
        			    String[] splitParam = split[1].split(":",2);
				        if(splitParam.length == 2){
			                //if the key is already in the hashmap, update the value
        			        if(pairs.containsKey(splitParam[0])){
        				       pairs.remove(splitParam[0]);
        				       pairs.put(splitParam[0], splitParam[1]);
        				       reply = "updated:" + splitParam[0];
        			        }else{
        				       pairs.put(splitParam[0], splitParam[1]);
        				       reply = "ok";
        			        }
					    }else{
					        reply = "error:unrecognizable input:"+command;
					    }
        		     }else if(cmdName.equals("remove")){
        			    if(pairs.containsKey(split[1])){
        				   pairs.remove(split[1]);
        				   reply = "ok";
        			    }else{
        				   reply = "no match";
        			   }
        		    }else{
        		       reply = "error:unrecognizable input:"+command;
        		    }	   
        	    }

        	    //write reply
        	    out.write(reply);
        	    out.newLine();
        	    out.flush();
			}
			//close connection
			connSock.close();

		}
	}
}