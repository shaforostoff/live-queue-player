package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.media.MediaDataSource;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

/**
 * Feeds a converted AIFF→WAV byte array into MediaPlayer / MediaExtractor /
 * MediaMetadataRetriever via the MediaDataSource API (requires API 23).
 */
final class AiffMediaDataSource extends MediaDataSource {

    private final byte[] wavData;

    AiffMediaDataSource(Context ctx, Uri uri) throws IOException {
        try (InputStream in = UriIo.open(ctx, uri)) {
            wavData = AiffConverter.convert(in);
        }
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) {
        if (position >= wavData.length) return -1;
        int available = (int) Math.min(size, wavData.length - position);
        System.arraycopy(wavData, (int) position, buffer, offset, available);
        return available;
    }

    @Override
    public long getSize() {
        return wavData.length;
    }

    @Override
    public void close() {
        // byte array; no native resources to release
    }
}
