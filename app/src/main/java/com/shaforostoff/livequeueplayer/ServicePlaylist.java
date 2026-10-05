package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;

final class ServicePlaylist extends ArrayList<ServicePlaylist.Entry> {

    private static final long serialVersionUID = 1L;
    private transient final ServicePlaylistGenerator generator;

    ServicePlaylist(Context context) {
        generator = new ServicePlaylistGenerator(context, this);
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

        static Entry of(String title, Uri location, int queueEntryId) {
            Entry e = new Entry();
            e.title = title != null ? title : "";
            e.location = location;
            e.queueEntryId = queueEntryId;
            return e;
        }

        /** A row of the persisted queue, keeping its stable entry id. */
        static Entry of(QueueStore.Entry stored) {
            return of(stored.name, stored.uri, stored.id);
        }
    }
}

