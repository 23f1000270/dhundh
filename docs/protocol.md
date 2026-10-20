# The Dhundh warning protocol, version 1

Dhundh phones talk to each other with Bluetooth LE extended advertisements. There is no pairing, no connection, no server and no internet: a phone that brakes hard shouts, and every phone in range hears it.

## Radio

* **Long range.** Phones that support the LE Coded PHY (Bluetooth 5) advertise on it. Coded PHY trades data rate for roughly four times the range of ordinary Bluetooth LE. Phones without it use the 1M PHY. Every phone scans on all PHYs it supports, so mixed fleets still hear each other.
* **Extended advertising.** A warning is 119 bytes, too big for a legacy 31-byte advertisement. It travels as service data under the 128-bit UUID `6f1d0a3e-8c2b-4c5e-9d7a-2b1e5f40d4a1`.
* **Bursts.** Each warning is advertised every 100 ms for 3 seconds, so a phone that misses a few packets still hears it.

## Message

| Offset | Size | Field | Notes |
|---:|---:|---|---|
| 0 | 1 | version | 1 |
| 1 | 1 | type | 1 hard braking, 2 stopped vehicle, 3 crash, 9 test |
| 2 | 1 | hops | 0 when sent; each relay adds 1; not signed |
| 3 | 4 | time | seconds since 2020-01-01 UTC, from GNSS |
| 7 | 1 | time | hundredths of a second |
| 8 | 4 | latitude | 1e-7 degree |
| 12 | 4 | longitude | 1e-7 degree |
| 16 | 2 | course | 0.01 degree, clockwise from north |
| 18 | 2 | speed | 0.01 m/s |
| 20 | 1 | deceleration | 0.1 m/s² |
| 21 | 2 | sequence | per key, wraps |
| 23 | 32 | public key | sender's Ed25519 key, rotated every 5 minutes |
| 55 | 64 | signature | Ed25519 over bytes 0 to 54 with hops set to 0 |

All integers are big-endian.

## Receiving

A phone that hears a packet:

1. decodes it and drops anything malformed;
2. drops it if this key and sequence number were already seen in the last minute;
3. verifies the signature;
4. drops it if the key has sent more than 30 messages in the last minute;
5. judges relevance: the hazard must be ahead (bearing within 35 degrees of our course), reported by a vehicle going our way (course within 45 degrees, so braking on the other carriageway is ignored), within 1 km and less than 6 seconds old;
6. alerts the driver if we would reach it within 30 seconds (caution) or 10 seconds or 60 metres (urgent);
7. relays it once, hops plus one, up to 4 hops, whether or not it concerned us. The car behind us may be heading straight for it.

Test packets (type 9) are shown but never relayed.

## Privacy

Packets carry no name, number plate, phone identifier or Bluetooth address that lasts. Android randomises the advertising address, and the signing key changes every five minutes, so a listener cannot follow one car for long. The position is the one the hazard happened at, which is the point of the message.
