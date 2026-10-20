package org.dhundh.core;

/**
 * Decides whether a received hazard matters to this vehicle, and how urgently.
 *
 * <p>A hazard is relevant when it is ahead of us (the bearing to it is close
 * to our course), it was reported by a vehicle travelling the same way (its
 * course is close to ours, so a car braking on the opposite carriageway does
 * not alarm us), it is within the look-ahead distance, and it is fresh.
 * Urgency comes from the time we would take to reach it at our current speed.
 *
 * <p>GNSS course is meaningless at walking pace, so below
 * {@link #MIN_COURSE_SPEED} the direction tests are relaxed to distance only.
 */
public final class Relevance {

    public static final double EARTH_RADIUS_M = 6_371_000.0;
    public static final double MIN_COURSE_SPEED = 4.0;      // m/s
    public static final double AHEAD_CONE_DEG = 35.0;
    public static final double SAME_WAY_DEG = 45.0;
    public static final double LOOK_AHEAD_M = 1000.0;
    public static final double MAX_AGE_S = 6.0;
    public static final double URGENT_S = 10.0;
    public static final double CAUTION_S = 30.0;

    public enum Level { IGNORE, CAUTION, URGENT }

    public static final class Verdict {
        public final Level level;
        public final double distanceM;
        public final double secondsToReach;
        public final String reason;

        Verdict(Level level, double distanceM, double secondsToReach, String reason) {
            this.level = level;
            this.distanceM = distanceM;
            this.secondsToReach = secondsToReach;
            this.reason = reason;
        }
    }

    private Relevance() {}

    /** Distance in metres, equirectangular approximation (fine below a few km). */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * EARTH_RADIUS_M;
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from north. */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return (Math.toDegrees(Math.atan2(x, y)) + 360) % 360;
    }

    public static double angleBetween(double a, double b) {
        double d = Math.abs(((a - b) % 360 + 360) % 360);
        return d > 180 ? 360 - d : d;
    }

    public static Verdict judge(HazardMessage h, double lat, double lon, double courseDeg, double speedMps,
                                long nowMs) {
        double age = (nowMs - h.timeMs) / 1000.0;
        if (age > MAX_AGE_S || age < -2.0) return new Verdict(Level.IGNORE, Double.NaN, Double.NaN, "stale");
        double d = distance(lat, lon, h.latitude, h.longitude);
        if (d > LOOK_AHEAD_M) return new Verdict(Level.IGNORE, d, Double.NaN, "too far");
        boolean moving = speedMps >= MIN_COURSE_SPEED;
        if (moving && d > 15) {
            double brg = bearing(lat, lon, h.latitude, h.longitude);
            if (angleBetween(brg, courseDeg) > AHEAD_CONE_DEG) return new Verdict(Level.IGNORE, d, Double.NaN, "not ahead");
            boolean senderMoving = h.speedMps >= MIN_COURSE_SPEED || h.type == HazardMessage.Type.HARD_BRAKE;
            if (senderMoving && angleBetween(h.courseDeg, courseDeg) > SAME_WAY_DEG) {
                return new Verdict(Level.IGNORE, d, Double.NaN, "other direction");
            }
        }
        double ttr = moving ? d / speedMps : Double.POSITIVE_INFINITY;
        Level level = ttr <= URGENT_S || d < 60 ? Level.URGENT : ttr <= CAUTION_S ? Level.CAUTION : Level.IGNORE;
        return new Verdict(level, d, ttr, level == Level.IGNORE ? "far enough" : "ahead");
    }
}
