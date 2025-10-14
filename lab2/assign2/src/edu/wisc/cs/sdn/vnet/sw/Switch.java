
package edu.wisc.cs.sdn.vnet.sw;

import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.MACAddress;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{
	// MAC address table that maps MAC addresses to interfaces
	private Map<MACAddress, SwitchEntry> macTable;

	// timeout period
	private static final long TIMEOUT = 15;

	/**
	 * Private class to store MAC entries with their timestamp
	 */
	private class SwitchEntry{
		Iface iface;
		long timestamp;

		SwitchEntry(Iface iface, long timestamp){
			this.iface = iface;
			this.timestamp = timestamp;
		}
	}

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.macTable = new HashMap<MACAddress, SwitchEntry>();
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

		//Grab the source MAC addr and reset timeout
		MACAddress src = etherPacket.getSourceMAC();
		long currTime = System.currentTimeMillis() / 1000; //convert to seconds
		System.out.println("*** -> Learning source MAC: " + src + " on interface: " + inIface.getName());
		macTable.put(src, new SwitchEntry(inIface, currTime));

		//Remove stale entries with private helper method
		removeStaleEntries(currTime);

		//Grab the destination MAC addr
		MACAddress dst = etherPacket.getDestinationMAC();
		System.out.println("*** -> Looking up destination MAC: " + dst);
		System.out.println("*** -> MAC table contains: " + macTable.keySet());
		SwitchEntry entry = macTable.get(dst);

		//If entry exists, forward to the correct interface
		if(entry != null){
			if(!entry.iface.equals(inIface)){
				System.out.println("Forwarding packet to interface: " + entry.iface.getName());
				sendPacket(etherPacket, entry.iface);
			}
		} else{
			// Dst not found, flood to all interfaces except inIface
			System.out.println("Flooding packet to all interfaces except " + inIface.getName());
			for(Iface iface : this.getInterfaces().values()){
				if(!iface.equals(inIface)){
					sendPacket(etherPacket, iface);
				}
			}
		}
	}

	/**
	 * Private helper to remove stale entries from the MAC table
	 * Entries > 15 seconds old are removed
	 * @param currTime current time in seconds
	 */
	private void removeStaleEntries(long currTime){
		Iterator<Map.Entry<MACAddress, SwitchEntry>> itr = macTable.entrySet().iterator();
		while(itr.hasNext()){
			Map.Entry<MACAddress, SwitchEntry> pair = itr.next();
			if((currTime - pair.getValue().timestamp) > TIMEOUT){
				itr.remove();
			}
		}
	}
}

