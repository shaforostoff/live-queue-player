package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class QueueStore {

    /**
     * The queue's own prefs file. It used to live in the settings file ("live_queue_player"), and a
     * SharedPreferences write always rewrites the whole file — so every EQ tap, output change or
     * fade setting rewrote the entire serialized queue as well.
     */
    private static final String PREFS = "play_queue";
    private static final String LEGACY_PREFS = "live_queue_player";
    static final String KEY_QUEUE = "persisted_queue_v1";
    private static final String KEY_PLAYBACK_OFFSET = "playback_offset";
    private static final String KEY_ANCHOR_ID = "anchor_entry_id";
    private static final String KEY_BROWSE_TAIL = "browse_tail";
    private static final String KEY_NAME = "name";
    private static final String KEY_URI  = "uri";
    private static final String KEY_ID   = "id";

    private QueueStore() {
    }

    /** Backing prefs for the queue; also where queue-change listeners register. */
    static SharedPreferences prefs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateFromSettingsFile(context, prefs);
        return prefs;
    }

    /**
     * Moves a queue saved by an older version out of the settings file, once. Cheap on every later
     * call: three lookups in the (already loaded) settings map, which no longer holds these keys.
     */
    private static synchronized void migrateFromSettingsFile(Context context, SharedPreferences prefs) {
        SharedPreferences legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        if (!legacy.contains(KEY_QUEUE) && !legacy.contains(KEY_PLAYBACK_OFFSET)
                && !legacy.contains(KEY_ANCHOR_ID)) {
            return;
        }
        SharedPreferences.Editor to = prefs.edit();
        if (legacy.contains(KEY_QUEUE)) to.putString(KEY_QUEUE, legacy.getString(KEY_QUEUE, null));
        if (legacy.contains(KEY_PLAYBACK_OFFSET)) to.putInt(KEY_PLAYBACK_OFFSET, legacy.getInt(KEY_PLAYBACK_OFFSET, 0));
        if (legacy.contains(KEY_ANCHOR_ID)) to.putInt(KEY_ANCHOR_ID, legacy.getInt(KEY_ANCHOR_ID, 0));
        // commit(), not apply(): the copy must be on disk before the original is dropped.
        if (!to.commit()) return;
        legacy.edit().remove(KEY_QUEUE).remove(KEY_PLAYBACK_OFFSET).remove(KEY_ANCHOR_ID).apply();
    }

    static final class Entry {
        final String name;
        final Uri    uri;
        final int    id;

        Entry(String name, Uri uri, int id) {
            this.name = name;
            this.uri  = uri;
            this.id   = id;
        }

        Entry(String name, Uri uri) {
            this(name, uri, 0);
        }
    }

    static void save(Context context, List<Entry> entries) {
        SharedPreferences.Editor edit = prefs(context).edit();

        if (entries == null || entries.isEmpty()) {
            edit.remove(KEY_QUEUE);
            edit.apply();
            return;
        }

        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            if (entry == null || entry.uri == null) continue;
            try {
                JSONObject object = new JSONObject();
                object.put(KEY_NAME, entry.name != null ? entry.name : "");
                object.put(KEY_URI, entry.uri.toString());
                if (entry.id > 0) object.put(KEY_ID, entry.id);
                array.put(object);
            } catch (Exception ignored) {
            }
        }

        edit.putString(KEY_QUEUE, array.toString());
        edit.apply();
    }

    static ArrayList<Entry> load(Context context) {
        ArrayList<Entry> result = new ArrayList<>();
        String raw = prefs(context).getString(KEY_QUEUE, null);
        if (raw == null || raw.length() == 0) return result;

        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;

                String name      = object.optString(KEY_NAME, "");
                String uriString = object.optString(KEY_URI, "");
                if (uriString.length() == 0) continue;
                int id = object.optInt(KEY_ID, 0);

                result.add(new Entry(name, Uri.parse(uriString), id));
            }
        } catch (Exception ignored) {
            // Corrupt persisted queue should not crash the app.
        }

        return result;
    }

    static void savePlaybackOffset(Context context, int offset) {
        SharedPreferences.Editor edit = prefs(context).edit();
        edit.putInt(KEY_PLAYBACK_OFFSET, offset);
        edit.apply();
    }

    static int loadPlaybackOffset(Context context) {
        return prefs(context).getInt(KEY_PLAYBACK_OFFSET, 0);
    }

    /**
     * The rest of the browsed folder after the playing track, handed to the Service when the
     * activity goes to the background so browse playback carries on through the folder.
     */
    static void saveBrowseTail(Context context, List<Uri> uris) {
        JSONArray array = new JSONArray();
        for (Uri uri : uris) array.put(uri.toString());
        prefs(context).edit().putString(KEY_BROWSE_TAIL, array.toString()).apply();
    }

    static List<Uri> loadBrowseTail(Context context) {
        List<Uri> uris = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_BROWSE_TAIL, "[]"));
            for (int i = 0; i < array.length(); i++) uris.add(Uri.parse(array.getString(i)));
        } catch (Exception ignored) {
        }
        return uris;
    }

    /** Persists the insert-anchor entry id; {@code anchorEntryId <= 0} clears it. */
    static void saveAnchor(Context context, int anchorEntryId) {
        SharedPreferences.Editor edit = prefs(context).edit();
        if (anchorEntryId > 0) edit.putInt(KEY_ANCHOR_ID, anchorEntryId);
        else edit.remove(KEY_ANCHOR_ID);
        edit.apply();
    }

    /** Returns the persisted insert-anchor entry id, or 0 when none is set. */
    static int loadAnchor(Context context) {
        return prefs(context).getInt(KEY_ANCHOR_ID, 0);
    }

    static void clear(Context context) {
        SharedPreferences.Editor edit = prefs(context).edit();
        edit.remove(KEY_QUEUE);
        edit.remove(KEY_PLAYBACK_OFFSET);
        edit.remove(KEY_ANCHOR_ID);
        edit.apply();
    }
}

