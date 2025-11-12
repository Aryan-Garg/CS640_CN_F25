
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
 * Router with RIP v2 (distance vector) control plane.
 * - Runs RIP only if no static route file was supplied.
 * - Sends a RIP request on init; periodic unsolicited responses every 10s.
 * - Expires learned routes after 30s (not directly connected).
 * - Handles RIP request/response on UDP port 520.
 *
 * Assumes ArpCache, RouteTable, and Device/Iface infrastructure from course code.
 */
public class Router extends Device
{
    /** Routing table for the router */
    private RouteTable routeTable;

    /** ARP cache */
    private ArpCache arpCache;

    /** If we should run RIP (no static route file) */
    private final boolean runRIP;

    /** Periodic timers */
    private Timer ripTimer;
    private Timer expiryTimer;

    /** Constants */
    private static final int RIP_PERIOD_MS = 10_000;     // unsolicited response interval
    private static final int ROUTE_TIMEOUT_MS = 30_000;  // learned route timeout
    private static final int RIP_INFINITY = 16;

    public Router(String host, DumpFile logfile, boolean runRIP)
    { super(host, logfile); 
        this.routeTable = new RouteTable(); 
        this.arpCache = new ArpCache(); 
        this.runRIP = runRIP; 
    }

    public RouteTable getRouteTable() { return this.routeTable; }

    /**
     * Load a routing table from a file (used only when -r is present).
     */
    public boolean loadRouteTable(String routeTableFile)
    {
        return this.routeTable.load(routeTableFile);
    }

    /** Load an ARP cache from a file (optional) */
    public boolean loadArpCache(String arpCacheFile)
    {
        return this.arpCache.load(arpCacheFile);
    }

    @Override
    public void handlePacket(Ethernet etherPacket, Iface inIface)
    {
        if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
        {
            // Non-IP: we don't route these.
            return;
        }

        IPv4 ip = (IPv4) etherPacket.getPayload();

        // Verify checksum and TTL
        if (!verifyAndDecrementTTL(ip)) { return; }

        // RIP handling on UDP/520
        if (this.runRIP && ip.getProtocol() == IPv4.PROTOCOL_UDP)
        {
            UDP udp = (UDP) ip.getPayload();
            if (udp.getDestinationPort() == UDP.RIP_PORT || udp.getSourcePort() == UDP.RIP_PORT)
            {
                handleRip(etherPacket, inIface);
                return;
            }
        }

        forwardIPv4(etherPacket);
    }

    /** Forward IPv4 packet using longest prefix match and ARP cache */
    private void forwardIPv4(Ethernet etherPacket)
    {
        IPv4 ip = (IPv4) etherPacket.getPayload();
        int dst = ip.getDestinationAddress();

        RouteTable.RouteEntry best = this.routeTable.lookup(dst);
        if (best == null) { sendICMPUnreachable(etherPacket); return; }

        Iface outIface = best.getInterface();
        int nextHop = best.getGatewayAddress();
        if (nextHop == 0) nextHop = dst;

        // Rewrite L2 addresses
        ARP.ArpEntry arp = this.arpCache.lookup(nextHop);
        if (arp == null) { /* No ARP resolution available in this assignment path; drop. */ return; }

        etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());
        etherPacket.setDestinationMACAddress(arp.getMac().toBytes());

        this.sendPacket(etherPacket, outIface);
    }

    /** Very small ICMP unreachable helper (best-effort, not graded here) */
    private void sendICMPUnreachable(Ethernet original)
    {
        // Optional: implement if your framework expects this. Safe to no-op.
    }

    /** Decrement TTL and recompute header checksum. Return false if expired or bad checksum. */
    private boolean verifyAndDecrementTTL(IPv4 ip)
    {
        short oldCksum = ip.getChecksum();
        ip.resetChecksum();
        if (oldCksum != ip.getChecksum())
        {
            // Bad checksum
            return false;
        }
        byte ttl = ip.getTtl();
        ttl--;
        if (ttl <= 0) { return false; }
        ip.setTtl(ttl);
        ip.resetChecksum();
        return true;
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
