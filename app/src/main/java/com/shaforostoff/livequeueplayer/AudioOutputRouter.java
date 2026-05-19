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

    static void applyPreferredOutput(Context context, MediaPlayer player) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        AudioDeviceInfo device = resolvePrimaryDevice(context);
        if (device != null) {
            player.setPreferredDevice(device);
        }
    }

    static boolean canUseDragPreview(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;

        AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        return findBluetooth(outputs) == null
                && findWired(outputs) != null
                && findUsb(outputs) != null;
    }

    private static AudioDeviceInfo resolvePrimaryDevice(Context context) {
        int preferred = getPreferredOutput(context);
        if (preferred == OUTPUT_DEFAULT) return null;

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;

        AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        AudioDeviceInfo bluetooth = findBluetooth(outputs);
        AudioDeviceInfo wired = findWired(outputs);
        AudioDeviceInfo usb = findUsb(outputs);

        // Keep main playback pinned to the preferred output whenever it exists.
        if (preferred == OUTPUT_BLUETOOTH && bluetooth != null) return bluetooth;
        if (preferred == OUTPUT_WIRED && wired != null) return wired;
        if (preferred == OUTPUT_USB && usb != null) return usb;
        return null;
    }

    static AudioDeviceInfo resolveSecondaryDevice(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;

        AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        AudioDeviceInfo bluetooth = findBluetooth(outputs);
        AudioDeviceInfo wired = findWired(outputs);
        AudioDeviceInfo usb = findUsb(outputs);
        AudioDeviceInfo speaker = findSpeaker(outputs);

        // Preview playback must stay off whenever Bluetooth A2DP output is present.
        if (bluetooth != null) return null;

        int preferred = getPreferredOutput(context);


        if (wired != null && usb != null) {
            if (preferred == OUTPUT_WIRED) return usb;
            if (preferred == OUTPUT_USB) return wired;
            return speaker;
        }

        // Single external output -> drag preview should use speakers.
        boolean hasBluetooth = bluetooth != null;
        boolean hasWired = wired != null;
        boolean hasUsb = usb != null;
        if ((hasBluetooth ? 1 : 0) + (hasWired ? 1 : 0) + (hasUsb ? 1 : 0) == 1) {
            return speaker;
        }

        // No external outputs -> no preview while dragging.
        return null;
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

