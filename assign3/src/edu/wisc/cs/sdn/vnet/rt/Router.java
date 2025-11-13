package edu.wisc.cs.sdn.vnet.rt;

import net.floodlightcontroller.packet.Ethernet; /** use for getEtherType -> To determine IPv4 or not */
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;


/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}

	public void startRIP() {
    // Seed directly connected routes (gateway = 0; metric handled by RouteTable)
    for (Iface iface : this.interfaces.values()) {
        int subnet = iface.getIpAddress() & iface.getSubnetMask();
        int mask   = iface.getSubnetMask();

        // Insert "direct" route with no gateway
        // Signature is the standard CS640: insert(destination, gateway, mask, iface)
        boolean ok = this.routeTable.insert(subnet, 0, mask, iface);

        System.out.println(String.format(
            "[RIP-init] Direct route %s/%d via iface %s %s",
            net.floodlightcontroller.packet.IPv4.fromIPv4Address(subnet),
            Integer.bitCount(mask),
            iface.getName(),
            ok ? "(added)" : "(already present)"
        ));
    }

	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		System.out.println("After toString ....");
		if (etherPacket == null) {
        	System.out.println("Null Ethernet frame received - drop pkt");
        	return;
    	}
		/********************************************************************/
		try{
			short etherType;
			try {
            etherType = etherPacket.getEtherType();
        	} catch (Exception e) {
        	    System.err.println("Failed to read EtherType - dropping pkt: " + e);
        	    e.printStackTrace();
        	    return;
        	}
			System.out.println("*** -> EtherType: " + etherType);
			if (etherType != Ethernet.TYPE_IPv4) {
            	System.out.println("Not IPv4 - dropping");
            	return;
        	}
			IPacket payload = etherPacket.getPayload();
			if (!(payload instanceof IPv4)) {
            	System.out.println("Ethernet payload not IPv4 instance - dropping");
            	return;
        	}
        	IPv4 ipv4Packet = (IPv4) payload;
			// Verify checksum then TTL
			short originalChecksum = ipv4Packet.getChecksum();
    		ipv4Packet.setChecksum((short) 0);
    		byte[] hdrBytes = ipv4Packet.serialize(); // recomputes checksum internally
    		IPv4 temp = new IPv4();
    		temp.deserialize(hdrBytes, 0, hdrBytes.length);
			short computed = temp.getChecksum();
    		if (originalChecksum != computed) {
    		    System.out.println("Invalid checksum — dropping pkt");
    		    return;
    		}
			// After success verify TTL > 1:
			byte ttl = ipv4Packet.getTtl();
			ttl--;
			if (ttl <= 0){
				System.out.println("TTL <= 0 dropping");
				return;
			}
			ipv4Packet.setTtl(ttl);
    		ipv4Packet.setChecksum((short)0);
			// See if the packet is destined for one of the interfaces or not
			/** If the packet’s destination IP address exactly matches one of
				the interface’s IP addresses (not necessarily the incoming interface), then you no need to do any further
				processing - i.e., router should drop the packet. */
			for (Iface iface : this.interfaces.values()) {
            	if (ipv4Packet.getDestinationAddress() == iface.getIpAddress()) {
            	    System.out.println("Packet destined for router interface " + iface.getName() + " - dropping");
            	    return;
            	}
       		}
			// else: use lookup from RouterTable.java to find the destination IP address match.
			RouteEntry bestRoute = this.routeTable.lookup(ipv4Packet.getDestinationAddress());
			if (bestRoute == null || bestRoute.getInterface() == null) {
    			System.out.println("No valid route found => dropping pkt");
    			return;
			}
			// If matches a dest, then determine next-hop IP address and lookup MAC of that IP. 
			int nextHop = bestRoute.getGatewayAddress();
    		if (nextHop == 0) {
    		    nextHop = ipv4Packet.getDestinationAddress();
    		}
			// call lookup(...) in the edu.wisc.cs.sdn.vnet.rt.ArpCache class to obtain the MAC from the statically populated ARP cache.
			ArpEntry arpEntry = this.arpCache.lookup(nextHop);
    		if (arpEntry == null || arpEntry.getMac() == null) {
    		    System.out.println("ARP entry not found => dropping pkt");
    		    return;
    		}
			// Tis iz the new dest MAC addr & the MAC of the outgoing interface should be the new source MAC address for the Ethernet frame.
			etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());
    		etherPacket.setSourceMACAddress(bestRoute.getInterface().getMacAddress().toBytes());
			// --- Recompute IPv4 checksum via serialization ---
    		ipv4Packet.setChecksum((short) 0);
    		ipv4Packet.serialize();  // will auto-fill checksum
				// After all this call sendPacket(...) inherited from edu.wisc.cs.sdn.vnet.Device to send
			sendPacket(etherPacket , bestRoute.getInterface());
			System.out.println("*** [+] Forwarded packet to " + IPv4.fromIPv4Address(ipv4Packet.getDestinationAddress()) +
                           " via iface " + bestRoute.getInterface().getName() +
                           " (nextHop=" + IPv4.fromIPv4Address(nextHop) + ", dstMAC=" + arpEntry.getMac() + ")");
		} catch (Exception e){
			System.err.println("Exception in handlePacket: " + e);
    		e.printStackTrace();
		}
		/********************************************************************/
	}
}
