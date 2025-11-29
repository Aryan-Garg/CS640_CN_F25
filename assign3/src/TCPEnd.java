

import java.util.*;

public class TCPend {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        Map<String, String> opt = parseArgs(args);

        // Decide sender vs receiver
        if (opt.containsKey("-s")) {
            // Sender mode
            Sender.run(opt);
        } else {
            // Receiver mode
            Receiver.run(opt);
        }
    }

    private static void usage() {
        System.out.println("Sender:");
        System.out.println("  java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>");
        System.out.println("Receiver:");
        System.out.println("  java TCPend -p <port> -m <mtu> -c <sws> -f <file name>");
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-p") || a.equals("-s") || a.equals("-a") ||
                a.equals("-f") || a.equals("-m") || a.equals("-c")) {
                if (i + 1 < args.length) {
                    m.put(a, args[++i]);
                }
            }
        }
        return m;
    }
}
