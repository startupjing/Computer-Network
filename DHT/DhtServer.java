/** Server for simple distributed hash table that stores (key,value) strings.
 *  
 *  usage: DhtServer myIp numRoutes cfgFile [ cache ] [ debug ] [ predFile ]
 *  
 *  myIp    is the IP address to use for this server's socket
 *  numRoutes    is the max number of nodes allowed in the DHT's routing table;
 *          typically lg(numNodes)
 *  cfgFile    is the name of a file in which the server writes the IP
 *        address and port number of its socket
 *  cache    is an optional argument; if present it is the literal string
 *        "cache"; when cache is present, the caching feature of the
 *        server is enabled; otherwise it is not
 *  debug    is an optional argument; if present it is the literal string
 *        "debug"; when debug is present, a copy of every packet received
 *        and sent is printed on stdout
 *  predFile    is an optional argument specifying the configuration file of
 *        this node's predecessor in the DHT; this file is used to obtain
 *        the IP address and port number of the precessor's socket,
 *        allowing this node to join the DHT by contacting predecessor
 *  
 *  The DHT uses UDP packets containing ASCII text. Here's an example of the
 *  UDP payload for a get request from a client.
 *  
 *  CSE473 DHTPv0.1
 *  type:get
 *  key:dungeons
 *  tag:12345
 *  ttl:100
 *  
 *  The first line is just an identifying string that is required in every
 *  DHT packet. The remaining lines all start with a keyword and :, usually
 *  followed by some additional text. Here, the type field specifies that
 *  this is a get request; the key field specifies the key to be looked up;
 *  the tag is a client-specified tag that is returned in the response; and
 *  can be used by the client to match responses with requests; the ttl is
 *  decremented by every DhtServer and if <0, causes the packet to be discarded.
 *  
 *  Possible responses to the above request include:
 *  
 *  CSE473 DHTPv0.1
 *  type:success
 *  key:dungeons
 *  value:dragons
 *  tag:12345
 *  ttl:95
 *  
 *  or
 *  
 *  CSE473 DHTPv0.1
 *  type:no match
 *  key:dungeons
 *  tag:12345
 *  ttl:95
 *  
 *  Put requests are formatted similarly, but in this case the client typically
 *  specifies a value field (omitting the value field causes the pair with the
 *  specified key to be removed).
 *  
 *  The packet type “failure” is used to indicate an error of some sort; in 
 *  this case, the “reason” field provides an explanation of the failure. 
 *  The “join” type is used by a server to join an existing DHT. In the same
 *  way, the “leave” type is used by the leaving server to circle around the 
 *  DHT asking other servers’ to delete it from their routing tables.  The 
 *  “transfer” type is used to transfer (key,value) pairs to a newly added 
 *  server. The “update” type is used to update the predecessor, successor, 
 *  or hash range of another DHT server, usually when a join or leave even 
 *  happens. 
 *
 *  Other fields and their use are described briefly below
 *  clientAdr     is used to specify the IP address and port number of the 
 *              client that sent a particular request; it is added to a request
 *              packet by the first server to receive the request, before 
 *              forwarding the packet to another node in the DHT; an example of
 *              the format is clientAdr:123.45.67.89:51349.
 *  relayAdr      is used to specify the IP address and port number of the first
 *              server to receive a request packet from the client; it is added
 *              to the packet by the first server before forwarding the packet.
 *  hashRange     is a pair of integers separated by a colon, specifying a range
 *              of hash indices; it is included in the response to a “join” 
 *              packet, to inform the new DHT server of the set of hash values
 *              it is responsible for; it is also included in the update packet
 *              to update the hash range a server is responsible for.
 *  succInfo      is the IP address and port number of a server, followed by its
 *              first hash index; this information is included in the response
 *              to a join packet to inform the new DHT server about its 
 *              immediate successor; it’s also included in the update packet 
 *              to change the immediate successor of a DHT server; an example 
 *              of the format is succInfo:123.45.6.7:5678:987654321.
 *  predInfo    is also the IP address and port number of a server, followed
 *              by its first hash index; this information is included in a join
 *              packet to inform the successor DHT server of its new 
 *              predecessor; it is also included in update packets to update 
 *              the new predecessor of a server.
 *  senderInfo    is the IP address and port number of a DHT server, followed by
 *              its first hash index; this information is sent by a DHT to 
 *              provide routing information that can be used by other servers.
 *              It also used in leave packet to let other servers know the IP
 *              address and port number information of the leaving server.
 */
