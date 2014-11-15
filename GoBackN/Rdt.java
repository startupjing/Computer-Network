/** Reliable Data Transport class.
 *
 *  This class implements a reliable data transport service.
 *  It uses a go-back-N sliding window protocol on a packet basis.
 *
 *  An application layer thread provides new packet payloads to be
 *  sent using the provided send() method, and retrieves newly arrived
 *  payloads with the receive() method. Each application layer payload
 *  is sent as a separate UDP packet, along with a sequence number and
 *  a type flag that identifies a packet as a data packet or an
 *  acknowledgment. The sequence numbers are 15 bits.
 */

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Rdt implements Runnable {
	private int wSize;	// protocol window size
	private long timeout;	// retransmission timeout in ns
	private Substrate sub;	// Substrate object for packet IO

	private ArrayBlockingQueue<String> fromSrc;
	private ArrayBlockingQueue<String> toSnk;

	// Sending structures and necessary information
	private Packet[] sendBuf; // not yet acked packets
	private short sendBase = 0;	// seq# of first packet in send window
	private short sendSeqNum = 0;	// next seq# after send window
	private short dupAcks = 0; // should only happen for sendBase-1 packet

	// Receiving structures and necessary information
	private Packet[] recvBuf; // undelivered packets
	private short recvBase = 0;  // seq# of oldest undelivered packet (to application)
	private short expSeqNum = 0;	// seq# of packet we expect to receive (from substrate)
	private short lastRcvd = -1; // last packet received properly

	// Time keeping variabels
	private long now = 0;		// current time (relative to t0)
	private long sendAgain = 0;	// time when we send all unacked packets

	private Thread myThread;
	private boolean quit;

	/** Initialize a new Rdt object.
	 *  @param wSize is the window size used by protocol; the sequence #
	 *  space is twice the window size
	 *  @param timeout is the time to wait before retransmitting
	 *  @param sub is a reference to the Substrate object that this object
	 *  uses to handle the socket IO
	 */
	Rdt(int wSize, double timeout, Substrate sub) 
	{
		this.wSize = Math.min(wSize,(1 << 14) - 1);
		this.timeout = ((long) (timeout * 1000000000)); // sec to ns
		this.sub = sub;

		// create queues for application layer interface
		fromSrc = new ArrayBlockingQueue<String>(1000,true);
		toSnk = new ArrayBlockingQueue<String>(1000,true);
		quit = false;

		sendBuf = new Packet[2*wSize];
		recvBuf = new Packet[2*wSize];
	}

	/** Start the Rdt running. */
	public void start() throws Exception {
		myThread = new Thread(this); myThread.start();
	}

	/** Stop the Rdt.  */
	public void stop() throws Exception { quit = true; myThread.join(); }

	/** Increment sequence number, handling wrap-around.
	 *  @param x is a sequence number
	 *  @return next sequence number after x
	 */
	private short incr(short x) {
		x++; return (x < 2*wSize ? x : 0);
	}
    
    /** Decrement sequence number, handling wrap-around.
	 *  @param x is a sequence number
	 *  @return sequence number before x
	 */
	private short decr(short x) {
		x--; return (short)(x<0 ? 2*wSize-1 : x);
	}

	/** Compute the difference between two sequence numbers,
	 *  accounting for "wrap-around"
	 *  @param x is a sequence number
	 *  @param y is another sequence number
	 *  @return difference, assuming x is "clockwise" from y
	 */
	private int diff(short x, short y) {
		return (x >= y ? x-y : (x + 2*wSize) - y);
	}

	/** Main thread for the Rdt object.
	 *
	 *  Inserts payloads received from the application layer into
	 *  packets, and sends them to the substrate. The packets include
	 *  the number of packets and chars sent so far (including the
	 *  current packet). It also takes packets received from
	 *  the substrate and sends the extracted payloads
	 *  up to the application layer. To ensure that packets are
	 *  delivered reliably and in-order, using a sliding
	 *  window protocol with the go-back-N feature.
	 */
	public void run(){
		long t0 = System.nanoTime();
		long now = 0;		// current time (relative to t0)
		//stopTimer is used to stop the timer when sendBuf is empty
		//and host is still waiting for packets to arrive
		boolean stopTimer = false;
		boolean firstTime = false;
		//enableDupAck is used to determine if duplicate acks
		//functionality is turned on/off
		boolean enableDupAck = true;

		while (!quit || sendBuf[sendBase]!=null ) {
			now = System.nanoTime() - t0;
			// if receive buffer has a packet that can be
			//    delivered, deliver it to sink
            if(recvBuf[recvBase] != null){
            	//get the packet from receive buffer
            	Packet p = recvBuf[recvBase];
                //deliver the packet to sink and increment recvBase
            	try{ 
            	    toSnk.put(p.payload);
            	    recvBuf[recvBase] = null;
            	    recvBase = incr(recvBase);
            	}catch(InterruptedException e){
            		e.printStackTrace();
            	}
            }

			// else if the substrate has an incoming packet
			//      get the packet from the substrate and process it
			// 	if it's a data packet, ack it and add it
			//	   to receive buffer as appropriate
			//	if it's an ack, update the send buffer and
			//	   related data as appropriate
			//	   reset the timer if necessary
			else if(sub.incoming()){
                 Packet p = sub.receive();
                 //data packet
                 if(p.type == 0){
                 	 //packet arrive in order, store in recvBuf and send ack
                 	 if(p.seqNum == expSeqNum){ 	
                 	 	 recvBuf[expSeqNum] = p;
                 	 	 lastRcvd = expSeqNum;
                 	 	 //build ack packet
                 	 	 Packet pAck = new Packet();
                 	     pAck.type = 1;
                 	 	 pAck.seqNum = expSeqNum;
                 	 	 expSeqNum = incr(expSeqNum);
                 	 	 //turn on dupAck functionality
                 	 	 enableDupAck = true;
                 	 	 sub.send(pAck);
                 	 //packet not arrive in order, just drop it and send ack
                 	 }else if(lastRcvd != -1){
                 	 	 //build ack packet
                 	 	 Packet pAck = new Packet();
                 	 	 pAck.type = 1;
                 	 	 pAck.seqNum = lastRcvd;
                 	 	 sub.send(pAck);
                 	 }
                 //ack packet
                 }else if(p.type == 1){	 
                     //ack packet is a duplicate ack
                 	 if(p.seqNum == decr(sendBase)){
                 	 	 dupAcks ++;
                 	 	 //resend packets in the sendBuf if number of duplicate acks >=4
                 	 	 //(1 original ack and 3 duplicate acks) and functionality is turned on
                 	 	 if(dupAcks >= 4 && enableDupAck){
                 	 	 	 short temp = sendBase;
                 	 	 	 //resend all packets in the sendBuf
				             for(int i=1; i<=diff(sendSeqNum, sendBase); i++){
				             	  //sleep if sub is not ready
					              while (!sub.ready()) {
					              	 try{ 
								    		Thread.sleep(1);
						            	}catch(InterruptedException e){
						            		e.printStackTrace();
						            	}
					              }
					              sub.send(sendBuf[temp]);
					              temp = incr(temp);
				             }
				             //reset timer and dupAck
				             sendAgain = now + timeout;
				             dupAcks = 0;
				             //turn off dupAck functionality 
				             enableDupAck = false;
                 	 	 }
                 	 //seqNum of ack packet is in the range of sendBuf
                 	 //also, cumulative acks have been taken into consideration
                 	 }else if(diff(p.seqNum, sendBase) < wSize && sendBuf[p.seqNum] != null){
                 	 	 //reset dupAcks to zero because of receiving a different ack
                 	 	 dupAcks = 0;
                 	 	 //ack corresponding un-acked packets in sendBuf
                 	 	 while(sendBase != incr(p.seqNum)){
                 	 	 	  sendBuf[sendBase] = null;
                 	 	 	  sendBase = incr(sendBase);
                 	 	 }
                         //reset timer
                 	 	 sendAgain = now + timeout;
                 	 	 //stop the timer if sendBuf is empty
                 	 	 if(sendBuf[sendBase] == null){
                 	 	    stopTimer = true;           	 	
                 	     }  
                 	 }
                }

			}
			// else if the resend timer has expired, re-send all
			//      packets in the window and reset the timer
			else if((now >= sendAgain) && (sendBase != sendSeqNum) && !stopTimer){
				short temp = sendBase;
				//resend all packets in the sendBuf
				for(int i=1; i<=diff(sendSeqNum, sendBase); i++){
					//sleep if sub is not ready
					while (!sub.ready()) {
					   try{ 
				    		Thread.sleep(1);
		            	}catch(InterruptedException e){
		            		e.printStackTrace();
		            	}
					}
					sub.send(sendBuf[temp]);
					temp = incr(temp);
				}
				//reset timer
				sendAgain = now + timeout;
				//turn on duplicate ack functionality
				enableDupAck = true;
			}

			// else if there is a message from the source waiting
			//      to be sent and the send window is not full
			//	and the substrate can accept a packet
			//      create a packet containing the message,
			//	and send it, after updating the send buffer
			//	and related data
			else if(!fromSrc.isEmpty() && sub.ready() && diff(sendSeqNum,sendBase)<wSize){
				//build data packet
				Packet p = new Packet();
				p.type = 0;
				p.seqNum = sendSeqNum;
				p.payload = fromSrc.poll();
				//put packet into sendBuf
				sendBuf[sendSeqNum] = p;
				//start the timer if it is the first packet added to sendBuf
				if(!firstTime){
					firstTime = true;
					sendAgain = now + timeout;
				}
				sendSeqNum = incr(sendSeqNum);
				sub.send(p);
				//enable timer
				stopTimer = false;
				//reset timer after retransmission
				sendAgain = now + timeout;
			}
			// else nothing to do, so sleep for 1 ms
		    else{
		    	try{ 
		    		Thread.sleep(1);
            	}catch(InterruptedException e){
            		e.printStackTrace();
            	}
		    }
		}
	}

	/** Send a message to peer.
	 *  @param message is a string to be sent to the peer
	 */
	public void send(String message) {
		try {
			fromSrc.put(message);
			//System.err.println("put message into fromSrc");
			//System.err.println(fromSrc.size());
		} catch(Exception e) {
			System.err.println("Rdt:send: put exception" + e);
			System.exit(1);
		}
	}
		
	/** Test if Rdt is ready to send a message.
	 *  @return true if Rdt is ready
	 */
	public boolean ready() { return fromSrc.remainingCapacity() > 0; }

	/** Get an incoming message.
	 *  @return next message
	 */
	public String receive() {
		String s = null;
		try {
			s = toSnk.take();
		} catch(Exception e) {
			System.err.println("Rdt:send: take exception" + e);
			System.exit(1);
		}
		return s;
	}
	
	/** Test for the presence of an incoming message.
	 *  @return true if there is an incoming message
	 */
	public boolean incoming() { return toSnk.size() > 0; }
}
