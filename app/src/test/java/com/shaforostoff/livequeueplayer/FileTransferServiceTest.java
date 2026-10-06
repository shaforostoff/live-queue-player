package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowApplication;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * A send keeps the process in the foreground while it has somewhere to go, so switching apps can't
 * freeze it mid-file; it lets go while the bridge has given up, and when the run is over.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FileTransferServiceTest {

  private App app;
  private ShadowApplication shadowApp;
  private BluetoothFileSender sender;
  private File source;

  @Before
  public void setUp() throws Exception {
    app = (App) RuntimeEnvironment.getApplication();
    shadowApp = shadowOf(app);
    sender = app.getFileSender(); // on the app's bridge, which has no link: the run parks
    source = Files.createTempFile("track", ".mp3").toFile();
    Files.write(source.toPath(), new byte[1_000]);
  }

  @After
  public void tearDown() {
    sender.cancel();
  }

  @Test
  public void aParkedRunHoldsTheForegroundUntilTheBridgeGivesUp() throws Exception {
    sender.enqueue(Arrays.asList(job()));
    idle();
    assertStarted();

    FileTransferService service = Robolectric.buildService(FileTransferService.class).create().startCommand(0, 1).get();
    Notification shown = shadowOf(service).getLastForegroundNotification();
    assertNotNull("in the foreground, with a notification", shown);
    await(() -> {
      idle();
      return String.valueOf(shadowOf(app.getSystemService(android.app.NotificationManager.class))
          .getNotification(2).extras.getCharSequence(Notification.EXTRA_TEXT)).contains("t.mp3");
    });

    sender.onLinkAbandoned(); // nothing to do until the user is back
    idle();
    assertTrue(shadowOf(service).isStoppedBySelf());

    sender.onLinkUp(); // they are back, and so is the link: in the foreground again
    idle();
    assertStarted();
  }

  @Test
  public void stopInTheNotificationCancelsTheRun() throws Exception {
    sender.enqueue(Arrays.asList(job()));
    idle();
    ServiceController<FileTransferService> controller = Robolectric.buildService(FileTransferService.class).create();
    controller.startCommand(0, 1);

    controller.withIntent(new Intent(app, FileTransferService.class).setAction(FileTransferService.ACTION_STOP))
        .startCommand(0, 2);
    await(() -> {
      idle();
      return shadowOf(controller.get()).isStoppedBySelf();
    });
    assertFalse(sender.isPending("t.mp3"));
  }

  @Test
  public void aServiceComingUpAfterTheRunEndedStopsAtOnce() throws Exception {
    sender.enqueue(Arrays.asList(job()));
    idle();
    sender.cancel();
    await(() -> {
      idle();
      return !sender.isPending("t.mp3");
    });
    idle();

    // Started for the run, up only now: it still enters the foreground first, then goes.
    FileTransferService service = Robolectric.buildService(FileTransferService.class).create().startCommand(0, 1).get();
    assertTrue("left the foreground it entered", shadowOf(service).isForegroundStopped());
    assertTrue(shadowOf(service).isStoppedBySelf());
  }

  private BluetoothFileSender.Job job() {
    return new BluetoothFileSender.Job(Uri.fromFile(source), "t.mp3", "t.mp3");
  }

  private void assertStarted() {
    Intent started = shadowApp.getNextStartedService();
    assertNotNull("the foreground service was started", started);
    assertEquals(FileTransferService.class.getName(), started.getComponent().getClassName());
    while (shadowApp.getNextStartedService() != null) { } // drop repeats
  }

  private static void idle() {
    shadowOf(Looper.getMainLooper()).idle();
  }

  private static void await(BooleanSupplier done) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000;
    while (!done.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out");
      Thread.sleep(20);
    }
  }
}