import java.io.*;
import java.net.*;
import java.util.*;
import java.lang.*;

import sun.misc.Signal;
import sun.misc.SignalHandler;

public class DhtServer {
	private static int numRoutes;	// number of routes in routing table
	private static boolean cacheOn;	// enables caching when true
	private static boolean debug;	// enables debug messages when true

	private static HashMap<String,String> map;	// key/value pairs
	private static HashMap<String,String> cache;	// cached pairs
	private static List<Pair<InetSocketAddress,Integer>> rteTbl;

	private static DatagramSocket sock;
	private static InetSocketAddress myAdr;
	private static InetSocketAddress predecessor; // DHT predecessor
	private static Pair<InetSocketAddress,Integer> myInfo; 
	private static Pair<InetSocketAddress,Integer> predInfo; 
	private static Pair<InetSocketAddress,Integer> succInfo; // successor
	private static Pair<Integer,Integer> hashRange; // my DHT hash range
	private static int sendTag;		// tag for new outgoing packets
	// flag for waiting leave message circle back
	private static boolean stopFlag;
	 
	/** Main method for DHT server.
	 *  Processes command line arguments, initializes data, joins DHT,
	 *  then starts processing requests from clients.
	 */
	public static void main(String[] args) {
		// process command-line arguments
		if (args.length < 3) {
			System.err.println("usage: DhtServer myIp numRoutes " +
					   "cfgFile [debug] [ predFile ] ");
			System.exit(1);
		}
		numRoutes = Integer.parseInt(args[1]);
		String cfgFile = args[2];
		cacheOn = debug = false;
		stopFlag = false;
		String predFile = null;
		for (int i = 3; i < args.length; i++) {
			if (args[i].equals("cache")) cacheOn = true;
			else if (args[i].equals("debug")) debug = true;
			else predFile = args[i];
		}
		
		// open socket for receiving packets
		// write ip and port to config file
		// read predecessor's ip/port from predFile (if there is one)
		InetAddress myIp = null; sock = null; predecessor = null;
		try {	
			myIp = InetAddress.getByName(args[0]);
			sock = new DatagramSocket(0,myIp);
			BufferedWriter cfg =
				new BufferedWriter(
				    new OutputStreamWriter(
					new FileOutputStream(cfgFile),
					"US-ASCII"));
			cfg.write("" +	myIp.getHostAddress() + " " +
					sock.getLocalPort());
			cfg.newLine();
			cfg.close();
			if (predFile != null) {
				BufferedReader pred =
					new BufferedReader(
					    new InputStreamReader(
						new FileInputStream(predFile),
						"US-ASCII"));
				String s = pred.readLine();
				String[] chunks = s.split(" ");
				predecessor = new InetSocketAddress(
					chunks[0],Integer.parseInt(chunks[1]));
			}
		} catch(Exception e) {
			System.err.println("usage: DhtServer myIp numRoutes " +
					   "cfgFile [ cache ] [ debug ] " +
					   "[ predFile ] ");
			System.exit(1);
		}
		myAdr = new InetSocketAddress(myIp,sock.getLocalPort());
		
		// initialize data structures	
		map = new HashMap<String,String>();
		cache = new HashMap<String,String>();
		rteTbl = new LinkedList<Pair<InetSocketAddress,Integer>>();

		// join the DHT (if not the first node)
		hashRange = new Pair<Integer,Integer>(0,Integer.MAX_VALUE);
		myInfo = null;
		succInfo = null;
		predInfo = null;
		if (predecessor != null) {
			join(predecessor);
		} else {
			myInfo = new Pair<InetSocketAddress,Integer>(myAdr,0);
			succInfo = new Pair<InetSocketAddress,Integer>(myAdr,0);
			predInfo = new Pair<InetSocketAddress,Integer>(myAdr,0);
		}


		// start processing requests from clients
		Packet p = new Packet();
		Packet reply = new Packet();
		InetSocketAddress sender = null;
		sendTag = 1;

		/* this function will be called if there's a "TERM" or "INT"
		 * captured by the signal handler. It simply execute the leave
		 * function and leave the program.
		 */ 
		SignalHandler handler = new SignalHandler() {  
		    public void handle(Signal signal) {  
		        leave();
		        System.exit(0);
		    }  
		};
		//Signal.handle(new Signal("KILL"), handler); // capture kill -9 signal
		Signal.handle(new Signal("TERM"), handler); // capture kill -15 signal
		Signal.handle(new Signal("INT"), handler); // capture ctrl+c
		
		while (true) {
			try { sender = p.receive(sock,debug);
			} catch(Exception e) {
				System.err.println("received packet failure");
				continue;
			}
			if (sender == null) {
				System.err.println("received packet failure");
				continue;
			}
			if (!p.check()) {
				reply.clear();
				reply.type = "failure";
				reply.reason = p.reason;
				reply.tag = p.tag;
				reply.ttl = p.ttl;
				reply.send(sock,sender,debug);
				continue;
			}
			handlePacket(p,sender);
		}
	}

