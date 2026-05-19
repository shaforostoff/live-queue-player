package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.net.Uri;

/**
 * Routes drag-preview audio through SilenceStreamer's existing AudioTrack on the
 * secondary output — no new codec pipeline, no audio routing reconfiguration.
 */
final class PreviewManager {

    static boolean isEnabled(Context context) {
        return AudioOutputRouter.canUseAudioPreview(context);
    }

    // Checked by AudioPlayer to suppress focus-loss handling during preview.
    static volatile boolean isPreviewActive = false;

    private final Context context;

    PreviewManager(Context context) {
        this.context = context;
    }

    void startPreview(Uri uri) {
        isPreviewActive = true;
        SilenceStreamer.startPreview(context, uri);
    }

    void stopPreview() {
        isPreviewActive = false;
        SilenceStreamer.stopPreview();
    }
}
