package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.util.Locale;

final class ServicePlaylistGenerator {

    private final Context context;
    private final ServicePlaylist playlist;

    ServicePlaylistGenerator(Context context, ServicePlaylist playlist) {
        this.context = context;
        this.playlist = playlist;
    }

    private static boolean isM3uUri(Uri location) {
        String seg = location.getLastPathSegment();
        if (seg == null) seg = location.getPath();
        if (seg == null) return false;
        String lower = seg.toLowerCase(Locale.ROOT);
        return lower.endsWith(".m3u") || lower.endsWith(".m3u8");
    }

    void generate(Uri location) {
        generate(titleFor(location), location);
    }

    /**
     * Derive a display title from a Uri without assuming a non-null path. Opaque URIs (a scheme
     * with no // authority, e.g. an unresolved playlist line that survived into the queue) return
     * null from getPath(), so {@code new File(getPath())} would throw — crashing the service when
     * the persisted queue is replayed on launch.
     */
    static String titleFor(Uri location) {
        String path = location.getPath();
        if (path != null) {
            String name = new File(path).getName();
            if (!name.isEmpty()) return name;
        }
        String lastSegment = location.getLastPathSegment();
        if (lastSegment != null && !lastSegment.isEmpty()) return lastSegment;
        return location.toString();
    }

    private void generate(String title, Uri location) {
        if (isM3uUri(location)) {
            addPlaylist(location);
            return;
        }

        // A track shared from a transient provider (e.g. Telegram) is only readable while the share
        // grant lives; copy it into the granted music folder now so the persisted queue can replay
        // it later. A no-op for file:// and for URIs we already hold a durable grant on.
        location = MediaImporter.durableCopyIfNeeded(context, location, title);

        var entry = new ServicePlaylist.Entry();
        entry.title = title;
        entry.location = location;
        playlist.add(entry);
    }

    /**
     * The playlist's tracks, resolved as the file browser resolves them. A content:// playlist (an
     * "Open with" from a file manager) has no folder to resolve relative lines against, so only its
     * absolute URIs play. Nested playlists are skipped, as in the browser: one that lists itself
     * would otherwise recurse until the stack overflows.
     */
    private void addPlaylist(Uri m3u) {
        PlaylistResolver resolver = new PlaylistResolver(context.getContentResolver(), null, null);
        File file = "file".equals(m3u.getScheme()) && m3u.getPath() != null ? new File(m3u.getPath()) : null;
        int sizeBefore = playlist.size();
        for (String line : resolver.readLines(m3u)) {
            Uri target = resolver.resolveTargetUri(file, m3u, line);
            if (target != null && !isM3uUri(target)) generate(PlaylistResolver.displayName(line), target);
        }
        if (playlist.size() == sizeBefore) {
            Exceptions.throwError(context, context.getString(R.string.no_playable_files_in_playlist, titleFor(m3u)));
        }
    }
}
