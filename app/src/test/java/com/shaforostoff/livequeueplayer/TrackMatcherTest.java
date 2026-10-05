package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

/**
 * File-name matching of remote track requests against the library walk: a match in the request's
 * own parent folder beats a plain name match, which beats an extension-stripped one; names compare
 * case- and Unicode-form-insensitively; the first file wins within a rank.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class TrackMatcherTest {

  private static final String NFC_NINO = "Niño";
  private static final String NFD_NINO = "Niño";

  private static BluetoothQueueBridge.TrackRequest request(String file, String path) {
    return new BluetoothQueueBridge.TrackRequest(file, path);
  }

  private static Uri uri(String s) {
    return Uri.parse("file:///" + s);
  }

  @Test
  public void parentFolderHintBeatsAnEarlierPlainNameMatch() {
    TrackMatcher.Accumulator m = new TrackMatcher.Accumulator(
        Arrays.asList(request("song.mp3", "Artist/Album/song.mp3")));
    m.match("song.mp3", "Other", uri("other"));
    m.match("song.mp3", "Album", uri("album"));
    m.match("song.mp3", "Album", uri("album2"));
    assertEquals(uri("album"), m.result().get(0));
  }

  @Test
  public void nameMatchBeatsAnEarlierExtensionStrippedMatch() {
    TrackMatcher.Accumulator m = new TrackMatcher.Accumulator(
        Arrays.asList(request("song.flac", "")));
    m.match("song.mp3", "x", uri("mp3"));
    m.match("song.flac", "x", uri("flac"));
    assertEquals(uri("flac"), m.result().get(0));
  }

  @Test
  public void extensionStrippedMatchIsTheFallback() {
    TrackMatcher.Accumulator m = new TrackMatcher.Accumulator(
        Arrays.asList(request("song.flac", "")));
    m.match("song.mp3", "x", uri("mp3"));
    m.match("song.m4a", "x", uri("m4a"));
    assertEquals(uri("mp3"), m.result().get(0));
  }

  @Test
  public void namesCompareIgnoringCaseAndUnicodeForm() {
    List<BluetoothQueueBridge.TrackRequest> requests = Arrays.asList(
        request("SONG.MP3", ""),
        request(NFD_NINO + ".mp3", "a/" + NFD_NINO + "/" + NFD_NINO + ".mp3"));
    TrackMatcher.Accumulator m = new TrackMatcher.Accumulator(requests);
    m.match("song.mp3", "x", uri("song"));
    m.match(NFC_NINO + ".MP3", "x", uri("plain"));
    m.match(NFC_NINO + ".mp3", NFC_NINO, uri("hinted"));
    assertEquals(Arrays.asList(uri("song"), uri("hinted")), m.result());
  }

  @Test
  public void everyRequestForTheSameFileMatchesAndUnmatchedOnesStayNull() {
    TrackMatcher.Accumulator m = new TrackMatcher.Accumulator(Arrays.asList(
        request("a.mp3", ""), request("a.mp3", "d/a.mp3"), request("missing.mp3", "")));
    m.match("a.mp3", "d", uri("a"));
    List<Uri> result = m.result();
    assertEquals(uri("a"), result.get(0));
    assertEquals(uri("a"), result.get(1));
    assertNull(result.get(2));
  }
}
