package com.shaforostoff.livequeueplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.session.MediaSession;
import android.os.Build;

import java.util.Locale;



class Notifications implements MediaPlayerStateListener {

  public static final String NOTIFICATION_CHANNEL = "nc";
  public static final int NOTIFICATION_ID = 1;
  private final Service service;

  Notification notification;
  Notification.Builder builder;

  /** Non-null only in Browse mode; drives MediaStyle. */
  private MediaSession.Token sessionToken;

  public Notifications(Service service) {
    this.service = service;
  }

  public void create() {
    if (Build.VERSION.SDK_INT >= 26) {
      var name = "Playback Control";
      var description = "Notification audio controls";
      var importance = NotificationManager.IMPORTANCE_LOW;
      var notificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL, name, importance);
      notificationChannel.setDescription(description);
      ((NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(notificationChannel);
      notificationChannel.setSound(null, null);
      notificationChannel.setVibrationPattern(null);
    }
  }

  void setupNotificationBuilder(String title) {
    if (Build.VERSION.SDK_INT >= 26) {
      builder = new Notification.Builder(service, NOTIFICATION_CHANNEL);
    } else {
      builder = new Notification.Builder(service);
    }

    builder.setSmallIcon(R.drawable.ic_notif);
    builder.setContentTitle(title);
    builder.setSound(null);
    builder.setVibrate(null);
    builder.setContentText("buffering");

    if (sessionToken != null) {
      builder.setCategory(Notification.CATEGORY_TRANSPORT);
      builder.setStyle(new Notification.MediaStyle().setMediaSession(sessionToken));
    } else {
      builder.setCategory(Notification.CATEGORY_SERVICE);
    }
  }

  @Override
  public void setState(boolean playing) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && sessionToken != null) {
      builder.setContentText(playing ? "" : formatPosition(Service.sPlaybackPositionMs, Service.sPlaybackDurationMs));
    } else {
      builder.setContentText(playing ? "Playing" : "Stopped");
    }
    buildNotification();
    update();
  }

  private static String formatPosition(int posMs, int durMs) {
    int p = posMs / 1000, d = durMs / 1000;
    return String.format(Locale.US, "%d:%02d / %d:%02d", p / 60, p % 60, d / 60, d % 60);
  }

  void buildNotification() {
    notification = builder.build();
  }

  void getNotification(final String title, MediaSession.Token sessionToken) {
    this.sessionToken = sessionToken;
    setupNotificationBuilder(title);
    buildNotification();
    update();
  }

  /**
   * Ensures a notification object exists so the Service can promote itself to the foreground
   * immediately, before track metadata or the MediaSession token are ready. Builds a minimal
   * placeholder (service category, "buffering" text) only if nothing has been built yet; once
   * real playback starts, getNotification() replaces it with the media-styled notification.
   * Safe to call repeatedly.
   */
  void ensurePlaceholder() {
    if (notification == null) {
      setupNotificationBuilder(service.getString(R.string.app_name));
      buildNotification();
    }
  }

  /**
   * Swap the media notification for an idle placeholder while a remote-host session keeps the
   * service in the foreground with nothing playing (see Service#isRemoteHostSession). Rebuilds
   * unconditionally — unlike ensurePlaceholder() — because the previous track's media-styled
   * notification is still present and would linger looking playable.
   */
  void showIdleHostPlaceholder() {
    sessionToken = null;
    setupNotificationBuilder(service.getString(R.string.app_name));
    builder.setContentText("Remote session active");
    buildNotification();
    update();
  }

  private void update() {
    ((NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification);
  }

  @Override
  public void onMediaPlayerReset() {
    ((NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
  }

  @Override
  public void onMediaPlayerDestroy() {
  }
}
