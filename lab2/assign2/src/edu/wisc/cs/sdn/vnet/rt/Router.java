package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet; /** use for getEtherType -> To determine IPv4 or not */
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;

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
		
		/********************************************************************/
		/* TODO: Handle packets  
		 * Complete the handlePacket(...) method to update and send a received packet out the appropriate interface of the router. */
		boolean isv4 = (etherPacket.getEtherType() ==  0x0800); // 0x0800 is for TYPE_IPv4 in Ethernet.java file
		if (isv4){
			// Cast packet to IPv4 first then get payload tho
			IPv4 ipv4_etherPacket = (IPv4) etherPacket.getPayload();

			// Verify checksum then TTL
			short originalChecksum = ipv4_etherPacket.getChecksum();
    		ipv4_etherPacket.setChecksum((short) 0);
    		byte[] data = ipv4_etherPacket.serialize(); // recomputes checksum internally
    		IPv4 temp = new IPv4();
    		temp.deserialize(data, 0, data.length);
    		if (originalChecksum != temp.getChecksum()) {
    		    System.out.println("Invalid checksum — dropping pkt");
    		    return;
    		}

			// After success verify TTL > 1:
			byte ttl = ipv4_etherPacket.getTtl();
			ttl--;
			if (ttl <= 0){
				System.out.println("TTL expired — dropping packet");
				return;
			}
			else{
				ipv4_etherPacket.setTtl(ttl);
    			ipv4_etherPacket.resetChecksum();
				// See if the packet is destined for one of the interfaces or not
				/** If the packet’s destination IP address exactly matches one of
					the interface’s IP addresses (not necessarily the incoming interface), then you no need to do any further
					processing - i.e., router should drop the packet. */
				for (Iface iface : this.interfaces.values()) {
        			if (ipv4_etherPacket.getDestinationAddress() == iface.getIpAddress()) {
        			    System.out.println("Packet destined for router => dropping pkt");
        			    return;
        			}
    			}
				// else: use lookup from RouterTable.java to find the destination IP address match.
				RouteEntry bestRoute = this.routeTable.lookup(ipv4_etherPacket.getDestinationAddress());
				if (bestRoute == null) {
        			System.out.println("No route found => dropping pkt");
        			return;
    			}
				// If matches a dest, then determine next-hop IP address and lookup MAC of that IP. 
				int nextHop = bestRoute.getGatewayAddress();
    			if (nextHop == 0) {
    			    nextHop = ipv4_etherPacket.getDestinationAddress();
    			}
				// call lookup(...) in the edu.wisc.cs.sdn.vnet.rt.ArpCache class to obtain the MAC from the statically populated ARP cache.
				ArpEntry arpEntry = this.arpCache.lookup(nextHop);
    			if (arpEntry == null) {
    			    System.out.println("ARP entry not found => dropping pkt");
    			    return;
    			}
				// Tis iz the new dest MAC addr & the MAC of the outgoing interface should be the new source MAC address for the Ethernet frame.
				etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());
    			etherPacket.setSourceMACAddress(bestRoute.getInterface().getMacAddress().toBytes());
				// --- Recompute IPv4 checksum via serialization ---
    			ipv4_etherPacket.setChecksum((short) 0);
    			ipv4_etherPacket.serialize();  // will auto-fill checksum
				// After all this call sendPacket(...) inherited from edu.wisc.cs.sdn.vnet.Device to send
				sendPacket(etherPacket , bestRoute.getInterface());
			}
		}
		else{
			// Packet dropped
			return;
		}
		
		/********************************************************************/
	}
}
