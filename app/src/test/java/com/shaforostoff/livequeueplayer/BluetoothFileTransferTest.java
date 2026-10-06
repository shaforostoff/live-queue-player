package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * A missing track pushed from the client lands at the same root-relative path under the host's
 * root, and a dropped link resumes where the host's partial left off. Sender and receiver run back
 * to back over an in-memory link that delivers each direction in order on its own thread, as the
 * bridge's read thread would, and can be cut and restored like a real reconnect.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class BluetoothFileTransferTest {

  private final Context context = RuntimeEnvironment.getApplication();
  private File sourceRoot;
  private File targetRoot;
  private final ExecutorService toHost = Executors.newSingleThreadExecutor();
  private final ExecutorService toClient = Executors.newSingleThreadExecutor();
  private final Wire wire = new Wire();

  private BluetoothFileSender sender;
  private BluetoothFileReceiver receiver;
  private final List<String> received = new ArrayList<>();
  private volatile int[] finished; // sent, existing, failed, host full (1/0)
  private volatile boolean waitedForLink;
  private final java.util.concurrent.atomic.AtomicInteger waitReports = new java.util.concurrent.atomic.AtomicInteger();

  @Before
  public void setUp() throws Exception {
    sourceRoot = Files.createTempDirectory("client").toFile();
    targetRoot = Files.createTempDirectory("host").toFile();

    receiver = newReceiver(new Pipe(toClient, () -> sender));
    sender = new BluetoothFileSender(context, new Pipe(toHost, () -> receiver));
    sender.setCallback(new BluetoothFileSender.Callback() {
      @Override public void onProgress(int index, int total, String name, int percent) {}
      @Override public void onWaitingForLink(int index, int total, String name) {
        waitedForLink = true;
        waitReports.incrementAndGet();
      }
      @Override public void onFinished(int sent, int existing, int failed, boolean hostFull) {
        finished = new int[]{sent, existing, failed, hostFull ? 1 : 0};
      }
    });
  }

  @After
  public void tearDown() {
    toHost.shutdownNow();
    toClient.shutdownNow();
  }

  @Test
  public void missingTrackLandsAtTheSameRelativePath() throws Exception {
    byte[] audio = randomBytes(100_000); // spans many chunks, the last one partial
    File source = write(new File(sourceRoot, "Artist/Album/song.mp3"), audio);

    sender.enqueue(Arrays.asList(job(source, "Artist/Album/song.mp3")));
    awaitFinished();

    assertArrayEquals(new int[]{1, 0, 0, 0}, finished);
    File landed = new File(targetRoot, "Artist/Album/song.mp3");
    assertArrayEquals(audio, Files.readAllBytes(landed.toPath()));
    assertEquals(Arrays.asList("song.mp3 " + landed.getPath()), received);
    assertEquals(Arrays.asList("song.mp3"), Arrays.asList(landed.getParentFile().list()));
  }

  @Test
  public void aDroppedLinkResumesWhereTheHostLeftOff() throws Exception {
    byte[] audio = randomBytes(100_000);
    File source = write(new File(sourceRoot, "A/song.mp3"), audio);
    File partial = new File(targetRoot, "A/.song.mp3.100000.part");
    File landed = new File(targetRoot, "A/song.mp3");
    wire.dropAfterChunks = 5;

    sender.enqueue(Arrays.asList(job(source, "A/song.mp3")));
    await(() -> waitedForLink);

    // While down: the bytes so far wait under the hidden name, out of the library's sight.
    await(() -> partial.length() == 5 * 8192);
    assertFalse(landed.exists());

    wire.reconnect();
    awaitFinished();

    assertArrayEquals(new int[]{1, 0, 0, 0}, finished);
    assertArrayEquals(audio, Files.readAllBytes(landed.toPath()));
    assertFalse(partial.exists());
    // Every byte crossed the wire exactly once: the resume sent only what the host lacked.
    assertEquals(audio.length, wire.deliveredBytes.get());
  }

  @Test
  public void cancellingWhileDisconnectedDropsThePartialOnReconnect() throws Exception {
    File source = write(new File(sourceRoot, "song.mp3"), randomBytes(100_000));
    File partial = new File(targetRoot, ".song.mp3.100000.part");
    wire.dropAfterChunks = 3;

    sender.enqueue(Arrays.asList(job(source, "song.mp3")));
    await(() -> waitedForLink);
    sender.cancel(); // can't reach the host yet
    awaitFinished();
    assertTrue(partial.exists());

    wire.reconnect(); // nothing to resume: the client says so, and the host lets it go
    await(() -> !partial.exists());

    assertArrayEquals(new int[]{0, 0, 0, 0}, finished);
    assertFalse(new File(targetRoot, "song.mp3").exists());
  }

  @Test
  public void anExistingFileIsQueuedButNeverOverwritten() throws Exception {
    File source = write(new File(sourceRoot, "A/x.flac"), randomBytes(5_000));
    byte[] theirs = randomBytes(300);
    File existing = write(new File(targetRoot, "A/x.flac"), theirs);

    sender.enqueue(Arrays.asList(job(source, "A/x.flac")));
    awaitFinished();

    assertArrayEquals(new int[]{0, 1, 0, 0}, finished);
    assertArrayEquals(theirs, Files.readAllBytes(existing.toPath()));
    assertEquals(Arrays.asList("x.flac " + existing.getPath()), received);
  }

  @Test
  public void aPathOutsideTheRootIsRefused() throws Exception {
    File source = write(new File(sourceRoot, "x.mp3"), randomBytes(1_000));

    sender.enqueue(Arrays.asList(job(source, "../escaped/x.mp3")));
    awaitFinished();

    assertArrayEquals(new int[]{0, 0, 1, 0}, finished);
    assertFalse(new File(targetRoot.getParentFile(), "escaped").exists());
    assertTrue(received.isEmpty());
  }

  @Test
  public void aPartialSurvivesARestartAndResumes() throws Exception {
    Recorder replies = new Recorder();
    BluetoothFileReceiver before = newReceiver(replies);
    before.onFileMessage("file_begin", begin(7, "Dir/cut.mp3", 10_000));
    before.onFileChunk(chunk(7, randomBytes(4_000)));
    before.onLinkLost();
    assertTrue(new File(targetRoot, "Dir/.cut.mp3.10000.part").isFile());

    BluetoothFileReceiver after = newReceiver(replies); // a new process: only the disk remains
    after.onFileMessage("file_begin", begin(1, "Dir/cut.mp3", 10_000));

    assertEquals(4_000, replies.last.optLong("offset"));
  }

  @Test
  public void aPartialIsDroppedOnceTheClientMovesOnToAnotherFile() throws Exception {
    Recorder replies = new Recorder();
    BluetoothFileReceiver r = newReceiver(replies);
    r.onFileMessage("file_begin", begin(1, "old.mp3", 10_000));
    r.onFileChunk(chunk(1, randomBytes(4_000)));
    r.onLinkLost();
    File stale = new File(targetRoot, ".old.mp3.10000.part");
    assertTrue(stale.exists());

    newReceiver(replies).onFileMessage("file_begin", begin(2, "new.mp3", 10_000));

    assertFalse(stale.exists());
    assertTrue(new File(targetRoot, ".new.mp3.10000.part").exists());
  }

  @Test
  public void aPartialOfADifferentSizeIsNotResumed() throws Exception {
    Recorder replies = new Recorder();
    BluetoothFileReceiver r = newReceiver(replies);
    r.onFileMessage("file_begin", begin(1, "x.mp3", 10_000));
    r.onFileChunk(chunk(1, randomBytes(4_000)));
    r.onLinkLost();

    r.onFileMessage("file_begin", begin(2, "x.mp3", 12_000)); // the client's copy changed

    assertEquals(0, replies.last.optLong("offset"));
    assertFalse(new File(targetRoot, ".x.mp3.10000.part").exists());
  }

  @Test
  public void aShortFileIsDeletedAtItsEnd() throws Exception {
    BluetoothFileReceiver r = newReceiver(new Recorder());
    r.onFileMessage("file_begin", begin(3, "short.mp3", 10_000));
    r.onFileChunk(chunk(3, randomBytes(4_000)));
    r.onFileMessage("file_end", new JSONObject().put("id", 3));

    assertEquals(0, targetRoot.list().length);
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(received.isEmpty());
  }

  @Test
  public void relativeSegmentsDropEmptyPartsAndRejectParentSteps() {
    assertEquals(Arrays.asList("A", "B", "c.mp3"), BluetoothFileReceiver.relativeSegments("/A//./B\\c.mp3"));
    assertNull(BluetoothFileReceiver.relativeSegments("A/../../c.mp3"));
    assertNull(BluetoothFileReceiver.relativeSegments("/"));
  }

  @Test
  public void hiddenNamesAndNonAudioFilesAreRefused() throws Exception {
    assertNull(BluetoothFileReceiver.relativeSegments("A/.nomedia"));
    assertNull(BluetoothFileReceiver.relativeSegments(".hidden/x.mp3"));

    Recorder replies = new Recorder();
    BluetoothFileReceiver r = newReceiver(replies);
    r.onFileMessage("file_begin", begin(1, "A/payload.apk", 1_000));
    assertEquals("not_audio", replies.last.optString("reason"));
    r.onFileMessage("file_begin", begin(2, "A/.nomedia", 0));
    assertEquals("bad_path", replies.last.optString("reason"));
    assertFalse(new File(targetRoot, "A").exists());
  }

  @Test
  public void aFullHostEndsTheRun() throws Exception {
    File one = write(new File(sourceRoot, "1.mp3"), randomBytes(1_000));
    File two = write(new File(sourceRoot, "2.mp3"), randomBytes(1_000));
    receiver.minFreeAfter = Long.MAX_VALUE / 4; // no volume has this much

    sender.enqueue(Arrays.asList(job(one, "1.mp3"), job(two, "2.mp3")));
    awaitFinished();

    // The first one finds the host full; the second isn't tried, nor is anything left behind.
    assertArrayEquals(new int[]{0, 0, 1, 1}, finished);
    assertEquals(0, targetRoot.list().length);
  }

  @Test
  public void aFileWhoseDoneReplyWasLostCountsAsSentAndIsQueuedOnce() throws Exception {
    byte[] audio = randomBytes(20_000);
    File source = write(new File(sourceRoot, "A/song.mp3"), audio);
    wire.cutBeforeReply = "file_done"; // it lands, but the link dies before the client hears so

    sender.enqueue(Arrays.asList(job(source, "A/song.mp3")));
    await(() -> waitedForLink);
    wire.reconnect();
    awaitFinished();

    assertArrayEquals(new int[]{1, 0, 0, 0}, finished);
    File landed = new File(targetRoot, "A/song.mp3");
    assertArrayEquals(audio, Files.readAllBytes(landed.toPath()));
    assertEquals(Arrays.asList("song.mp3 " + landed.getPath()), received);
  }

  @Test
  public void aWorkerWaitingForTheLinkParksInsteadOfPolling() throws Exception {
    File source = write(new File(sourceRoot, "song.mp3"), randomBytes(100_000));
    wire.dropAfterChunks = 2;

    sender.enqueue(Arrays.asList(job(source, "song.mp3")));
    await(() -> waitedForLink);
    Thread worker = thread("bt-file-send");
    await(() -> worker.getState() == Thread.State.WAITING); // no timeout: nothing wakes it but the link
    Thread.sleep(1_200);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(Thread.State.WAITING, worker.getState());
    assertEquals(1, waitReports.get());

    wire.reconnect();
    awaitFinished();
    assertArrayEquals(new int[]{1, 0, 0, 0}, finished);
  }

  @Test
  public void aPathAlreadyQueuedIsNotQueuedTwice() throws Exception {
    File source = write(new File(sourceRoot, "song.mp3"), randomBytes(1_000));
    wire.up = false; // hold the first one in flight

    sender.enqueue(Arrays.asList(job(source, "song.mp3"), job(source, "song.mp3")));
    await(() -> waitedForLink);
    assertTrue(sender.isPending("song.mp3"));
    sender.enqueue(Arrays.asList(job(source, "song.mp3")));

    wire.reconnect();
    awaitFinished();
    assertArrayEquals(new int[]{1, 0, 0, 0}, finished);
    assertFalse(sender.isPending("song.mp3"));
  }

  @Test
  public void aFileLandingWhileDetachedReachesTheNextActivity() throws Exception {
    File source = write(new File(sourceRoot, "song.mp3"), randomBytes(1_000));
    receiver.setCallback(null); // mid-rotation

    sender.enqueue(Arrays.asList(job(source, "song.mp3")));
    awaitFinished();
    assertTrue(received.isEmpty());

    List<String> paths = new ArrayList<>();
    receiver.setCallback((name, path, uri) -> paths.add(path));
    assertEquals(Arrays.asList("song.mp3"), paths);
  }

  @Test
  public void anExistingFolderIsUsedWhateverItsUnicodeForm() throws Exception {
    String composed = "Ni\u00f1o";      // as Android writes it
    String decomposed = "Nin\u0303o";   // as a Mac copies it
    File folder = new File(targetRoot, composed);
    assertTrue(folder.mkdirs());
    byte[] theirs = randomBytes(300);
    write(new File(folder, "Ma\u00f1ana.mp3"), theirs);
    File source = write(new File(sourceRoot, "new.mp3"), randomBytes(2_000));
    File other = write(new File(sourceRoot, "Manana.mp3"), randomBytes(2_000));

    sender.enqueue(Arrays.asList(
        job(source, decomposed + "/new.mp3"),
        job(other, decomposed + "/Man\u0303ana.mp3")));
    awaitFinished();

    assertArrayEquals(new int[]{1, 1, 0, 0}, finished);
    assertEquals(Arrays.asList(composed), Arrays.asList(targetRoot.list()));
    assertTrue(new File(folder, "new.mp3").isFile());
    assertArrayEquals(theirs, Files.readAllBytes(new File(folder, "Ma\u00f1ana.mp3").toPath()));
  }

  // -- helpers ---------------------------------------------------------------

  private interface SinkRef { BluetoothQueueBridge.FileSink get(); }

  /** Shared state of both directions: up or down, plus a scripted cut after N chunks. */
  private final class Wire {
    volatile boolean up = true;
    volatile int dropAfterChunks = -1;
    volatile String cutBeforeReply; // the host's reply of this type is lost with the link
    int chunks;
    final AtomicLong deliveredBytes = new AtomicLong();

    /** Like a dead socket: each side's read loop ends after what it already received. */
    void cut() {
      up = false;
      toHost.execute(receiver::onLinkLost);
      toClient.execute(sender::onLinkLost);
    }

    void reconnect() {
      up = true;
      toHost.execute(receiver::onLinkUp);
      toClient.execute(sender::onLinkUp);
    }
  }

  /** One direction of the wire: JSON round-trips like the real frames, delivered in order. */
  private final class Pipe implements BluetoothFileLink {
    private final ExecutorService direction;
    private final SinkRef peer;

    Pipe(ExecutorService direction, SinkRef peer) {
      this.direction = direction;
      this.peer = peer;
    }

    @Override public boolean send(String type, Object... kv) {
      if (!wire.up) return false;
      if (type.equals(wire.cutBeforeReply)) {
        wire.cutBeforeReply = null;
        wire.cut();
        return false;
      }
      JSONObject decoded = encode(type, kv);
      direction.execute(() -> peer.get().onFileMessage(type, decoded));
      return true;
    }

    @Override public synchronized boolean sendFileChunk(int id, byte[] buf, int len) {
      if (!wire.up) return false;
      if (++wire.chunks == wire.dropAfterChunks + 1) {
        wire.cut(); // this chunk is lost on the dying link
        return false;
      }
      byte[] frame = chunk(id, Arrays.copyOf(buf, len));
      wire.deliveredBytes.addAndGet(len);
      direction.execute(() -> peer.get().onFileChunk(frame));
      return true;
    }

    @Override public boolean isConnected() {
      return wire.up;
    }
  }

  /** A link that only remembers the receiver's last reply. */
  private static final class Recorder implements BluetoothFileLink {
    JSONObject last;

    @Override public boolean send(String type, Object... kv) {
      last = encode(type, kv);
      return true;
    }

    @Override public boolean sendFileChunk(int id, byte[] buf, int len) {
      return true;
    }

    @Override public boolean isConnected() {
      return true;
    }
  }

  private static JSONObject encode(String type, Object... kv) {
    try {
      JSONObject msg = new JSONObject().put("type", type);
      for (int i = 0; i + 1 < kv.length; i += 2) msg.put((String) kv[i], kv[i + 1]);
      return new JSONObject(msg.toString());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private BluetoothFileReceiver newReceiver(BluetoothFileLink link) {
    StorageBrowser storage = new StorageBrowser(context);
    storage.listFolder(targetRoot);
    BluetoothFileReceiver r = new BluetoothFileReceiver(context, storage, link);
    r.setCallback((name, path, uri) -> received.add(name + " " + uri.getPath()));
    return r;
  }

  private static Thread thread(String name) {
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (t.getName().equals(name)) return t;
    }
    throw new AssertionError("no thread " + name);
  }

  private void awaitFinished() {
    await(() -> finished != null);
  }

  /** Spins the main looper (where callbacks land) until {@code done}, in real time. */
  private static void await(BooleanSupplier done) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      shadowOf(Looper.getMainLooper()).idle();
      if (done.getAsBoolean()) return;
      try {
        TimeUnit.MILLISECONDS.sleep(10);
      } catch (InterruptedException e) {
        break;
      }
    }
    throw new AssertionError("timed out");
  }

  private static JSONObject begin(int id, String path, long size) throws Exception {
    String name = path.substring(path.lastIndexOf('/') + 1);
    return new JSONObject().put("id", id).put("path", path).put("file", name).put("size", size);
  }

  private static BluetoothFileSender.Job job(File source, String path) {
    return new BluetoothFileSender.Job(Uri.fromFile(source), path, source.getName());
  }

  private static byte[] chunk(int id, byte[] data) {
    byte[] frame = new byte[BluetoothQueueBridge.FILE_CHUNK_HEADER + data.length];
    frame[0] = 0x02;
    frame[1] = (byte) (id >>> 24);
    frame[2] = (byte) (id >>> 16);
    frame[3] = (byte) (id >>> 8);
    frame[4] = (byte) id;
    System.arraycopy(data, 0, frame, BluetoothQueueBridge.FILE_CHUNK_HEADER, data.length);
    return frame;
  }

  private static byte[] randomBytes(int n) {
    byte[] b = new byte[n];
    new Random(n).nextBytes(b);
    return b;
  }

  private static File write(File f, byte[] data) throws Exception {
    f.getParentFile().mkdirs();
    Files.write(f.toPath(), data);
    return f;
  }
}
