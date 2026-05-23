package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;

final class Playlist extends ArrayList<Playlist.Entry> {

    private static final long serialVersionUID = 1L;
    private transient final PlaylistGenerator generator;

    Playlist(Context context) {
        generator = new PlaylistGenerator(context, this);
    }

    void generate(ArrayList<Uri> locations) {
        for (var location : locations) {
            generator.generate(location);
        }
    }

    void generate(Uri location) {
        generator.generate(location);
    }

    static final class Entry {
        String title;
        Uri location;
        int queueEntryId = -1;
    }
}

