package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Opens a media {@link Uri} for reading, robustly.
 *
 * <p>The queue stores entries as {@code file://} URIs (see {@link QueueStore}). On some devices
 * {@link android.content.ContentResolver#openInputStream} fails to open such a URI when the file
 * name contains spaces — it throws {@code FileNotFoundException}, which the AIFF/ALAC data sources
 * surface as a misleading "Read error" and a skipped track. We try several strategies in turn and
 * log which one works, so the failing case can be diagnosed and the track still plays.
 */
final class UriIo {

  private static final String TAG = "URIIO";

  private UriIo() {}

  static InputStream open(Context context, Uri uri) throws IOException {
    Log.w(TAG, "open uri=" + uri + " scheme=" + uri.getScheme()
        + " path=" + uri.getPath() + " encodedPath=" + uri.getEncodedPath());

    // 1) ContentResolver — works for content:// and for file:// without troublesome characters.
    try {
      InputStream in = context.getContentResolver().openInputStream(uri);
      if (in != null) { Log.w(TAG, "  -> openInputStream OK"); return in; }
      Log.w(TAG, "  openInputStream returned null");
    } catch (Exception e) {
      Log.w(TAG, "  openInputStream failed: " + e);
    }

    // 2) Direct file open, trying the raw path and a percent-decoded variant.
    String path = uri.getPath();
    if (path != null) {
      for (String candidate : new String[]{ path, Uri.decode(path) }) {
        File f = new File(candidate);
        Log.w(TAG, "  try File '" + candidate + "' exists=" + f.exists() + " canRead=" + f.canRead());
        try {
          return new FileInputStream(f);
        } catch (Exception e) {
          Log.w(TAG, "  FileInputStream failed: " + e);
        }
      }
    }

    // 3) Asset file descriptor — the route MediaPlayer.setDataSource(Context,Uri) uses internally,
    //    which handles some URIs that openInputStream does not.
    try {
      AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r");
      if (afd != null) { Log.w(TAG, "  -> openAssetFileDescriptor OK"); return afd.createInputStream(); }
    } catch (Exception e) {
      Log.w(TAG, "  openAssetFileDescriptor failed: " + e);
    }

    throw new IOException("Cannot open: " + uri);
  }
}
