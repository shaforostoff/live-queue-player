package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class SilenceStreamer {

    /** Checked by FileBrowserQueueActivity to gate drag-preview. */
    static volatile boolean isActive = false;

    /** Preferred output value in effect when the current instance was started. */
    static volatile int sPreferredOutputAtStart = AudioOutputRouter.OUTPUT_DEFAULT;

    static volatile long previewPositionMs;
    static volatile long previewDurationMs;

    static volatile SilenceStreamer current;

    private volatile AudioTrack audioTrack;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean fadingOut;
    private volatile boolean pendingFlush;
    private final AtomicReference<PcmDecoder> previewDecoder = new AtomicReference<>();
    /** Bumped on every start/stop so a slow background decode can detect it was superseded. */
    private final AtomicInteger previewGen = new AtomicInteger();

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

        final AudioTrack trackRef = track;
        audioTrack = track;
        final int bufSize = minBuffer * 4;
        running = true;
        isActive = true;
        current = this;

        thread = new Thread(() -> {
            final byte[] silence = new byte[bufSize];
            final byte[] decBuf = new byte[bufSize];
            final byte[] monoBuf = new byte[bufSize / 2];
            boolean died = false;
            while (running) {
                if (pendingFlush) {
                    pendingFlush = false;
                    try {
                        trackRef.pause();
                        trackRef.flush();
                        trackRef.play();
                    } catch (Exception e) {
                        // If play() failed after pause()/flush() the track is stuck paused; the next
                        // blocking write() would never return and interrupt() can't unblock it. Treat
                        // the track as dead and tear down instead of wedging the thread forever.
                        died = true;
                        break;
                    }
                }
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
                        if (previewDecoder.compareAndSet(dec, null)) {
                            // Natural EOS: we own the cleanup.
                            dec.close();
                            restorePlaybackRate();
                        }
                        // External stop: doStopPreview already closed and restored the rate;
                        // doStartPreview may have already set the new decoder's rate — don't clobber it.
                    } else if (n > 0) {
                        previewPositionMs = dec.positionUs / 1000;
                        if (trackRef.write(decBuf, 0, n) < 0) { died = true; break; }
                        continue;
                    } else {
                        continue; // codec pipeline momentarily dry; don't inject silence
                    }
                }
                if (trackRef.write(silence, 0, silence.length) < 0) { died = true; break; }
            }
            if (fadingOut) {
                final int chunkSize = bufSize / 8; // ~10ms per write paces the loop naturally
                for (int i = 20; i > 0; i--) {
                    trackRef.setVolume(i / 20f);
                    if (trackRef.write(silence, 0, chunkSize) < 0) break;
                }
            }
            // Clear the shared reference before releasing so a concurrent reader (the decoder-init
            // thread, the UI thread) never touches a released track.
            audioTrack = null;
            try { trackRef.stop(); } catch (IllegalStateException ignored) {}
            trackRef.release();
            if (died) onStreamerDied();
        }, "SilenceStreamer");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        current = null;
        PreviewManager.isPreviewActive = false;
        doStopPreview();
        fadingOut = false;
        running = false;
        isActive = false;
        if (thread != null) { thread.interrupt(); thread = null; }
        // audioTrack released by streaming thread on exit
    }

    /**
     * The streaming thread exited because the AudioTrack write failed — typically the secondary
     * output device vanished (ERROR_DEAD_OBJECT). Reset the lifecycle statics so a later
     * {@link #ensure(Context)} (e.g. from the device-topology callback or the activity's sync tick)
     * can start a fresh streamer instead of finding a stale isActive flag and no-op'ing forever.
     */
    private void onStreamerDied() {
        synchronized (SilenceStreamer.class) {
            if (current != this) return; // already superseded by stop()/release()
            current = null;
            isActive = false;
        }
        running = false;
        fadingOut = false;
        PreviewManager.isPreviewActive = false;
        doStopPreview(); // close any decoder still installed when the track failed
    }

    private void fadeOutAndStop() {
        current = null;
        PreviewManager.isPreviewActive = false;
        doStopPreview();
        isActive = false;
        fadingOut = true;
        running = false;
        thread = null; // detach; streaming thread cleans up itself
    }

    /**
     * Start a SilenceStreamer on the secondary output if none is running yet.
     * Resolves device outputs first so sResolvedSecondary is populated.
     * Safe to call when a Service-owned instance is already active — it is a no-op then.
     */
    static void ensure(Context context) {
        if (isActive) return;
        AudioOutputRouter.resolve(context);
        sPreferredOutputAtStart = AudioOutputRouter.getPreferredOutput(context);
        new SilenceStreamer().start();
    }

    static void reinitIfOutputChanged(Context context) {
        if (!isActive) return;
        if (AudioOutputRouter.getPreferredOutput(context) == sPreferredOutputAtStart) return;
        release();
        ensure(context);
    }

    /** Stop the current instance immediately, if any. Counterpart to ensure(). */
    static void release() {
        SilenceStreamer inst = current;
        if (inst != null) inst.stop();
    }

    /** Stop the current instance with a short fade-out, if any. */
    static void fadeOutAndRelease() {
        SilenceStreamer inst = current;
        if (inst != null) inst.fadeOutAndStop();
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
        final int gen;
        synchronized (this) {
            clearPreviewLocked();
            gen = previewGen.incrementAndGet();
        }
        // Build the decoder off the calling (UI) thread: software ALAC decodes the whole track to
        // PCM up front (see AlacMediaDataSource), which would otherwise freeze the file browser.
        Thread init = new Thread(() -> {
            PcmDecoder decoder;
            try {
                decoder = new PcmDecoder(context, uri);
            } catch (Exception ignored) {
                return;
            }
            synchronized (this) {
                if (gen != previewGen.get()) {
                    decoder.close(); // a newer start/stop superseded us while decoding
                    return;
                }
                AudioTrack at = audioTrack;
                if (at != null) {
                    try { at.setPlaybackRate(decoder.sampleRate); } catch (Exception ignored) {}
                }
                previewPositionMs = 0;
                previewDurationMs = decoder.durationUs / 1000;
                previewDecoder.set(decoder);
            }
        }, "PreviewDecoderInit");
        init.setDaemon(true);
        init.start();
    }

    private synchronized void doStopPreview() {
        clearPreviewLocked();
        previewGen.incrementAndGet();
    }

    private void clearPreviewLocked() {
        PcmDecoder old = previewDecoder.getAndSet(null);
        if (old != null) {
            old.close();
            restorePlaybackRate();
            pendingFlush = true;
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
