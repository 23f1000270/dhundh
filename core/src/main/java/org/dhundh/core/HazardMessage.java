package org.dhundh.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * One hazard warning, as broadcast over Bluetooth LE extended advertising.
 *
 * <p>Wire format (big-endian), 119 bytes:
 * <pre>
 *  0  u8   version (1)
 *  1  u8   type (see {@link Type})
 *  2  u8   hops  (incremented by relays; NOT covered by the signature)
 *  3  u32  time, seconds since 2020-01-01 UTC (GNSS time)
 *  7  u8   time, hundredths of a second
 *  8  i32  latitude,  1e-7 degree
 * 12  i32  longitude, 1e-7 degree
 * 16  u16  course over ground, 0.01 degree (0 = north, clockwise)
 * 18  u16  speed, 0.01 m/s
 * 20  u8   deceleration, 0.1 m/s^2
 * 21  u16  sequence number (per key)
 * 23  32B  sender's Ed25519 public key (ephemeral, rotated every few minutes)
 * 55  64B  Ed25519 signature over bytes 0..54 with the hops byte set to 0
 * </pre>
 * Relays change only the hops byte, so the original sender's signature still
 * verifies after any number of hops.
 */
public final class HazardMessage {

    public static final int VERSION = 1;
    public static final int SIGNED_LENGTH = 55;
    public static final int LENGTH = SIGNED_LENGTH + 64;
    public static final long EPOCH_2020_MS = 1577836800000L;
    public static final int MAX_HOPS = 4;

    public enum Type {
        HARD_BRAKE(1), STOPPED(2), CRASH(3),
        /** Field-test packet: shown to the receiver, never relayed, never a real alarm. */
        TEST(9);

        public final int code;

        Type(int code) {
            this.code = code;
        }

        static Type of(int code) {
            for (Type t : values()) {
                if (t.code == code) return t;
            }
            throw new IllegalArgumentException("unknown hazard type " + code);
        }
    }

    public final Type type;
    public final int hops;
    public final long timeMs;          // unix time in milliseconds (10 ms resolution on air)
    public final double latitude;
    public final double longitude;
    public final double courseDeg;
    public final double speedMps;
    public final double decelMps2;
    public final int sequence;
    public final byte[] publicKey;     // 32 bytes
    public final byte[] signature;     // 64 bytes, may be null before signing

    public HazardMessage(Type type, int hops, long timeMs, double latitude, double longitude, double courseDeg,
                         double speedMps, double decelMps2, int sequence, byte[] publicKey, byte[] signature) {
        if (publicKey == null || publicKey.length != 32) throw new IllegalArgumentException("public key must be 32 bytes");
        if (signature != null && signature.length != 64) throw new IllegalArgumentException("signature must be 64 bytes");
        this.type = type;
        this.hops = hops;
        this.timeMs = timeMs;
        this.latitude = latitude;
        this.longitude = longitude;
        this.courseDeg = ((courseDeg % 360) + 360) % 360;
        this.speedMps = speedMps;
        this.decelMps2 = decelMps2;
        this.sequence = sequence & 0xFFFF;
        this.publicKey = publicKey.clone();
        this.signature = signature == null ? null : signature.clone();
    }

    /** The bytes the signature covers: everything before the signature, hops forced to 0. */
    public byte[] signedBytes() {
        ByteBuffer b = ByteBuffer.allocate(SIGNED_LENGTH).order(ByteOrder.BIG_ENDIAN);
        long rel = timeMs - EPOCH_2020_MS;
        if (rel < 0) throw new IllegalStateException("time before 2020");
        b.put((byte) VERSION);
        b.put((byte) type.code);
        b.put((byte) 0);
        b.putInt((int) (rel / 1000));
        b.put((byte) ((rel % 1000) / 10));
        b.putInt((int) Math.round(latitude * 1e7));
        b.putInt((int) Math.round(longitude * 1e7));
        b.putShort((short) (Math.round(courseDeg * 100) % 36000));
        b.putShort((short) Math.min(0xFFFF, Math.round(speedMps * 100)));
        b.put((byte) Math.min(0xFF, Math.round(decelMps2 * 10)));
        b.putShort((short) sequence);
        b.put(publicKey);
        return b.array();
    }

    public HazardMessage signed(Signer signer) {
        return new HazardMessage(type, hops, timeMs, latitude, longitude, courseDeg, speedMps, decelMps2,
                sequence, publicKey, signer.sign(signedBytes()));
    }

    public byte[] encode() {
        if (signature == null) throw new IllegalStateException("sign before encoding");
        byte[] out = Arrays.copyOf(signedBytes(), LENGTH);
        out[2] = (byte) hops;
        System.arraycopy(signature, 0, out, SIGNED_LENGTH, 64);
        return out;
    }

    public static HazardMessage decode(byte[] data) {
        if (data == null || data.length != LENGTH) throw new IllegalArgumentException("bad length");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int version = b.get() & 0xFF;
        if (version != VERSION) throw new IllegalArgumentException("unsupported version " + version);
        Type type = Type.of(b.get() & 0xFF);
        int hops = b.get() & 0xFF;
        long secs = b.getInt() & 0xFFFFFFFFL;
        int centis = b.get() & 0xFF;
        double lat = b.getInt() / 1e7;
        double lon = b.getInt() / 1e7;
        double course = (b.getShort() & 0xFFFF) / 100.0;
        double speed = (b.getShort() & 0xFFFF) / 100.0;
        double decel = (b.get() & 0xFF) / 10.0;
        int seq = b.getShort() & 0xFFFF;
        byte[] key = new byte[32];
        b.get(key);
        byte[] sig = new byte[64];
        b.get(sig);
        long timeMs = EPOCH_2020_MS + secs * 1000 + centis * 10L;
        return new HazardMessage(type, hops, timeMs, lat, lon, course, speed, decel, seq, key, sig);
    }

    public boolean verify(Signer verifier) {
        return signature != null && verifier.verify(publicKey, signedBytes(), signature);
    }

    /** Same message, one hop further. The signature is untouched and still valid. */
    public HazardMessage relayed() {
        if (hops >= MAX_HOPS) throw new IllegalStateException("hop limit reached");
        return new HazardMessage(type, hops + 1, timeMs, latitude, longitude, courseDeg, speedMps, decelMps2,
                sequence, publicKey, signature);
    }
}