	/** Hash a string, returning a 32 bit integer.
	 *  @param s is a string, typically the key from some get/put operation.
	 *  @return and integer hash value in the interval [0,2^31).
	 */
	public static int hashit(String s) {
		while (s.length() < 16) s += s;
		byte[] sbytes = null;
		try { sbytes = s.getBytes("US-ASCII"); 
		} catch(Exception e) {
			System.out.println("illegal key string");
			System.exit(1);
		}
		int i = 0;
		int h = 0x37ace45d;
		while (i+1 < sbytes.length) {
			int x = (sbytes[i] << 8) | sbytes[i+1];
			h *= x;
			int top = h & 0xffff0000;
			int bot = h & 0xffff;
			h = top | (bot ^ ((top >> 16)&0xffff));
			i += 2;
		}
		if (h < 0) h = -(h+1);
		return h;
	}

	/** Leave an existing DHT.
	 *  
	 *	Send a leave packet to it's successor and wait until stopFlag is 
	 * 	set to "true", which means leave packet is circle back.
	 *
	 *	Send an update packet with the new hashRange and succInfo fields to 
	 *  its predecessor, and sends an update packet with the predInfo 
	 *  field to its successor. 
	 *	
	 *	Transfers all keys and values to predecessor.  
	 *	Clear all the existing cache, map and rteTbl information
	 */
	public static void leave() {
		//if leave is called on the initial server, do nothing
		if(hashRange.left.intValue() == 0){
			return;
		}

		//send a leave packet to successor
		Packet leavePkt = new Packet();
		leavePkt.type = "leave";
		leavePkt.tag = sendTag;
		leavePkt.senderInfo = myInfo;
		leavePkt.send(sock,succInfo.left,debug);

		//wait until stopFlag is set to true
		while(!stopFlag){
			continue;
		}
       
		//send an update packet to predecessor
		//with new hashrange and new succInfo
		leavePkt.clear();
		leavePkt.type = "update";
		leavePkt.succInfo = succInfo;
		leavePkt.senderInfo = myInfo;
		leavePkt.hashRange = new Pair<Integer,Integer>(predInfo.right, hashRange.right);
        leavePkt.send(sock,predecessor,debug);

		//send an update packet to successor
		//with predInfo
		leavePkt.clear();
		leavePkt.type = "update";
		leavePkt.predInfo = predInfo;
		leavePkt.senderInfo = myInfo;
		leavePkt.send(sock,succInfo.left,debug);
        

        //transfer all (key,val) pairs to predecessor
        leavePkt.clear();
        leavePkt.type = "transfer";
        leavePkt.senderInfo = myInfo;
        //iterate through the map and transfer every pair
        //eventually, map will be emptied
        Iterator it = map.entrySet().iterator();
        while(it.hasNext()){
        	String currKey = (String)((Map.Entry)it.next()).getKey();
        	leavePkt.key = currKey;
        	leavePkt.val = map.get(currKey);
        	leavePkt.send(sock,predecessor,debug);
        	it.remove();
        }
        
        //clear cache and table
        cache = null;
        rteTbl = null;
	}
	
