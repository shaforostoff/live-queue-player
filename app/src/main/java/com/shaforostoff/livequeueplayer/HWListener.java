package com.shaforostoff.livequeueplayer;

import static android.content.Intent.EXTRA_KEY_EVENT;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
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
      private void send(byte action) {
        Intent i = new Intent(service, Service.class);
        i.putExtra(Launcher.TYPE, action);
        service.startService(i);
      }
    });

    playbackStateBuilder = new PlaybackState.Builder();
    playbackStateBuilder.setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE);
    mediaSession.setPlaybackState(playbackStateBuilder.build());

    mediaSession.setActive(true);
  }

  @Override
  public void setState(boolean playing) {
    if (playing)
      playbackStateBuilder.setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1);
    else
      playbackStateBuilder.setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1);
    mediaSession.setPlaybackState(playbackStateBuilder.build());
  }

  MediaSession.Token getSessionToken() {
    return mediaSession != null ? mediaSession.getSessionToken() : null;
  }

  @Override
  public void onMediaPlayerReset() {
    mediaSession.setActive(false);
    mediaSession.release();
  }

  @Override
  public void onMediaPlayerDestroy() {
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

