# What Dhundh does and does not protect against

Dhundh warns drivers. It never brakes or steers a car. So the worst a bad actor can do is make phones beep when nothing is wrong, or try to stop real warnings from arriving. We design for both, and we are honest about the limits.

## What the signature buys

Every warning is signed with the sender's Ed25519 key, and relays cannot change anything but the hop count. That stops:

* **Tampering in transit.** A relay cannot move a hazard 500 metres down the road or change "test" into "crash".
* **Replays with edits.** A recorded warning cannot be rebroadcast with a new time or position.
* **Floods from one key.** More than 30 warnings a minute from one key are dropped.

## What it does not stop

* **Fresh fake warnings.** Anyone can make a key and sign a lie. Keys are anonymous on purpose, so there is no authority to revoke them. A person with a laptop by the road could make nearby Dhundh phones beep.
* **Plain replays.** Re-sending an unchanged recorded warning within six seconds of it being made will be accepted once per receiver, since the sequence number is new to them. After six seconds it is stale and dropped.
* **Jamming.** Bluetooth can be jammed. Dhundh fails silent: no warning, which is no worse than today.

## Why this is acceptable for a warning system, and what comes next

A false beep costs a driver a moment of attention and some trust in the app. It cannot crash the car. To keep that trust:

* warnings must be plausible before they alarm: ahead of us, same direction of travel, fresh, and within a speed the road allows;
* in a later version, receivers will check that a warning's claimed position fits its Bluetooth signal strength, and give stronger alerts when two or more independent keys report the same hazard.

If warnings ever drive actuation (automatic braking), this model is not enough. That would need authenticated identities, as in the certificate-based security of C-V2X, and is out of scope here.