	/** Handle a update packet from a prospective DHT node.
	 *  @param p is the received join packet
	 *  @param adr is the socket address of the host that
	 *  
	 *	The update message might contains infomation need update,
	 *	including predInfo, succInfo, and hashRange. 
	 *  And add the new Predecessor/Successor into the routing table.
	 *	If succInfo is updated, succInfo should be removed from 
	 *	the routing table and the new succInfo should be added
	 *	into the new routing table.
	 */
	public static void handleUpdate(Packet p, InetSocketAddress adr) {
		if (p.predInfo != null){
			predInfo = p.predInfo;
		}
		if (p.succInfo != null){
			succInfo = p.succInfo;
			addRoute(succInfo);
		}
		if (p.hashRange != null){
			hashRange = p.hashRange;
		}
	}

	/** Handle a leave packet from a leaving DHT node.
	*  @param p is the received join packet
	*  @param adr is the socket address of the host that sent the leave packet
	*
	*  If the leave packet is sent by this server, set the stopFlag.
	*  Otherwise firstly send the received leave packet to its successor,
	*  and then remove the routing entry with the senderInfo of the packet.
	*/
	public static void handleLeave(Packet p, InetSocketAddress adr) {
		if (p.senderInfo.equals(myInfo)){
			stopFlag = true;
			return;
		}
		// send the leave message to successor 
		p.send(sock, succInfo.left, debug);

		//remove the senderInfo from route table
		removeRoute(p.senderInfo);
	}
	
	/** Join an existing DHT.
	 *  @param predAdr is the socket address of a server in the DHT,
	 *  
	 *	Send join packet to preAdr and retrive reply only if it is 
	 *  from preAdr. After checking is done, set hashRange and succInfo
	 *  using the information from the reply. Also, add route to the table.
	 */
	public static void join(InetSocketAddress predAdr) {
		//create a join packet and send to preAdr
		Packet joinSend = new Packet();
		joinSend.type = "join";
		sendTag ++;
		joinSend.tag = sendTag;
		joinSend.send(sock,predAdr,debug);

		//retrieve reply packet only from preAdr
		//and drop other packets
		Packet joinRes = new Packet();
		while(true){
			//end while loop if the packet is from preAdr
			if(joinRes.receive(sock,debug).equals(predAdr)){
				break;
			}
		}

		//check packet error
		if(!joinRes.check()){
			System.out.println("ERROR: Join failed");
			System.exit(1);
		}

		//check success and tag consistency
		if(!joinRes.type.equals("success") || joinRes.tag != joinSend.tag){
            System.out.println("ERROR: Join failed");
            System.out.println("The received packet is " + joinRes.toString());
            System.exit(1);
		}

		//join success, set corresponding values
		succInfo = joinRes.succInfo;
		hashRange = joinRes.hashRange;
		//add new route
		addRoute(succInfo);
        
        //update myInfo and predInfo using reponse join packet
		myInfo = new Pair<InetSocketAddress,Integer>(myAdr,joinRes.hashRange.left);
		predInfo = joinRes.predInfo;

	}
	
