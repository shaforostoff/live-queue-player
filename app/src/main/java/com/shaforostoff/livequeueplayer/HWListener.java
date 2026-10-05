package com.shaforostoff.livequeueplayer;

import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;

/**
 * Owns the service's MediaSession: media keys (headset, Bluetooth, the activity's own dispatched
 * PLAY), system transport controls and MediaBrowser clients all arrive through its callback.
 */
class HWListener {

  private final Service service;
  private MediaSession mediaSession;
  private PlaybackState.Builder playbackStateBuilder;

  HWListener(Service service) {
    this.service = service;
  }

  void create() {
    mediaSession = new MediaSession(service, HWListener.class.toString());

    // Media keys go through the framework's default onMediaButtonEvent, which maps them onto the
    // callbacks below: PLAY/PAUSE/STOP/NEXT directly, and PLAY_PAUSE or the headset hook to onPlay
    // or onPause from the current PlaybackState once the double-tap window passes (a double tap
    // skips). This used to also forward every key itself, so a PLAY_PAUSE toggled once at once and
    // then again when the framework's delayed onPlay/onPause landed on the already-toggled state.
    mediaSession.setCallback(new MediaSession.Callback() {
      @Override public void onPlay()           { send(Launcher.PLAY); }
      @Override public void onPause()          { send(Launcher.PAUSE); }
      @Override public void onSkipToNext()     { send(Launcher.SKIP); }
      @Override public void onStop()           { send(Launcher.KILL); }
      @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
        int index;
        try { index = Integer.parseInt(mediaId); } catch (NumberFormatException e) { return; }
        Intent i = new Intent(service, Service.class);
        i.putExtra(Launcher.TYPE, Launcher.PLAY_FROM_QUEUE_INDEX);
        i.putExtra(Service.EXTRA_QUEUE_INDEX, index);
        service.startService(i);
      }
      @Override public void onSeekTo(long pos) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        Intent i = new Intent(service, Service.class);
        i.putExtra(Launcher.TYPE, Launcher.SEEK);
        i.putExtra(Service.EXTRA_SEEK_TO_MS, (int) pos);
        service.startService(i);
      }
      private void send(byte action) {
        Intent i = new Intent(service, Service.class);
        i.putExtra(Launcher.TYPE, action);
        service.startService(i);
      }
    });

    playbackStateBuilder = new PlaybackState.Builder();
    long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
        | PlaybackState.ACTION_SKIP_TO_NEXT
        | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
        | PlaybackState.ACTION_STOP;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      actions |= PlaybackState.ACTION_SEEK_TO;
    }
    playbackStateBuilder.setActions(actions);
    mediaSession.setPlaybackState(playbackStateBuilder.build());

    mediaSession.setActive(true);
  }

  public void setState(boolean playing) {
    long position = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ? Service.sPlaybackPositionMs
        : PlaybackState.PLAYBACK_POSITION_UNKNOWN;
    int state = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
    playbackStateBuilder.setState(state, position, 1.0f);
    mediaSession.setPlaybackState(playbackStateBuilder.build());
  }

  /**
   * Stop (as opposed to pause): STATE_STOPPED clears the system/lock-screen media control,
   * unlike STATE_PAUSED which keeps a resumable-looking transport. The session stays active
   * and still advertises ACTION_PLAY, so a later media-button/headset play can resume.
   */
  void setStopped() {
    if (mediaSession == null) return;
    playbackStateBuilder.setState(PlaybackState.STATE_STOPPED, 0, 0f);
    mediaSession.setPlaybackState(playbackStateBuilder.build());
  }

  void setTrackMetadata(String title, long durationMs) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || mediaSession == null) return;
    mediaSession.setMetadata(new MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
        .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        .build());
  }

  void updatePlaybackPosition(int positionMs) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || mediaSession == null) return;
    int state = Service.sIsPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
    playbackStateBuilder.setState(state, positionMs, 1.0f);
    mediaSession.setPlaybackState(playbackStateBuilder.build());
  }

  MediaSession.Token getSessionToken() {
    return mediaSession != null ? mediaSession.getSessionToken() : null;
  }

  /** Release the MediaSession; the service is going away. */
  public void onMediaPlayerDestroy() {
    if (mediaSession != null) {
      mediaSession.setActive(false);
      mediaSession.release();
      mediaSession = null;
    }
  }
}
