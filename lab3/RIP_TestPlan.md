
# CS640 A3 — RIP v2 implementation: Test plan and stress checklist

## Quick start
- Build and run without `-r` to enable RIP:
  ```bash
  java -jar VirtualNetwork.jar -v r1 -a arp_cache
  ```
- Use `pair_rt.topo`, `triangle_rt.topo`, `linear5.topo`.
- Use `tcpdump` on hosts to observe RIP: `sudo tcpdump -n -vv -e -i h1-eth0 port 520`

## Functional tests

### 1. Initialization
- [ ] Router seeds directly connected routes (metric 0, no gateway).
- [ ] Immediately sends RIP **request** out all interfaces (UDP 520 → 224.0.0.9, L2 broadcast).

### 2. Periodic unsolicited responses (every 10s)
- [ ] Each interface emits RIP **response** to 224.0.0.9 with all table entries.
- [ ] Ethernet dst MAC is FF:FF:FF:FF:FF:FF.

### 3. Handling RIP request
- [ ] Unicast RIP **response** back to requesting IP/MAC, same interface only.

### 4. Handling RIP response (distance vector)
- [ ] For each entry: install or update `metric = min(16, neighborMetric + 1)`.
- [ ] Gateway set to **sender IP**; interface = incoming interface.
- [ ] Ignore or mark 16 as unreachable; do not forward such routes.
- [ ] Triggered update: send response on input interface after any change.

### 5. Timeouts
- [ ] Learned routes (non-direct) expire if not refreshed for **> 30s**.
- [ ] Directly connected routes are never removed.

### 6. Forwarding data
- [ ] Longest prefix match selects best route.
- [ ] If gateway == 0 use destination IP as next hop.
- [ ] Ethernet rewrite: src MAC = outIface MAC; dst MAC via ARP cache.
- [ ] Drop if ARP not found.

## Failover tests

### Triangle ring
- Topology: r1—r2—r3—r1 with h1 behind r1 and h2 behind r2.
- Procedure:
  1. Wait for convergence; ping h1 ↔ h2 succeeds.
  2. Bring link r1–r2 **down**: `link r1 r2 down`.
  3. Ensure ping still succeeds via r1→r3→r2.
  4. Restore link; convergence should recover with shortest path.

### Route expiry
- Break a link so a path disappears.
- Verify the corresponding route is aged out after 30s.

## Negative tests

- [ ] UDP packet to port ≠ 520 is not processed by RIP path.
- [ ] Bad IPv4 checksum drops packet.
- [ ] TTL expiry drops packet.
- [ ] Response with metric ≥ 16 does not get installed as usable route.

## Observability
- [ ] Add temporary debug logs around: initial seeding, request send, response send, insert/update, expiry, LPM hit/miss.

## tcpdump hints
- RIP request:
  - `IP rX -> 224.0.0.9: UDP, length ...` and Ethernet dst FF:FF:FF:FF:FF:FF
- RIP response (unsolicited):
  - Same as above but with entries listed.
- Unicast response to requester:
  - L3 dst = requester; L2 dst = requester MAC.

## Edge cases
- [ ] Two neighbors advertising same prefix; prefer lower metric.
- [ ] Same neighbor increases metric; route updates accordingly.
- [ ] Interface subnet matches entry; ensure direct route remains metric 0.
- [ ] Mixed masks; LPM chooses longest match.

## Cleanup
- [ ] Timers are daemon threads; process exits cleanly when simulator stops.

