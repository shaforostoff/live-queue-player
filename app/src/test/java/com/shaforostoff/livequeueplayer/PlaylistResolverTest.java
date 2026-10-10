package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;

import android.net.Uri;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Playlist lines resolved against a plain-file music folder (the default browsing mode). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 33)
public class PlaylistResolverTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void windowsSeparatorsResolveInAFileFolder() throws Exception {
        File music = tmp.newFolder("Music");
        File track = new File(music, "Di Sarli/01.mp3");
        File other = new File(tmp.getRoot(), "Cortinas/c.mp3");
        //noinspection ResultOfMethodCallIgnored
        track.getParentFile().mkdirs();
        //noinspection ResultOfMethodCallIgnored
        other.getParentFile().mkdirs();
        //noinspection ResultOfMethodCallIgnored
        track.createNewFile();
        //noinspection ResultOfMethodCallIgnored
        other.createNewFile();
        File playlist = new File(music, "tanda.m3u8");

        PlaylistResolver resolver = new PlaylistResolver(null, null, null);
        assertEquals(Uri.fromFile(track),
                resolver.resolveTargetUri(playlist, Uri.fromFile(playlist), "Di Sarli\\01.mp3"));
        assertEquals(Uri.fromFile(new File(music, "../Cortinas/c.mp3")),
                resolver.resolveTargetUri(playlist, Uri.fromFile(playlist), "..\\Cortinas\\c.mp3"));
    }

    /** The service's path (a playlist opened from another app) resolves lines the same way. */
    @Test
    public void servicePlaylistSkipsBomHeaderMissingTracksAndItself() throws Exception {
        File music = tmp.newFolder("Music");
        File track = new File(music, "Di Sarli/01.mp3");
        //noinspection ResultOfMethodCallIgnored
        track.getParentFile().mkdirs();
        //noinspection ResultOfMethodCallIgnored
        track.createNewFile();
        File playlist = new File(music, "tanda.m3u8");
        Files.write(playlist.toPath(), ("\uFEFF#EXTM3U\n#EXTINF:180,Di Sarli - 01\nDi Sarli\\01.mp3\n"
                + "missing.mp3\ntanda.m3u8\n").getBytes(StandardCharsets.UTF_8));

        ServicePlaylist list = new ServicePlaylist(RuntimeEnvironment.getApplication());
        list.generate(Uri.fromFile(playlist));
        assertEquals(1, list.size());
        assertEquals(Uri.fromFile(track), list.get(0).location);
    }
}
