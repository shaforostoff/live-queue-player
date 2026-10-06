package com.shaforostoff.livequeueplayer;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;

/**
 * Where a track pushed over Bluetooth belongs in the host's queue. A remote request queues what the
 * host matched at once, and the tracks it lacked arrive later, one by one; queued wherever new
 * tracks go, they would end up after the whole batch in arrival order. This remembers each
 * request's order — queue entry ids for the tracks queued, paths for those still to come — so an
 * arrival can be placed beside its neighbours in the request instead.
 *
 * <p>App-scoped, so it outlives a rotation; UI thread only. Keeps only the last few requests.
 */
final class ArrivalOrder {

    private static final int MAX_BATCHES = 8;

    private static final class Batch {
        final String[] keys; // per request: its path key, or null for a track queued at once
        final int[] ids;     // per request: its queue entry id, or 0 while it is still to come

        Batch(String[] keys, int[] ids) {
            this.keys = keys;
            this.ids = ids;
        }
    }

    private final ArrayDeque<Batch> batches = new ArrayDeque<>();

    /**
     * Records a request in order: {@code ids[i]} is the queue entry of request {@code i}, or 0 if
     * it was not found and may arrive later at {@code paths.get(i)}.
     */
    void record(List<String> paths, int[] ids) {
        String[] keys = new String[ids.length];
        boolean anyPending = false;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != 0) continue;
            keys[i] = key(paths.get(i));
            anyPending |= keys[i] != null;
        }
        if (!anyPending) return;
        batches.addFirst(new Batch(keys, ids.clone()));
        while (batches.size() > MAX_BATCHES) batches.removeLast();
    }

    /**
     * The queue entries around {@code path} in the newest request still waiting for it: {@code [0]}
     * those before it, nearest first, and {@code [1]} those after it, nearest first. It then counts
     * as arrived, as entry {@code id}, so a later neighbour can be placed beside it. Two empty
     * arrays if no request is waiting for it.
     */
    int[][] arrive(String path, int id) {
        String key = key(path);
        if (key == null) return new int[][]{new int[0], new int[0]};
        for (Iterator<Batch> it = batches.iterator(); it.hasNext(); ) {
            Batch b = it.next();
            for (int i = 0; i < b.keys.length; i++) {
                if (b.ids[i] != 0 || !key.equals(b.keys[i])) continue;
                int[][] around = {collect(b.ids, i - 1, -1), collect(b.ids, i + 1, 1)};
                b.ids[i] = id;
                if (!hasPending(b)) it.remove();
                return around;
            }
        }
        return new int[][]{new int[0], new int[0]};
    }

    private static int[] collect(int[] ids, int from, int step) {
        int n = 0;
        for (int i = from; i >= 0 && i < ids.length; i += step) if (ids[i] != 0) n++;
        int[] out = new int[n];
        n = 0;
        for (int i = from; i >= 0 && i < ids.length; i += step) if (ids[i] != 0) out[n++] = ids[i];
        return out;
    }

    private static boolean hasPending(Batch b) {
        for (int i = 0; i < b.ids.length; i++) if (b.ids[i] == 0 && b.keys[i] != null) return true;
        return false;
    }

    /** The path as the receiver lands it, in one Unicode form; null if it can't be landed. */
    private static String key(String path) {
        List<String> segments = BluetoothFileReceiver.relativeSegments(path);
        return segments == null ? null : TextNormalizer.compose(String.join("/", segments));
    }
}
