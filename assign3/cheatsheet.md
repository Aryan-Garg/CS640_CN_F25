mininet@mininet-17.cs.wisc.edu
wAgNhdnK

1. 
./run_pox.sh

2. 
sudo ./run_mininet.py topos/pair_rt.topo -a
sudo ./run_mininet.py topos/single_rt.topo -a

3. 
ant
java -jar VirtualNetwork.jar -v r1 -a arp_cache
java -jar VirtualNetwork.jar -v r2 -a arp_cache
java -jar VirtualNetwork.jar -v r3 -a arp_cache