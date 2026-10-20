# Field test plan

The simulator says early warnings help if they arrive fast and far enough. The field test measures whether real phones in real vehicles deliver that.

## Setup

* Three Android phones with Bluetooth 5. Record each phone's model and whether it reports LE Coded PHY (shown on the app's main screen).
* Three vehicles on a straight, closed or quiet stretch of road (we use a campus road with permission). Phones mounted on the dashboard.
* Nothing in this test involves real emergency braking on a public road. Hard-braking tests use the app's "Send a test warning" button; braking detection is tested separately at low speed on a closed stretch.

## Measurements

Every phone writes `dhundh_log.csv` (app storage, `Android/data/org.dhundh.app/files/`). From it we compute:

1. **Range.** Parked vehicles, sender fixed, receiver driven away in 25-metre steps. Ten test warnings per step. Record the share received and the signal strength. Repeat for Coded PHY and 1M PHY, phones inside cars and outside.
2. **Latency.** Difference between the receiver's clock when the packet arrived and the time in the packet, with both phones on GNSS time.
3. **Relay.** Three vehicles in a line, the first and last out of direct range. Does the middle phone pass the warning on, and how much later does it arrive?
4. **Relevance.** Vehicles driving towards each other must not alert; vehicles behind must not alert; vehicles following must.
5. **Battery.** Battery drop per hour with the app running, screen off.

## Results

To be filled in after the test. Each number will come with the phone models and conditions it was measured under.
