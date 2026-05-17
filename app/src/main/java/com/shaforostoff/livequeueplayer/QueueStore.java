package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class QueueStore {

    private static final String PREFS = "live_queue_player";
    private static final String KEY_QUEUE = "persisted_queue_v1";
    private static final String KEY_PLAYBACK_OFFSET = "playback_offset";
    private static final String KEY_NAME = "name";
    private static final String KEY_URI  = "uri";

    private QueueStore() {
    }

    static final class Entry {
        final String name;
        final Uri    uri;

        Entry(String name, Uri uri) {
            this.name = name;
            this.uri  = uri;
        }
    }

    static void save(Context context, List<Entry> entries) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit();

        if (entries == null || entries.isEmpty()) {
            edit.remove(KEY_QUEUE);
            commit(edit);
            return;
        }

        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            if (entry == null || entry.uri == null) continue;
            try {
                JSONObject object = new JSONObject();
                object.put(KEY_NAME, entry.name != null ? entry.name : "");
                object.put(KEY_URI, entry.uri.toString());
                array.put(object);
            } catch (Exception ignored) {
            }
        }

        edit.putString(KEY_QUEUE, array.toString());
        commit(edit);
    }

    static ArrayList<Entry> load(Context context) {
        ArrayList<Entry> result = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_QUEUE, null);
        if (raw == null || raw.length() == 0) return result;

        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;

                String name      = object.optString(KEY_NAME, "");
                String uriString = object.optString(KEY_URI, "");
                if (uriString.length() == 0) continue;

                result.add(new Entry(name, Uri.parse(uriString)));
            }
        } catch (Exception ignored) {
            // Corrupt persisted queue should not crash the app.
        }

        return result;
    }

    static void savePlaybackOffset(Context context, int offset) {
        SharedPreferences.Editor edit = context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit();
        edit.putInt(KEY_PLAYBACK_OFFSET, offset);
        commit(edit);
    }

    static int loadPlaybackOffset(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                      .getInt(KEY_PLAYBACK_OFFSET, 0);
    }

    static void clear(Context context) {
        SharedPreferences.Editor edit = context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit();
        edit.remove(KEY_QUEUE);
        edit.remove(KEY_PLAYBACK_OFFSET);
        commit(edit);
    }

    private static void commit(SharedPreferences.Editor edit) {
        edit.apply();
    }
}

