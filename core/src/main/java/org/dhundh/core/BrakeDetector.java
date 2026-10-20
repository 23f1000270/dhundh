package org.dhundh.core;

/**
 * Turns GNSS speed and phone acceleration into hazard events.
 *
 * <p>The phone can sit anywhere in the car at any angle, so we do not try to
 * find the car's forward axis. Instead:
 * <ul>
 *   <li>Hard braking needs both the GNSS speed to fall fast (at least
 *       {@link #BRAKE_MPS2} averaged over {@link #WINDOW_S}) and the phone to
 *       feel a matching horizontal acceleration. Either alone is too easy to
 *       fool: GNSS speed jumps under bridges, and potholes shake the phone.</li>
 *   <li>Stopped means slower than 1.5 m/s for 3 s on a road where we were
 *       doing more than 12 m/s in the last 30 s: a stop where nobody stops.</li>
 *   <li>A crash is an acceleration spike above 4 g.</li>
 * </ul>
 * Each kind of event is reported at most once per {@link #COOLDOWN_S}.
 */
public final class BrakeDetector {

    public static final double BRAKE_MPS2 = 4.0;
    public static final double WINDOW_S = 1.0;
    public static final double CRASH_MPS2 = 4 * 9.81;
    public static final double COOLDOWN_S = 10.0;

    private final double[] t = new double[64];
    private final double[] v = new double[64];
    private int head = 0;
    private int count = 0;
    private double peakHorizontal = 0;
    private double peakAt = -1e9;
    private double fastUntil = -1e9;
    private double slowSince = Double.NaN;
    private double lastBrake = -1e9;
    private double lastStop = -1e9;
    private double lastCrash = -1e9;

    /** Feed a GNSS fix. Returns an event type or null. */
    public HazardMessage.Type onSpeed(double timeS, double speedMps) {
        t[head] = timeS;
        v[head] = speedMps;
        head = (head + 1) % t.length;
        count = Math.min(count + 1, t.length);
        if (speedMps > 12) fastUntil = timeS + 30;

        if (speedMps < 1.5) {
            if (Double.isNaN(slowSince)) slowSince = timeS;
            if (timeS - slowSince >= 3 && timeS <= fastUntil && timeS - lastStop > COOLDOWN_S) {
                lastStop = timeS;
                return HazardMessage.Type.STOPPED;
            }
        } else {
            slowSince = Double.NaN;
        }

        double decel = decelOver(WINDOW_S, timeS);
        boolean phoneAgrees = timeS - peakAt <= 1.5 && peakHorizontal >= 0.6 * BRAKE_MPS2;
        if (decel >= BRAKE_MPS2 && phoneAgrees && timeS - lastBrake > COOLDOWN_S) {
            lastBrake = timeS;
            return HazardMessage.Type.HARD_BRAKE;
        }
        return null;
    }

    /**
     * Feed one accelerometer sample with gravity already removed (Android's
     * linear acceleration sensor), plus the gravity direction to split
     * horizontal from vertical. Returns CRASH or null.
     */
    public HazardMessage.Type onAccel(double timeS, double ax, double ay, double az, double gx, double gy, double gz) {
        double gn = Math.sqrt(gx * gx + gy * gy + gz * gz);
        double total = Math.sqrt(ax * ax + ay * ay + az * az);
        double vertical = gn > 0 ? Math.abs((ax * gx + ay * gy + az * gz) / gn) : 0;
        double horizontal = Math.sqrt(Math.max(0, total * total - vertical * vertical));
        if (horizontal > peakHorizontal || timeS - peakAt > 1.5) {
            peakHorizontal = horizontal;
            peakAt = timeS;
        }
        if (total >= CRASH_MPS2 && timeS - lastCrash > COOLDOWN_S) {
            lastCrash = timeS;
            return HazardMessage.Type.CRASH;
        }
        return null;
    }

    /** Deceleration (m/s^2, positive when slowing) over the last second of GNSS fixes. */
    public double recentDecel(double now) {
        return Math.max(0, decelOver(WINDOW_S, now));
    }

    /** Average deceleration (positive when slowing) over the last `window` seconds. */
    double decelOver(double window, double now) {
        if (count < 2) return 0;
        int newest = (head - 1 + t.length) % t.length;
        int idx = newest;
        for (int k = 1; k < count; k++) {
            int prev = (newest - k + t.length) % t.length;
            idx = prev;
            if (now - t[prev] >= window) break;
        }
        double dt = t[newest] - t[idx];
        return dt > 0 ? (v[idx] - v[newest]) / dt : 0;
    }
}
