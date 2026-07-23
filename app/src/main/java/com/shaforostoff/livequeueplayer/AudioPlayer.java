package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * audio playing logic class
 */
class AudioPlayer extends Thread implements MediaPlayer.OnCompletionListener, MediaPlayerStateListener, PlaybackEngine {

  private final Service service;
  private final MediaPlayer mediaPlayer;
  private final AudioManager audioManager;
  private final AudioManager.OnAudioFocusChangeListener legacyFocusChangeListener;
  private final AudioDeviceCallback audioDeviceCallback;
  private AudioFocusRequest audioFocusRequest;
  private volatile boolean released;
  private volatile boolean prepared;          // true once prepare() has returned successfully
  private volatile Boolean pendingPlayIntent; // transport command that arrived before prepared
  private volatile boolean fadeOutInProgress;
  private volatile boolean pausedForFocusLoss;
  private final AtomicInteger fadeToken = new AtomicInteger();
  // Serializes volume writes between the background fade thread and cancelFadeOutAndResume()
  // (main thread). Both do a token-check-then-setVolume; without a common lock a resume's
  // baseGain restore can be overwritten by the fade thread's next near-zero step, leaving
  // playback running but silent. See fadeOutAndStop()/cancelFadeOutAndResume().
  private final Object fadeLock = new Object();
  private final float baseGain;
  // Last gain written to the native player. Read by the debug chaos harness to catch the
  // fade-out-vs-resume race (5da8d45): a resumed track must sit at baseGain, not near-silent.
  volatile float debugLastVolume = 1f;
  private PowerManager.WakeLock transitionWakeLock;
  private EqController equalizer;

