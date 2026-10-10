package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowAudioManager;

import java.util.Arrays;

/** Which output counts as the main one, so losing it (and only it) pauses playback. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class AudioOutputRouterTest {

    private Application app;
    private ShadowAudioManager audio;
    private final AudioDeviceInfo wired = device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES);
    private final AudioDeviceInfo bluetooth = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        audio = shadowOf((AudioManager) app.getSystemService(Context.AUDIO_SERVICE));
    }

    @Test
    public void chosenOutputIsMain_theOtherIsNot() {
        audio.setOutputDevices(Arrays.asList(bluetooth, wired));
        AudioOutputRouter.setPreferredOutput(app, AudioOutputRouter.OUTPUT_WIRED);
        AudioOutputRouter.resolve(app);
        assertTrue(AudioOutputRouter.contains(new AudioDeviceInfo[]{wired}, AudioOutputRouter.sResolvedMain));
        assertFalse(AudioOutputRouter.contains(new AudioDeviceInfo[]{bluetooth}, AudioOutputRouter.sResolvedMain));
    }

    @Test
    public void defaultOutput_isBluetoothOverTheCableThatPrelistens() {
        audio.setOutputDevices(Arrays.asList(wired, bluetooth));
        AudioOutputRouter.setPreferredOutput(app, AudioOutputRouter.OUTPUT_DEFAULT);
        AudioOutputRouter.resolve(app);
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioOutputRouter.sResolvedMain.getType());
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioOutputRouter.sResolvedSecondary.getType());
    }

    @Test
    public void speakerOnly_hasNoMainToLose() {
        audio.setOutputDevices(Arrays.asList(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)));
        AudioOutputRouter.setPreferredOutput(app, AudioOutputRouter.OUTPUT_DEFAULT);
        AudioOutputRouter.resolve(app);
        assertNull(AudioOutputRouter.sResolvedMain);
    }

    /** The preview stream is muted the moment it lands anywhere but the preview output. */
    @Test
    public void previewRerouted_offThePreviewOutput_isNotOnIt() {
        // Pinned to wired earbuds: falling to the Bluetooth main (or the speaker) is a leak.
        assertTrue(SilenceStreamer.onPreviewOutput(wired, wired, false));
        assertFalse(SilenceStreamer.onPreviewOutput(bluetooth, wired, false));
        // Android 13: Bluetooth earbuds can't be pinned, so the cable to the mixer is the leak.
        assertTrue(SilenceStreamer.onPreviewOutput(bluetooth, null, true));
        assertFalse(SilenceStreamer.onPreviewOutput(wired, null, true));
        assertTrue("an unknown route must not silence a preview",
                SilenceStreamer.onPreviewOutput(null, wired, false));
    }

    private static AudioDeviceInfo device(int type) {
        return AudioDeviceInfoBuilder.newBuilder().setType(type).build();
    }
}
