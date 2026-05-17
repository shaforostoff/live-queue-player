package com.shaforostoff.livequeueplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

class Notifications implements MediaPlayerStateListener {

  /**
   * notification channel id
   */
  public static final String NOTIFICATION_CHANNEL = "nc";
  /**
   * notification id
   */
  public static final int NOTIFICATION_ID = 1;
  private final Service service;
  /**
   * notification for playback control
   */
  Notification notification;
  Notification.Builder builder;

  public Notifications(Service service) {
    this.service = service;
  }

  public void create() {
    if (Build.VERSION.SDK_INT >= 26) {
      /* create a notification channel */
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

    builder.setCategory(Notification.CATEGORY_SERVICE);
    builder.setSmallIcon(R.drawable.ic_notif);
    builder.setContentTitle(title);
    builder.setSound(null);
    builder.setVibrate(null);
    builder.setContentText("buffering");
  }

  @Override
  public void setState(boolean playing) {
    builder.setContentText((playing ? "Playing" : "Stopped"));
    buildNotification();
    update();
  }

  void buildNotification() {
    notification = builder.build();
  }

  void getNotification(final String title) {
    setupNotificationBuilder(title);
    buildNotification();
    update();
  }

  /**
   * update notification content and place on stack
   */
  private void update() {
    ((NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification);
  }

  @Override
  public void onMediaPlayerReset() {
    /* remove notification from stack */
    ((NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
  }

  @Override
  public void onMediaPlayerDestroy() {
  }
}

