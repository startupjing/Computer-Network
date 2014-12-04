import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Router module for an overlay router.
 *
 *  The Router class implements a distributed routing algorithm.
 *  
 *  The router will use a path-vector style intra-domain protocol.
 *  The router will send hello packets every second, and neighbors response with
 *  hello2u packets. Routers will use timestamp in hello packets to measure the round
 *  trip delay of the link, and the link cost is set to be the half of round-trip delay.
 *  Also, the router smoothes our variations in the individual delay measurement using a 
 *  parameter alpha=0.1. If the router fails to receive three consecutive hello packets from
 *  a neighbor, then it will set that link to be failed. All routes using the failed link are
 *  considered to be invalid. To recover from a link failure event, a link failure advertisement
 *  will be used. After the router finds out a link is failed, it will continue to send hello
 *  packets to the failed link and restores it as soon as a response is received.
 *
 *  Hello packet format:
 *  RPv0
 *  type: hello
 *  timestamp: 123.456
 *  Timestamp is the time from an arbitrary starting point at the sending router.  
 *
 *  Response to a hello packet:
 *  RPv0
 *  type: hello2u
 *  timestamp: 123.456
 *  Timestamps are echoed back in the replies to hello packets.
 *
 *  Router advertisement packet format:
 *  RPv0
 *  type: advert
 *  pathvec: 1.5.0.0/16 345.678 .346 1.2.0.1 1.2.3.4 1.5.4.3
 *  Each path vector starts with an advertised prefix, followed by a timestamp for the vector
 *  and the cost of the path. The remainder is a list of IP addresses of routes along the path,
 *  ending with the router that originated this advertisement.
 *  Each router sends a route advertisement for each of its own prefixes to neighbors every 10s.
 *  When a router receives a route advertisement from its neighbor, it first checks to see if
 *  its own IP address is in the path vector. If so, ignore the advertisement. Otherwise, it may
 *  update the routing table according to the contents of path vector:
 *  1) If routing table has no entry for the subnet specified in the advertisement, add a new route.
 *  2) If routing table already has an entry for the subnet, then consider the following:
 *     a) ignore the advertisement if it arrives on a disabled link
 *     b) if route is invalid, replace it with the new route specified in the advertisement
 *     c) if new route uses the same path as existing entry, update timestamp and cost
 *     d) if new route defines a different path, update the current entry if one of the following is true
 *        - new cost is at least 10% smaller than the old cost
 *        - new route is at least 20s newer than the old route
 *        - current route uses a disabled link
 *  Note that if a new route is added to the routing table, or the link of existing entry is updated,
 *  the router will change the corresponding entry in the forwarding table.
 * 
 *  Link failure advertisement format:
 *  RPv0
 *  type: fadvert
 *  linkfail: 1.5.0.3 1.4.3.4 345.678 .678 1.2.0.1 1.3.3.4 1.5.0.3
 *  The first two IP addresses identify the failed link, following by the timestamp and a list of 
 *  IP addresses of the router along the path. 
 *  To handle link failure message, the router first checks whether the routes in its routing table
 *  use the failed link. If so, set it to be invalid and send a link failure advertisement to neighbors.
 *  Otherwise, do nothing. Also, if the router finds its own IP address in the list of address, it will
 *  ignore the received link failure advertisement. 
 */
public class Router implements Runnable {
	private Thread myThread;	// thread that executes run() method
	private int myIp;		// ip address in the overlay
	private String myIpString;	// String representation
	private ArrayList<Prefix> pfxList; // list of prefixes to advertise
	private ArrayList<NborInfo> nborList; // list of info about neighbors

	private class LinkInfo { // class used to record link information
		public int peerIp;	// IP address of peer in overlay net
		public double cost;	// in seconds
		public boolean gotReply; // flag to detect hello replies
		public int helloState;	// set to 3 when hello reply received
					// decremented whenever hello reply
					// is not received; when 0, link is down

