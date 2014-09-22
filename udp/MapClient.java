import java.io.*;
import java.net.*;

/**
 * Author: Jing Lu
 * Date: 09/06/2014
 *
 * A client that put,get,remove string pairs to the server and receive response
 *
 * To use MapClient.java, type java MapClient [addr] [port] [cmd] [arg1] [arg2] ...
 * 
 * The client will send the command in a UDP packet to the remote server
 * and print the response from the server
 *
 * The commands that the client can do:
 * get key: get the corresponding value according to the key
 * Print "no match" if key is not found
 *
 * put key val: add a new pair (key,val) 
 * If the key already exists, update it with the new value
 *
 * remove key: remove the pair (key,val)
 * If the key is not found, print "no match"
 *
 **/

public class MapClient {
    public static void main(String args[]) throws Exception {
		//decide if there is command information
        if(args.length < 3){
            System.out.println("No command information found");
        	System.exit(1);
        }

        //get server address, server port number and command
        InetAddress serverAddr = InetAddress.getByName(args[0]);
        int serverPort = Integer.parseInt(args[1]);
        String command = args[2];

        //add additional parameters if necessary
        if(args.length > 3){
        	command = command + ":" + args[3];
        }

	    if(args.length > 4){
		    command += ":";
		    //the case when the value in the pair (key, val) 
		    //contains multiple words
		    command += args[4];
		    for(int i=5; i<args.length; i++){
        	     command = command + " " + args[i];
		    }
        }
		
        //open socket
        DatagramSocket sock = new DatagramSocket();

        //build packet addressed to server, then send the packet
        byte[] outBuf = command.getBytes("US-ASCII");
        DatagramPacket outPkt = new DatagramPacket(outBuf, outBuf.length, serverAddr, serverPort);
        sock.send(outPkt);

        //create buffer and packet for reply, and receive response
        byte[] inBuf = new byte[3000];
        DatagramPacket inPkt = new DatagramPacket(inBuf, inBuf.length);
        sock.receive(inPkt);

        //print buffer contents
        String reply = new String(inBuf,0,inPkt.getLength(),"US-ASCII");
        System.out.println(reply);

        //close socket
        sock.close();

	}
}