	/** Handle a join packet from a prospective DHT node.
	 *  @param p is the received join packet
	 *  @param succAdr is the socket address of the host that
	 *  sent the join packet (the new successor)
	 *
	 *  To response join packet:
	 *  a) Set type to "success";
	 *  b) Split hashRange in half and assign the top half to succAdr
	 *  and update current hashRange to lower half;
	 *  c) Update new succInfo for both current node and succAdr.
	 *  d) Add route to table
	 *
	 *  To transfer pairs in top half of hashRange:
	 *  Iterate through hashmap, if the hash of current key is found belonged to 
	 *  top half, send (key,val) pair using transfer packet
	 */
	public static void handleJoin(Packet p, InetSocketAddress succAdr) {
		//response will normally be a success packet
		p.type = "success";

		//split hashRange in half
		int low = hashRange.left.intValue();
		int high = hashRange.right.intValue();
		int mid = (high-low)/2;
		//update own hashRange to lower half
		hashRange.right = new Integer(mid+low);

		//give top half of hashRange to new successor
		int firstHash = low + mid + 1;
		p.hashRange = new Pair<Integer,Integer>(firstHash, high);

		//Node that sent join request gets successor of current node 
		//as its successor
		p.succInfo = succInfo;
		p.predInfo = myInfo;
		//current node gets this node(succAdr) as its new successor
		succInfo = new Pair<InetSocketAddress,Integer>(succAdr,firstHash);
        //add route to table
        addRoute(succInfo);
        //send reponse packet
        p.send(sock,succAdr,debug);

        //create a transfer packet and transfer all pairs
        //that belonged to top half of original hashRange
        Packet transPkt = new Packet();
        transPkt.type = "transfer";
        sendTag ++;
        transPkt.tag = sendTag;

        //use iterator to iterate through hashmap
        Iterator it = map.entrySet().iterator();
        while(it.hasNext()){
        	String currKey = (String)((Map.Entry)it.next()).getKey();
        	 //if a key is found to belong to top half,
             //set key and val in transfer packet and send it
        	if(hashit(currKey) >= firstHash){
        		transPkt.key = currKey;
        		transPkt.val = map.get(currKey);
        		transPkt.send(sock,succAdr,debug);
        		it.remove();
        	}
        }
	}
	
	/** Handle a get packet.
	 *  @param p is a get packet
	 *  @param senderAdr is the the socket address of the sender
	 *
	 *  Three cases:
	 *  a) If hash value is in hashRange, 
	 *     get the value from hashmap.
	 *  b) If cache is on and cache has the key
	 *     get the value from cache
	 *  c) Otherwise, forward to other servers
	 * 
	 *  When sending packet, send to relay server if the packet
	 *  is not directly from client. Otherwise, we send directly
	 *  to client.
	 * 
	 */
	public static void handleGet(Packet p, InetSocketAddress senderAdr) {
		// this version is incomplete; you will have to extend
		// it to support caching
		InetSocketAddress replyAdr;
		int hash = hashit(p.key);
		int left = hashRange.left.intValue();
		int right = hashRange.right.intValue();

		if (left <= hash && hash <= right) {
			// respond to request using map
			if (p.relayAdr != null) {
				replyAdr = p.relayAdr;
				p.senderInfo = new 
				    Pair<InetSocketAddress,Integer>(myAdr,left);
			} else {
				replyAdr = senderAdr;
			}
			if (map.containsKey(p.key)) {
				p.type = "success"; p.val = map.get(p.key);
			} else {
				p.type = "no match";
			}
			p.send(sock,replyAdr,debug);
		//the case when cache is on and cache has the key
		}else if(cacheOn && cache.containsKey(p.key)){
			p.type = "success";
			p.val = cache.get(p.key);
			//decide replyAddr
			//send to relay server if packet is not directly from client
			if (p.relayAdr != null) {
				replyAdr = p.relayAdr;
				p.senderInfo = new 
				    Pair<InetSocketAddress,Integer>(myAdr,left);
            //send directly to client
			} else {
				replyAdr = senderAdr;
			}
			p.send(sock,replyAdr,debug);
	    }else {
			// forward around DHT
			if (p.relayAdr == null) {
				p.relayAdr = myAdr; p.clientAdr = senderAdr;
			}
			forward(p,hash);
		}
	}
	
