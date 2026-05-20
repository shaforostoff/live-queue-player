package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;

import java.util.concurrent.atomic.AtomicReference;

final class SilenceStreamer {

    /** Checked by FileBrowserQueueActivity to gate drag-preview. */
    static volatile boolean isActive = false;

    static volatile long previewPositionMs;
    static volatile long previewDurationMs;

    static volatile SilenceStreamer current;

    private AudioTrack audioTrack;
    private Thread thread;
    private volatile boolean running;
    private final AtomicReference<PcmDecoder> previewDecoder = new AtomicReference<>();

    void start() {
        if (running) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;

        AudioDeviceInfo secondary = AudioOutputRouter.sResolvedSecondary;
        boolean secondaryIsDefault = AudioOutputRouter.sResolvedSecondaryIsDefault;
        if (secondary == null && !secondaryIsDefault) return;

        int sampleRate = 44100;
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) return;

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();

        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuffer * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception ignored) {
            return;
        }

        try {
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                return;
            }
            if (secondary != null && !track.setPreferredDevice(secondary)) {
                track.release();
                return;
            }
            track.play();
        } catch (Exception ignored) {
            track.release();
            return;
        }

        audioTrack = track;
        final int bufSize = minBuffer * 4;
        running = true;
        isActive = true;
        current = this;

        thread = new Thread(() -> {
            final byte[] silence = new byte[bufSize];
            final byte[] decBuf = new byte[bufSize];
            final byte[] monoBuf = new byte[bufSize / 2];
            while (running) {
                PcmDecoder dec = previewDecoder.get();
                if (dec != null) {
                    int n;
                    if (dec.channelCount == 1) {
                        n = dec.read(monoBuf, 0, monoBuf.length);
                        if (n > 0) n = upmixMonoToStereo(monoBuf, n, decBuf);
                    } else {
                        n = dec.read(decBuf, 0, decBuf.length);
                    }
                    if (n < 0) {
                        previewDecoder.compareAndSet(dec, null);
                        dec.close();
                        restorePlaybackRate();
                    } else if (n > 0) {
                        previewPositionMs = dec.positionUs / 1000;
                        if (audioTrack.write(decBuf, 0, n) < 0) break;
                        continue;
                    } else {
                        continue; // codec pipeline momentarily dry; don't inject silence
                    }
                }
                if (audioTrack.write(silence, 0, silence.length) < 0) break;
            }
        }, "SilenceStreamer");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        current = null;
        PreviewManager.isPreviewActive = false;
        doStopPreview();
        running = false;
        isActive = false;
        if (thread != null) { thread.interrupt(); thread = null; }
        if (audioTrack != null) {
            try { audioTrack.stop(); } catch (IllegalStateException ignored) {}
            audioTrack.release();
            audioTrack = null;
        }
    }

    /**
     * Start a SilenceStreamer on the secondary output if none is running yet.
     * Resolves device outputs first so sResolvedSecondary is populated.
     * Safe to call when a Service-owned instance is already active — it is a no-op then.
     */
    static void ensure(Context context) {
        if (isActive) return;
        AudioOutputRouter.resolve(context);
        new SilenceStreamer().start();
    }

    /** Stop the current instance, if any. Counterpart to ensure(). */
    static void release() {
        SilenceStreamer inst = current;
        if (inst != null) inst.stop();
    }

    static void startPreview(Context context, Uri uri) {
        SilenceStreamer inst = current;
        if (inst != null) inst.doStartPreview(context, uri);
    }

    static void stopPreview() {
        SilenceStreamer inst = current;
        if (inst != null) inst.doStopPreview();
    }

    private void doStartPreview(Context context, Uri uri) {
        doStopPreview();
        try {
            PcmDecoder decoder = new PcmDecoder(context, uri);
            AudioTrack at = audioTrack;
            if (at != null) {
                try { at.setPlaybackRate(decoder.sampleRate); } catch (Exception ignored) {}
            }
            previewPositionMs = 0;
            previewDurationMs = decoder.durationUs / 1000;
            previewDecoder.set(decoder);
        } catch (Exception ignored) {}
    }

    private void doStopPreview() {
        PcmDecoder old = previewDecoder.getAndSet(null);
        if (old != null) {
            old.close();
            restorePlaybackRate();
        }
        previewPositionMs = 0;
        previewDurationMs = 0;
    }

    private void restorePlaybackRate() {
        AudioTrack at = audioTrack;
        if (at != null) {
            try { at.setPlaybackRate(44100); } catch (Exception ignored) {}
        }
    }

    private static int upmixMonoToStereo(byte[] mono, int monoBytes, byte[] stereo) {
        int stereoBytes = monoBytes * 2;
        for (int i = 0, j = 0; i < monoBytes; i += 2, j += 4) {
            stereo[j] = mono[i];
            stereo[j + 1] = mono[i + 1];
            stereo[j + 2] = mono[i];
            stereo[j + 3] = mono[i + 1];
        }
        return stereoBytes;
    }
}
