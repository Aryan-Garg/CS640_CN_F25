ssh -X mininet@mininet-17.cs.wisc.edu
wAgNhdnK

1. 
./run_pox.sh

2. 
sudo ./run_mininet.py topos/single_rt.topo -a

3. 
ant
java -jar VirtualNetwork.jar -v r1 -a arp_cache
java -jar VirtualNetwork.jar -v r2 -a arp_cache
java -jar VirtualNetwork.jar -v r3 -a arp_cache


# Lab 4

java -cp bin TCPend -p 6007 -s 10.0.2.102 -a 6008 -f sendfile.md -m 1024 -c 4
java -cp bin TCPend -p 6008 -m 1024 -c 4 -f sendFile_received2.md