package com.heartwith.uploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, instrumentedPackages = "com.heartwith.uploader")
@LooperMode(LooperMode.Mode.PAUSED)
public final class HeartwithUploaderDeliveryTest {

    @Test
    public void retryResendsTheFrozenBatchAndNewSamplesWaitForTheNextOne() {
        RecordingClient client = new RecordingClient();
        HeartwithUploader uploader = newUploader(client);
        client.batchCode = 503;
        uploader.submitHeartRate(80, 0L, null, "test");
        idleSeconds(10);
        assertEquals(1, client.batches.size());
        uploader.submitHeartRate(95, 0L, null, "test");
        uploader.submitHeartRate(96, 0L, null, "test");
        client.batchCode = 200;
        idleSeconds(45);
        assertTrue(client.batches.size() >= 3);
        byte[] first = client.batches.get(0);
        byte[] retry = client.batches.get(1);
        assertEquals(seqOf(first), seqOf(retry));
        assertEquals(1, countBpm(retry));
        byte[] next = client.batches.get(2);
        assertTrue(seqOf(next) > seqOf(first));
        assertEquals(2, countBpm(next));
        uploader.close();
    }

    @Test
    public void configureDoesNotWaitForAnInFlightRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        HeartwithUploader uploader = new HeartwithUploader(worker, (url, type, body, auth) -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new HeartwithHttpClient.Response(200,
                    "{\"collector_id\":\"test\",\"collector_token\":\"token\"}");
        });
        uploader.configure(config("Allen"));
        uploader.submitSleepStatus(sleep());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        long started = System.nanoTime();
        uploader.configure(config("Allen 2"));
        uploader.submitHeartRate(80, 0L, null, "test");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        release.countDown();
        assertTrue("configure blocked for " + elapsedMs + " ms", elapsedMs < 1_000L);
        uploader.close();
        worker.shutdown();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    public void backlogSurvivesARestartAndKeepsTheFrozenSeq() {
        MemoryStore store = new MemoryStore();
        RecordingClient failing = new RecordingClient();
        failing.batchCode = 503;
        HeartwithUploader first = newUploader(failing);
        first.setStore(store);
        first.submitHeartRate(80, 0L, null, "test");
        idleSeconds(10);
        first.submitHeartRate(90, 0L, null, "test");
        assertEquals(1, failing.batches.size());
        assertNotNull(store.data);
        assertTrue(store.data.length > 0);
        first.close();

        RecordingClient healthy = new RecordingClient();
        HeartwithUploader second = newUploader(healthy);
        second.setStore(store);
        idleSeconds(5);
        assertTrue(healthy.batches.size() >= 1);
        assertEquals(seqOf(failing.batches.get(0)), seqOf(healthy.batches.get(0)));
        idleSeconds(15);
        int bpmCount = 0;
        for (byte[] batch : healthy.batches) {
            bpmCount += countBpm(batch);
        }
        assertEquals(2, bpmCount);
        assertEquals("store cleared after the backlog drained", 0, store.data.length);
        second.close();
    }

    private static HeartwithUploader newUploader(HeartwithHttpClient client) {
        HeartwithUploader uploader = new HeartwithUploader(Runnable::run, client);
        uploader.configure(config("test"));
        return uploader;
    }

    private static HeartwithUploadConfig config(String name) {
        return new HeartwithUploadConfig(true, "https://example.test", name, "test", "android", "1");
    }

    private static HeartwithSleepStatus sleep() {
        return new HeartwithSleepStatus("asleep", System.currentTimeMillis(), 0L, 0L, 0L, "test", false, 0L);
    }

    private static void idleSeconds(long seconds) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds));
    }

    /** Reads the uint that follows the "seq" text key in a batch body. */
    private static long seqOf(byte[] body) {
        byte[] key = {0x63, 's', 'e', 'q'};
        int at = indexOf(body, key, 0);
        assertTrue(at >= 0);
        int i = at + key.length;
        int head = body[i] & 0xff;
        int info = head & 0x1f;
        if (info < 24) return info;
        int bytes = info == 24 ? 1 : info == 25 ? 2 : info == 26 ? 4 : 8;
        long value = 0;
        for (int b = 1; b <= bytes; b++) {
            value = (value << 8) | (body[i + b] & 0xff);
        }
        return value;
    }

    private static int countBpm(byte[] body) {
        byte[] key = {0x63, 'b', 'p', 'm'};
        int count = 0;
        int from = 0;
        while ((from = indexOf(body, key, from)) >= 0) {
            count++;
            from += key.length;
        }
        return count;
    }

    private static int indexOf(byte[] data, byte[] needle, int from) {
        outer:
        for (int i = from; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static final class MemoryStore implements HeartwithUploadStore {
        byte[] data;

        @Override
        public byte[] load() {
            return data;
        }

        @Override
        public void save(byte[] snapshot) {
            data = snapshot;
        }
    }

    private static final class RecordingClient implements HeartwithHttpClient {
        int batchCode = 200;
        final List<byte[]> batches = new ArrayList<>();

        @Override
        public Response post(String url, String contentType, byte[] body, String authorization) {
            if (url.endsWith("/sessions")) {
                return new Response(200, "{\"collector_id\":\"test\",\"collector_token\":\"token\"}");
            }
            batches.add(body);
            return new Response(batchCode, "{}");
        }
    }
}
