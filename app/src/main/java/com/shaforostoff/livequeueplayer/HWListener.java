package com.shaforostoff.livequeueplayer;

import static android.content.Intent.EXTRA_KEY_EVENT;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.view.KeyEvent;

/**
 * Hardware Listener for button controls
 */
public class HWListener extends BroadcastReceiver implements MediaPlayerStateListener {

  private Service service;
  private MediaSession mediaSession;
  private PlaybackState.Builder playbackStateBuilder;

  public HWListener() {
    super();
  }

  public HWListener(Service service) {
    this.service = service;
  }

  void create() {
    mediaSession = new MediaSession(service, HWListener.class.toString());

    mediaSession.setCallback(new MediaSession.Callback() {
      @Override
      public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
        onReceive(service, mediaButtonIntent);
        return super.onMediaButtonEvent(mediaButtonIntent);
      }
      @Override public void onPlay()           { send(Launcher.PLAY); }
      @Override public void onPause()          { send(Launcher.PAUSE); }
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
    long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      actions |= PlaybackState.ACTION_SEEK_TO;
    }
    playbackStateBuilder.setActions(actions);
    mediaSession.setPlaybackState(playbackStateBuilder.build());

    mediaSession.setActive(true);
  }

  @Override
  public void setState(boolean playing) {
    long position = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ? Service.sPlaybackPositionMs
        : PlaybackState.PLAYBACK_POSITION_UNKNOWN;
    int state = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
    playbackStateBuilder.setState(state, position, 1.0f);
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

  @Override
  public void onMediaPlayerReset() {
    // Keep the session alive so the next track in Browse mode can reuse it.
  }

  @Override
  public void onMediaPlayerDestroy() {
    if (mediaSession != null) {
      mediaSession.setActive(false);
      mediaSession.release();
      mediaSession = null;
    }
  }

  /**
   * Responds to media keycodes (ie. from bluetooth ear phones, etc.).
   * Does not connect directly to service variable because service may not be initialized.
   */
  @Override
  public void onReceive(Context context, Intent intent) {
    final KeyEvent event = intent.getParcelableExtra(EXTRA_KEY_EVENT);
    if (event.getAction() == KeyEvent.ACTION_DOWN) {
      intent = new Intent(context, Service.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY);
      switch (event.getKeyCode()) {
        case KeyEvent.KEYCODE_MEDIA_PLAY -> intent.putExtra(Launcher.TYPE, Launcher.PLAY);
        case KeyEvent.KEYCODE_MEDIA_PAUSE -> intent.putExtra(Launcher.TYPE, Launcher.PAUSE);
        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
          intent.putExtra(Launcher.TYPE, Launcher.PLAY_PAUSE);
        case KeyEvent.KEYCODE_MEDIA_STOP -> intent.putExtra(Launcher.TYPE, Launcher.KILL);
      }
      context.startService(intent);
    }
  }
}

