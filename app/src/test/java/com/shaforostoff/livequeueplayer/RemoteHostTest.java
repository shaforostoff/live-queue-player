package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.net.Uri;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;

/** A client's track requests matched against a plain-file library, off the activity. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class RemoteHostTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void matchesByPathThenByNameAndReportsTheMissing() throws Exception {
        File music = tmp.newFolder("Music");
        File samePath = touch(new File(music, "Di Sarli/01 Bahia Blanca.flac"));
        File elsewhere = touch(new File(music, "Other/Pugliese/02 La Yumba.mp3"));

        Application app = RuntimeEnvironment.getApplication();
        StorageBrowser browser = new StorageBrowser(app);
        browser.listFolder(music);
        RemoteHost host = new RemoteHost(app, browser,
                new MetadataExtractor(app.getContentResolver()), null, () -> { });

        RemoteHost.Match m = host.match(Arrays.asList(
                new BluetoothQueueBridge.TrackRequest("01 Bahia Blanca.flac", "Di Sarli/01 Bahia Blanca.flac"),
                new BluetoothQueueBridge.TrackRequest("02 La Yumba.mp3", "Pugliese/02 La Yumba.mp3"),
                new BluetoothQueueBridge.TrackRequest("03 Gone.mp3", "Gone/03 Gone.mp3")));

        assertEquals(Uri.fromFile(samePath), m.uris.get(0));
        assertEquals(Uri.fromFile(elsewhere), m.uris.get(1));
        assertNull(m.uris.get(2));
        assertEquals(2, m.byName);
        assertEquals(Arrays.asList("03 Gone.mp3"), m.notFound);
        assertEquals("Gone/03 Gone.mp3", m.missing.getJSONObject(0).getString("path"));
    }

    private static File touch(File f) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        //noinspection ResultOfMethodCallIgnored
        f.createNewFile();
        return f;
    }
}
