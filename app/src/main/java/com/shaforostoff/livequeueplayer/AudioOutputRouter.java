package com.shaforostoff.livequeueplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
final class AudioOutputRouter {

    static final int OUTPUT_DEFAULT = 0;
    static final int OUTPUT_BLUETOOTH = 1;
    static final int OUTPUT_WIRED = 2;
    static final int OUTPUT_USB = 3;

    private static final String PREFS = "live_queue_player";
    private static final String KEY_OUTPUT = "preferred_output";
    private static final String KEY_FADE_OUT_SECONDS = "fade_out_seconds";
    static final int DEFAULT_FADE_OUT_SECONDS = 5;

    static volatile AudioDeviceInfo sResolvedPrimary;
    static volatile AudioDeviceInfo sResolvedSecondary;

    private AudioOutputRouter() {
    }

    static int getPreferredOutput(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_OUTPUT, OUTPUT_DEFAULT);
    }

    static void setPreferredOutput(Context context, int value) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        var edit = prefs.edit().putInt(KEY_OUTPUT, value);
        edit.apply();
    }

    static int getFadeOutSeconds(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_FADE_OUT_SECONDS, DEFAULT_FADE_OUT_SECONDS);
    }

    static void setFadeOutSeconds(Context context, int seconds) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
               .edit().putInt(KEY_FADE_OUT_SECONDS, seconds).apply();
    }

    /** Snapshot getDevices() once and derive both primary and secondary from the same list. */
    static void resolve(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            sResolvedPrimary = null;
            sResolvedSecondary = null;
            return;
        }
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) { sResolvedPrimary = null; sResolvedSecondary = null; return; }

        AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        int preferred = getPreferredOutput(context);

        AudioDeviceInfo bluetooth = findBluetooth(outputs);
        AudioDeviceInfo wired     = findWired(outputs);
        AudioDeviceInfo usb       = findUsb(outputs);
        AudioDeviceInfo speaker   = findSpeaker(outputs);

        AudioDeviceInfo primary = null;
        if (preferred == OUTPUT_BLUETOOTH && bluetooth != null) primary = bluetooth;
        else if (preferred == OUTPUT_WIRED && wired != null)   primary = wired;
        else if (preferred == OUTPUT_USB   && usb  != null)    primary = usb;

        AudioDeviceInfo secondary = null;
        if (bluetooth == null && wired != null && usb != null) {
            if (preferred == OUTPUT_WIRED)      secondary = usb;
            else if (preferred == OUTPUT_USB)   secondary = wired;
            else                                secondary = speaker;
        }

        sResolvedPrimary   = primary;
        sResolvedSecondary = secondary;
    }

    static void applyPreferredOutput(Context context, MediaPlayer player) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        AudioDeviceInfo device = sResolvedPrimary;
        if (device != null) {
            player.setPreferredDevice(device);
        }
    }

    static boolean canUseAudioPreview(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;

        AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        return findBluetooth(outputs) == null
                && findWired(outputs) != null
                && findUsb(outputs) != null;
    }

    @SuppressLint("InlinedApi")
    private static AudioDeviceInfo findBluetooth(AudioDeviceInfo[] outputs) {
        return findFirst(outputs,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? AudioDeviceInfo.TYPE_BLE_HEADSET : -1,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? AudioDeviceInfo.TYPE_BLE_SPEAKER : -1
        );
    }

    @SuppressLint("InlinedApi")
    private static AudioDeviceInfo findWired(AudioDeviceInfo[] outputs) {
        return findFirst(outputs,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_AUX_LINE
        );
    }

    @SuppressLint("InlinedApi")
    private static AudioDeviceInfo findUsb(AudioDeviceInfo[] outputs) {
        return findFirst(outputs,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_USB_HEADSET
        );
    }

    @SuppressLint("InlinedApi")
    private static AudioDeviceInfo findSpeaker(AudioDeviceInfo[] outputs) {
        return findFirst(outputs,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE : -1
        );
    }

    private static AudioDeviceInfo findFirst(AudioDeviceInfo[] outputs, int... types) {
        if (outputs == null) return null;
        for (AudioDeviceInfo info : outputs) {
            for (int t : types) {
                if (t == info.getType()) {
                    return info;
                }
            }
        }
        return null;
    }
}