		// link cost statistics
		public int count;
		public double totalCost;
		public double minCost;
		public double maxCost;

		LinkInfo() {
			cost = 0; gotReply = true; helloState = 3;
			count = 0; totalCost = 0; minCost = 10; maxCost = 0;
		}
	}
	private ArrayList<LinkInfo> lnkVec;  // indexed by link number

	private class Route { // routing table entry
		public Prefix pfx;	// destination prefix for route
		public double timestamp; // time this route was generated
		public double cost;	// cost of route in ns
		public LinkedList<Integer> path; // list of router IPs;
					// destination at end of list
		public int outLink;	// outgoing link for this route
		public boolean valid = true;;	//indicate the valid of the route
	}
	private ArrayList<Route> rteTbl;  // routing table

	private Forwarder fwdr;		// reference to Forwarder object

	private double now;		// current time in ns
	private static final double sec = 1000000000;  // ns per sec

	private int debug;		// controls debugging output
	private boolean quit;		// stop thread when true
	private boolean enFA;		// link failure advertisement enable


	/** Initialize a new Router object.
	 *  
	 *  @param myIp is an integer representing the overlay IP address of
	 *  this node in the overlay network
	 *  @param fwdr is a reference to the Forwarder object through which
	 *  the Router sends and receives packets
	 *  @param pfxList is a list of prefixes advertised by this router
	 *  @param nborList is a list of neighbors of this node
	 *
	 *  @param debug is an integer that controls the amount of debugging
	 *  information that is to be printed
	 */

	Router(int myIp, Forwarder fwdr, ArrayList<Prefix> pfxList,
			ArrayList<NborInfo> nborList, int debug, boolean enFA) {
		this.myIp = myIp; this.myIpString = Util.ip2string(myIp);
		this.fwdr = fwdr; this.pfxList = pfxList;
		this.nborList = nborList; this.debug = debug;
		this.enFA = enFA;

		lnkVec = new ArrayList<LinkInfo>();
		for (NborInfo nbor : nborList) {
			LinkInfo lnk = new LinkInfo();
			lnk.peerIp = nbor.ip;
			lnk.cost = nbor.delay;
			lnkVec.add(lnk);
		}
		rteTbl = new ArrayList<Route>();
		quit = false;
	}

	/** Instantiate and start a thread to execute run(). */
	public void start() {
		myThread = new Thread(this); myThread.start();
	}

	/** Terminate the thread. */
	public void stop() throws Exception { quit = true; myThread.join(); }

	/** This is the main thread for the Router object.
	 *
	 * A hello packet is sent to neighbors every second, and advertisements
     * of own prefixes are sent every ten seconds.
     * When receive a hello packet, response with hello2u packet.
     * When receive a response of own hello packet, update link costs.
     * When receive an advertisement, first check if itself is in the path.
     * If so, ignore the message. Otherwise, update routing and forwarding
     * table according to the contents of the advertisement.
	 */
	public void run() {
		double t0 = System.nanoTime()/sec;
		now = 0;
		double helloTime, pvSendTime;
		helloTime = pvSendTime = now;
		while (!quit) {
			now = System.nanoTime()/sec - t0;		
			//time to send hello packets
			if(now > helloTime + 1){
				sendHellos();
				helloTime = now;
			//time to send advertisements
			}else if(now > pvSendTime + 10){
                sendPathVecs();
                pvSendTime = now;
            //handle incoming packet from forwarder
			}else if(fwdr.incomingPkt()){
                handleIncoming();
            //sleep if nothing to do
			}else{
				try{
					Thread.sleep(1);
				}catch(Exception e){
					System.err.println("ERROR: Router unable to sleep");
				}
			}
		}
		String s = String.format("Router link cost statistics\n" + 
			"%8s %8s %8s %8s %8s\n","peerIp","count","avgCost",
			"minCost","maxCost");
		for (LinkInfo lnk : lnkVec) {
			if (lnk.count == 0) continue;
			s += String.format("%8s %8d %8.3f %8.3f %8.3f\n",
				Util.ip2string(lnk.peerIp), lnk.count,
				lnk.totalCost/lnk.count,
				lnk.minCost, lnk.maxCost);
		}
		System.out.println(s);
	}

