package com.heartwith.uploader;

import android.util.Log;

final class HeartwithUploaderDebug {
    static final boolean ENABLED = true;
    private static final String TAG = "HeartwithUploader";

    private HeartwithUploaderDebug() {
    }

    static void log(String message) {
        Log.i(TAG, message);
    }
}
