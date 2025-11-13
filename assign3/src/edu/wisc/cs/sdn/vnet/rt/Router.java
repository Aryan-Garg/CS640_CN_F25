package edu.wisc.cs.sdn.vnet.rt;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet; /** use for getEtherType -> To determine IPv4 or not */
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;
import net.floodlightcontroller.packet.UDP;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;
	
	private static class RipInfo {
        int metric;
        long lastUpdate;
        boolean isDirect;

        RipInfo(int metric, boolean isDirect) {
            this.metric = metric;
            this.isDirect = isDirect;
            this.lastUpdate = System.currentTimeMillis();
        }
    }

    /** key = destination subnet; value = RIP info */
    private Map<Integer, RipInfo> ripMap = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	private static final int RIP_PORT = 520;
    private static final int RIP_TIMEOUT_MS = 30000;
    private static final int RIP_RESPONSE_INTERVAL_MS = 10000;
    private static final int RIP_INFINITY = 16;

	

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


	public void startRIP() 
	{
    	// Seed directly connected routes (gateway = 0; metric handled by RouteTable)
    	for (Iface iface : this.interfaces.values()) {
    	    int subnet = iface.getIpAddress() & iface.getSubnetMask();
    	    int mask   = iface.getSubnetMask();

    	    // Insert "direct" route with no gateway
    	    // Signature is the standard CS640: insert(destination, gateway, mask, iface)
    	    this.routeTable.insert(subnet, 0, mask, iface);
			
			this.ripMap.put(subnet, new RipInfo(0, true));

    	    System.out.println(
        		String.format(
            	"Inserted direct route: subnet=%s mask=%s iface=%s",
            	IPv4.fromIPv4Address(subnet),
            	IPv4.fromIPv4Address(mask),
            	iface.getName()
        	));
    	}

		/* Send RIP requests */
        for (Iface iface : this.interfaces.values()) {
            sendRipRequest(iface);
        }

		/* Periodic unsolicited responses */
        scheduler.scheduleAtFixedRate(() -> sendPeriodicRipResponses(), 0, RIP_RESPONSE_INTERVAL_MS, TimeUnit.MILLISECONDS);
		
		/* Route timeout sweeper */
        scheduler.scheduleAtFixedRate(() -> sweepRipTimeouts(), 1, 1, TimeUnit.SECONDS);
	}

	 private void sendRipRequest(Iface iface)
    {
        RIPv2 rip = new RIPv2();
        rip.setCommand(RIPv2.COMMAND_REQUEST);
        rip.setEntries(new LinkedList<RIPv2Entry>()); // empty = request all routes

        sendRipPacket(rip, iface, IPv4.toIPv4Address("224.0.0.9"), Ethernet.toMACAddress("FF:FF:FF:FF:FF:FF"));
    }

	private void sendPeriodicRipResponses()
    {
        for (Iface iface : this.interfaces.values()) {
            RIPv2 rip = buildRipResponse();
            sendRipPacket(rip, iface,
                    IPv4.toIPv4Address("224.0.0.9"),
                    Ethernet.toMACAddress("FF:FF:FF:FF:FF:FF"));
        }
    }

	private RIPv2 buildRipResponse()
    {
        RIPv2 rip = new RIPv2();
        rip.setCommand(RIPv2.COMMAND_RESPONSE);

        List<RIPv2Entry> entries = new LinkedList<>();

        for (RouteEntry entry : this.routeTable.getEntries()) {
            int subnet = entry.getDestinationAddress();
            int mask   = entry.getMaskAddress();

            RipInfo info = ripMap.get(subnet);
            if (info == null) continue;

            RIPv2Entry r = new RIPv2Entry();
            r.setAddress(subnet);
            r.setSubnetMask(mask);
            r.setMetric(info.metric);

            entries.add(r);
        }

        rip.setEntries(entries);
        return rip;
    }


	private void sendRipPacket(RIPv2 rip, Iface outIface, int dstIp, byte[] dstMac)
    {
        UDP udp = new UDP();
        udp.setSourcePort((short)RIP_PORT);
        udp.setDestinationPort((short)RIP_PORT);

        IPv4 ip = new IPv4();
        ip.setProtocol(IPv4.PROTOCOL_UDP);
        ip.setSourceAddress(outIface.getIpAddress());
        ip.setDestinationAddress(dstIp);
        ip.setTtl((byte)1);

        Ethernet ether = new Ethernet();
        ether.setSourceMACAddress(outIface.getMacAddress().toBytes());
        ether.setDestinationMACAddress(dstMac);
        ether.setEtherType(Ethernet.TYPE_IPv4);

        udp.setPayload(rip);
        ip.setPayload(udp);
        ether.setPayload(ip);

        sendPacket(ether, outIface);

        System.out.println("Sent RIP packet out " + outIface.getName());
    }


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

	@Override
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		// System.out.println("After toString ....");
		if (etherPacket == null) {
        	System.out.println("[DROP] Null Ethernet frame");
        	return;
    	}
		/********************************************************************/
		short etherType;
		try{
			etherType = etherPacket.getEtherType();
		} catch (Exception e){
			System.err.println("[DROP] Bad EtherType: " + e);
    		e.printStackTrace();
    		return;
		}
		
		System.out.println("[DEBUG] EtherType: " + etherType);
		if (etherType != Ethernet.TYPE_IPv4) {
        	System.out.println("[DROP] Not IPv4");
        	return;
        }
		IPacket payload = etherPacket.getPayload();
		if (!(payload instanceof IPv4)) {
        	System.out.println(" [DROP] Ethernet payload not IPv4 instance");
        	return;
        }
        IPv4 ipv4Packet = (IPv4) payload;
		if (ipv4Packet.getProtocol() == IPv4.PROTOCOL_UDP) {
        	UDP udp = (UDP) ipv4Packet.getPayload();
        	
			if (udp.getDestinationPort() == RIP_PORT) {

				boolean selfPacket = false;
				for (Iface iface : this.interfaces.values()) {
    				if (ipv4Packet.getSourceAddress() == iface.getIpAddress()) {
    				    selfPacket = true;
    				    break;
    				}
				}

				if (selfPacket) {
				    // DEBUG print 
				    System.out.println("[RIP] Dropping self-originated RIP packet");
				    return;
				}

				System.out.println("[RIP] Received RIP packet on " + inIface.getName());
        	    handleRipPacket(etherPacket, ipv4Packet, udp, inIface);
        	    return;
        	}
        }
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
			System.out.println("[DROP] TTL expired (<= 0)");
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
    		System.out.println("[DROP] No valid route found => dropping pkt");
    		return;
		}
		// If matches a dest, then determine next-hop IP address and lookup MAC of that IP. 
		int nextHop = bestRoute.getGatewayAddress();
    	if (nextHop == 0) nextHop = ipv4Packet.getDestinationAddress();
    	
		ArpEntry arpEntry = this.arpCache.lookup(nextHop);
    	if (arpEntry == null || arpEntry.getMac() == null) {
    	    System.out.println("ARP entry not found => dropping pkt");
    	    return;
    	}
		// Tis iz the new dest MAC addr & the MAC of the outgoing interface should be the new source MAC address for the Ethernet frame.
		etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());
    	etherPacket.setSourceMACAddress(bestRoute.getInterface().getMacAddress().toBytes());
    	ipv4Packet.setChecksum((short) 0);
    	ipv4Packet.serialize();  // will auto-fill checksum
		
		// After all this call sendPacket(...) inherited from edu.wisc.cs.sdn.vnet.Device to send
		sendPacket(etherPacket , bestRoute.getInterface());
		System.out.println("*** [++FORWARD] " + IPv4.fromIPv4Address(ipv4Packet.getDestinationAddress()) +
                       " via iface " + bestRoute.getInterface().getName() +
                       " (nextHop=" + IPv4.fromIPv4Address(nextHop) + ", dstMAC=" + arpEntry.getMac() + ")");
		
	}

	public void handleRipPacket(Ethernet ether, IPv4 ip, UDP udp, Iface iface)
    {
        RIPv2 rip = (RIPv2) udp.getPayload();

        if (rip.getCommand() == RIPv2.COMMAND_REQUEST) {
            /* Reply with full routing table to requesting router */
            RIPv2 response = buildRipResponse();
            sendRipPacket(response, iface, ip.getSourceAddress(), ether.getSourceMACAddress());
            return;
        }

        if (rip.getCommand() == RIPv2.COMMAND_RESPONSE) {
            processRipResponse(rip, ip.getSourceAddress(), iface);
        }
    }

	private void processRipResponse(RIPv2 rip, int senderIp, Iface iface)
    {
        long now = System.currentTimeMillis();

        for (RIPv2Entry entry : rip.getEntries()) {

            int subnet = entry.getAddress();
            int mask   = entry.getSubnetMask();
            int metric = entry.getMetric();

            int newMetric = Math.min(RIP_INFINITY, metric + 1);

            RouteEntry existing = routeTable.lookup(subnet);

            boolean exists = (existing != null &&
                    existing.getMaskAddress() == mask);

            RipInfo existingInfo = ripMap.get(subnet);

            if (!exists) {
                if (newMetric >= RIP_INFINITY) continue;

                routeTable.insert(subnet, senderIp, mask, iface);
                ripMap.put(subnet, new RipInfo(newMetric, false));
                System.out.println("[RIP] Added new route " +
                    IPv4.fromIPv4Address(subnet) +
                    " metric=" + newMetric);
            }
            else {
                if (existingInfo == null) continue;

                if (newMetric < existingInfo.metric) {
                    routeTable.update(subnet, mask, senderIp, iface);
                    existingInfo.metric = newMetric;
                    existingInfo.lastUpdate = now;
                    System.out.println("[RIP] Improved route " +
                        IPv4.fromIPv4Address(subnet) +
                        " metric=" + newMetric);
                }
                else {
                    existingInfo.lastUpdate = now;
                }
            }
        }
    }

	private void sweepRipTimeouts()
    {
        long now = System.currentTimeMillis();

        for (Integer subnet : new HashSet<>(ripMap.keySet())) {
            RipInfo info = ripMap.get(subnet);
            if (info == null || info.isDirect) continue;

            if (now - info.lastUpdate > RIP_TIMEOUT_MS) {
                /* Timed out */
                for (RouteEntry e : routeTable.getEntries()) {
                    if (e.getDestinationAddress() == subnet) {
                        routeTable.remove(subnet, e.getMaskAddress());
                        break;
                    }
                }
                ripMap.remove(subnet);
                System.out.println("[RIP] Removed stale route " +
                    IPv4.fromIPv4Address(subnet));
            }
        }
    }

}
