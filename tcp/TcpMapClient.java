/**
 * Author: Jing Lu
 * Date: 09/15/2014
 *
 * A client that put,get,remove string pairs to the server and receive response
 *
 * To use MapClient.java, type java MapClient serverAddress [port] [cmd] [arg1] [arg2] ...
 * 
 * The client will send the command in a TCP packet to the remote server
 * and print the response from the server
 *
 * The commands that the client can do:
 * get:key -- get the corresponding value according to the key
 * Print "no match" if key is not found
 *
 * get all -- get all pairs stored in the server
 * If no pair s currently stored, print "no match"
 *
 * put:key:val -- add a new pair (key,val) 
 * If the key already exists, update it with the new value
 *
 * remove:key -- remove the pair (key,val)
 * If the key is not found, print "no match"
 *
 **/

import java.io.*;
import java.net.*;

public class TcpMapClient {
	public static void main(String args[]) throws Exception {
        //default port number
		int serverPort = 30123;
        
        //print error message if serverName is missing
        if(args.length < 1){
        	System.out.println("Please type: java MapClient serverName [serverPort]");
        	System.exit(1);
        }

		//if port number is not provided, use default value 30123 
		if(args.length > 1){
			serverPort = Integer.parseInt(args[1]);
		}

		Socket sock = new Socket(args[0], serverPort);

        //create buffers for input and output stream
		BufferedReader in = new BufferedReader(new InputStreamReader(
			                sock.getInputStream(), "US-ASCII"));
		BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
			                sock.getOutputStream(), "US-ASCII"));

		//create buffered reader for System.in
		BufferedReader sysin = new BufferedReader(new InputStreamReader(
			                   System.in));

		String line;

		//keep reading commands until empty line
		while(true){
			System.out.println("Please enter the command: ");
			line = sysin.readLine();
			
			//if line is empty, break
			if(line == null || line.length()==0){
				break;
			}

            //write line on socket
			out.write(line);
			out.newLine();
			out.flush();

            //print reply to System.out
			System.out.println(in.readLine());

		}
        
        //close connection
		sock.close();

	}
}