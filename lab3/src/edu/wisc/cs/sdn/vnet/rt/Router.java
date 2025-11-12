package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.ICMP;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.MACAddress;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;

	/** ARP cache for the router */
	private ArpCache arpCache;

	private final boolean runRIP;
    private Timer ripTimer;
    private Timer expiryTimer;
    private static final int RIP_PERIOD_MS = 10_000;     // unsolicited response interval
    private static final int ROUTE_TIMEOUT_MS = 30_000;  // learned route timeout
    private static final int RIP_INFINITY = 16;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
		this.runRIP = runRIP;
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
		/* TODO: Handle packets                                             */

		switch(etherPacket.getEtherType())
		{
		case Ethernet.TYPE_IPv4:
			this.handleIpPacket(etherPacket, inIface);
			break;
		// Ignore all other packet types, for now
		}

		/********************************************************************/
	}

	private void handleIpPacket(Ethernet etherPacket, Iface inIface)
	{
		// Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }

		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
		System.out.println("Handle IP packet");

		// Verify checksum
		short origCksum = ipPacket.getChecksum();
		ipPacket.resetChecksum();
		byte[] serialized = ipPacket.serialize();
		ipPacket.deserialize(serialized, 0, serialized.length);
		short calcCksum = ipPacket.getChecksum();
		if (origCksum != calcCksum)
		{ return; }

		// Check TTL
		ipPacket.setTtl((byte)(ipPacket.getTtl()-1));
		if (0 == ipPacket.getTtl())
		{ return; }

		// Reset checksum now that TTL is decremented
		ipPacket.resetChecksum();

		// Check if packet is destined for one of router's interfaces
		for (Iface iface : this.interfaces.values())
		{
			if (ipPacket.getDestinationAddress() == iface.getIpAddress())
			{ return; }
		}

		// Do route lookup and forward
		this.forwardIpPacket(etherPacket, inIface);
	}

	private void forwardIpPacket(Ethernet etherPacket, Iface inIface)
	{
		// Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }
		System.out.println("Forward IP packet");

		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
		int dstAddr = ipPacket.getDestinationAddress();

		// Find matching route table entry 
		RouteEntry bestMatch = this.routeTable.lookup(dstAddr);

		// If no entry matched, do nothing
		if (null == bestMatch)
		{ return; }

		// Make sure we don't sent a packet back out the interface it came in
		Iface outIface = bestMatch.getInterface();
		if (outIface == inIface)
		{ return; }

		// Set source MAC address in Ethernet header
		etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());

		// If no gateway, then nextHop is IP destination
		int nextHop = bestMatch.getGatewayAddress();
		if (0 == nextHop)
		{ nextHop = dstAddr; }

		// Set destination MAC address in Ethernet header
		ArpEntry arpEntry = this.arpCache.lookup(nextHop);
		if (null == arpEntry)
		{ return; }
		etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());

		this.sendPacket(etherPacket, outIface);
	}

    /* ============================= RIP ============================= */

    /** Initialize RIP – called by Main only when no static route file is provided. */
    public void startRIP()
    {
        if (!this.runRIP) return;

        // Seed route table with directly connected subnets (metric 0, no gateway)
        for (Iface iface : this.getInterfaces().values())
        {
            int subnet = iface.getIpAddress() & iface.getSubnetMask();
            this.routeTable.insert(subnet, 0, iface, 0 /* metric */, true /* directlyConnected */);
        }

        // Send initial RIP request out all interfaces
        sendRipRequestAll();

        // Periodic unsolicited responses
        this.ripTimer = new Timer("rip-broadcast", true);
        this.ripTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() { sendRipResponseAll(); }
        }, 0, RIP_PERIOD_MS);

        // Expiry sweeper
        this.expiryTimer = new Timer("rip-expirer", true);
        this.expiryTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() { routeTable.expireStale(ROUTE_TIMEOUT_MS); }
        }, ROUTE_TIMEOUT_MS, 1_000);
    }

    private void handleRip(Ethernet etherPacket, Iface inIface)
    {
        IPv4 ip = (IPv4) etherPacket.getPayload();
        UDP udp = (UDP) ip.getPayload();
        RIPv2 rip = (RIPv2) udp.getPayload();

        if (rip.getCommand() == RIPv2.COMMAND_REQUEST)
        {
            // Send a response back to the requester (unicast to source IP/MAC)
            sendRipResponseUnicast(inIface, etherPacket.getSourceMACAddress(), ip.getSourceAddress());
            return;
        }

        if (rip.getCommand() == RIPv2.COMMAND_RESPONSE)
        {
            boolean changed = false;
            int srcRouter = ip.getSourceAddress();
            for (RIPv2Entry e : rip.getEntries())
            {
                int prefix = e.getAddress();
                int mask = e.getSubnetMask();
                int metric = e.getMetric();

                // hop cost +1 via neighbor
                long newMetric = (long)metric + 1L;
                if (newMetric >= RIP_INFINITY) newMetric = RIP_INFINITY;

                if (newMetric < RIP_INFINITY)
                {
                    // Insert or update with gateway = srcRouter
                    boolean updated = this.routeTable.insert(prefix & mask, srcRouter, inIface, (int)newMetric, false);
                    changed = changed || updated;
                }
                else
                {
                    // Learned as unreachable – mark as stale to be expired
                    boolean updated = this.routeTable.markUnreachable(prefix & mask, srcRouter, inIface);
                    changed = changed || updated;
                }
            }

            if (changed)
            {
                // Triggered update: respond on inIface to speed convergence
                sendRipResponseIface(inIface);
            }
        }
    }

    private void sendRipRequestAll()
    {
        for (Iface iface : this.getInterfaces().values())
        {
            RIPv2 rip = new RIPv2();
            rip.setCommand(RIPv2.COMMAND_REQUEST);
            rip.setEntries(Collections.emptyList());
            Ethernet eth = buildRipEther(rip, iface, true, 0, null);
            this.sendPacket(eth, iface);
        }
    }

    private void sendRipResponseAll()
    {
        for (Iface iface : this.getInterfaces().values()) { sendRipResponseIface(iface); }
    }

    private void sendRipResponseIface(Iface iface)
    {
        RIPv2 rip = new RIPv2();
        rip.setCommand(RIPv2.COMMAND_RESPONSE);
        List<RIPv2Entry> entries = this.routeTable.toRipEntries();
        rip.setEntries(entries);
        Ethernet eth = buildRipEther(rip, iface, true, 0, null);
        this.sendPacket(eth, iface);
    }

    private void sendRipResponseUnicast(Iface outIface, MACAddress dstMac, int dstIp)
    {
        RIPv2 rip = new RIPv2();
        rip.setCommand(RIPv2.COMMAND_RESPONSE);
        rip.setEntries(this.routeTable.toRipEntries());
        Ethernet eth = buildRipEther(rip, outIface, false, dstIp, dstMac);
        this.sendPacket(eth, outIface);
    }

    /** Build Ethernet/IP/UDP/RIP packet */
    private Ethernet buildRipEther(RIPv2 rip, Iface outIface, boolean multicast, int unicastDstIp, MACAddress unicastDstMac)
    {
        // UDP
        UDP udp = new UDP();
        udp.setSourcePort(UDP.RIP_PORT);
        udp.setDestinationPort(UDP.RIP_PORT);
        udp.setPayload(rip);

        // IP
        IPv4 ip = new IPv4();
        ip.setTtl((byte) 64);
        ip.setProtocol(IPv4.PROTOCOL_UDP);
        ip.setSourceAddress(outIface.getIpAddress());
        if (multicast) ip.setDestinationAddress(IPv4.toIPv4Address("224.0.0.9"));
        else ip.setDestinationAddress(unicastDstIp);
        ip.setPayload(udp);

        // Ethernet
        Ethernet ether = new Ethernet();
        ether.setEtherType(Ethernet.TYPE_IPv4);
        ether.setSourceMACAddress(outIface.getMacAddress().toBytes());
        if (multicast)
            ether.setDestinationMACAddress(MACAddress.valueOf("ff:ff:ff:ff:ff:ff").toBytes());
        else
            ether.setDestinationMACAddress(unicastDstMac.toBytes());
        ether.setPayload(ip);
        return ether;
    }
}
