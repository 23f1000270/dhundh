package org.dhundh.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Everything that happens to a received packet before the driver hears about it:
 * decode, verify the signature, drop replays and floods, decide relevance, and
 * decide whether to pass it on.
 */
public final class Inbox {

    public static final int MAX_PER_KEY_PER_MIN = 30;

    public static final class Outcome {
        public final HazardMessage message;          // null if dropped
        public final Relevance.Verdict verdict;      // null if dropped before judging
        public final HazardMessage relay;            // non-null if we should rebroadcast
        public final String dropped;                 // reason, or null

        Outcome(HazardMessage m, Relevance.Verdict v, HazardMessage relay, String dropped) {
            this.message = m;
            this.verdict = v;
            this.relay = relay;
            this.dropped = dropped;
        }
    }

    private final Signer verifier;
    private final Map<String, Long> seen = new HashMap<>();
    private final Map<String, List<Long>> perKey = new HashMap<>();

    public Inbox(Signer verifier) {
        this.verifier = verifier;
    }

    public Outcome receive(byte[] packet, double lat, double lon, double course, double speed, long nowMs) {
        HazardMessage m;
        try {
            m = HazardMessage.decode(packet);
        } catch (IllegalArgumentException e) {
            return new Outcome(null, null, null, "malformed");
        }
        String id = hex(m.publicKey) + ":" + m.sequence;
        prune(nowMs);
        if (seen.containsKey(id)) return new Outcome(null, null, null, "duplicate");
        if (!m.verify(verifier)) return new Outcome(null, null, null, "bad signature");
        List<Long> times = perKey.computeIfAbsent(hex(m.publicKey), k -> new ArrayList<>());
        times.add(nowMs);
        if (times.size() > MAX_PER_KEY_PER_MIN) return new Outcome(null, null, null, "rate limit");
        seen.put(id, nowMs);
        Relevance.Verdict v = Relevance.judge(m, lat, lon, course, speed, nowMs);
        // Pass fresh warnings back along the road even if they do not concern us:
        // the car behind us may be heading straight for the hazard.
        boolean fresh = !"stale".equals(v.reason);
        boolean relayable = fresh && m.type != HazardMessage.Type.TEST && m.hops < HazardMessage.MAX_HOPS;
        HazardMessage relay = relayable ? m.relayed() : null;
        return new Outcome(m, v, relay, null);
    }

    private void prune(long nowMs) {
        seen.values().removeIf(t -> nowMs - t > 60_000);
        Iterator<Map.Entry<String, List<Long>>> it = perKey.entrySet().iterator();
        while (it.hasNext()) {
            List<Long> l = it.next().getValue();
            l.removeIf(t -> nowMs - t > 60_000);
            if (l.isEmpty()) it.remove();
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