	/** Lookup route in routing table.
	 *
	 * @param pfx is IP address prefix to be looked up.
	 * @return a reference to the Route that matches the prefix or null
	 */
	private Route lookupRoute(Prefix pfx) {
		for(Route curr: rteTbl){
			 if(curr.pfx.equals(pfx)){
			 	 return curr;
			 }
		}
		return null;

	}

	/** Add a route to the routing table.
	 * 
	 *  @param rte is a route to be added to the table; no check is
	 *  done to make sure this route does not conflict with an existing
	 *  route
	 */
	private void addRoute(Route rte) {
		rteTbl.add(rte);
	}

	 /** Update a route in the routing table.
	 *
	 *  @param rte is a reference to a route in the routing table.
	 *  @param nuRte is a reference to a new route that has the same
	 *  prefix as rte
	 *  @return true if rte is modified, else false
	 *
	 *  This method replaces certain fields in rte with fields
	 *  in nuRte. Specifically,
	 *
	 *  if nuRte has a link field that refers to a disabled
	 *  link, ignore it and return false
	 *
	 *  else, if the route is invalid, then update the route
	 *  and return true,
	 *
	 *  else, if both routes have the same path and link,
	 *  then the timestamp and cost fields of rte are updated
	 *
	 *  else, if nuRte has a cost that is less than .9 times the
	 *  cost of rte, then all fields in rte except the prefix fields
	 *  are replaced with the corresponding fields in nuRte
	 *
	 *  else, if nuRte is at least 20 seconds newer than rte
	 *  (as indicated by their timestamps), then all fields of
	 *  rte except the prefix fields are replaced
	 *
	 *  else, if the link field for rte refers to a link that is
	 *  currently disabled, replace all fields in rte but the
	 *  prefix fields
	 */
	private boolean updateRoute(Route rte, Route nuRte) {
		//referenced link is disabled
		if(lnkVec.get(nuRte.outLink).helloState == 0){
			return false;
		}
        
        //if the route is invalid, update the route
		if(!rte.valid && !rte.path.equals(nuRte.path) && nuRte.valid){
			rte.path = nuRte.path;
			rte.outLink = nuRte.outLink;
			rte.timestamp = nuRte.timestamp;
			rte.cost = nuRte.cost;
			rte.valid = true;
			return true;
		}

		//update timestamp and cost
		//if both routes have same path and outlink
		if(rte.path.equals(nuRte.path) && rte.outLink == nuRte.outLink){
			rte.timestamp = nuRte.timestamp;
			rte.cost = nuRte.cost;
			return true;
		}

		//relace all fields except prefix if one of the following is true
		//1. nuRte has cost less than 90% of rte's cost
		//2. nuRte is at least 20s newer than rte
		//3. referenced link by rte is disabled
		if(nuRte.cost < 0.9*rte.cost || nuRte.timestamp > rte.timestamp + 20 || lnkVec.get(rte.outLink).helloState == 0){
			rte.path = nuRte.path;
			rte.outLink = nuRte.outLink;
			rte.timestamp = nuRte.timestamp;
			rte.cost = nuRte.cost;
			return true;
		}
		return false;
	}
				
