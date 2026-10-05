package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The queue lives in its own prefs file; a queue saved by an older version moves there once. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class QueueStoreTest {

  private final Context context = RuntimeEnvironment.getApplication();

  private SharedPreferences settings() {
    return context.getSharedPreferences("live_queue_player", Context.MODE_PRIVATE);
  }

  @Test
  public void queueSavedByAnOlderVersionMovesOutOfTheSettingsFile() {
    settings().edit()
        .putString(QueueStore.KEY_QUEUE, "[{\"name\":\"a\",\"uri\":\"file:///a.mp3\",\"id\":7}]")
        .putInt("playback_offset", 3)
        .putInt("anchor_entry_id", 7)
        .putBoolean("peq_enabled", true)
        .commit();

    List<QueueStore.Entry> queue = QueueStore.load(context);
    assertEquals(1, queue.size());
    assertEquals(Uri.parse("file:///a.mp3"), queue.get(0).uri);
    assertEquals(7, queue.get(0).id);
    assertEquals(3, QueueStore.loadPlaybackOffset(context));
    assertEquals(7, QueueStore.loadAnchor(context));

    assertFalse(settings().contains(QueueStore.KEY_QUEUE));
    assertFalse(settings().contains("playback_offset"));
    assertFalse(settings().contains("anchor_entry_id"));
    assertTrue("settings must stay where they are", settings().getBoolean("peq_enabled", false));
  }

  @Test
  public void savesNoLongerTouchTheSettingsFile() {
    QueueStore.save(context, new ArrayList<>(Arrays.asList(
        new QueueStore.Entry("b", Uri.parse("file:///b.mp3"), 2))));
    QueueStore.savePlaybackOffset(context, 1);
    assertEquals(1, QueueStore.load(context).size());
    assertFalse(settings().contains(QueueStore.KEY_QUEUE));
    assertFalse(settings().contains("playback_offset"));
  }
}
