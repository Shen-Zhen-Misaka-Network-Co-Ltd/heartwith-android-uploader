package com.heartwith.uploader;

/**
 * Optional durable storage for samples that could not be uploaded yet.
 *
 * <p>The uploader only writes while it holds a backlog (at most once per
 * {@link HeartwithUploader#BACKLOG_PERSIST_INTERVAL_MS}) and clears the store once the
 * backlog drains, so a healthy connection causes no disk writes.
 */
public interface HeartwithUploadStore {
    /** Returns the last saved snapshot, or {@code null} when nothing is stored. */
    byte[] load() throws Exception;

    /** Replaces the stored snapshot; an empty array clears it. */
    void save(byte[] snapshot) throws Exception;
}
