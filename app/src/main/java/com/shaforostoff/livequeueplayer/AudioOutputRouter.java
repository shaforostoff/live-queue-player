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
    static volatile boolean sResolvedSecondaryIsDefault;

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

        // On Android 13 and older, setPreferredDevice to BT is ignored by the OS;
        // default routing already sends audio to BT when connected.
        final boolean defaultIsBluetooth = preferred == OUTPUT_DEFAULT
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && isBluetoothConnected(am, outputs);

        AudioDeviceInfo primary = null;
        if (preferred == OUTPUT_BLUETOOTH && bluetooth != null) primary = bluetooth;
        else if (preferred == OUTPUT_WIRED && wired != null)   primary = wired;
        else if (preferred == OUTPUT_USB   && usb  != null)    primary = usb;

        AudioDeviceInfo secondary = null;
        if (primary != null) {
            for (AudioDeviceInfo candidate : new AudioDeviceInfo[]{bluetooth, wired, usb}) {
                if (candidate != null && candidate != primary) {
                    secondary = candidate;
                    break;
                }
            }
        } else if (defaultIsBluetooth) {
            for (AudioDeviceInfo candidate : new AudioDeviceInfo[]{wired, usb}) {
                if (candidate != null) {
                    secondary = candidate;
                    break;
                }
            }
        }

        // Android 13 and older: explicit routing to BT device is ignored;
        // fall back to OS default routing, which naturally sends audio to BT when connected.
        boolean secondaryIsDefault = secondary == bluetooth
                && bluetooth != null
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        if (secondaryIsDefault) secondary = null;

        sResolvedPrimary            = primary;
        sResolvedSecondary          = secondary;
        sResolvedSecondaryIsDefault = secondaryIsDefault;
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
        int available = (isBluetoothConnected(am, outputs) ? 1 : 0)
                      + (findWired(outputs) != null        ? 1 : 0)
                      + (findUsb(outputs)   != null        ? 1 : 0);
        return available >= 2;
    }

    @SuppressLint("InlinedApi")
    private static AudioDeviceInfo findBluetooth(AudioDeviceInfo[] outputs) {
        return findFirst(outputs,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
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

    private static boolean isBluetoothConnected(AudioManager am, AudioDeviceInfo[] outputs) {
        return findBluetooth(outputs) != null;
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