	/** Send hello packet to all neighbors.
	 *
	 *  First check for replies. If no reply received on some link,
	 *  update the link status by subtracting 1. If that makes it 0,
	 *  the link is considered down, so we mark all routes using 
	 *  that link as invalid. Also, if certain routes are marked as 
	 *  invalid, we will need to print the table if debug larger 
	 *  than 1, and we need to send failure advertisement by 
	 *  calling sendFailureAdvert if failure advertisement is enable.
	 */
	public void sendHellos() {
		int lnk = 0;
		for (LinkInfo lnkInfo : lnkVec) {
			// if no reply to the last hello, subtract 1 from
			// link status if it's not already 0
			if(!lnkInfo.gotReply && lnkInfo.helloState > 0){
				lnkInfo.helloState --;
				boolean failLnk = (lnkInfo.helloState==0);
				boolean validChange = false;
				// go through the routes to check routes 
				// that contain the failed link
	            if(failLnk){
	            	for(Route r: rteTbl){
	            		if(r.outLink == lnk){
	            			r.valid = false;
	            			validChange = true;
	            		}
	            	}
	            	// print routing table if debug is enabled 
					// and valid field of route is changed
					if(debug >0 && validChange){
						printTable();
					}
					// send link failure advertisement if enFA is enabled
					// and valid field of route is changed
		            if(enFA && validChange){
		            	sendFailureAdvert(lnk);
		            }
		        }
            }
			// send new hello, after setting gotReply to false
			lnkInfo.gotReply = false;
			Packet p = new Packet();
			p.srcAdr = myIp;
			p.destAdr = lnkInfo.peerIp;
			p.protocol = 2;
			p.ttl = 100;
			p.payload = String.format("RPv0\ntype: hello\ntimestamp: %.4f\n", now);
			fwdr.sendPkt(p, lnk);
			lnk++;
		}
	}

	/** Send initial path vector to each of our neighbors.  */
	public void sendPathVecs() {
		for(Prefix pfx: pfxList){
			for(int i=0; i<nborList.size(); i++){
				//check if the route to send is invalid
				boolean inValid = (lookupRoute(pfx)!=null) && (!lookupRoute(pfx).valid);
				if(lnkVec.get(i).helloState == 0 || inValid){
					continue;
				//send path vector to neighbors
				}else{
					Packet p = new Packet();
					p.srcAdr = myIp;
					p.destAdr = lnkVec.get(i).peerIp;
					p.protocol = 2;
					p.ttl = 100;
					p.payload = String.format("RPv0\ntype: advert\npathvec: %s %.3f 0 %s\n", pfx.toString(), now, myIpString);
					fwdr.sendPkt(p,i);
				}
			}
		}
	}


	/** Send link failure advertisement to all available neighbors
	 *
	 *  @param failedLnk is the number of link on which is failed.
	 *
	 */
	public void sendFailureAdvert(int failedLnk){
		int failIp = lnkVec.get(failedLnk).peerIp;
		String failIpString = Util.ip2string(failIp);
		for (int lnk = 0; lnk < nborList.size(); lnk++) {
			if (lnkVec.get(lnk).helloState == 0) continue;
			Packet p = new Packet();
			p.protocol = 2; p.ttl = 100;
			p.srcAdr = myIp;
			p.destAdr = lnkVec.get(lnk).peerIp;
			p.payload = String.format("RPv0\ntype: fadvert\n"
				+ "linkfail: %s %s %.3f %s\n",
				myIpString,  failIpString, now, myIpString);
			fwdr.sendPkt(p,lnk);
		}
	}

