package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

final class Exceptions {
  static final String IllegalArgument = "Requires cookies, which the app does not support.";
  static final String IllegalState = "Unusable player state, close app and try again.";
  static final String IO = "Read error, try giving storage permission.";
  static final String Security = "File location protected, cannot be accessed.";
  static final String FormatNotSupported = "Media Player Error, maybe format not support";

  /** The user-facing message for a failure to load or prepare a track. */
  static String messageFor(Throwable e) {
    if (e instanceof IllegalArgumentException) return IllegalArgument;
    if (e instanceof SecurityException) return Security;
    if (e instanceof IllegalStateException) return IllegalState;
    if (e instanceof java.io.IOException) return IO;
    return FormatNotSupported;
  }

  /**
   * create and display error toast to report errors
   */
  static void throwError(Context context, String msg) {
    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
  }
}