	/** Handle a put packet.
	 *  @param p is a put packet
	 *  @param senderAdr is the the socket address of the sender
	 *  
	 *	Two cases:
	 *  a) If hash value is in the range, insert pair into map
	 *     if val is not null. Reply the packet to either relay server
	 *     or client
	 *  b) If hash value is out of range, forward to other servers.
	 *     Also, if current node is the first server that receives the request,
	 *     set it to be the relay server.
	 */
	public static void handlePut(Packet p, InetSocketAddress senderAdr) {
		InetSocketAddress replyAdr;
		int hash = hashit(p.key);
		int left = hashRange.left.intValue();
		int right = hashRange.right.intValue();
        
        //hash value is in the range
		if(left<=hash && hash<=right){
			//send to relay server
            if (p.relayAdr != null) {
				replyAdr = p.relayAdr;
				p.senderInfo = new 
				    Pair<InetSocketAddress,Integer>(myAdr,left);
		    //send to client
			} else {
				replyAdr = senderAdr;
			}
			if(p.val != null){
				map.put(p.key, p.val);
				p.type = "success";
			}else{
				p.type = "failure";
			}
			p.send(sock,replyAdr,debug);
		//forward to other servers if hash not in the range
		}else{
			//if relayAdr is null, current node becomes relay server
			if(p.relayAdr == null){
				p.relayAdr = myAdr;
				p.clientAdr = senderAdr;
			}
			forward(p,hash);
		}
	}

	/** Handle a transfer packet.
	 *  @param p is a transfer packet
	 *  @param senderAdr is the the address (ip:port) of the sender
	 *  
	 *	Insert (key,val) pair into map only when
	 *  the hash value is in the range
	 */
	public static void handleXfer(Packet p, InetSocketAddress senderAdr) {
		int hash = hashit(p.key);
		int left = hashRange.left.intValue();
		int right = hashRange.right.intValue();
		//accept pair if hash value is in the range
		if(left<=hash && hash<=right){
			map.put(p.key, p.val);
		}
	}
	
	/** Handle a reply packet.
	 *  @param p is a reply packet, more specifically, a packet of type
	 *  "success", "failure" or "no match"
	 *  @param senderAdr is the the address (ip:port) of the sender
	 *  
	 *  When current node gets a response packet, it assumes itself as the relay server.
	 *  Send the response to client after removeing clientAdr,relayAdr and senderInfo.
	 *  If cache is on and reponse packet gives correct result, store the pair in the cache.
	 */
	public static void handleReply(Packet p, InetSocketAddress senderAdr) {
		//remove clientAdr, relayAdr, senderInfo in the packet
		InetSocketAddress client = p.clientAdr;
		p.clientAdr = null;
		p.relayAdr = null;
		p.senderInfo = null;
		p.send(sock,client,debug);
		//add (key,val) pair to cache 
		if(cacheOn && p.type.equals("success")){
			if(p.key != null && p.val != null){
				cache.put(p.key, p.val);
			}
		}
	}
	
	/** Handle packets received from clients or other servers
	 *  @param p is a packet
	 *  @param senderAdr is the address (ip:port) of the sender
	 */
	public static void handlePacket(Packet p, InetSocketAddress senderAdr) {
		if (p.senderInfo != null & !p.type.equals("leave"))
			addRoute(p.senderInfo);
		if (p.type.equals("get")) {
			handleGet(p,senderAdr);
		} else if (p.type.equals("put")) {
			handlePut(p, senderAdr);
		} else if (p.type.equals("transfer")) {
			handleXfer(p, senderAdr);
		} else if (p.type.equals("success") ||
			p.type.equals("no match") ||
		    p.type.equals("failure")) {
			handleReply(p, senderAdr);
		} else if (p.type.equals("join")) {
			handleJoin(p, senderAdr);
		} else if (p.type.equals("update")){
			handleUpdate(p, senderAdr);
		} else if (p.type.equals("leave")){
			handleLeave(p, senderAdr);
		}
	}
	
