package com.heartwith.uploader;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HeartwithUploader {
    private static final long MIN_BATCH_WINDOW_MS = 8_000L;
    private static final long MAX_BATCH_WINDOW_MS = 30_000L;
    private static final long FLUSH_TIMER_SLACK_MS = 5_000L;
    private static final long OFFLINE_CACHE_MS = 300_000L;
    private static final long INITIAL_RETRY_BACKOFF_MS = 15_000L;
    private static final long MAX_RETRY_BACKOFF_MS = 120_000L;
    private static final int CHANGE_FLUSH_BPM = 3;
    private static final Pattern COLLECTOR_ID = Pattern.compile("\"collector_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern COLLECTOR_TOKEN = Pattern.compile("\"collector_token\"\\s*:\\s*\"([^\"]+)\"");

    private final Executor worker;
    private final HeartwithHttpClient httpClient;
    private final Handler handler;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private HeartwithUploadConfig config;
    private HeartwithUploadStatusListener statusListener;
    private Session session;
    private long seq = 1;
    private long lastFlushMs;
    private long nextUploadAttemptMs;
    private long retryBackoffMs = INITIAL_RETRY_BACKOFF_MS;
    private int lastUploadedBpm = -1;
    private boolean uploadInFlight;
    private boolean delayedFlushScheduled;
    private long delayedFlushDueElapsedMs;
    private int delayedFlushGeneration;

    public HeartwithUploader(Executor worker) {
        this(worker, new UrlConnectionHeartwithHttpClient());
    }

    public HeartwithUploader(Executor worker, HeartwithHttpClient httpClient) {
        this.worker = worker;
        this.httpClient = httpClient;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public synchronized void configure(HeartwithUploadConfig next) {
        if (next == null) {
            return;
        }
        if (config == null
                || !next.serverUrl.equals(config.serverUrl)
                || !next.displayName.equals(config.displayName)
                || !next.deviceModel.equals(config.deviceModel)
                || !next.clientPlatform.equals(config.clientPlatform)) {
            session = null;
            seq = 1;
            retryBackoffMs = INITIAL_RETRY_BACKOFF_MS;
            nextUploadAttemptMs = 0L;
            if (HeartwithUploaderDebug.ENABLED) {
                HeartwithUploaderDebug.log("config changed; reset session");
            }
        }
        config = next;
        if (next.enabled && !samples.isEmpty()) {
            scheduleDelayedFlush(1_000L);
        }
    }

    public synchronized void setStatusListener(HeartwithUploadStatusListener listener) {
        statusListener = listener;
    }

    public void submitHeartRate(final int bpm, final long timestampMs, final Integer rssi, final String source) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                onHeartRate(bpm, timestampMs, rssi, source);
            }
        });
    }

    public void flush() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                flushLockedEntry();
            }
        });
    }

    private synchronized void onHeartRate(int bpm, long timestampMs, Integer rssi, String source) {
        if (bpm < 30 || bpm > 240) {
            return;
        }
        long now = System.currentTimeMillis();
        samples.addLast(new Sample(timestampMs > 0 ? timestampMs : now, bpm, rssi, source));
        trim(now);
        if (!shouldFlush(now, bpm) || uploadInFlight || now < nextUploadAttemptMs) {
            notifyStatus("已缓存心率，等待批量上传");
            scheduleDelayedFlush(nextFlushDelayMs(now));
            return;
        }
        flushLocked();
    }

    private synchronized void flushLockedEntry() {
        trim(System.currentTimeMillis());
        if (samples.isEmpty()) {
            return;
        }
        if (uploadInFlight || System.currentTimeMillis() < nextUploadAttemptMs) {
            scheduleDelayedFlush(nextFlushDelayMs(System.currentTimeMillis()));
            return;
        }
        flushLocked();
    }

    private void flushLocked() {
        uploadInFlight = true;
        try {
            if (config == null) {
                notifyStatus("上传配置未就绪，继续缓存");
                if (HeartwithUploaderDebug.ENABLED) {
                    HeartwithUploaderDebug.log("upload config unavailable; cache samples");
                }
                return;
            }
            if (!config.enabled) {
                clearSamplesAndCancelDelayedFlush();
                session = null;
                notifyStatus("上传已关闭");
                return;
            }
            ensureSession();
            if (session == null || samples.isEmpty()) {
                return;
            }
            int sampleCount = samples.size();
            byte[] body = buildBatchCbor(session.collectorId, seq, config);
            HeartwithHttpClient.Response response = httpClient.post(
                    config.serverUrl + "/api/v1/hr/batches",
                    "application/cbor",
                    body,
                    "Bearer " + session.collectorToken);
            if (!response.isSuccessful()) {
                throw new IllegalStateException("batch http " + response.code);
            }
            Sample last = samples.peekLast();
            lastUploadedBpm = last == null ? lastUploadedBpm : last.bpm;
            clearSamplesAndCancelDelayedFlush();
            lastFlushMs = System.currentTimeMillis();
            nextUploadAttemptMs = 0L;
            retryBackoffMs = INITIAL_RETRY_BACKOFF_MS;
            notifyStatus("上传成功 " + sampleCount + " 条 · seq " + seq);
            if (HeartwithUploaderDebug.ENABLED) {
                HeartwithUploaderDebug.log("upload ok: samples=" + sampleCount + ", seq=" + seq);
            }
            seq += 1;
        } catch (Throwable throwable) {
            nextUploadAttemptMs = System.currentTimeMillis() + retryBackoffMs;
            retryBackoffMs = Math.min(MAX_RETRY_BACKOFF_MS, retryBackoffMs * 2L);
            notifyStatus("上传失败：" + throwable.getClass().getSimpleName() + " · 已缓存 " + samples.size() + " 条");
            if (HeartwithUploaderDebug.ENABLED) {
                HeartwithUploaderDebug.log("upload failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
            scheduleDelayedFlush(nextFlushDelayMs(System.currentTimeMillis()));
        } finally {
            uploadInFlight = false;
        }
    }

    private void ensureSession() throws Exception {
        if (session != null) {
            return;
        }
        String json = "{"
                + "\"display_name\":\"" + escapeJson(config.displayName) + "\","
                + "\"device_model\":\"" + escapeJson(config.deviceModel) + "\","
                + "\"client_platform\":\"" + escapeJson(config.clientPlatform) + "\","
                + "\"app_version\":\"" + escapeJson(config.appVersion) + "\""
                + "}";
        HeartwithHttpClient.Response response = httpClient.post(
                config.serverUrl + "/api/v1/collector/sessions",
                "application/json; charset=utf-8",
                json.getBytes(StandardCharsets.UTF_8),
                null);
        if (!response.isSuccessful()) {
            throw new IllegalStateException("session http " + response.code);
        }
        String collectorId = match(response.body, COLLECTOR_ID);
        String token = match(response.body, COLLECTOR_TOKEN);
        if (collectorId == null || token == null) {
            throw new IllegalStateException("session response missing credentials");
        }
        session = new Session(collectorId, token);
        notifyStatus("会话已创建，等待上传");
        if (HeartwithUploaderDebug.ENABLED) {
            HeartwithUploaderDebug.log("session created: collector=" + collectorId);
        }
    }

    private boolean shouldFlush(long now, int bpm) {
        if (samples.isEmpty()) {
            return false;
        }
        if (lastFlushMs == 0L) {
            lastFlushMs = samples.peekFirst().tMs;
        }
        Sample first = samples.peekFirst();
        long sinceLastFlushMs = now - lastFlushMs;
        if (lastUploadedBpm <= 0) {
            return sinceLastFlushMs >= MIN_BATCH_WINDOW_MS;
        }
        if (sinceLastFlushMs < MIN_BATCH_WINDOW_MS) {
            return false;
        }
        if (Math.abs(bpm - lastUploadedBpm) >= CHANGE_FLUSH_BPM) {
            return true;
        }
        return first != null && now - first.tMs >= MAX_BATCH_WINDOW_MS;
    }

    private long nextFlushDelayMs(long now) {
        if (nextUploadAttemptMs > now) {
            return Math.max(1_000L, Math.min(MAX_RETRY_BACKOFF_MS, nextUploadAttemptMs - now));
        }
        if (samples.isEmpty()) {
            return MAX_BATCH_WINDOW_MS;
        }
        long sinceLastFlush = lastFlushMs <= 0L ? 0L : now - lastFlushMs;
        if (lastUploadedBpm <= 0) {
            return Math.max(1_000L, MIN_BATCH_WINDOW_MS - sinceLastFlush);
        }
        Sample last = samples.peekLast();
        if (last != null && Math.abs(last.bpm - lastUploadedBpm) >= CHANGE_FLUSH_BPM) {
            return Math.max(1_000L, MIN_BATCH_WINDOW_MS - sinceLastFlush);
        }
        Sample first = samples.peekFirst();
        long firstAgeMs = first == null ? 0L : now - first.tMs;
        return Math.max(1_000L, MAX_BATCH_WINDOW_MS - firstAgeMs);
    }

    private void scheduleDelayedFlush(long delayMs) {
        if (samples.isEmpty()) {
            return;
        }
        long normalizedDelayMs = relaxedFlushDelayMs(delayMs);
        long dueElapsedMs = android.os.SystemClock.elapsedRealtime() + normalizedDelayMs;
        if (delayedFlushScheduled && dueElapsedMs >= delayedFlushDueElapsedMs - 250L) {
            return;
        }
        delayedFlushScheduled = true;
        delayedFlushDueElapsedMs = dueElapsedMs;
        final int generation = ++delayedFlushGeneration;
        try {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    worker.execute(new Runnable() {
                        @Override
                        public void run() {
                            flushDelayedEntry(generation);
                        }
                    });
                }
            }, normalizedDelayMs);
        } catch (Throwable ignored) {
            delayedFlushScheduled = false;
            delayedFlushDueElapsedMs = 0L;
        }
    }

    private synchronized void flushDelayedEntry(int generation) {
        if (generation != delayedFlushGeneration) {
            return;
        }
        delayedFlushScheduled = false;
        delayedFlushDueElapsedMs = 0L;
        flushLockedEntry();
    }

    private long relaxedFlushDelayMs(long delayMs) {
        long baseDelayMs = Math.max(1_000L, delayMs);
        if (baseDelayMs <= 2_000L) {
            return baseDelayMs;
        }
        return baseDelayMs + Math.min(FLUSH_TIMER_SLACK_MS, Math.max(1_000L, baseDelayMs / 4L));
    }

    private byte[] buildBatchCbor(String collectorId, long packetSeq, HeartwithUploadConfig uploadConfig) {
        long sentAtMs = System.currentTimeMillis();
        Cbor cbor = new Cbor();
        cbor.map(8);
        cbor.text("schema").uint(1);
        cbor.text("collector_id").text(collectorId);
        cbor.text("seq").uint(packetSeq);
        cbor.text("sent_at_ms").uint(sentAtMs);
        cbor.text("display_name").text(uploadConfig.displayName);
        cbor.text("device_model").text(uploadConfig.deviceModel);
        cbor.text("samples").array(samples.size());
        for (Sample sample : samples) {
            cbor.map(2);
            cbor.text("dt_ms").sint(sample.tMs - sentAtMs);
            cbor.text("bpm").uint(sample.bpm);
        }
        Sample last = samples.peekLast();
        boolean hasRssi = last != null && last.rssi != null;
        cbor.text("ble").map(hasRssi ? 2 : 1);
        cbor.text("source").text(last == null ? "heartwith" : last.source);
        if (hasRssi) {
            cbor.text("rssi").sint(last.rssi);
        }
        return cbor.bytes();
    }

    private void trim(long now) {
        long cutoff = now - OFFLINE_CACHE_MS;
        while (!samples.isEmpty() && samples.peekFirst().tMs < cutoff) {
            samples.removeFirst();
        }
    }

    private void notifyStatus(String status) {
        HeartwithUploadStatusListener listener = statusListener;
        if (listener != null) {
            listener.onUploadStatus(status);
        }
    }

    private void clearSamplesAndCancelDelayedFlush() {
        samples.clear();
        delayedFlushGeneration++;
        delayedFlushScheduled = false;
        delayedFlushDueElapsedMs = 0L;
    }

    private String match(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class Session {
        final String collectorId;
        final String collectorToken;

        Session(String collectorId, String collectorToken) {
            this.collectorId = collectorId;
            this.collectorToken = collectorToken;
        }
    }

    private static final class Sample {
        final long tMs;
        final int bpm;
        final Integer rssi;
        final String source;

        Sample(long tMs, int bpm, Integer rssi, String source) {
            this.tMs = tMs;
            this.bpm = bpm;
            this.rssi = rssi;
            this.source = source == null || source.trim().isEmpty() ? "heartwith" : source;
        }
    }

    private static final class Cbor {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Cbor uint(long value) {
            typeAndValue(0, value);
            return this;
        }

        Cbor sint(long value) {
            if (value >= 0) {
                typeAndValue(0, value);
            } else {
                typeAndValue(1, -1L - value);
            }
            return this;
        }

        Cbor text(String value) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            typeAndValue(3, bytes.length);
            out.write(bytes, 0, bytes.length);
            return this;
        }

        Cbor array(int size) {
            typeAndValue(4, size);
            return this;
        }

        Cbor map(int size) {
            typeAndValue(5, size);
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }

        private void typeAndValue(int major, long value) {
            int prefix = major << 5;
            if (value < 24) {
                out.write(prefix | (int) value);
            } else if (value <= 0xffL) {
                out.write(prefix | 24);
                out.write((int) value);
            } else if (value <= 0xffffL) {
                out.write(prefix | 25);
                out.write((int) (value >>> 8));
                out.write((int) value);
            } else if (value <= 0xffff_ffffL) {
                out.write(prefix | 26);
                for (int shift = 24; shift >= 0; shift -= 8) {
                    out.write((int) (value >>> shift));
                }
            } else {
                out.write(prefix | 27);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) (value >>> shift));
                }
            }
        }
    }
}
