
package edu.wisc.cs.sdn.vnet.rt;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.RIPv2Entry;

/** Routing table with RIP metadata (metric, lastUpdated, directlyConnected). */
public class RouteTable
{
    public static class RouteEntry
    {
        private int destination;   // network address (masked)
        private int gateway;       // next hop IP (0 for direct)
        private int mask;          // subnet mask
        private Iface iface;
        private int metric;        // RIP cost (0 for direct)
        private long lastUpdated;  // epoch ms
        private boolean direct;

        public RouteEntry(int destination, int gateway, int mask, Iface iface, int metric, boolean direct)
        {
            this.destination = destination;
            this.gateway = gateway;
            this.mask = mask;
            this.iface = iface;
            this.metric = metric;
            this.direct = direct;
            touch();
        }

        public void touch() { this.lastUpdated = System.currentTimeMillis(); }

        public int getDestinationAddress() { return destination; }
        public int getGatewayAddress() { return gateway; }
        public int getMaskAddress() { return mask; }
        public Iface getInterface() { return iface; }
        public int getMetric() { return metric; }
        public boolean isDirect() { return direct; }

        public void set(int gateway, Iface iface, int metric, boolean direct)
        {
            this.gateway = gateway;
            this.iface = iface;
            this.metric = metric;
            this.direct = direct;
            touch();
        }
    }

    private final List<RouteEntry> entries = new ArrayList<>();

    /** Load static routes file (dest gateway mask ifaceName) */
    public boolean load(String filename)
    {
        try (BufferedReader br = new BufferedReader(new FileReader(filename)))
        {
            String line;
            while ((line = br.readLine()) != null)
            {
                if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) continue;
                int destination = IPv4.toIPv4Address(parts[0]);
                int gateway = IPv4.toIPv4Address(parts[1]);
                int mask = IPv4.toIPv4Address(parts[2]);
                String ifName = parts[3];
                Iface iface = Device.lookupInterfaceStatic(ifName); // helper expected in framework
                if (iface == null) continue;
                insert(destination & mask, gateway, iface, /*metric*/ gateway==0?0:1, false);
            }
            return true;
        }
        catch (Exception e) { return false; }
    }

    /** Longest-prefix match for destination IP */
    public RouteEntry lookup(int ip)
    {
        RouteEntry best = null;
        int bestLen = -1;
        for (RouteEntry e : entries)
        {
            if ((ip & e.getMaskAddress()) == e.getDestinationAddress())
            {
                int len = Integer.bitCount(e.getMaskAddress());
                if (len > bestLen) { best = e; bestLen = len; }
            }
        }
        return best;
    }

    /** Insert or update; returns true if table changed. */
    public synchronized boolean insert(int destination, int gateway, Iface iface, int metric, boolean direct)
    {
        int mask = (iface != null) ? iface.getSubnetMask() : 0xffffffff;
        // If iface is not null, we prefer its mask; otherwise assume /32.
        if (iface != null) destination = destination & iface.getSubnetMask();

        for (RouteEntry e : entries)
        {
            if (e.getDestinationAddress() == destination && e.getMaskAddress() == ((iface!=null)?iface.getSubnetMask():e.getMaskAddress()))
            {
                // Prefer lower metric or different next-hop update
                if (e.getGatewayAddress() != gateway || e.getInterface() != iface || e.getMetric() != metric)
                {
                    e.set(gateway, iface, metric, direct);
                    return true;
                }
                e.touch();
                return false;
            }
        }
        RouteEntry ne = new RouteEntry(destination, gateway, (iface!=null)?iface.getSubnetMask():0xffffffff, iface, metric, direct);
        entries.add(ne);
        return true;
    }

    /** Mark a route learned via a specific nextHop as unreachable; returns true if anything changed. */
    public synchronized boolean markUnreachable(int destination, int gateway, Iface iface)
    {
        for (RouteEntry e : entries)
        {
            if (e.getDestinationAddress() == destination && e.getGatewayAddress() == gateway && e.getInterface() == iface && !e.isDirect())
            {
                // Inflate metric; keep until expiry sweeper removes
                e.set(gateway, iface, 16, false);
                return true;
            }
        }
        return false;
    }

    /** Expire non-direct routes older than timeoutMs. */
    public synchronized void expireStale(long timeoutMs)
    {
        long now = System.currentTimeMillis();
        Iterator<RouteEntry> it = entries.iterator();
        while (it.hasNext())
        {
            RouteEntry e = it.next();
            if (e.isDirect()) continue;
            if (now - e.lastUpdated > timeoutMs) { it.remove(); }
        }
    }

    /** Export table as RIP entries for responses. */
    public synchronized List<RIPv2Entry> toRipEntries()
    {
        List<RIPv2Entry> out = new ArrayList<>();
        for (RouteEntry e : entries)
        {
            RIPv2Entry re = new RIPv2Entry();
            re.setAddress(e.getDestinationAddress());
            re.setSubnetMask(e.getMaskAddress());
            re.setNextHop(e.getGatewayAddress());
            re.setMetric(e.getMetric());
            out.add(re);
        }
        return out;
    }

    /** For debugging */
    public synchronized String toString()
    {
        StringBuilder sb = new StringBuilder();
        for (RouteEntry e : entries)
        {
            sb.append(String.format("%s/%d via %s dev %s metric %d%s\n",
                IPv4.fromIPv4Address(e.getDestinationAddress()),
                Integer.bitCount(e.getMaskAddress()),
                (e.getGatewayAddress()==0)?"direct":IPv4.fromIPv4Address(e.getGatewayAddress()),
                (e.getInterface()!=null)?e.getInterface().getName():"-",
                e.getMetric(),
                e.isDirect()?" (direct)":""
            ));
        }
        return sb.toString();
    }
}