	/** Retrieve and process packet received from Forwarder.
	 *
	 *  For hello packets, we simply echo them back.
	 *  For replies to our own hello packets, we update costs.
	 *  For advertisements, we update routing state and propagate
	 *  as appropriate.
	 */
	public void handleIncoming() {
		// parse the packet payload
		Pair<Packet,Integer> pp = fwdr.receivePkt();
		Packet p = pp.left; int lnk = pp.right;
		String[] lines = p.payload.split("\n");
		if (!lines[0].equals("RPv0")) return;
		String[] chunks = lines[1].split(":");
		if (!chunks[0].equals("type")) return;
		String type = chunks[1].trim();

		// if it's an route advert, call handleAdvert
		if(type.equals("advert")){
			handleAdvert(lines, lnk);
			return;
		}
		// if it's an link failure advert, call handleFailureAdvert
        if(type.equals("fadvert")){
        	handleFailureAdvert(lines, lnk);
        	return;
        }
		String[] split = lines[2].split(":");
		if(!split[0].equals("timestamp")){
			return;
		}
		//echo it back if it's hello packet
		if(type.equals("hello")){
			Packet res = new Packet();
			res.srcAdr = myIp;
			res.destAdr = p.srcAdr;
			res.protocol = 2;
			res.ttl = 100;
			res.payload = String.format("RPv0\ntype: hello2u\n" + lines[2] + "\n");
			if(fwdr.ready4pkt()){
				fwdr.sendPkt(res, lnk);
			}
			return;
		}
		//only left possibility is hello2u packet
		if(!type.equals("hello2u")){
			return;
		}
		// else it's a reply to a hello packet
		LinkInfo lnkInfo = lnkVec.get(lnk);
		//measure round-trip delay
		double tp = Double.parseDouble(split[1]);
		double c = (now - tp)/2;
		//update cost statistics
		lnkInfo.cost = 0.9*lnkInfo.cost + 0.1*c;
		lnkInfo.totalCost = lnkInfo.totalCost + c;
		lnkInfo.minCost = Math.min(c, lnkInfo.minCost);
		lnkInfo.maxCost = Math.max(c, lnkInfo.maxCost);
		lnkInfo.count ++;
		//reset statistics
		lnkInfo.gotReply = true;
		lnkInfo.helloState = 3;
	}

	/** Handle an advertisement received from another router.
	 *
	 *  @param lines is a list of lines that defines the packet;
	 *  the first two lines have already been processed at this point
	 *
	 *  @param lnk is the number of link on which the packet was received
	 */
	private void handleAdvert(String[] lines, int lnk) {
		// example path vector line
		// pathvec: 1.2.0.0/16 345.678 .052 1.2.0.1 1.2.3.4

        // Parse the path vector line.
        String[] split = lines[2].split(":");
        if(!split[0].trim().equals("pathvec")){
        	return;
        }
        String[] info = split[1].trim().split(" ");
        if(info.length < 4){
        	return;
        }
        // Form a new route, with cost equal to path vector cost
        // plus the cost of the link on which it arrived.
        Route r = new Route();
        r.valid = true;
        r.path = new LinkedList<Integer>();
        for(int i=3; i<info.length; i++){
        	// If there is loop in path vector, ignore this packet.
        	if(info[i].equals(myIpString)){
        		return;
        	}
        	r.path.add(Util.string2ip(info[i]));
        }
        r.pfx = new Prefix(info[0]);
        r.timestamp = Double.parseDouble(info[1]);
        r.cost = lnkVec.get(lnk).cost + Double.parseDouble(info[2]);
        r.outLink = lnk;

        // Look for a matching route in the routing table
        // and update as appropriate; whenever an update
        // changes the path, print the table if debug>0;
        // whenever an update changes the output link,
        // update the forwarding table as well.
        Route matchRoute = lookupRoute(r.pfx);
        //previosu link number
        int prevLink = fwdr.getLink(r.pfx);
        //add a new entry if no match found
        if(matchRoute == null){
        	addRoute(r);
        	if(debug > 0){
        		printTable();
        	}
        	fwdr.addRoute(r.pfx, r.outLink);
        }else{
        	//previous path in the route
        	LinkedList<Integer> prevPath = matchRoute.path;
        	if(updateRoute(matchRoute, r)){
        		//check if path or link is updated
	        	boolean pathUpdate = (!prevPath.equals(r.path));
	        	boolean lnkUpdate = (prevLink != r.outLink);
	        	if(debug>0 && pathUpdate){
	        		printTable();
	        	}
	        	if(lnkUpdate){
	        		fwdr.addRoute(r.pfx, r.outLink);
	        	}
	        }
	        
        }
        // If the new route changed the routing table,
        // extend the path vector and send it to other neighbors.
        String payload = String.format("RPv0\ntype: advert\npathvec: %s %.3f %.4f %s", r.pfx.toString(), r.timestamp, r.cost, myIpString);
        LinkedList<Integer> newPath = lookupRoute(r.pfx).path;
        for (int curr :newPath){
				payload += String.format (" %s",Util.ip2string(curr));
		}
		payload += "\n";
		//advertise to neighbors
        for(int i=0; i<nborList.size(); i++){
        	if(i != lnk){
        		Packet p = new Packet();
        		p.protocol = 2;
        		p.ttl = 100;
        		p.srcAdr = myIp;
        		p.destAdr = nborList.get(i).ip;
        		p.payload = payload;
        		fwdr.sendPkt(p,i);
        	}
        }
	}

