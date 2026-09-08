package com.heartwith.uploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, instrumentedPackages = "com.heartwith.uploader")
@LooperMode(LooperMode.Mode.PAUSED)
public final class HeartwithUploaderRetryTest {
    private final RecordingClient client = new RecordingClient();
    private HeartwithUploader uploader;

    @Before
    public void setUp() {
        uploader = new HeartwithUploader(Runnable::run, client);
        uploader.configure(new HeartwithUploadConfig(
                true, "https://example.test", "test", "test", "android", "1"));
    }

    @After
    public void tearDown() {
        uploader.close();
    }

    @Test
    public void repeatedBatchUnauthorizedBacksOffDespiteSuccessfulSessionCreation() {
        client.batchCode = 401;
        submitSleep();
        assertEquals(1, client.batchTimes.size());
        idleSeconds(19);
        assertEquals(2, client.batchTimes.size());
        idleSeconds(19);
        assertEquals(2, client.batchTimes.size());
        idleSeconds(16);
        assertEquals(3, client.batchTimes.size());
        assertEquals(3, client.sessionRequests);
        assertTrue(client.batchTimes.get(2) - client.batchTimes.get(1) >= 30_000L);
    }

    @Test
    public void sessionFailureAdvancesOnlyOnceAndRetriesWithoutNewSamples() {
        client.sessionCode = 403;
        submitSleep();
        assertEquals(1, client.sessionRequests);
        idleSeconds(19);
        assertEquals(2, client.sessionRequests);
        idleSeconds(19);
        assertEquals(2, client.sessionRequests);
        client.sessionCode = 200;
        idleSeconds(16);
        assertEquals(3, client.sessionRequests);
        assertEquals(1, client.batchTimes.size());
        idleSeconds(60);
        assertEquals(1, client.batchTimes.size());
    }

    @Test
    public void successfulUploadResetsAuthenticationBackoff() {
        client.batchCode = 401;
        submitSleep();
        idleSeconds(19);
        client.batchCode = 200;
        idleSeconds(35);
        assertEquals(3, client.batchTimes.size());
        client.batchCode = 401;
        submitSleep();
        assertEquals(4, client.batchTimes.size());
        idleSeconds(19);
        assertEquals(5, client.batchTimes.size());
    }

    @Test
    public void transientFailureDoesNotInheritFifteenMinuteClientErrorDelay() {
        client.batchCode = 400;
        submitSleep();
        awaitBatchCount(7);
        client.batchCode = 500;
        awaitBatchCount(8);
        idleSeconds(125);
        assertEquals(9, client.batchTimes.size());
    }

    @Test
    public void closingUploaderCancelsPendingRetry() {
        client.batchCode = 503;
        submitSleep();
        uploader.close();
        idleSeconds(180);
        assertEquals(1, client.batchTimes.size());
    }

    @Test
    public void changingHeartRateStillRespectsMinimumWindow() {
        uploader.submitHeartRate(80, 0L, null, "test");
        idleSeconds(7);
        uploader.submitHeartRate(120, 0L, null, "test");
        assertEquals(0, client.batchTimes.size());
        idleSeconds(1);
        uploader.submitHeartRate(120, 0L, null, "test");
        assertEquals(1, client.batchTimes.size());
        idleSeconds(7);
        uploader.submitHeartRate(80, 0L, null, "test");
        assertEquals(1, client.batchTimes.size());
        idleSeconds(1);
        uploader.submitHeartRate(80, 0L, null, "test");
        assertEquals(2, client.batchTimes.size());
    }

    @Test
    public void stableHeartRateFlushesAtMaximumWindowWithSlack() {
        uploader.submitHeartRate(80, 0L, null, "test");
        idleSeconds(10);
        assertEquals(1, client.batchTimes.size());
        idleSeconds(1);
        uploader.submitHeartRate(81, 0L, null, "test");
        idleSeconds(29);
        assertEquals(1, client.batchTimes.size());
        idleSeconds(6);
        assertEquals(2, client.batchTimes.size());
    }

    @Test
    public void successfulUploadInvalidatesOldDelayedFlush() {
        uploader.submitHeartRate(80, 0L, null, "test");
        idleSeconds(1);
        submitSleep();
        assertEquals(1, client.batchTimes.size());
        idleSeconds(1);
        uploader.submitHeartRate(80, 0L, null, "test");
        idleSeconds(8);
        assertEquals(1, client.batchTimes.size());
        idleSeconds(27);
        assertEquals(2, client.batchTimes.size());
    }

    private void submitSleep() {
        uploader.submitSleepStatus(new HeartwithSleepStatus(
                "asleep", System.currentTimeMillis(), 0L, 0L, 0L, "test", false, 0L));
    }

    private void awaitBatchCount(int count) {
        for (int attempt = 0; attempt < 2_000 && client.batchTimes.size() < count; attempt++) {
            idleSeconds(1);
        }
        assertEquals(count, client.batchTimes.size());
    }

    private void idleSeconds(long seconds) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds));
    }

    private static final class RecordingClient implements HeartwithHttpClient {
        int sessionCode = 200;
        int batchCode = 200;
        int sessionRequests;
        final List<Long> batchTimes = new ArrayList<>();

        @Override
        public Response post(String url, String contentType, byte[] body, String authorization) {
            if (url.endsWith("/sessions")) {
                sessionRequests++;
                return new Response(sessionCode, "{\"collector_id\":\"test\",\"collector_token\":\"token\"}");
            }
            batchTimes.add(System.currentTimeMillis());
            return new Response(batchCode, "{}");
        }
    }
}
