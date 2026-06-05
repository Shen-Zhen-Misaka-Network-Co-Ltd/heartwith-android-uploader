package com.heartwith.uploader;

public final class HeartwithSleepStatus {
    public static final String STATE_IN_BED = "in_bed";
    public static final String STATE_ASLEEP = "asleep";
    public static final String STATE_AWAKE = "awake";

    public final String state;
    public final long observedAtMs;
    public final long bedAtMs;
    public final long sleepAtMs;
    public final long wakeAtMs;
    public final long goBedAtMs;
    public final long deviceBedAtMs;
    public final long leaveBedAtMs;
    public final long deviceWakeAtMs;
    public final String source;
    public final boolean stable;
    public final long durationMinutes;

    public HeartwithSleepStatus(
            String state,
            long observedAtMs,
            long bedAtMs,
            long sleepAtMs,
            long wakeAtMs,
            String source,
            boolean stable,
            long durationMinutes
    ) {
        this(
                state,
                observedAtMs,
                bedAtMs,
                sleepAtMs,
                wakeAtMs,
                bedAtMs,
                sleepAtMs,
                wakeAtMs,
                wakeAtMs,
                source,
                stable,
                durationMinutes);
    }

    public HeartwithSleepStatus(
            String state,
            long observedAtMs,
            long bedAtMs,
            long sleepAtMs,
            long wakeAtMs,
            long goBedAtMs,
            long deviceBedAtMs,
            long leaveBedAtMs,
            long deviceWakeAtMs,
            String source,
            boolean stable,
            long durationMinutes
    ) {
        this.state = normalizeState(state);
        this.observedAtMs = observedAtMs > 0L ? observedAtMs : System.currentTimeMillis();
        this.bedAtMs = Math.max(0L, bedAtMs);
        this.sleepAtMs = Math.max(0L, sleepAtMs);
        this.wakeAtMs = Math.max(0L, wakeAtMs);
        this.goBedAtMs = Math.max(0L, goBedAtMs);
        this.deviceBedAtMs = Math.max(0L, deviceBedAtMs);
        this.leaveBedAtMs = Math.max(0L, leaveBedAtMs);
        this.deviceWakeAtMs = Math.max(0L, deviceWakeAtMs);
        this.source = source == null || source.trim().isEmpty() ? "mi_health_sleep" : source.trim();
        this.stable = stable;
        this.durationMinutes = Math.max(0L, durationMinutes);
    }

    private static String normalizeState(String value) {
        if (STATE_ASLEEP.equals(value) || STATE_AWAKE.equals(value) || "awake_in_bed".equals(value)) {
            if ("awake_in_bed".equals(value)) {
                return STATE_AWAKE;
            }
            return value;
        }
        return STATE_IN_BED;
    }
}
