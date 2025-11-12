
# CS640 Assignment 3 — RIP v2 Distance Vector (Complete Solution)

This submission implements RIP v2 in `Router.java`, extends `RouteTable.java` to track metrics and expiry, and starts RIP only when **no** static route file is provided (per assignment).

## Files changed
- `Router.java` — RIP control plane, IPv4 forwarding, timers.
- `RouteTable.java` — entries with metric, lastUpdated, direct flag, LPM lookup, RIP export, expiry.
- `Main.java` — enables RIP when `-r` is absent.

## How RIP is implemented
- On startup without `-r`:
  - Seed direct routes (metric 0).
  - Send RIP **request** to 224.0.0.9 on all interfaces.
  - Schedule unsolicited **responses** every 10s.
  - Expire learned routes if not refreshed for 30s.
- Receive:
  - **Request** → unicast **response** to requester.
  - **Response** → update table with metric = min(16, neighbor + 1); trigger a response on that interface if changed.

## Run
- With static table:
  ```bash
  java -jar VirtualNetwork.jar -v r1 -r rtable.r1 -a arp_cache
  ```
- With RIP:
  ```bash
  java -jar VirtualNetwork.jar -v r1 -a arp_cache
  ```

## Testing
See **RIP_TestPlan.md** for a production-grade checklist and tcpdump tips.

