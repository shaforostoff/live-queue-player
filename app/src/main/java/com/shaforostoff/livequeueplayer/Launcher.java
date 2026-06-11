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
    public static final byte SEEK = 10;
    public static final byte APPLY_EQ = 11;
    public static final byte CLEAR_PLAYED_QUEUE = 12;
    public static final byte PLAY_FROM_QUEUE_INDEX = 13;

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
            var bluetoothButton = findViewById(R.id.output_bluetooth);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                bluetoothButton.setVisibility(View.GONE);
            }
            int preferred = AudioOutputRouter.getPreferredOutput(this);
            switch (preferred) {
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
            fadeOutLabel.setText(getString(R.string.fade_out_label, savedFadeOut));
            fadeOutSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int seconds = progress + 1;
                    fadeOutLabel.setText(getString(R.string.fade_out_label, seconds));
                    AudioOutputRouter.setFadeOutSeconds(Launcher.this, seconds);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });

         /* open file-browser + queue screen */
            findViewById(R.id.browser_queue_opener).setOnClickListener(v -> {
                startActivity(new Intent(this, FileBrowserQueueActivity.class));
            });

             findViewById(R.id.explore_mode_button).setOnClickListener(v -> {
                Intent browseIntent = new Intent(this, FileBrowserQueueActivity.class);
                browseIntent.putExtra(FileBrowserQueueActivity.EXTRA_BROWSE_MODE, true);
                startActivity(browseIntent);
            });

            findViewById(R.id.remote_receive_opener).setOnClickListener(v -> {
                Intent intent = new Intent(this, FileBrowserQueueActivity.class);
                intent.putExtra(FileBrowserQueueActivity.EXTRA_REMOTE_QUEUE_FILL_MODE, true);
                intent.putExtra(FileBrowserQueueActivity.EXTRA_REMOTE_QUEUE_SERVER_MODE, 1);
                startActivity(intent);
            });
            findViewById(R.id.remote_send_opener).setOnClickListener(v -> {
                Intent intent = new Intent(this, FileBrowserQueueActivity.class);
                intent.putExtra(FileBrowserQueueActivity.EXTRA_REMOTE_QUEUE_FILL_MODE, true);
                intent.putExtra(FileBrowserQueueActivity.EXTRA_REMOTE_QUEUE_SERVER_MODE, 0);
                startActivity(intent);
            });
             /* stop playback after the current track finishes */
             stopAfterCurrentButton = findViewById(R.id.stop_after_current);
             stopAfterCurrentButton.setOnClickListener(v -> {
                  if (!Service.sIsPlaying) {
                      Toast.makeText(this, R.string.no_track_playing, Toast.LENGTH_SHORT).show();
                      return;
                  }
                  final Intent clearIntent = new Intent(this, Service.class);
                  clearIntent.putExtra(Launcher.TYPE, Launcher.CLEAR_QUEUE);
                  startService(clearIntent);
                  Toast.makeText(this, R.string.stop_after_current_toast, Toast.LENGTH_SHORT).show();
              });

             /* clear queued tracks in the running service */
             findViewById(R.id.clear_queue).setOnClickListener(v -> {
                 if (!Service.sIsPlaying) {
                     final Intent clearIntent = new Intent(this, Service.class);
                     clearIntent.putExtra(Launcher.TYPE, Launcher.CLEAR_QUEUE);
                     startService(clearIntent);
                     QueueStore.clear(this);
                     Toast.makeText(this, R.string.queue_cleared_toast, Toast.LENGTH_SHORT).show();
                     return;
                 }
                 // A track is playing: ask which side of the current track to clear.
                 final String[] options = {
                         getString(R.string.clear_above_current),
                         getString(R.string.clear_below_current)
                 };
                 new AlertDialog.Builder(this)
                         .setTitle(R.string.clear_queue_dialog_title)
                         .setItems(options, (d, which) -> {
                             if (which == 0) clearAboveCurrent();
                             else clearBelowCurrent();
                         })
                         .setNegativeButton(android.R.string.cancel, null)
                         .show();
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
     * Clear the already-played tracks queued before the currently playing one. The current
     * track becomes the first entry in the persisted queue (playback offset reset to 0).
     */
    private void clearAboveCurrent() {
        final Intent clearIntent = new Intent(this, Service.class);
        clearIntent.putExtra(Launcher.TYPE, Launcher.CLEAR_PLAYED_QUEUE);
        startService(clearIntent);

        int offset = QueueStore.loadPlaybackOffset(this);
        int removeBefore = offset + Service.sCurrentIndex; // persisted index of current track
        java.util.ArrayList<QueueStore.Entry> entries = QueueStore.load(this);
        if (removeBefore > 0 && removeBefore < entries.size()) {
            QueueStore.save(this, entries.subList(removeBefore, entries.size()));
            QueueStore.savePlaybackOffset(this, 0);
        }
        Toast.makeText(this, R.string.played_cleared_toast, Toast.LENGTH_SHORT).show();
    }

    /**
     * Clear the upcoming tracks queued after the currently playing one. Trims the persistent
     * queue so FileBrowserQueueActivity sees the same state as the service: only entries up to
     * and including the current track.
     */
    private void clearBelowCurrent() {
        final Intent clearIntent = new Intent(this, Service.class);
        clearIntent.putExtra(Launcher.TYPE, Launcher.CLEAR_QUEUE);
        startService(clearIntent);

        int offset = QueueStore.loadPlaybackOffset(this);
        int keepUpTo = offset + Service.sCurrentIndex + 1;
        java.util.ArrayList<QueueStore.Entry> entries = QueueStore.load(this);
        if (keepUpTo < entries.size()) {
            QueueStore.save(this, entries.subList(0, keepUpTo));
        }
        Toast.makeText(this, R.string.upcoming_cleared_toast, Toast.LENGTH_SHORT).show();
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
        try (InputStream is = getResources().openRawResource(R.raw.license);
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            text = baos.toString(StandardCharsets.UTF_8.name());
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
                .setTitle(R.string.license_dialog_title)
                .setView(sv)
                .setPositiveButton(R.string.license_dialog_close, null)
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

