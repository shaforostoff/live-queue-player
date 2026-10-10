package com.shaforostoff.livequeueplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Keeps the client process running while {@link BluetoothFileSender} pushes tracks to the host.
 * Without it, switching to another app leaves the process cached, and Android 14+ freezes cached
 * processes within seconds: the transfer stalls mid-file, and with nothing read from it the link
 * to the host eventually times out.
 *
 * <p>Foreground of type connectedDevice (the Bluetooth link to the host), with the transfer's
 * progress and a Stop action in its notification. The sender starts it when a run starts, or when
 * a parked run's link comes back, and it stops itself once the sender has nothing in flight or
 * the bridge has stopped trying to reconnect — a parked run then waits without it, and picks it up
 * again when the user is back and the link returns.
 *
 * <p>Started with startForegroundService and never stopped from outside: it always reaches
 * startForeground first, then asks the sender whether it is still wanted, so a run that ends
 * before it is up can't trip the start-foreground deadline.
 */
public final class FileTransferService extends android.app.Service implements BluetoothFileSender.Callback {

    private static final String CHANNEL = "file_transfer";
    private static final int NOTIFICATION_ID = 2; // 1 is playback (Notifications)
    static final String ACTION_STOP = "com.shaforostoff.livequeueplayer.STOP_FILE_TRANSFER";
    private static final long UPDATE_INTERVAL_MS = 1_000; // the system drops faster notification updates

    private BluetoothFileSender sender;
    private Notification.Builder builder;
    private long notifiedAt;

    /** Starts it; false if the system refused (the app is not in the foreground). Any thread. */
    static boolean start(Context context) {
        Intent intent = new Intent(context, FileTransferService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
            return true;
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException (an IllegalStateException) from the
            // background on 12+; the transfer goes on for as long as the process is left running.
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sender = ((App) getApplication()).getFileSender();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            //noinspection deprecation
            builder = new Notification.Builder(this);
        }
        Intent stop = new Intent(this, FileTransferService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
        builder.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.transfer_notification_title))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notif),
                        getString(R.string.transfer_stop), stopIntent).build());
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) {
            // The launcher intent brings the existing task, remote mode and all, back to the front.
            builder.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // First, whatever follows: a startForegroundService must be answered with startForeground.
        if (!enterForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) sender.cancel();
        // Shows the current status and says whether it is still wanted; it calls finish() later.
        if (!sender.attachService(this)) finish();
        return START_NOT_STICKY;
    }

    private boolean enterForeground() {
        try {
            Notification n = builder.build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (RuntimeException e) {
            // A refused start (the app went to the background first) or a missing Bluetooth
            // permission, which connectedDevice requires on 14+: run on without it.
            return false;
        }
    }

    /** The sender has nothing in flight, or nothing to resume until the user is back. */
    void finish() {
        sender.attachService(null);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        sender.attachService(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // -- BluetoothFileSender.Callback: the notification mirrors the in-app status line ----------

    @Override
    public void onProgress(int index, int total, String name, int percent) {
        long now = SystemClock.elapsedRealtime();
        if (now - notifiedAt < UPDATE_INTERVAL_MS) return;
        builder.setContentText(getString(R.string.transfer_progress, index, total, name, percent))
                .setProgress(100, percent, false);
        notifyAt(now);
    }

    @Override
    public void onCompressing(int index, int total, String name, int percent) {
        long now = SystemClock.elapsedRealtime();
        if (now - notifiedAt < UPDATE_INTERVAL_MS) return;
        builder.setContentText(getString(R.string.transfer_compressing, index, total, name, percent))
                .setProgress(100, percent, false);
        notifyAt(now);
    }

    @Override
    public void onWaitingForLink(int index, int total, String name) {
        builder.setContentText(getString(R.string.transfer_waiting, index, total, name))
                .setProgress(0, 0, true);
        notifyAt(SystemClock.elapsedRealtime());
    }

    @Override
    public void onFinished(int sent, int existing, int failed, boolean hostFull) {
        // The sender calls finish() right after.
    }

    private void notifyAt(long now) {
        notifiedAt = now;
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, builder.build());
    }
}
