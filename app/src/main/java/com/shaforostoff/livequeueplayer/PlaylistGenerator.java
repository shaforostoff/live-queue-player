package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;
import java.util.Scanner;

final class PlaylistGenerator {

    private final Context context;
    private final Playlist playlist;
    private final M3UParser m3UParser;

    PlaylistGenerator(Context context, Playlist playlist) {
        this.context = context;
        this.playlist = playlist;
        this.m3UParser = new M3UParser(context, this);
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
    private static String titleFor(Uri location) {
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
            // special processing if it is a m3u file
            m3UParser.parse(location);
            return;
        }

        var entry = new Playlist.Entry();
        entry.title = title;
        entry.location = location;
        playlist.add(entry);
    }

    public static final class M3UParser {

        private final Context context;
        private final PlaylistGenerator generator;

        M3UParser(Context context, PlaylistGenerator generator) {
            this.context = context;
            this.generator = generator;
        }

        void parse(Uri m3uLocation) {
            try {
                if ("content".equals(m3uLocation.getScheme())) {
                    parse(new Scanner(context.getContentResolver().openInputStream(m3uLocation)), null);
                } else {
                    File m3uFile = new File(m3uLocation.getPath());
                    parse(new Scanner(m3uFile), m3uFile.getParentFile());
                }
            } catch (FileNotFoundException e) {
                Exceptions.throwError(context, "File not found!\nLocation: " + m3uLocation);
            }
        }

        private Uri resolveLocation(String line, File baseDir) {
            Uri uri = Uri.parse(line);
            if (uri.getScheme() == null && baseDir != null && !line.startsWith("/")) {
                return Uri.fromFile(new File(baseDir, line).toPath().normalize().toFile());
            }
            return uri;
        }

        private void parse(Scanner input, File baseDir) {
            while (input.hasNextLine()) {
                var line = input.nextLine().trim();
                if (line.length() == 0)
                    continue;
                var entry = new Playlist.Entry();
                if (line.startsWith("#EXTINF:")) {
                    var infoAndName = line.split(",");
                    entry.title = infoAndName[infoAndName.length - 1];
                    entry.location = resolveLocation(input.nextLine().trim(), baseDir);
                    generator.generate(entry.title, entry.location);
                } else if (!line.startsWith("#")) {
                    entry.title = new File(line).getName();
                    entry.location = resolveLocation(line, baseDir);
                    generator.generate(entry.title, entry.location);
                }
            }
        }
    }
}