  /**
   * Initiate an audio player, throws exceptions if failed.
   *
   * @param service  the service initialising this.
   * @param location the Uri containing the location of the audio.
   * @throws IllegalArgumentException when the media player need cookies, but we do not supply it.
   * @throws IllegalStateException    when the media player is not in the correct state.
   * @throws SecurityException        when the audio file is protected and cannot be played.
   * @throws IOException              when the audio file cannot be read.
   */
  public AudioPlayer(Service service, Uri location) throws IllegalArgumentException, IllegalStateException, SecurityException, IOException {
    this.service = service;
    this.audioManager = (AudioManager) service.getSystemService(android.content.Context.AUDIO_SERVICE);
    this.legacyFocusChangeListener = this::onMainAudioFocusChange;
    this.audioDeviceCallback = new AudioDeviceCallback() {
      @Override
      public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
        reapplyPreferredOutput();
      }

      @Override
      public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
        reapplyPreferredOutput();
      }
    };
    /* initiate new audio player */
    mediaPlayer = new MediaPlayer();

    // Keep CPU awake only while playback is active.
    mediaPlayer.setWakeMode(service.getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);

    // Bridge the gap between reset() releasing the old wake lock and start() acquiring the new
    // one. Without this, prepare() (blocking I/O) can stall indefinitely when the CPU sleeps.
    PowerManager pm = (PowerManager) service.getSystemService(android.content.Context.POWER_SERVICE);
    transitionWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveQueuePlayer:TrackTransition");
    transitionWakeLock.setReferenceCounted(false);
    transitionWakeLock.acquire(30_000); // released after prepare()+start(); 30 s safety timeout

    /* setup player variables */
    if (AiffConverter.isAiff(service, location)) {
      mediaPlayer.setDataSource(new AiffMediaDataSource(service, location));
    } else if (AlacMediaDataSource.shouldUseFor(service, location)) {
      // ALAC isn't decoded by MediaPlayer; decode to PCM/WAV in memory and feed that instead.
      mediaPlayer.setDataSource(new AlacMediaDataSource(service, location));
    } else {
      mediaPlayer.setDataSource(service, location);
    }
    AudioOutputRouter.applyPreferredOutput(service, mediaPlayer);
    baseGain = new MetadataExtractor(service.getContentResolver()).readReplayGain(location);

    mediaPlayer.setAudioAttributes(
      new AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .build()
    );

    mediaPlayer.setLooping(false);

    /* setup listeners for further logics */
    mediaPlayer.setOnCompletionListener(this);

    if (audioManager != null) {
      audioManager.registerAudioDeviceCallback(audioDeviceCallback, null);
    }
  }

  @Override
  public void run() {
    /* get ready for playback */
    try {
      mediaPlayer.prepare();
      mediaPlayer.setVolume(baseGain, baseGain);
      debugLastVolume = baseGain;
      // Attach the equalizer to this session and apply persisted settings. Guarded internally,
      // so an unsupported device just skips EQ rather than failing playback. Skip it entirely when
      // a second output is available: the EQ is a single global effect that can't be confined to
      // the main output, so it must never touch audio while previewing is possible. The condition
      // is snapshotted at track start so it stays fixed for the whole track. On API 28+ use the
      // parametric DynamicsProcessing backend; older devices keep the graphic Equalizer.
      if (!AudioOutputRouter.sAudioPreviewAvailableAtTrackStart) {
        int sessionId = mediaPlayer.getAudioSessionId();
        Context ctx = service.getApplicationContext();
        equalizer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? new DynamicsEqController(sessionId, ctx)
            : new EqualizerController(sessionId, ctx);
      }
      // prepare() has returned, so the native player is now in a valid state — transport commands
      // may touch it from here on. Anything that arrived earlier was deferred (see setState).
      prepared = true;
      // Report the now-cheaply-available duration back to the service, replacing its old blocking
      // MediaMetadataRetriever read on the main thread at every transition. getDuration() returns
      // -1 for unknown/streamed sources; the service clamps that to 0 (its existing "unknown" value).
      int durationMs;
      try {
        durationMs = mediaPlayer.getDuration();
      } catch (IllegalStateException e) {
        durationMs = 0;
      }
      service.onTrackDurationResolved(this, durationMs);
      // Request audio focus with GAIN priority for main playback
      // This ensures preview (with TRANSIENT_MAY_DUCK) won't interrupt us
      requestAudioFocus();
      // Honor any PLAY/PAUSE that landed during the (long, for ALAC) prepare; default to play.
      Boolean pending = pendingPlayIntent;
      service.setState(pending == null || pending);
      releaseTransitionWakeLock(); // MediaPlayer now holds its own PARTIAL_WAKE_LOCK via setWakeMode
    } catch (IllegalStateException e) {
      releaseTransitionWakeLock();
      Exceptions.throwError(service, Exceptions.IllegalState);
      service.playOrDestroy();
    } catch (IOException e) {
      releaseTransitionWakeLock();
      Exceptions.throwError(service, Exceptions.IO);
      service.playOrDestroy();
    }
  }

  private void releaseTransitionWakeLock() {
    if (transitionWakeLock != null && transitionWakeLock.isHeld()) {
      transitionWakeLock.release();
    }
    transitionWakeLock = null;
  }

  /**
   * check if audio is playing
   */
  public boolean isPlaying() {
    if (released) return false;
    try {
      return mediaPlayer.isPlaying();
    } catch (IllegalStateException e) {
      // MediaPlayer can be in an invalid state (released/errored) during a backgrounded
      // auto-advance transition; treat that as "not playing" rather than crashing the service.
      return false;
    }
  }

  @Override
  public void setState(boolean playing) {
    if (released) return;
    if (!prepared) {
      // prepare() is still running. For ALAC the decode happens *inside* prepare() on this player's
      // background thread, so this window is seconds long; a PLAY/PAUSE forwarded to the native
      // MediaPlayer now hits it mid-prepare ("start called in state 4 / error -38") and poisons the
      // prepare, failing the track. Defer the intent — run() applies the latest one once prepared.
      pendingPlayIntent = playing;
      return;
    }
    try {
      if (playing) {
        if (pausedForFocusLoss) {
          // Re-request focus lost to another app; this call comes from the main thread so the
          // Looper requirement for AudioFocusRequest.Builder is satisfied.
          pausedForFocusLoss = false;
          requestAudioFocus();
        }
        mediaPlayer.start();
      } else {
        pausedForFocusLoss = false; // explicit user pause should not auto-resume on focus gain
        mediaPlayer.pause();
      }
    } catch (IllegalStateException ignored) {
      // A transport command (PLAY/PAUSE) can arrive before prepare() finishes — the window is wide
      // for ALAC, whose decode runs inside prepare() — or after an undecodable file left the player
      // in an error state. run() issues the authoritative start() once prepare() succeeds, so
      // swallowing this premature/invalid call prevents crashing the service.
    }
  }

  public void cancelFadeOutAndResume() {
    if (released) return;

    // Bump the token and restore baseGain under fadeLock so the fade thread — which holds the
    // same lock across its own token-check-then-setVolume — cannot overwrite this restore with a
    // stale mid-fade (near-zero) step value after we return. Without this the track resumes silent.
    synchronized (fadeLock) {
      fadeToken.incrementAndGet();
      fadeOutInProgress = false;
      try {
        mediaPlayer.setVolume(baseGain, baseGain);
        debugLastVolume = baseGain;
        if (!mediaPlayer.isPlaying()) {
          mediaPlayer.start();
        }
      } catch (IllegalStateException ignored) {
      }
    }
  }

  public boolean isFadeOutInProgress() {
    return fadeOutInProgress;
  }

  public void seekTo(int positionMs) {
    if (!released) {
      try { mediaPlayer.seekTo(positionMs); }
      catch (IllegalStateException ignored) {}
    }
  }

  @Override public float debugLastVolume() { return debugLastVolume; }
  @Override public float debugBaseGain() { return baseGain; }

  /** Re-read persisted equalizer settings and push them to the live effect. */
  public void applyEqualizerSettings() {
    if (equalizer != null) equalizer.applySettings(service);
  }

  /**
   * notifies playback completion
   */
  @Override
  public void onCompletion(MediaPlayer mp) {
    service.onMediaPlayerComplete();
  }

  @Override
  public void onMediaPlayerDestroy() {
    releasePlayer();
    if (!isInterrupted()) interrupt();
  }

  @Override
  public void onMediaPlayerReset() {
    // Track change replaces this AudioPlayer with a fresh one, so fully release instead of
    // just resetting the MediaPlayer — otherwise the AudioDeviceCallback, AudioFocusRequest
    // and native MediaPlayer resources leak for the rest of the queue's lifetime.
    releasePlayer();
  }

  /**
   * Fade out playback over the requested duration, then stop the service.
   */
  @SuppressWarnings("unused")
  public void fadeOutAndStop(long durationMs) {
    if (released) {
      service.onFadeOutComplete();
      return;
    }

    final int token = fadeToken.incrementAndGet();
    fadeOutInProgress = true;
    new Thread(() -> {
      try {
        if (released || !mediaPlayer.isPlaying()) {
          if (token == fadeToken.get() && !released) {
            fadeOutInProgress = false;
            service.onFadeOutComplete();
          }
          return;
        }

        final int steps = 40;
        final long stepDelay = Math.max(1L, durationMs / steps);
        for (int i = steps; i >= 0 && !released; i--) {
          // Hold fadeLock across the token check AND the volume write so a concurrent
          // cancelFadeOutAndResume() can't slip its baseGain restore between them and then have
          // this thread stomp it back to a near-silent step value. Whoever takes the lock last
          // wins: if cancel ran, the token no longer matches and we bail without writing; if we
          // ran, cancel's baseGain is applied afterwards and is the final value. Sleep outside
          // the lock so cancel is never blocked for a whole step.
          synchronized (fadeLock) {
            if (token != fadeToken.get()) {
              fadeOutInProgress = false;
              return;
            }
            float t = i / (float) steps;
            float volume = (t == 0f) ? 0f : baseGain * (float) Math.pow(10.0, -40.0 * (1.0 - t) / 20.0);
            try {
              mediaPlayer.setVolume(volume, volume);
              debugLastVolume = volume;
            } catch (IllegalStateException ignored) {
              break;
            }
          }
          SystemClock.sleep(stepDelay);
        }

        if (!released) {
          // Same guard as the ramp: a cancel that lands here (fully ramped, about to pause) has
          // already resumed the track, so this thread must not pause it back off.
          synchronized (fadeLock) {
            if (token != fadeToken.get()) {
              fadeOutInProgress = false;
              return;
            }
            try {
              mediaPlayer.pause();
            } catch (IllegalStateException ignored) {
            }
          }
        }
      } finally {
        // Skip onFadeOutComplete if we were released externally (e.g. KILL while fading): the
        // service has already torn down playback and may have started a new track. Calling
        // onFadeOutComplete now would tear down that new track. Likewise skip if a cancel bumped
        // the token — it resumed this player and owns it now. Decide under fadeLock so the
        // token/flag read is atomic with cancel, then invoke the service callback outside the
        // lock (it reaches back into the service and must not run while holding a playback lock).
        boolean complete;
        synchronized (fadeLock) {
          complete = token == fadeToken.get() && !released;
          if (complete) fadeOutInProgress = false;
        }
        if (complete) service.onFadeOutComplete();
      }
    }).start();
  }

  private void releasePlayer() {
    if (!released) {
      released = true;
      releaseTransitionWakeLock();
      if (audioManager != null) {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
      }
      abandonAudioFocus();
      if (equalizer != null) {
        equalizer.release();
        equalizer = null;
      }
      mediaPlayer.release();
    }
  }

  /**
   * release and kill service
   */
  @Override
  public void interrupt() {
    releasePlayer();
    super.interrupt();
  }

  /**
   * Request audio focus for main playback.
   * Uses AUDIOFOCUS_GAIN to ensure preview playback (TRANSIENT_MAY_DUCK)
   * won't interrupt main playback.
   */
  private void requestAudioFocus() {
    if (audioManager == null) return;

    try {
      // API 8+ supports requestAudioFocus with stream type
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        // Legacy API for Android < 8.0
        audioManager.requestAudioFocus(
            legacyFocusChangeListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        );
      } else {
        // Modern API for Android 8.0+
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build();

        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(this::onMainAudioFocusChange)
            .build();

        audioManager.requestAudioFocus(audioFocusRequest);
      }
    } catch (Exception ignored) {
      // Audio focus request failed - continue playing anyway
    }
  }

  /**
   * Abandon audio focus when playback ends.
   */
  private void abandonAudioFocus() {
    if (audioManager == null) return;

    try {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        audioManager.abandonAudioFocus(legacyFocusChangeListener);
      } else if (audioFocusRequest != null) {
        audioManager.abandonAudioFocusRequest(audioFocusRequest);
        audioFocusRequest = null;
      }
    } catch (Exception ignored) {
    }
  }

  private void onMainAudioFocusChange(int focusChange) {
    if (released) return;
    try {
      if (focusChange == AudioManager.AUDIOFOCUS_LOSS
          || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
        // Drag preview can trigger either LOSS or LOSS_TRANSIENT depending on the OEM.
        // Suppress both while preview is active; the drag is user-driven and brief.
        if (!PreviewManager.isPreviewActive && mediaPlayer.isPlaying()) {
          int pos = mediaPlayer.getCurrentPosition();
          mediaPlayer.pause();
          pausedForFocusLoss = true;
          service.onAudioFocusLoss(pos);
        }
      } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
        if (!fadeOutInProgress && pausedForFocusLoss && !mediaPlayer.isPlaying()) {
          pausedForFocusLoss = false;
          int pos = mediaPlayer.getCurrentPosition();
          mediaPlayer.start();
          service.onAudioFocusResume(pos);
        }
      }
    } catch (IllegalStateException ignored) {
    }
  }

  private void reapplyPreferredOutput() {
    if (released || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
    AudioOutputRouter.resolve(service);
    try {
      AudioOutputRouter.applyPreferredOutput(service, mediaPlayer);
    } catch (IllegalStateException ignored) {
    }
  }
}

