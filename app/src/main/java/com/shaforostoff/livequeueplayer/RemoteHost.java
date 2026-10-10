package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The host (REMOTE_RECEIVE) side of the remote protocol, for what is not the play queue itself:
 * matching a client's track requests against this library, and the client's volume and EQ
 * controls. The queue commands (request, move, remove, anchor, play) stay with the activity, which
 * owns the queue.
 */
final class RemoteHost {

    /** Cap on the unmatched requests a match_result lists back for a file transfer offer. */
    private static final int MAX_MISSING_REPORTED = 500;

    /** What a batch of track requests matched here, request by request. */
    static final class Match {
        /** Per request: the track found here, or null. */
        final List<Uri> uris;
        int byName, byTag, byFuzzy;
        String tagName, fuzzyName; // the matched title, when exactly one matched that way
        /** The requests not found, by file name. */
        final List<String> notFound = new ArrayList<>();
        /** What the client may offer to push over Bluetooth (see BluetoothFileSender). */
        final JSONArray missing = new JSONArray();

        Match(List<Uri> uris) {
            this.uris = uris;
        }
    }

    private final Context context;
    private final StorageBrowser storageBrowser;
    private final MetadataExtractor metadataExtractor;
    private final BluetoothController link;
    private final Runnable applyEq;

    /** {@code applyEq} pushes the stored EQ settings to the live player. */
    RemoteHost(Context context, StorageBrowser storageBrowser, MetadataExtractor metadataExtractor,
               BluetoothController link, Runnable applyEq) {
        this.context = context;
        this.storageBrowser = storageBrowser;
        this.metadataExtractor = metadataExtractor;
        this.link = link;
        this.applyEq = applyEq;
    }

    /**
     * Finds each requested track here: by path or file name first, then by title and artist in the
     * tag cache. Worker thread; it may walk the whole library.
     */
    Match match(List<BluetoothQueueBridge.TrackRequest> tracks) {
        Match m = new Match(findRequestedAudioUris(tracks));
        for (Uri u : m.uris) if (u != null) m.byName++;

        if (m.byName < tracks.size()) {
            List<Map.Entry<String, MetadataExtractor.TagEntry>> cacheSnapshot =
                    metadataExtractor.snapshotCacheEntries();
            String tagMatchedTitle = null, fuzzyMatchedTitle = null;
            for (int i = 0; i < tracks.size(); i++) {
                if (m.uris.get(i) != null) continue;
                BluetoothQueueBridge.TrackRequest req = tracks.get(i);
                if (!req.title.isEmpty()) {
                    TrackMatcher.TagMatch match =
                            TrackMatcher.findInTagCacheByTitleAndArtist(req.title, req.artist, cacheSnapshot);
                    if (match != null) {
                        m.uris.set(i, match.uri);
                        if (match.exact) {
                            m.byTag++;
                            tagMatchedTitle = match.label;
                        } else {
                            m.byFuzzy++;
                            fuzzyMatchedTitle = match.label;
                        }
                    }
                }
            }
            m.tagName   = m.byTag   == 1 ? tagMatchedTitle   : null;
            m.fuzzyName = m.byFuzzy == 1 ? fuzzyMatchedTitle : null;
        }

        for (int i = 0; i < tracks.size(); i++) {
            if (m.uris.get(i) != null) continue;
            BluetoothQueueBridge.TrackRequest req = tracks.get(i);
            m.notFound.add(req.file);
            if (m.missing.length() >= MAX_MISSING_REPORTED) continue;
            try {
                m.missing.put(new JSONObject().put("file", req.file).put("path", req.path));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    /** Tells the client what {@code m} matched. Send it after queueing, so a queue request it triggers sees them. */
    void sendMatchResult(Match m) {
        int none = m.notFound.size();
        link.send("match_result", "name", m.byName, "tag", m.byTag, "fuzzy", m.byFuzzy, "none", none,
                "tag_name", m.tagName, "fuzzy_name", m.fuzzyName,
                "none_name", none == 1 ? m.notFound.get(0) : null,
                "missing", m.missing.length() > 0 ? m.missing : null);
    }

    void setVolume(JSONObject obj) {
        int value = obj.optInt("value", -1);
        if (value < 0) return;

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, value)), 0);
        pushVolumeState();
    }