	/** Add an entry to the route tabe.
	 *  @param newRoute is a pair (addr,hash) where addr is the socket
	 *  address for some server and hash is the first hash in that
	 *  server's range
	 *
	 *  If the number of entries in the table exceeds the max
	 *  number allowed, the first entry that does not refer to
	 *  the successor of this server, is removed.
	 *  If debug is true and the set of stored routes does change,
	 *  print the string "rteTbl=" + rteTbl. (IMPORTANT)
	 */
	public static void addRoute(Pair<InetSocketAddress,Integer> newRoute){
		boolean isChanged = false;
		//search through the table to see if newRoute already exists
		for(Pair<InetSocketAddress,Integer> curr: rteTbl){
			if(curr.equals(newRoute)){
				return;
			}
		}
		//add newRoute to the table if not found
		rteTbl.add(newRoute);

		//check if table size exceeds max number allowed
		if(rteTbl.size() <= numRoutes){
			isChanged = true;
		//remove first entry(not successor) if table exceeds max size
		}else{
            for(Pair<InetSocketAddress,Integer> curr: rteTbl){
            	if(!curr.equals(succInfo)){
            		//change ocurrs if the node to be removed
            		//is not new reroute
            		isChanged = !curr.equals(newRoute);
            		rteTbl.remove(curr);
            		break;
            	}
            }
		}
		//print if debug is true and changes occur
		if(debug && isChanged){
            System.out.println("rteTbl=" + rteTbl);
        }
	}

	/** Remove an entry from the route tabe.
	 *  @param rmRoute is the route information for some server 
	 *  need to be removed from route table
	 *
	 *  If the route information exists in current entries, remove it.
	 *	Otherwise, do nothing.
	 *  If debug is true and the set of stored routes does change,
	 *  print the string "rteTbl=" + rteTbl. (IMPORTANT)
	 */
	public static void removeRoute(Pair<InetSocketAddress,Integer> rmRoute){
		boolean isChanged = false;
		//search to see if rmRoute exists in the table
		//remove it if found and do nothing otherwise
		for(Pair<InetSocketAddress,Integer> curr: rteTbl){
			if(curr.equals(rmRoute)){
				rteTbl.remove(curr);
				isChanged = true;
			}
		}
		//print if debug is true and changes do occur
		if(debug && isChanged){
			System.out.println("rteTbl=" + rteTbl);
		}

	}


	/** Forward a packet using the local routing table.
	 *  @param p is a packet to be forwarded
	 *  @param hash is the hash of the packet's key field
	 *
	 *  This method selects a server from its route table that is
	 *  "closest" to the target of this packet (based on hash).
	 *  If firstHash is the first hash in a server's range, then
	 *  we seek to minimize the difference hash-firstHash, where
	 *  the difference is interpreted modulo the range of hash values.
	 *  IMPORTANT POINT - handle "wrap-around" correctly. 
	 *  Once a server is selected, p is sent to that server.
	 */
	public static void forward(Packet p, int hash) {
		//want to find selectedServer with minimum closetDiff
		Pair<InetSocketAddress,Integer> selectedServer = null;
		int closestDiff = 0;
		//search throught the list
		for(Pair<InetSocketAddress,Integer> curr: rteTbl){
			int diff = hash - curr.right;
			//modulo the range of hash values
			if(diff < 0){
				diff = diff + Integer.MAX_VALUE + 1;
			}
			//update best choice so far
			if(selectedServer == null || diff < closestDiff){
				selectedServer = curr;
				closestDiff = diff;
			}
		}
		p.send(sock,selectedServer.left,debug);
	}
}
