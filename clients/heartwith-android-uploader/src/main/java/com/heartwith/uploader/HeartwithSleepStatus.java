package com.heartwith.uploader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    public final List<Segment> segments;

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
                durationMinutes,
                null);
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
        this(
                state,
                observedAtMs,
                bedAtMs,
                sleepAtMs,
                wakeAtMs,
                goBedAtMs,
                deviceBedAtMs,
                leaveBedAtMs,
                deviceWakeAtMs,
                source,
                stable,
                durationMinutes,
                null);
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
            long durationMinutes,
            List<Segment> segments
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
        if (segments == null || segments.isEmpty()) {
            this.segments = Collections.emptyList();
        } else {
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        }
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

    public static final class Segment {
        public final long bedAtMs;
        public final long wakeAtMs;
        public final long deviceBedAtMs;
        public final long deviceWakeAtMs;
        public final long durationMinutes;
        public final long deepMinutes;
        public final long lightMinutes;
        public final long remMinutes;
        public final long awakeMinutes;
        public final long awakeCount;
        public final long score;

        public Segment(
                long bedAtMs,
                long wakeAtMs,
                long deviceBedAtMs,
                long deviceWakeAtMs,
                long durationMinutes,
                long deepMinutes,
                long lightMinutes,
                long remMinutes,
                long awakeMinutes,
                long awakeCount,
                long score
        ) {
            this.bedAtMs = Math.max(0L, bedAtMs);
            this.wakeAtMs = Math.max(0L, wakeAtMs);
            this.deviceBedAtMs = Math.max(0L, deviceBedAtMs);
            this.deviceWakeAtMs = Math.max(0L, deviceWakeAtMs);
            this.durationMinutes = Math.max(0L, durationMinutes);
            this.deepMinutes = Math.max(0L, deepMinutes);
            this.lightMinutes = Math.max(0L, lightMinutes);
            this.remMinutes = Math.max(0L, remMinutes);
            this.awakeMinutes = Math.max(0L, awakeMinutes);
            this.awakeCount = Math.max(0L, awakeCount);
            this.score = Math.max(0L, score);
        }
    }
}
