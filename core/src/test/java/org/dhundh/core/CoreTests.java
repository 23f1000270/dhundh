package org.dhundh.core;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free tests for the core. Run: ./gradlew :core:test (or see README). */
public final class CoreTests {

    private static final List<String> failures = new ArrayList<>();
    private static int passed = 0;

    public static void main(String[] args) {
        run("message round-trips through bytes", CoreTests::roundTrip);
        run("signature covers every field except hops", CoreTests::signature);
        run("relays keep the signature valid and stop at the hop limit", CoreTests::relay);
        run("inbox drops duplicates, forgeries and floods", CoreTests::inbox);
        run("relevance: ahead, same way, fresh", CoreTests::relevanceAhead);
        run("relevance: ignores the other carriageway and things behind", CoreTests::relevanceIgnores);
        run("brake detector needs GNSS and phone to agree", CoreTests::brake);
        run("stopped on a fast road, and crash spikes", CoreTests::stoppedAndCrash);
        System.out.println(passed + " passed, " + failures.size() + " failed");
        for (String f : failures) System.out.println("FAIL " + f);
        if (!failures.isEmpty()) System.exit(1);
    }

    interface Test { void run() throws Exception; }

    static void run(String name, Test t) {
        try {
            t.run();
            passed++;
        } catch (Throwable e) {
            failures.add(name + ": " + e);
        }
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static final long NOW = 1_790_000_000_000L; // 2026-09-21, unix ms

    static HazardMessage sample(Signer s, int seq, long time, double lat, double lon, double course) {
        return new HazardMessage(HazardMessage.Type.HARD_BRAKE, 0, time, lat, lon, course, 22.5, 6.3, seq,
                s.publicKey(), null).signed(s);
    }

    static void roundTrip() {
        Signer s = new JdkSigner();
        HazardMessage m = sample(s, 7, NOW + 120, 27.4924321, 77.6737654, 151.25);
        byte[] b = m.encode();
        check(b.length == HazardMessage.LENGTH && b.length == 119, "length " + b.length);
        HazardMessage d = HazardMessage.decode(b);
        check(d.type == m.type && d.sequence == 7 && d.hops == 0, "fields");
        check(Math.abs(d.latitude - 27.4924321) < 1e-7 && Math.abs(d.longitude - 77.6737654) < 1e-7, "position");
        check(Math.abs(d.courseDeg - 151.25) < 0.01 && Math.abs(d.speedMps - 22.5) < 0.01, "motion");
        check(Math.abs(d.decelMps2 - 6.3) < 0.051 && d.timeMs == NOW + 120, "decel/time " + d.timeMs);
        check(d.verify(s), "verifies after decode");
    }

    static void signature() {
        Signer s = new JdkSigner();
        byte[] b = sample(s, 1, NOW, 12.99, 80.23, 10).encode();
        for (int i = 0; i < HazardMessage.SIGNED_LENGTH; i++) {
            if (i == 2) continue; // hops
            byte[] t = b.clone();
            t[i] ^= 0x01;
            try {
                check(!HazardMessage.decode(t).verify(s), "tampering byte " + i + " must break the signature");
            } catch (IllegalArgumentException e) {
                // tampering produced an invalid header: also rejected
            }
        }
        byte[] h = b.clone();
        h[2] = 3;
        check(HazardMessage.decode(h).verify(s), "hops is outside the signature");
    }

    static void relay() {
        Signer s = new JdkSigner();
        HazardMessage m = sample(s, 2, NOW, 12.99, 80.23, 10);
        for (int i = 0; i < HazardMessage.MAX_HOPS; i++) m = HazardMessage.decode(m.relayed().encode());
        check(m.hops == HazardMessage.MAX_HOPS && m.verify(s), "still valid after max hops");
        boolean threw = false;
        try { m.relayed(); } catch (IllegalStateException e) { threw = true; }
        check(threw, "no relay past the limit");
    }

    static void inbox() {
        Signer s = new JdkSigner();
        Inbox in = new Inbox(s);
        // receiver 200 m south of the hazard, driving north at 25 m/s
        double lat = 12.9900, lon = 80.2300;
        double hzLat = lat + 200 / 111_195.0;
        byte[] p = sample(s, 5, NOW, hzLat, lon, 0).encode();
        Inbox.Outcome first = in.receive(p, lat, lon, 0, 25, NOW + 300);
        check(first.dropped == null && first.verdict.level == Relevance.Level.URGENT, "urgent: " + first.dropped);
        check(first.relay != null && first.relay.hops == 1, "relayed once");
        check("duplicate".equals(in.receive(p, lat, lon, 0, 25, NOW + 400).dropped), "duplicate dropped");
        byte[] forged = sample(s, 6, NOW, hzLat, lon, 0).encode();
        forged[60] ^= 0x10; // corrupt the signature
        check("bad signature".equals(in.receive(forged, lat, lon, 0, 25, NOW + 400).dropped), "forgery dropped");
        Inbox flood = new Inbox(s);
        String last = null;
        for (int i = 0; i < Inbox.MAX_PER_KEY_PER_MIN + 5; i++) {
            last = flood.receive(sample(s, 100 + i, NOW, hzLat, lon, 0).encode(), lat, lon, 0, 25, NOW + 10).dropped;
        }
        check("rate limit".equals(last), "flood limited, got " + last);
    }

    static void relevanceAhead() {
        Signer s = new JdkSigner();
        double lat = 28.40, lon = 77.30;
        // 400 m ahead to the north-east, we drive at 45 degrees
        double d = 400 / Math.sqrt(2);
        double hLat = lat + d / 111_195.0;
        double hLon = lon + d / (111_195.0 * Math.cos(Math.toRadians(lat)));
        HazardMessage h = sample(s, 1, NOW, hLat, hLon, 47);
        Relevance.Verdict v = Relevance.judge(h, lat, lon, 45, 25, NOW + 500);
        check(Math.abs(v.distanceM - 400) < 2, "distance " + v.distanceM);
        check(v.level == Relevance.Level.CAUTION, "16 s away at 25 m/s is caution, got " + v.level);
        Relevance.Verdict fast = Relevance.judge(h, lat, lon, 45, 42, NOW + 500);
        check(fast.level == Relevance.Level.URGENT, "under 10 s away is urgent, got " + fast.level);
        Relevance.Verdict slow = Relevance.judge(h, lat, lon, 45, 12, NOW + 500);
        check(slow.level == Relevance.Level.IGNORE, "33 s away is beyond the caution horizon, got " + slow.level);
        check(Relevance.judge(h, lat, lon, 45, 25, NOW + 9000).level == Relevance.Level.IGNORE, "stale ignored");
    }

    static void relevanceIgnores() {
        Signer s = new JdkSigner();
        double lat = 28.40, lon = 77.30;
        double hLat = lat + 300 / 111_195.0;
        HazardMessage oncoming = sample(s, 1, NOW, hLat, lon, 180); // braking on the other carriageway
        check(Relevance.judge(oncoming, lat, lon, 0, 25, NOW).reason.equals("other direction"), "other direction");
        HazardMessage behind = sample(s, 2, NOW, lat - 300 / 111_195.0, lon, 0);
        check(Relevance.judge(behind, lat, lon, 0, 25, NOW).reason.equals("not ahead"), "behind ignored");
    }

    static void brake() {
        BrakeDetector a = new BrakeDetector();
        // GNSS alone: speed drops 25 -> 18 in one second, phone feels nothing
        check(a.onSpeed(0, 25) == null && a.onSpeed(1, 18) == null, "GNSS alone is not enough");
        BrakeDetector b = new BrakeDetector();
        b.onSpeed(0, 25);
        b.onAccel(0.6, 4.2, 3.0, 0.2, 0, 0, 9.81); // horizontal ~5.2 m/s^2
        check(b.onSpeed(1, 19) == HazardMessage.Type.HARD_BRAKE, "both agree: hard brake");
        check(b.onSpeed(2, 13) == null, "cooldown");
        BrakeDetector c = new BrakeDetector();
        c.onSpeed(0, 25);
        c.onAccel(0.5, 0.2, 0.1, 7.0, 0, 0, 9.81); // pothole: vertical jolt only
        check(c.onSpeed(1, 19) == null, "vertical jolt is not braking");
    }

    static void stoppedAndCrash() {
        BrakeDetector b = new BrakeDetector();
        b.onSpeed(0, 20);
        HazardMessage.Type got = null;
        for (int i = 1; i <= 6; i++) {
            HazardMessage.Type e = b.onSpeed(i, i <= 1 ? 10 : 0.5);
            if (e != null) got = e;
        }
        check(got == HazardMessage.Type.STOPPED, "stopped after being fast, got " + got);
        BrakeDetector city = new BrakeDetector();
        HazardMessage.Type g2 = null;
        for (int i = 0; i <= 6; i++) {
            HazardMessage.Type e = city.onSpeed(i, i < 2 ? 8 : 0.5);
            if (e != null) g2 = e;
        }
        check(g2 == null, "a normal stop at city speed is not a hazard");
        check(new BrakeDetector().onAccel(0, 30, 25, 5, 0, 0, 9.81) == HazardMessage.Type.CRASH, "crash spike");
    }
}