	/** Handle the failure advertisement received from another router.
	 *
	 *  @param lines is a list of lines that defines the packet;
	 *  the first two lines have already been processed at this point
	 *
	 *  @param lnk is the number of link on which the packet was received
	 */
	private void handleFailureAdvert(String[] lines, int lnk) {
		// example path vector line
		// fadvert: 1.2.0.1 1.3.0.1 345.678 1.4.0.1 1.2.0.1
		// meaning link 1.2.0.1 to 1.3.0.1 is failed

        // Parse the path vector line.
        String[] split = lines[2].split(":");
        if(!split[0].trim().equals("linkfail")){
        	return;
        }
        String[] info = split[1].trim().split(" ");
        if(info.length < 4){
        	return;
        }
        // If there is loop in path vector, ignore this packet.
        for(int i=3; i<info.length; i++){
        	if(info[i].equals(myIpString)){
        		return;
        	}
        }
        // go through routes to check if it contains the link
		// set the route as invalid (false) if it does
		// update the time stamp if route is changed
		List<Route> changedRoutes = new ArrayList<Route>();
		int fromIp = Util.string2ip(info[0]);
		int toIp = Util.string2ip(info[1]);
		boolean routeChange = false;
		for(Route r: rteTbl){
			List<Integer> path = r.path;
			int idx1 = path.indexOf(fromIp);
			int idx2 = path.indexOf(toIp);
			//check if a route uses the failed link
			if(idx1!=-1 && idx2!=-1 && Math.abs(idx1-idx2)==1){
				changedRoutes.add(r);
				r.valid = false;
				r.timestamp = Double.parseDouble(info[2]);
				routeChange = true;
			}
		}
		// print route table if route is changed and debug is enabled
		if(debug>0 && routeChange){
			printTable();
		}
		
		// If one route is changed, extend the message 
		// and send it to other neighbors.
		if(routeChange){
			String payload = String.format("RPv0\ntype: fadvert\nlinkfail: " + info[0] + " " + info[1] + " " + info[2] + " " + myIpString);
			for(int i=3; i<info.length; i++){
        	   payload += " " + info[i];
            }
            payload += "\n";
            //advertise to neighbors
			for(int i=0; i<nborList.size(); i++){
				Packet p = new Packet();
				p.protocol = 2;
				p.ttl = 100;
				p.srcAdr = myIp;
				p.destAdr = nborList.get(i).ip;
				p.payload = payload;
				fwdr.sendPkt(p,i);
			}
		}
	}

	/** Print the contents of the routing table. */
	public void printTable() {
		String s = String.format("Routing table (%.3f)\n"
			+ "%10s %10s %8s %5s %10s \t path\n", now, "prefix", 
			"timestamp", "cost","link", "VLD/INVLD");
		for (Route rte : rteTbl) {
			s += String.format("%10s %10.3f %8.3f",
				rte.pfx.toString(), rte.timestamp, rte.cost);
			
			s += String.format(" %5d", rte.outLink);
			
			if (rte.valid == true)
				s+= String.format(" %10s", "valid");
			else
				s+= String.format(" %10s \t", "invalid");
			
			for (int r :rte.path)
				s += String.format (" %s",Util.ip2string(r));
			
			if (lnkVec.get(rte.outLink).helloState == 0)
				s += String.format("\t ** disabled link");
			s += "\n";
		}
		System.out.println(s);
	}
}
