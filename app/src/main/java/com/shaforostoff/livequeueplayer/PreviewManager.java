package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;

/**
 * Manages audio playback for drag preview on a secondary output device.
 * Enables simultaneous playback on primary and secondary outputs without interference.
 */
final class PreviewManager {

    /** Flip to true to re-enable audio preview on a secondary output during drag. */
    static final boolean ENABLED = false;

    // Checked by AudioPlayer to suppress transient focus-loss handling during preview.
    static volatile boolean isPreviewActive = false;

    private final Context context;
    private MediaPlayer dragPreviewPlayer;

    PreviewManager(Context context) {
        this.context = context;
    }

    void startPreview(Uri uri) {
        // Raise the flag BEFORE releasing the old player so there is no window
        // where a focus event can slip through and pause main playback.
        isPreviewActive = true;
        releasePlayer();

        try {
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(context, uri);

            // USAGE_GAME is mixed independently from USAGE_MEDIA on Android's audio policy,
            // so starting the preview does not trigger concurrent-stream ducking on the main player.
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? AudioAttributes.USAGE_GAME
                            : AudioAttributes.USAGE_MEDIA)
                    .build());

            // Route to secondary output device
            if (!AudioOutputRouter.applySecondaryOutputForDrag(context, player)) {
                player.release();
                isPreviewActive = false;
                return;
            }

            player.setOnPreparedListener(p -> {
                try {
                    p.start();
                } catch (IllegalStateException ignored) {
                }
            });

            player.setOnErrorListener((mp, what, extra) -> {
                stopPreview();
                return true;
            });

            player.prepareAsync();
            dragPreviewPlayer = player;

        } catch (Exception ignored) {
            stopPreview();
        }
    }

    void stopPreview() {
        isPreviewActive = false;
        releasePlayer();
    }

    private void releasePlayer() {
        if (dragPreviewPlayer != null) {
            try {
                if (dragPreviewPlayer.isPlaying()) dragPreviewPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            dragPreviewPlayer.release();
            dragPreviewPlayer = null;
        }
    }
}


