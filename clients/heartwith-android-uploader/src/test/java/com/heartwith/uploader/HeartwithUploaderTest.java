package com.heartwith.uploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HeartwithUploaderTest {
    @Test
    public void sessionBackoffAdvancesOncePerFailureAndCapsAtTenMinutes() {
        assertEquals(15_000L, HeartwithUploader.sessionBackoffDelayMs(1));
        assertEquals(30_000L, HeartwithUploader.sessionBackoffDelayMs(2));
        assertEquals(120_000L, HeartwithUploader.sessionBackoffDelayMs(4));
        assertEquals(600_000L, HeartwithUploader.sessionBackoffDelayMs(8));
    }

    @Test
    public void permanentClientErrorsUseLongBackoff() {
        assertTrue(HeartwithUploader.shouldUseLongClientErrorBackoff(400));
        assertTrue(HeartwithUploader.shouldUseLongClientErrorBackoff(404));
        assertTrue(HeartwithUploader.shouldUseLongClientErrorBackoff(422));
        assertFalse(HeartwithUploader.shouldUseLongClientErrorBackoff(408));
        assertFalse(HeartwithUploader.shouldUseLongClientErrorBackoff(429));
        assertFalse(HeartwithUploader.shouldUseLongClientErrorBackoff(500));
    }
}
