package su.dsr.f515.qnx_adas;

/** Immutable speed sample; unknown is distinct from a measured zero. */
final class SpeedReading {
    static final SpeedReading UNKNOWN = new SpeedReading(Float.NaN, 0, "unavailable");
    final float kmh;
    final long elapsedMs;
    final String source;

    SpeedReading(float kmh, long elapsedMs, String source) {
        this.kmh = kmh;
        this.elapsedMs = elapsedMs;
        this.source = source;
    }

    static boolean valid(float kmh) { return kmh >= 0 && kmh <= 280; }

    boolean fresh(long now, long maxAge) {
        return valid(kmh) && elapsedMs > 0 && now >= elapsedMs && now - elapsedMs <= maxAge;
    }

    static SpeedReading select(SpeedReading vhal, SpeedReading gps, long now) {
        if (vhal.fresh(now, 1200)) return vhal;
        if (gps.fresh(now, 3000)) return gps;
        return UNKNOWN;
    }

    static SpeedReading parseBridge(String line, long now) {
        String[] fields = line.split(" ");
        if (fields.length != 3 || !fields[0].equals("SPEED")) return UNKNOWN;
        try {
            SpeedReading reading = new SpeedReading(Float.parseFloat(fields[2]),
                    Long.parseLong(fields[1]), "VHAL");
            return reading.fresh(now, 1200) ? reading : UNKNOWN;
        } catch (NumberFormatException e) { return UNKNOWN; }
    }
}