    void pushVolumeState() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        link.send("volume_state",
                "value", am.getStreamVolume(AudioManager.STREAM_MUSIC),
                "max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    /** Apply an equalizer change requested by the remote sender, then echo the new state back. The
     *  host's active backend (parametric on API 28+, graphic below) decides which settings store the
     *  command is routed to: a parametric host takes {@code section}-addressed changes, a graphic one
     *  {@code band}-addressed gains. */
    void setEq(JSONObject obj) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (obj.has("enabled")) {
                ParametricEqSettings.setEnabled(context,
                        obj.optBoolean("enabled", ParametricEqSettings.isEnabled(context)));
            }
            int slot = obj.optInt("section", -1);
            if (slot >= 0 && slot < ParametricEqSettings.numSections()) {
                ParametricEq.Section cur = ParametricEqSettings.section(context, slot);
                if (obj.has("gain")) {
                    ParametricEqSettings.setGainMillibels(context, slot, obj.optInt("gain", cur.gainMb));
                }
                if (obj.has("freq")) {
                    ParametricEqSettings.setFreqHz(context, slot, obj.optInt("freq", cur.freqHz));
                }
                if (obj.has("q")) {
                    ParametricEqSettings.setQMilli(context, slot, obj.optInt("q", cur.qMilli));
                }
                if (obj.has("on")) {
                    ParametricEqSettings.setSectionOn(context, slot, obj.optBoolean("on", cur.on));
                }
            }
        } else {
            EqualizerSettings.Caps caps = EqualizerSettings.queryCapabilities(context);
            if (obj.has("enabled")) {
                EqualizerSettings.setEnabled(context, obj.optBoolean("enabled", EqualizerSettings.isEnabled(context)));
            }
            if (caps != null && obj.has("band") && obj.has("value")) {
                int band = obj.optInt("band", -1);
                if (band >= 0 && band < caps.numBands) {
                    int value = Math.max(caps.minLevel, Math.min(caps.maxLevel, obj.optInt("value", 0)));
                    EqualizerSettings.setBandLevel(context, band, value);
                }
            }
        }
        applyEq.run();
        pushEqState();
    }

    void pushEqState() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "eq_state");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Parametric (DynamicsProcessing) host: the sender renders its curve and its
                // frequency/Q controls from the "sections" array.
                ParametricEq.Section[] sections = ParametricEqSettings.sections(context);
                int n = sections.length;
                msg.put("mode", "parametric");
                msg.put("enabled", ParametricEqSettings.isEnabled(context));
                msg.put("gain_min", ParametricEqSettings.GAIN_MIN_MILLIBELS);
                msg.put("gain_max", ParametricEqSettings.GAIN_MAX_MILLIBELS);
                msg.put("q_min", ParametricEqSettings.Q_MIN_MILLI);
                msg.put("q_max", ParametricEqSettings.Q_MAX_MILLI);
                JSONArray secs  = new JSONArray();
                for (int i = 0; i < n; i++) {
                    ParametricEq.Section s = sections[i];
                    JSONObject o = new JSONObject();
                    o.put("type", s.type);
                    o.put("freq", s.freqHz);
                    o.put("q",    s.qMilli);
                    o.put("gain", s.gainMb);
                    o.put("on",   s.on);
                    // Per-slot frequency limits travel with the section so the sender can clamp its
                    // own optimistic update the same way the host would. The defaults travel for
                    // the same reason: a double tap on a value there resets it to this host's
                    // default, not to whatever the sender's own build would have chosen.
                    o.put("freq_min", ParametricEqSettings.freqMinHz(i));
                    o.put("freq_max", ParametricEqSettings.freqMaxHz(i));
                    o.put("gain_default", ParametricEqSettings.DEFAULT_GAIN_MILLIBELS);
                    o.put("freq_default", ParametricEqSettings.defaultFreqHz(i));
                    o.put("q_default", ParametricEqSettings.defaultQMilli(i));
                    secs.put(o);
                }
                msg.put("sections", secs);
            } else {
                // Graphic (Equalizer) host: fixed bands, gain only.
                EqualizerSettings.Caps caps = EqualizerSettings.queryCapabilities(context);
                msg.put("mode", "graphic");
                msg.put("enabled", EqualizerSettings.isEnabled(context));
                if (caps == null) {
                    msg.put("num_bands", 0);
                } else {
                    msg.put("num_bands", caps.numBands);
                    msg.put("min", caps.minLevel);
                    msg.put("max", caps.maxLevel);
                    JSONArray freqs  = new JSONArray();
                    JSONArray levels = new JSONArray();
                    for (int b = 0; b < caps.numBands; b++) {
                        freqs.put(caps.centerFreq[b]);
                        levels.put((int) EqualizerSettings.getBandLevel(context, b));
                    }
                    msg.put("freqs",  freqs);
                    msg.put("levels", levels);
                }
            }
            link.sendRaw(msg.toString());
        } catch (Exception ignored) {
        }
    }

    private List<Uri> findRequestedAudioUris(List<BluetoothQueueBridge.TrackRequest> requests) {
        int n = requests.size();
        // One consistent copy: this runs on a worker thread while the user may be navigating.
        StorageBrowser.Root root = storageBrowser.getRoot();
        boolean isDocTree = root.document != null;
        Uri rootDocUri = root.document;
        File fileRoot = root.folder;

        // Stage 1 — direct path: when the music folders are identical on both devices the full path
        // sent by the peer resolves directly, so we skip any scan entirely.
        Uri[] results = new Uri[n];
        int resolved = 0;
        for (int i = 0; i < n; i++) {
            String relPath = requests.get(i).path;
            if (relPath.isEmpty()) continue;
            Uri hit = isDocTree ? storageBrowser.resolveDirectDocumentPath(rootDocUri, relPath)
                                : storageBrowser.resolveDirectFilePath(fileRoot, relPath);
            if (hit != null) {
                results[i] = hit;
                resolved++;
            }
        }

        // Stage 2 — tag cache: the recursive tag scan already indexed every file's URI as a cache
        // key, so match the remaining requests by filename against that in-memory index instead of
        // re-walking the SAF tree (which costs a query() per folder). Misses fall through to stage 3.
        if (resolved < n) {
            List<Map.Entry<String, MetadataExtractor.TagEntry>> snapshot =
                    metadataExtractor.snapshotCacheEntries();
            if (!snapshot.isEmpty()) {
                List<Uri> cacheHits = findAllInTagCache(requests, snapshot);
                for (int i = 0; i < n; i++) {
                    if (results[i] == null && cacheHits.get(i) != null) {
                        results[i] = cacheHits.get(i);
                        resolved++;
                    }
                }
            }
        }

        // Stage 3 — tree walk: only for whatever stages 1-2 couldn't resolve (e.g. files added after
        // the scan, or a request that arrived before the scan finished). One pass over the library;
        // a parent-folder hint match outranks a plain name match, which outranks one that only
        // matches with the extension stripped (see TrackMatcher).
        if (resolved < n) {
            TrackMatcher.Accumulator matcher = new TrackMatcher.Accumulator(requests);
            StorageBrowser.FileVisitor match = (name, mime, parentName, uri) -> matcher.match(name, parentName, uri);
            if (isDocTree) {
                // The root document carries its own tree, unlike the browser's current tree field.
                storageBrowser.walkDocumentTree(rootDocUri, rootDocUri, match);
            } else if (fileRoot != null && fileRoot.exists()) {
                StorageBrowser.walkFileTree(fileRoot, match);
            }
            List<Uri> walk = matcher.result();
            for (int i = 0; i < n; i++) {
                if (results[i] == null && walk.get(i) != null) results[i] = walk.get(i);
            }
        }

        List<Uri> out = new ArrayList<>(n);
        for (Uri u : results) out.add(u);
        return out;
    }

    /**
     * Resolves requests against the in-memory tag cache, whose keys are every scanned file's URI.
     * Mirrors the tree walk in {@link #findRequestedAudioUris}'s filename / parent-hint / extension matching but touches
     * no SAF. Returns one URI per request (null where unmatched).
     */
    private List<Uri> findAllInTagCache(List<BluetoothQueueBridge.TrackRequest> requests,
                                        List<Map.Entry<String, MetadataExtractor.TagEntry>> snapshot) {
        TrackMatcher.Accumulator matcher = new TrackMatcher.Accumulator(requests);
        for (Map.Entry<String, MetadataExtractor.TagEntry> e : snapshot) {
            Uri childUri = Uri.parse(MetadataExtractor.keyToUri(e.getKey()));
            // For SAF the document id is a single path segment holding the whole "/"-separated
            // subtree path; for file:// it's the file path. Either way the last two "/" components
            // are the file name and its parent folder.
            String docPath = "content".equals(childUri.getScheme())
                    ? childUri.getLastPathSegment() : childUri.getPath();
            if (docPath == null) continue;
            int lastSlash = docPath.lastIndexOf('/');
            String childName = lastSlash >= 0 ? docPath.substring(lastSlash + 1) : docPath;
            if (childName.isEmpty()) continue;
            String dirName = "";
            if (lastSlash > 0) {
                int prevSlash = docPath.lastIndexOf('/', lastSlash - 1);
                dirName = docPath.substring(prevSlash + 1, lastSlash);
            }
            matcher.match(childName, dirName, childUri);
        }
        return matcher.result();
    }
}
