package com.shaforostoff.livequeueplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * activity for controlling the playback by invoking different logics based on incoming intents
 */
public class Launcher extends Activity {

    public static final String TYPE = "type";
    public static final byte NULL = 0;
    public static final byte PLAY_PAUSE = 1;
    public static final byte KILL = 2;
    public static final byte PLAY = 3;
    public static final byte PAUSE = 4;
    public static final byte SKIP = 6;
    @SuppressWarnings("unused")
    public static final byte STOP = 7;
    public static final byte APPEND_QUEUE = 8;
    public static final byte CLEAR_QUEUE = 9;

    private Button stopAfterCurrentButton;
    private BroadcastReceiver playbackStateReceiver;

    /**
     * redirect call to actual logic
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (!Intent.ACTION_VIEW.equals(getIntent().getAction())
                && !Intent.ACTION_SEND.equals(getIntent().getAction())
                && !Intent.ACTION_SEND_MULTIPLE.equals(getIntent().getAction())) {

            var outputGroup = (RadioGroup) findViewById(R.id.output_group);
            switch (AudioOutputRouter.getPreferredOutput(this)) {
                case AudioOutputRouter.OUTPUT_BLUETOOTH -> outputGroup.check(R.id.output_bluetooth);
                case AudioOutputRouter.OUTPUT_WIRED -> outputGroup.check(R.id.output_wired);
                case AudioOutputRouter.OUTPUT_USB -> outputGroup.check(R.id.output_usb);
                default -> outputGroup.check(R.id.output_default);
            }
            outputGroup.setOnCheckedChangeListener((group, checkedId) -> {
                int selected = AudioOutputRouter.OUTPUT_DEFAULT;
                if (checkedId == R.id.output_bluetooth) {
                    selected = AudioOutputRouter.OUTPUT_BLUETOOTH;
                } else if (checkedId == R.id.output_wired) {
                    selected = AudioOutputRouter.OUTPUT_WIRED;
                } else if (checkedId == R.id.output_usb) {
                    selected = AudioOutputRouter.OUTPUT_USB;
                }
                AudioOutputRouter.setPreferredOutput(this, selected);
            });

            var fadeOutLabel = (TextView) findViewById(R.id.fade_out_label);
            var fadeOutSlider = (SeekBar) findViewById(R.id.fade_out_slider);
            int savedFadeOut = AudioOutputRouter.getFadeOutSeconds(this);
            fadeOutSlider.setProgress(savedFadeOut - 1); // 0-9 maps to 1-10 s
            fadeOutLabel.setText("Fade out duration: " + savedFadeOut + " s");
            fadeOutSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int seconds = progress + 1;
                    fadeOutLabel.setText("Fade out duration: " + seconds + " s");
                    AudioOutputRouter.setFadeOutSeconds(Launcher.this, seconds);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });

         /* open file-browser + queue screen */
            findViewById(R.id.browser_queue_opener).setOnClickListener(v -> {
                startActivity(new Intent(this, FileBrowserQueueActivity.class));
            });

             findViewById(R.id.remote_queue_fill_opener).setOnClickListener(v -> {
                 Intent remoteQueueFillIntent = new Intent(this, FileBrowserQueueActivity.class);
                 remoteQueueFillIntent.putExtra(FileBrowserQueueActivity.EXTRA_REMOTE_QUEUE_FILL_MODE, true);
                 startActivity(remoteQueueFillIntent);
             });

             /* stop playback after the current track finishes */
             stopAfterCurrentButton = findViewById(R.id.stop_after_current);
             stopAfterCurrentButton.setOnClickListener(v -> {
                  if (!Service.sIsPlaying) {
                      Toast.makeText(this, "No track is playing", Toast.LENGTH_SHORT).show();
                      return;
                  }
                  final Intent clearIntent = new Intent(this, Service.class);
                  clearIntent.putExtra(Launcher.TYPE, Launcher.CLEAR_QUEUE);
                  startService(clearIntent);
                  Toast.makeText(this, "Playback will stop after current track", Toast.LENGTH_SHORT).show();
              });

             /* clear queued upcoming tracks in the running service */
             findViewById(R.id.clear_queue).setOnClickListener(v -> {
                 final Intent clearIntent = new Intent(this, Service.class);
                 clearIntent.putExtra(Launcher.TYPE, Launcher.CLEAR_QUEUE);
                 startService(clearIntent);
                 
                 if (!Service.sIsPlaying) {
                     QueueStore.clear(this);
                     Toast.makeText(this, "Queue cleared", Toast.LENGTH_SHORT).show();
                 } else {
                     // Trim the persistent queue so FileBrowserQueueActivity sees the same
                     // state as the service: only entries up to and including the current track.
                     int offset = QueueStore.loadPlaybackOffset(this);
                     int keepUpTo = offset + Service.sCurrentIndex + 1;
                     java.util.ArrayList<QueueStore.Entry> entries = QueueStore.load(this);
                     if (keepUpTo < entries.size()) {
                         QueueStore.save(this, entries.subList(0, keepUpTo));
                     }
                     Toast.makeText(this, "Cleared upcoming tracks", Toast.LENGTH_SHORT).show();
                 }
             });

             findViewById(R.id.show_license).setOnClickListener(v -> showLicenseDialog());

             // Register broadcast receiver to update button state
             playbackStateReceiver = new BroadcastReceiver() {
                 @Override
                 public void onReceive(Context context, Intent intent) {
                     updateStopAfterCurrentButtonState();
                 }
             };
             IntentFilter filter = new IntentFilter(Service.ACTION_PLAYBACK_STATE);
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                 registerReceiver(playbackStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
             } else {
                 registerReceiver(playbackStateReceiver, filter);
             }

             // Initial state update
             updateStopAfterCurrentButtonState();

            return;
        }
        onIntent(getIntent());
    }

    /**
     * redirect call to actual logic
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onIntent(intent);
    }

    /**
     * restarts service
     */
    private void onIntent(Intent intent) {
        intent.setClass(this, Service.class);
        startService(intent);

        /* does not need to keep this activity */
        finishAndRemoveTask();
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (playbackStateReceiver != null) {
            try {
                unregisterReceiver(playbackStateReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was not registered
            }
        }
    }

    private void showLicenseDialog() {
        String text;
        try (InputStream is = getResources().openRawResource(R.raw.license)) {
            text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "License text unavailable.";
        }

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("GNU General Public License v3")
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
    }

    private void updateStopAfterCurrentButtonState() {
        if (stopAfterCurrentButton == null) return;
        
        // Button is "active" when there are no pending tracks after current (i.e., stop is already scheduled)
        boolean isActive = Service.sIsPlaying && !Service.sHasPendingTracks;
        
        if (isActive) {
            // Make button appear pressed/active
            stopAfterCurrentButton.setAlpha(0.5f);
            stopAfterCurrentButton.setEnabled(false);
        } else {
            // Normal appearance
            stopAfterCurrentButton.setAlpha(1.0f);
            stopAfterCurrentButton.setEnabled(true);
        }
    }
}

