
package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;

public class Main
{
    public static void main(String[] args)
    {
        String host = null;
        String routeFile = null;
        String arpFile = null;
        DumpFile dump = null;

        for (int i=0; i<args.length; i++)
        {
            switch (args[i])
            {
                case "-v": host = args[++i]; break;
                case "-r": routeFile = args[++i]; break;
                case "-a": arpFile = args[++i]; break;
                case "-l": dump = new DumpFile(args[++i]); break;
                default: break;
            }
        }
        if (host == null) { System.err.println("Missing -v <routerName>"); System.exit(1); }

        boolean runRIP = (routeFile == null);
        Router router = new Router(host, dump, runRIP);

        if (arpFile != null && !router.loadArpCache(arpFile))
        { System.err.println("Warning: failed to load ARP cache from " + arpFile); }

        if (routeFile != null && !router.loadRouteTable(routeFile))
        { System.err.println("Warning: failed to load route table " + routeFile); }

        if (runRIP) { router.startRIP(); }

        // Hand over control to the framework (blocks)
        Device.run(router);
    }
}
