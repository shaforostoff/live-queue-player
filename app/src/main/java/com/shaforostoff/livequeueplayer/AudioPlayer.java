package com.shaforostoff.livequeueplayer;

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
class AudioPlayer extends Thread implements MediaPlayer.OnCompletionListener, MediaPlayerStateListener {

  private final Service service;
  private final MediaPlayer mediaPlayer;
  private final AudioManager audioManager;
  private final AudioManager.OnAudioFocusChangeListener legacyFocusChangeListener;
  private final AudioDeviceCallback audioDeviceCallback;
  private AudioFocusRequest audioFocusRequest;
  private volatile boolean released;
  private volatile boolean fadeOutInProgress;
  private volatile boolean pausedForFocusLoss;
  private final AtomicInteger fadeToken = new AtomicInteger();
  private final float baseGain;
  private PowerManager.WakeLock transitionWakeLock;

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
    transitionWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LittleMusicPlayer:TrackTransition");
    transitionWakeLock.setReferenceCounted(false);
    transitionWakeLock.acquire(30_000); // released after prepare()+start(); 30 s safety timeout

    /* setup player variables */
    if (AiffConverter.isAiff(service, location)) {
      mediaPlayer.setDataSource(new AiffMediaDataSource(service, location));
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
      // Request audio focus with GAIN priority for main playback
      // This ensures preview (with TRANSIENT_MAY_DUCK) won't interrupt us
      requestAudioFocus();
      service.setState(true);
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
    return mediaPlayer.isPlaying();
  }

  @Override
  public void setState(boolean playing) {
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
  }

  void cancelFadeOutAndResume() {
    if (released) return;

    fadeToken.incrementAndGet();
    fadeOutInProgress = false;
    try {
      mediaPlayer.setVolume(baseGain, baseGain);
      if (!mediaPlayer.isPlaying()) {
        mediaPlayer.start();
      }
    } catch (IllegalStateException ignored) {
    }
  }

  boolean isFadeOutInProgress() {
    return fadeOutInProgress;
  }

  void seekTo(int positionMs) {
    if (!released) {
      try { mediaPlayer.seekTo(positionMs); }
      catch (IllegalStateException ignored) {}
    }
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
    if (!released) {
      mediaPlayer.reset();
    }
  }

  /**
   * Fade out playback over the requested duration, then stop the service.
   */
  @SuppressWarnings("unused")
  public void fadeOutAndStop(long durationMs) {
    if (released) {
      service.stopSelf();
      return;
    }

    final int token = fadeToken.incrementAndGet();
    fadeOutInProgress = true;
    new Thread(() -> {
      try {
        if (released || !mediaPlayer.isPlaying()) {
          if (token == fadeToken.get()) {
            fadeOutInProgress = false;
            service.stopSelf();
          }
          return;
        }

        final int steps = 40;
        final long stepDelay = Math.max(1L, durationMs / steps);
        for (int i = steps; i >= 0 && !released; i--) {
          if (token != fadeToken.get()) {
            fadeOutInProgress = false;
            return;
          }
          float t = i / (float) steps;
          float volume = (t == 0f) ? 0f : baseGain * (float) Math.pow(10.0, -40.0 * (1.0 - t) / 20.0);
          try {
            mediaPlayer.setVolume(volume, volume);
          } catch (IllegalStateException ignored) {
            break;
          }
          SystemClock.sleep(stepDelay);
        }

        if (!released) {
          if (token != fadeToken.get()) {
            fadeOutInProgress = false;
            return;
          }
          try {
            mediaPlayer.pause();
          } catch (IllegalStateException ignored) {
          }
        }
      } finally {
        if (token == fadeToken.get()) {
          fadeOutInProgress = false;
          service.stopSelf();
        }
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

