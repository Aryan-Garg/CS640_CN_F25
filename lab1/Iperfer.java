import java.io.*;
import java.net.*;

public class Iperfer {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Error: missing or additional arguments");
            return;
        }

        boolean isClient = false;

        if ("-c".equals(args[0])){
            // Client side

            // Parse all args
            isClient = true;
            String hostname = null;
            int serverPort = -1;
            int test_time_interval = -1;
            if (args.length != 7){
                System.out.println("Error: missing or additional arguments");
                return;
            }
            for(int i = 1; i < args.length; i++){
                switch (args[i]) {
                    case "-h":
                        if (i+1 < args.length){
                            hostname = args[++i];
                        }                        
                        // else{
                        //     System.out.println("Error: missing or additional arguments");
                        //     return;
                        // }
                        break;
                
                    case "-p":
                        if (i+1 < args.length){
                            serverPort = Integer.parseInt(args[++i]);
                            if (serverPort < 1024 || serverPort > 65535){
                                System.out.println("Error: port number must be in the range 1024 to 65535");
                                return;
                            }
                        }                        
                        // else{
                        //     System.out.println("Error: missing or additional arguments");
                        //     return;
                        // }
                        break;
                    
                    case "-t":
                        if (i+1 < args.length){
                            test_time_interval = Integer.parseInt(args[++i]);
                        }                        
                        // else{
                        //     System.out.println("Error: missing or additional arguments");
                        //     return;
                        // }
                        break;
                }
            }
            openClientConnection(serverPort, hostname, test_time_interval);
        }
        else if("-s".equals(args[0])){
            // Server side 

            // Parse all args
            isClient = false;
            if (args.length != 3){
                System.out.println("Error: missing or additional arguments");
                return;
            }
            int port = -1;
            if ("-p".equals(args[1])){
                port = Integer.parseInt(args[2]);
                if (port < 1024 || port > 65535){
                    System.out.println("Error: port number must be in the range 1024 to 65535");
                    return;
                }
            }

            // Create socket that listens for incoming connections on port 
            openServerSocket(port);
            
        }
        else {
            // -s or -c was not passed
            System.out.println("Error: missing or additional arguments");
            return;
        }
    }


    private static void openServerSocket(int portNumber){
        // Modified from Reference: 
        // https://docs.oracle.com/javase/tutorial/networking/sockets/clientServer.html

        int CHUNK_SIZE = 1000;
        byte[] buffer = new byte[CHUNK_SIZE];

        try (
            ServerSocket serverSocket = new ServerSocket(portNumber);
            Socket clientSocket = serverSocket.accept();
            InputStream inp = clientSocket.getInputStream();
            // PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
            // BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        ){
            long totalBytesReceived = 0;
            long start = System.currentTimeMillis();
            int bytesRead;
            while ((bytesRead = inp.read(buffer)) != -1) {
                totalBytesReceived += bytesRead;
            }
            long end = System.currentTimeMillis();
            printSummary(start, end, totalBytesReceived);
           
        } catch (IOException e){
            System.out.println("Exception caught when trying to listen on port " + portNumber + " or listening for a connection");
            System.out.println(e.getMessage());
        }
    }


    private static void openClientConnection(int portNumber, String hostName, int time){
        // Modified from Reference: 
        // https://docs.oracle.com/javase/tutorial/networking/sockets/clientServer.html
        int chunkSize = 1000; // bytes
        byte[] buffer = new byte[chunkSize]; // each element/byte is zero by default
        try (
            Socket socket = new Socket(hostName, portNumber);
            OutputStream out = socket.getOutputStream()
            // PrintWriter out = new PrintWriter(thisSocket.getOutputStream(), true);
            // BufferedReader in = new BufferedReader(
            //     new InputStreamReader(thisSocket.getInputStream()));
        ) {
            long totalBytesSent = 0;
            long start = System.currentTimeMillis();

            while ((System.currentTimeMillis() - start) < time * 1000L) { // multiply by 1000 cuz time is inputted in sec and not ms.
                out.write(buffer);
                totalBytesSent += chunkSize;
            }

            long end = System.currentTimeMillis();
            printSummary(start, end, totalBytesSent);
        } catch (UnknownHostException e) {
            System.err.println("Don't know about host " + hostName);
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Couldn't get I/O for the connection to " + hostName);
            System.exit(1);
        }
    }


    private static void printSummary(long start, long end, long bytesSent){
        double inSeconds = (end - start) / 1000.0;
        long kBs_sent = bytesSent / 1000;
        double Mbps = ((kBs_sent * 8.0) / 1000.0) / inSeconds;
        System.out.printf("sent=%d KB rate=%.3f Mbps", kBs_sent, Mbps);
        return;
    }
}
