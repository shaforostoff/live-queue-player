package com.shaforostoff.livequeueplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.text.Editable;
import android.text.TextWatcher;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Activity providing a split-screen file browser (top) and play queue (bottom).
 */
public class FileBrowserQueueActivity extends Activity {

    public static final String EXTRA_REMOTE_QUEUE_FILL_MODE = "remote_queue_fill_mode";
    public static final String EXTRA_REMOTE_QUEUE_SERVER_MODE = "remote_queue_server_mode";
    public static final String EXTRA_BROWSE_MODE = "browse_mode";

    enum Mode {
        DJ,            // standard queue playback
        BROWSE,        // tap-to-play from file browser
        REMOTE_SEND,   // browse + BT: sends track requests to remote server
        REMOTE_RECEIVE // DJ + BT: receives track requests from remote client
    }

    enum TagState { UNKNOWN, LOADING, RESOLVED }

    @FunctionalInterface
    private interface SwipeAction { void onSwipe(int position); }
    @FunctionalInterface
    private interface SwipePredicate { boolean test(int position); }
    @FunctionalInterface
    private interface IndexedTask { void run(int index); }
    @FunctionalInterface
    private interface RelativePathResolver { String resolve(Uri entryUri); }

    private static final class SwipeState {
        float downX, downY;
        int startPosition = -1;
        boolean handled;
        boolean swiping;
        View swipingView;
        View contentView;

        void resetView() {
            if (contentView != null) {
                contentView.setTranslationX(0);
                contentView = null;
            }
            swipingView = null;
            swiping = false;
        }
    }

    private static final class DragState {
        int currentPosition = -1;
        boolean active;
        View ghostView;
        float touchOffsetX;
        float touchOffsetY;

        void reset() {
            currentPosition = -1;
            active = false;
            ghostView = null;
            touchOffsetX = 0;
            touchOffsetY = 0;
        }
    }

    private static final int PERMISSION_REQUEST_CODE = 2001;
    private static final int TREE_REQUEST_CODE = 2002;
    private static final String ACTION_SEND_MULTIPLE_COMPAT = "android.intent.action.SEND_MULTIPLE";
    private static final String BROWSER_PREFS = "browser_prefs";
    private static final String PREF_SORT_MODE = "sort_mode";
    private static final long PLAYBACK_SYNC_INTERVAL_MS = 1_000L;
    private static final int PROGRESS_LEVEL_MAX = 10_000;
    private static final int SORT_FILENAME = 0;
    private static final int SORT_YEAR = 1;
    private static final int SORT_GENRE = 2;
    private static final int SORT_BPM = 3;
    private static final int SORT_ARTIST = 4;

    private static final String[] AUDIO_EXTENSIONS = {
            ".m4a", ".mp3", ".mp4", ".aac", ".ogg", ".flac", ".aiff", ".aif",
            ".wav", ".opus", ".wma", ".3gp", ".m3u", ".m3u8"
    };
    // -- file browser state -------------------------------------------------
    private final List<FileEntry> fileEntries = new ArrayList<>();
    private final List<FileEntry> filteredFileEntries = new ArrayList<>();
    private FileAdapter fileAdapter;
    private EditText fileFilterInput;
    private View rootContainer;
    private String fileFilterQuery = "";
    private int fileSortMode = SORT_FILENAME;
    private int fileEntriesVersion = 0;
    private MetadataExtractor metadataExtractor;
    private int activeTagReadJobs;
    private int tagReadProgressTotal;
    private final AtomicInteger tagReadProgressDone = new AtomicInteger(0);
    private final AtomicBoolean progressRedrawPending = new AtomicBoolean(false);
    private final ExecutorService tagReadExecutor = Executors.newFixedThreadPool(4);
    private ClipDrawable progressClipDrawable;

    // -- queue state --------------------------------------------------------
    private final List<QueueEntry> queueEntries = new ArrayList<>();
    private QueueAdapter queueAdapter;
    private ListView queueList;
    private TextView queueEmptyHint;
    private boolean queueTransitionActive;
    private int currentPlayingQueueIndex = -1;
    private int draggingQueueIndex = -1;
    // set when a swipe gesture starts so the ListView's item-click (fired on finger
    // release) is ignored, even if the swipe didn't move far enough to trigger its action
    private boolean suppressItemClick;
    // Active swipe state for the queue list. Held as a field (not a local in the gesture handler)
    // so per-row refreshes can skip the row currently being swiped instead of clobbering it.
    private final SwipeState queueSwipeState = new SwipeState();
    // swipe slop thresholds (px), resolved once from display density
    private float swipeVerticalSlop;
    private float swipeHorizontalSlop;
    // row colours shared by the file-browser and queue adapters, resolved once
    private int themeBackgroundColor;
    private int progressTrackColor;
    private int progressFillColor;
    private int servicePlaybackOffset = 0;
    /** Entry id of the insert anchor (0 = none): new tracks are inserted above it. */
    private int anchorEntryId = 0;
    private Button stopButton;
    private Button browserStopButton;
    private Button sortButton;
    private Button openStorageButton;
    private View eqButton;
    private Mode mode = Mode.DJ;

    private Uri browseFileUri;
    private boolean browseNextQueued;
    private Uri browseNextUri;
    private boolean browseTransitionActive;
    private FileEntry currentBrowsePlaylistEntry;
    private Uri pendingBackScrollUri;
    private BluetoothController btController;
    private RemoteQueueController remoteQueueController;
    private String lastBroadcastPushedPlayKey;
    private View localQueuePanel;
    private View remoteQueuePanel;
    private boolean localQueueShownInRemoteMode = false;
    private Button clearButton;
    private Button saveButton;
    private int currentTrackPositionMs;
    private int currentTrackDurationMs;
    private PreviewManager audioPreviewManager;
    private Uri fileBrowserPreviewingUri;
    private Uri fileBrowserPreviewingEntryUri;
    private ListView fileBrowserList;
    private final BroadcastReceiver playbackStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if (!Service.ACTION_PLAYBACK_STATE.equals(intent.getAction())) return;

            int prevPlayingIndex = currentPlayingQueueIndex;
            boolean isPlaying = intent.getBooleanExtra(Service.EXTRA_IS_PLAYING, false);
            int serviceIndex = intent.getIntExtra(Service.EXTRA_CURRENT_INDEX, -1);
            int entryId = intent.getIntExtra(Service.EXTRA_CURRENT_ENTRY_ID, -1);
            Uri currentUri = intent.getParcelableExtra(Service.EXTRA_CURRENT_URI);
            boolean serviceBrowseMode = intent.getBooleanExtra(Service.EXTRA_BROWSE_MODE, false);
            currentTrackPositionMs = intent.getIntExtra(
                    Service.EXTRA_PLAYBACK_POSITION_MS,
                    Service.sPlaybackPositionMs);
            currentTrackDurationMs = intent.getIntExtra(
                    Service.EXTRA_PLAYBACK_DURATION_MS,
                    Service.sPlaybackDurationMs);
            // The fading button state is derived from Service.sFadeOutInProgress (see
            // isStopFadeInProgress()), so every broadcast just re-applies the current state.
            applyStopButtonState();

            if (serviceIndex < 0) {
                SilenceStreamer.reinitIfOutputChanged(FileBrowserQueueActivity.this);
                if (browseTransitionActive && !isStopFadeInProgress()) {
                    // Transient stop between sendStopNowCommand() and the new browse track starting.
                    // Keep browse state intact; the next broadcast will update us.
                    return;
                }
                if (queueTransitionActive && !isStopFadeInProgress()) {
                    return;
                }
                clearBrowseState();
                currentPlayingQueueIndex = -1;
                if (isStopFadeInProgress()) {
                    onFadeOutFinished();
                } else {
                    setPlaybackOffset(0);
                    resetCurrentTrackProgress();
                }
            } else if (serviceBrowseMode) {
                browseTransitionActive = false;
                currentPlayingQueueIndex = -1;
                if (browseNextUri != null && browseNextUri.equals(currentUri)) {
                    browseFileUri = browseNextUri;
                    browseNextQueued = false;
                    browseNextUri = null;
                    if (fileAdapter != null) fileAdapter.notifyDataSetChanged();
                }
            } else {
                queueTransitionActive = false;
                currentPlayingQueueIndex = resolvePlayingQueueIndex(entryId, serviceIndex, currentUri);
                if (currentPlayingQueueIndex >= 0 && currentPlayingQueueIndex != prevPlayingIndex) {
                    scrollTo(queueList, currentPlayingQueueIndex);
                }
            }

            clearAnchorIfPlaybackReached();
            refreshQueuePlaybackRows(prevPlayingIndex);
            maybeQueueNextBrowseTrack();
            pushPlayStateIfChanged();
        }
    };
    private boolean playbackReceiverRegistered;
    private final Handler uiHandler = new Handler();
    private final Runnable playbackStateSyncRunnable = new Runnable() {
        @Override
        public void run() {
            syncWithServiceState();
            if (!isDestroyed()) {
                uiHandler.postDelayed(this, PLAYBACK_SYNC_INTERVAL_MS);
            }
        }
    };
    private StorageBrowser storageBrowser;

    // ---------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser_queue);

        // -- resolve shared metrics & colours once --------------------------
        swipeVerticalSlop = dp(40f);
        swipeHorizontalSlop = dp(20f);
        TypedValue bg = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, bg, true);
        themeBackgroundColor = bg.data;
        progressTrackColor = getColor(R.color.queueProgressBackground);
        progressFillColor = getColor(R.color.queueProgressFill);

        // -- initialize drag preview manager --------------------------------
        audioPreviewManager = new PreviewManager(this);

        // -- view references -------------------------------------------------
        rootContainer   = findViewById(R.id.root_container);
        fileFilterInput = findViewById(R.id.file_filter_input);
        queueEmptyHint  = findViewById(R.id.queue_empty_hint);
        installSearchCursorKeyboardBinding();

        fileBrowserList = findViewById(R.id.file_browser_list);
        queueList                = findViewById(R.id.queue_list);
        View     queueContainer  = findViewById(R.id.queue_container);
        localQueuePanel  = findViewById(R.id.local_queue_panel);
        remoteQueuePanel = findViewById(R.id.remote_queue_panel);
        remoteQueuePanel.setVisibility(View.GONE);
        if (getIntent().getBooleanExtra(EXTRA_BROWSE_MODE, false)) {
            mode = Mode.BROWSE;
        }

        clearButton = findViewById(R.id.btn_clear_queue);
        openStorageButton = findViewById(R.id.btn_open_storage);
        sortButton = findViewById(R.id.btn_sort_files);
        saveButton = findViewById(R.id.btn_save_queue);
        stopButton = findViewById(R.id.btn_stop_queue);
        eqButton = findViewById(R.id.btn_eq);
        eqButton.setOnClickListener(v -> EqualizerDialog.show(this,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? new ParametricLocalEqSink(this)
                        : new LocalEqSink(this)));
        metadataExtractor = ((App) getApplication()).getMetadataExtractor();
        storageBrowser = new StorageBrowser(this);
        btController = new BluetoothController(this, new BluetoothController.Callback() {
            @Override
            public void onModeSelected() {
                // Mode is chosen up front in onCreate (server/client), and the receive-mode tag scan
                // is kicked off there too; this callback only refreshes UI once the bridge is up.
                applyPlayButtonModeState();
                if (fileAdapter != null) fileAdapter.notifyDataSetChanged();
                // If we re-attached to an already-live connection (e.g. after rotation), the fresh
                // RemoteQueueController is empty and no new "connected" event will fire — pull the
                // current queue so the mirror repopulates. On first setup isConnected() is still
                // false here (connect is async), so this is a no-op until the real connect arrives.
                if (mode == Mode.REMOTE_SEND && remoteQueueController != null
                        && btController.isConnected()) {
                    remoteQueueController.onConnected();
                }
            }
            @Override
            public void onQueueRequestsReceived(List<BluetoothQueueBridge.TrackRequest> tracks) {
                onRemoteQueueRequestsReceived(tracks);
            }
            @Override
            public void onMatchResultReceived(String jsonLine) {
                showMatchResultToast(jsonLine);
            }
            @Override
            public void onRemoteQueueMessageReceived(String type, JSONObject obj) {
                if (mode == Mode.REMOTE_SEND && remoteQueueController != null) {
                    if ("queue_state".equals(type))        remoteQueueController.onQueueStateReceived(obj);
                    else if ("play_state".equals(type))    remoteQueueController.onPlaybackStateReceived(obj);
                    else if ("queue_changed".equals(type)) remoteQueueController.requestQueue();
                    else if ("volume_state".equals(type))  remoteQueueController.onVolumeStateReceived(obj);
                    else if ("eq_state".equals(type))      remoteQueueController.onEqStateReceived(obj);
                    return;
                }
                if (mode != Mode.REMOTE_RECEIVE) return;
                switch (type) {
                    case "request_queue":   handleRemoteRequestQueue(obj);  break;
                    case "move_track":      handleRemoteMoveTrack(obj);     break;
                    case "remove_track":    handleRemoteRemoveTrack(obj);   break;
                    case "set_anchor":      handleRemoteSetAnchor(obj);     break;
                    case "stop_playback":   stopPlaybackWithFadeout();   pushPlayState(); break;
                    case "resume_playback": cancelFadeOutAndContinue();  pushPlayState(); break;
                    case "play_track":      handleRemotePlayTrack(obj);     break;
                    case "set_volume":      handleRemoteSetVolume(obj);     break;
                    case "request_volume":  pushVolumeState();              break;
                    case "set_eq":          handleRemoteSetEq(obj);         break;
                    case "request_eq":      pushEqState();                  break;
                }
            }
            @Override
            public void onConnectionStateChanged(boolean connected) {
                if (connected && mode == Mode.REMOTE_SEND && remoteQueueController != null) {
                    remoteQueueController.onConnected();
                }
            }
        });

        // -- adapters --------------------------------------------------------
        fileAdapter = new FileAdapter();
        fileBrowserList.setAdapter(fileAdapter);

        queueAdapter = new QueueAdapter();
        queueList.setAdapter(queueAdapter);
        servicePlaybackOffset = QueueStore.loadPlaybackOffset(this);

        fileFilterInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                fileFilterQuery = (s == null) ? "" : s.toString();
                applyFileFilter();
                if (!scrollToHighlightedFileEntry()) {
                    fileBrowserList.setSelection(0);
                }
            }
        });

        // -- file browser: tap to preview (when secondary output active) or add
        fileBrowserList.setOnItemClickListener((parent, view, position, id) -> {
            if (suppressItemClick) { suppressItemClick = false; return; }
            FileEntry entry = filteredFileEntries.get(position);
            if (entry.isDirectory()) {
                if (entry.file != null) navigateTo(entry.file);
                else navigateToDocumentEntry(entry.uri, entry.name);
                return;
            }
            if (isPlaylistFile(entry.name)) {
                enterPlaylistAsBrowseFolder(entry);
                return;
            }
            if (PreviewManager.isEnabled(this) && !hasBrowseBehavior()) {
                if (entry.uri.equals(fileBrowserPreviewingUri)) {
                    resetFileBrowserPreview();
                } else {
                    fileBrowserPreviewingUri = entry.uri;
                    fileBrowserPreviewingEntryUri = entry.uri;
                    startAudioPreview(entry.uri);
                    updateBrowserStopButtonVisibility();
                }
            } else {
                boolean queueTrackPlaying = Service.sIsPlaying && !Service.sBrowseMode && currentPlayingQueueIndex >= 0;
                if (hasBrowseBehavior() && !isStopFadeInProgress() && !(mode == Mode.BROWSE && queueTrackPlaying)) {
                    playBrowseFile(entry);
                } else {
                    addToQueue(entry.name, entry.uri);
                }
            }
        });
        installFileBrowserSwipeAdd(fileBrowserList);

        // -- queue: tap item to play when stopped ----------------------------
        queueList.setOnItemClickListener((parent, view, position, id) -> {
            if (suppressItemClick) { suppressItemClick = false; return; }
            if (localQueueShownInRemoteMode && position == currentPlayingQueueIndex && isPlaybackActiveOrFading()) {
                showLyricsOverlayForQueueEntry(queueEntries.get(position));
                return;
            }
            if (mode == Mode.REMOTE_SEND || Service.sBrowseMode) {
                clearBrowseState();
                fileAdapter.notifyDataSetChanged();
                playQueueFrom(position, isPlaybackActiveOrFading());
                return;
            }
            if (position == currentPlayingQueueIndex && isPlaybackActiveOrFading()) {
                showLyricsOverlayForQueueEntry(queueEntries.get(position));
                return;
            }
            if (isStopFadeInProgress()) {
                playQueueFrom(position, true);
            } else if (!Service.sIsPlaying && !queueTransitionActive) {
                playQueueFrom(position);
            } else {
                Toast.makeText(this, R.string.stop_playback_first, Toast.LENGTH_SHORT).show();
                //Toast.makeText(this, "Swipe right: remove. Stop playback to play this track", Toast.LENGTH_SHORT).show();
            }
        });
        installQueueGestureHandler(queueList);

        // -- navigation & playback buttons -----------------------------------
        clearButton.setOnClickListener(v -> clearQueueAndStopPlayback());
        applyPlayButtonModeState();

        if (getIntent().getBooleanExtra(EXTRA_REMOTE_QUEUE_FILL_MODE, false)) {
            int serverMode = getIntent().getIntExtra(EXTRA_REMOTE_QUEUE_SERVER_MODE, -1);
            if (serverMode == 1) {
                mode = Mode.REMOTE_RECEIVE;
                btController.startRemoteSetupAsServer();
            } else if (serverMode == 0) {
                enterRemoteSendMode();
                btController.startRemoteSetupAsClient();
            }
        }

        openStorageButton.setOnClickListener(v -> handleStorageButtonPressed());
        sortButton.setOnClickListener(v -> showSortDialog());
        fileSortMode = getSharedPreferences(BROWSER_PREFS, MODE_PRIVATE).getInt(PREF_SORT_MODE, SORT_FILENAME);
        applySaveButtonModeState();
        applySortButtonLoadingState();

        stopButton.setOnClickListener(v -> {
            if (isStopFadeInProgress() || isPlaybackPaused()) {
                cancelFadeOutAndContinue();
            } else if (hasBrowseBehavior() || Service.sBrowseMode) {
                stopPlaybackImmediately();
            } else {
                stopPlaybackWithFadeout();
            }
            // A local stop/resume must notify a connected client too, just as the remote
            // stop_playback / resume_playback handlers do (the later broadcast is deduped).
            if (mode == Mode.REMOTE_RECEIVE) pushPlayState();
        });

        browserStopButton = findViewById(R.id.btn_stop_browser);
        browserStopButton.setOnClickListener(v -> {
            resetFileBrowserPreview();
            if (Service.sBrowseMode) stopPlaybackImmediately();
        });

        // -- kick off storage permission + browse ----------------------------
        if (!restorePersistedDocumentTree()) {
            requestPermissionsAndBrowse();
        }
        // Sole trigger for the receive-mode whole-library tag scan (runs regardless of BT state).
        if (mode == Mode.REMOTE_RECEIVE) {
            startRecursiveTagScanAsync();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != TREE_REQUEST_CODE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri treeUri = data.getData();
        if (treeUri == null) {
            return;
        }

        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (flags == 0) {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        }
        try {
            getContentResolver().takePersistableUriPermission(treeUri, flags);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }

        storageBrowser.rememberLastTreeUri(treeUri);
        metadataExtractor.clearCache();



        if (!openDocumentTree(treeUri)) {
            Toast.makeText(this, R.string.storage_picker_failed, Toast.LENGTH_LONG).show();
        }
    }

    // -- permission handling -------------------------------------------------

    private void requestPermissionsAndBrowse() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.READ_MEDIA_AUDIO},
                        PERMISSION_REQUEST_CODE);
                return;
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE);
                return;
            }
        }
        startBrowsing();
    }

    private boolean restorePersistedDocumentTree() {
        Uri rememberedTreeUri = storageBrowser.getRememberedTreeUri();
        if (rememberedTreeUri != null
                && storageBrowser.hasReadPermissionForUri(rememberedTreeUri)
                && openDocumentTree(rememberedTreeUri)) {
            return true;
        }

        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            Uri uri = permission.getUri();
            if (permission.isReadPermission() && openDocumentTree(uri)) {
                storageBrowser.rememberLastTreeUri(uri);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startBrowsing();
            } else {
                Toast.makeText(this,
                        "Storage permission denied - cannot browse files.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == BluetoothController.PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                btController.onPermissionGranted();
            } else {
                Toast.makeText(this,
                        "Bluetooth permission denied.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // -- browsing helpers ----------------------------------------------------

    private void startBrowsing() {
        storageBrowser.clearBrowsingState();

        // Prefer the Music folder, fall back to root of external storage
        File musicDir   = StorageBrowser.getMusicDirectoryCompat();
        File storageDir = Environment.getExternalStorageDirectory();

        if (musicDir != null && musicDir.exists() && musicDir.canRead()) {
            navigateTo(musicDir);
        } else if (storageDir != null && storageDir.exists() && storageDir.canRead()) {
            navigateTo(storageDir);
        } else {
            // Last resort: use MediaStore to list all audio and show as flat list
            loadFromMediaStore();
        }
    }

    private void handleStorageButtonPressed() {
        if (canNavigateUpFromCurrentFolder()) {
            navigateUpFromCurrentFolder();
            return;
        }
        openStoragePicker();
    }

    private boolean canNavigateUpFromCurrentFolder() {
        if (currentBrowsePlaylistEntry != null) return true;
        if (storageBrowser.isBrowsingDocumentTree()) {
            return storageBrowser.canPopDocument();
        }
        return storageBrowser.canNavigateUpInFiles();
    }

    private void navigateUpFromCurrentFolder() {
        if (currentBrowsePlaylistEntry != null) { exitPlaylistBrowseFolder(); return; }
        clearFileFilterInput();
        if (storageBrowser.isBrowsingDocumentTree()) {
            navigateDocumentUp();
            return;
        }
        File dir  = storageBrowser.getCurrentFileDirectory();
        File root = storageBrowser.getCurrentFileRootDirectory();
        if (dir == null || root == null) {
            return;
        }
        if (StorageBrowser.sameFileLocation(dir, root)) {
            return;
        }
        File parent = dir.getParentFile();
        if (parent != null) {
            pendingBackScrollUri = Uri.fromFile(dir);
            navigateTo(parent);
        }
    }

    private void navigateTo(File dir) {
        stopBrowsePlaybackForFolderSwitch();
        currentBrowsePlaylistEntry = null;
        resetFileBrowserPreview();
        clearFileFilterInput();
        fileEntriesVersion++;
        fileEntries.clear();
        fileEntries.addAll(storageBrowser.listFolder(dir));
        sortFileEntriesInPlace();
        applyFileFilter();
        scrollToHighlightedFileEntry();
    }

    private void openStoragePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        Uri initialTreeUri = storageBrowser.getRememberedTreeUri();
        if (initialTreeUri != null && storageBrowser.hasReadPermissionForUri(initialTreeUri)) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialTreeUri);
        } else {
            // First use: point picker at SD card so the user can find it easily.
            Uri sdCardUri = storageBrowser.findSdCardDocumentUri();
            if (sdCardUri != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, sdCardUri);
            }
        }
        startActivityForResult(intent, TREE_REQUEST_CODE);
    }

    private void clearBrowseState() {
        browseTransitionActive = false;
        browseFileUri = null;
        browseNextQueued = false;
        browseNextUri = null;
    }

    private boolean openDocumentTree(Uri treeUri) {
        if (!storageBrowser.openDocumentTree(treeUri)) {
            return false;
        }
        return browseCurrentDocumentDirectory();
    }

    private void navigateToDocumentEntry(Uri documentUri, String documentName) {
        if (!storageBrowser.isBrowsingDocumentTree() || documentUri == null) {
            return;
        }

        stopBrowsePlaybackForFolderSwitch();
        resetFileBrowserPreview();
        clearFileFilterInput();
        storageBrowser.pushDocument(documentUri);
        if (!browseCurrentDocumentDirectory()) {
            storageBrowser.popDocument();
        }
    }

    private void navigateDocumentUp() {
        if (!storageBrowser.isBrowsingDocumentTree()) {
            return;
        }

        stopBrowsePlaybackForFolderSwitch();
        resetFileBrowserPreview();
        clearFileFilterInput();
        if (storageBrowser.canPopDocument()) {
            pendingBackScrollUri = storageBrowser.getCurrentDocumentUri();
            storageBrowser.popDocument();
            browseCurrentDocumentDirectory();
        } else {
            requestPermissionsAndBrowse();
        }
    }

    private void clearFileFilterInput() {
        fileFilterQuery = "";
        if (fileFilterInput == null) {
            return;
        }
        CharSequence current = fileFilterInput.getText();
        if (current != null && current.length() > 0) {
            fileFilterInput.setText("");
        }
        // Changing folder drops the search field's focus: an unfocused EditText shows no cursor on
        // every Android version. (Relying on the keyboard-visibility binding alone is not enough —
        // its IME inset callback doesn't fire on some versions, e.g. Android 13, leaving the cursor
        // blinking.) Moving focus to the focusable root also lets the keyboard close.
        hideSearchKeyboard();
        if (rootContainer != null) {
            rootContainer.requestFocus();
        }
    }

    private void hideSearchKeyboard() {
        if (fileFilterInput == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(fileFilterInput.getWindowToken(), 0);
        }
    }

    private boolean browseCurrentDocumentDirectory() {
        currentBrowsePlaylistEntry = null;
        if (!storageBrowser.hasDocumentLocation()) {
            return false;
        }

        fileEntriesVersion++;
        fileEntries.clear();

        List<FileEntry> listed = storageBrowser.readCurrentDocumentDirectory();
        if (listed == null) {
            applyFileFilter();
            return false;
        }

        fileEntries.addAll(listed);
        sortFileEntriesInPlace();
        applyFileFilter();
        scrollToHighlightedFileEntry();
        return true;
    }

    private void updateStorageButtonState() {
        if (openStorageButton == null) {
            return;
        }
        openStorageButton.setText(canNavigateUpFromCurrentFolder()
                ? R.string.navigate_back_button
                : R.string.browse_storage_button);
    }

    private void promptSaveQueueFilename() {
        if (queueEntries.isEmpty()) {
            Toast.makeText(this, R.string.save_queue_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasCurrentFolder()) {
            Toast.makeText(this, R.string.save_queue_no_folder, Toast.LENGTH_SHORT).show();
            return;
        }

        EditText input = new EditText(this);
        input.setHint(R.string.save_queue_dialog_hint);
        input.setSingleLine(true);
        input.setText("");
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle(R.string.save_queue_dialog_title)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save_queue_dialog_action,
                        (dialog, which) -> promptSaveDestinationThenSave(input.getText() == null
                                ? ""
                                : input.getText().toString()))
                .show();
    }

    private void showSortDialog() {
        CharSequence[] options = {
                getString(R.string.sort_mode_filename),
                getString(R.string.sort_mode_year),
                getString(R.string.sort_mode_genre),
                getString(R.string.sort_mode_bpm),
                getString(R.string.sort_mode_artist)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.sort_dialog_title)
                .setSingleChoiceItems(options, fileSortMode, (dialog, which) -> {
                    if (which != fileSortMode) {
                        fileSortMode = which;
                        getSharedPreferences(BROWSER_PREFS, MODE_PRIVATE).edit().putInt(PREF_SORT_MODE, fileSortMode).apply();
                        sortFileEntriesInPlace();
                        applyFileFilter();
                        if (!scrollToHighlightedFileEntry())
                            fileBrowserList.setSelection(0);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean hasCurrentFolder() {
        return storageBrowser.hasCurrentFolder();
    }

    private void promptSaveDestinationThenSave(String rawName) {
        String fileName = normalizePlaylistFileName(rawName);
        if (fileName == null) {
            Toast.makeText(this, R.string.save_queue_invalid_name, Toast.LENGTH_SHORT).show();
            return;
        }

        if (storageBrowser.isBrowsingDocumentTree()) {
            List<Uri> destinations = storageBrowser.getDocumentAncestry();
            if (destinations.size() == 1) {
                saveQueueToDocUri(fileName, destinations.get(0));
                return;
            }
            CharSequence[] names = new CharSequence[destinations.size()];
            for (int i = 0; i < destinations.size(); i++) {
                names[i] = getDocumentDisplayName(destinations.get(i));
            }
            final int[] selectedIndex = {0};
            new AlertDialog.Builder(this)
                    .setTitle(R.string.save_queue_dialog_title)
                    .setSingleChoiceItems(names, 0, (d, which) -> selectedIndex[0] = which)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save_queue_dialog_action,
                            (d, which) -> saveQueueToDocUri(fileName, destinations.get(selectedIndex[0])))
                    .show();
        } else {
            List<File> destinations = new ArrayList<>();
            File dir = storageBrowser.getCurrentFileDirectory();
            File root = storageBrowser.getCurrentFileRootDirectory();
            while (dir != null) {
                destinations.add(dir);
                if (StorageBrowser.sameFileLocation(dir, root)) break;
                dir = dir.getParentFile();
            }
            if (destinations.size() == 1) {
                saveQueueToFileDir(fileName, destinations.get(0));
                return;
            }
            CharSequence[] names = new CharSequence[destinations.size()];
            for (int i = 0; i < destinations.size(); i++) {
                names[i] = destinations.get(i).getName();
            }
            final int[] selectedIndex = {0};
            new AlertDialog.Builder(this)
                    .setTitle(R.string.save_queue_dialog_title)
                    .setSingleChoiceItems(names, 0, (d, which) -> selectedIndex[0] = which)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save_queue_dialog_action,
                            (d, which) -> saveQueueToFileDir(fileName, destinations.get(selectedIndex[0])))
                    .show();
        }
    }

    private String getDocumentDisplayName(Uri docUri) {
        try (Cursor cursor = getContentResolver().query(
                docUri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && name.length() > 0) return name;
            }
        } catch (Exception ignored) {}
        return fileName(DocumentsContract.getDocumentId(docUri));
    }

    /** Builds an #EXTM3U playlist from the queue, or returns null if no entry yields a path. */
    private String buildExportPlaylistContent(RelativePathResolver resolver) {
        StringBuilder playlist = new StringBuilder("#EXTM3U\n");
        int exportedCount = 0;
        for (QueueEntry entry : queueEntries) {
            String relativePath = resolver.resolve(entry.uri);
            if (relativePath == null || relativePath.length() == 0) continue;
            playlist.append(relativePath).append('\n');
            exportedCount++;
        }
        return exportedCount == 0 ? null : playlist.toString();
    }

    private void onQueueSaveResult(boolean saved, String fileName) {
        if (saved) {
            Toast.makeText(this,
                    getString(R.string.save_queue_success) + ": " + fileName,
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.save_queue_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void saveQueueToFileDir(String fileName, File destDir) {
        String content = buildExportPlaylistContent(uri -> resolveRelativeFilePath(uri, destDir));
        if (content == null) {
            Toast.makeText(this,
                    "No queue entries can be written as relative paths from this folder",
                    Toast.LENGTH_LONG).show();
            return;
        }
        boolean saved = writePlaylistToFileDir(fileName, content, destDir);
        File currentDir = storageBrowser.getCurrentFileDirectory();
        if (saved && currentDir != null && StorageBrowser.sameFileLocation(destDir, currentDir)) {
            refreshCurrentFolderListing();
        }
        onQueueSaveResult(saved, fileName);
    }

    private void saveQueueToDocUri(String fileName, Uri destDocUri) {
        String content = buildExportPlaylistContent(uri -> resolveRelativeDocumentPath(uri, destDocUri));
        if (content == null) {
            Toast.makeText(this,
                    "No queue entries can be written as relative paths from this folder",
                    Toast.LENGTH_LONG).show();
            return;
        }
        boolean saved = writePlaylistToDocumentFolder(fileName, content, destDocUri);
        if (saved && destDocUri.equals(storageBrowser.getCurrentDocumentUri())) {
            refreshCurrentFolderListing();
        }
        onQueueSaveResult(saved, fileName);
    }

    private void refreshCurrentFolderListing() {
        if (storageBrowser.isBrowsingDocumentTree()) {
            // The folder's contents just changed (e.g. a playlist was saved into it), so drop any
            // cached listing for it before re-reading.
            storageBrowser.invalidateDocumentListing(storageBrowser.getCurrentDocumentUri());
            browseCurrentDocumentDirectory();
            return;
        }
        File dir = storageBrowser.getCurrentFileDirectory();
        if (dir != null) {
            navigateTo(dir);
        }
    }

    private String normalizePlaylistFileName(String rawName) {
        String trimmed = rawName == null ? "" : rawName.trim();
        if (trimmed.length() == 0) {
            return null;
        }
        if (trimmed.contains("/") || trimmed.contains("\\")) {
            return null;
        }
        if (!trimmed.toLowerCase().endsWith(".m3u8")) {
            trimmed += ".m3u8";
        }
        return trimmed;
    }

    private boolean writePlaylistToFileDir(String fileName, String content, File destDir) {
        if (destDir == null) {
            return false;
        }

        File target = new File(destDir, fileName);
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(target, false), "UTF-8"))) {
            writer.write(content);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean writePlaylistToDocumentFolder(String fileName, String content, Uri destDocUri) {
        if (storageBrowser.getCurrentTreeUri() == null || destDocUri == null) {
            return false;
        }

        Uri targetUri = storageBrowser.findDocumentChildByName(destDocUri, fileName);
        if (targetUri == null) {
            try {
                targetUri = DocumentsContract.createDocument(
                        getContentResolver(),
                        destDocUri,
                        "application/vnd.apple.mpegurl",
                        fileName);
            } catch (Exception ignored) {
                targetUri = null;
            }
        }

        if (targetUri == null) {
            return false;
        }

        try (OutputStream stream = getContentResolver().openOutputStream(targetUri, "wt")) {
            if (stream == null) {
                return false;
            }
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, "UTF-8"))) {
                writer.write(content);
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }
    }


    private String resolveRelativeFilePath(Uri entryUri, File baseDir) {
        if (baseDir == null) {
            return null;
        }

        String scheme = entryUri.getScheme();
        String targetPath;
        if ("content".equalsIgnoreCase(scheme)) {
            targetPath = null;
            try (Cursor cursor = getContentResolver().query(
                    entryUri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    targetPath = cursor.getString(0);
                }
            } catch (Exception ignored) {}
        } else {
            if (scheme != null && !"file".equalsIgnoreCase(scheme)) {
                return null;
            }
            targetPath = entryUri.getPath();
        }
        if (targetPath == null || targetPath.length() == 0) {
            return null;
        }

        // Canonicalize both paths together so symlinks (e.g., /sdcard → /storage/emulated/0)
        // don't cause path-representation mismatches in the root check or relative computation.
        String basePath;
        String resolvedTargetPath;
        try {
            basePath = baseDir.getCanonicalPath();
            resolvedTargetPath = new File(targetPath).getCanonicalPath();
        } catch (Exception ignored) {
            basePath = baseDir.getAbsolutePath();
            resolvedTargetPath = targetPath;
        }

        File targetFile = new File(resolvedTargetPath);
        File parent = targetFile.getParentFile();
        if (parent != null && parent.getAbsolutePath().equals(basePath)) {
            return targetFile.getName();
        }

        return computeRelativePath(basePath, resolvedTargetPath);
    }

    private String resolveRelativeDocumentPath(Uri entryUri, Uri baseDocUri) {
        if (baseDocUri == null || storageBrowser.getCurrentTreeUri() == null) {
            return null;
        }

        try {
            String currentDocumentId = DocumentsContract.getDocumentId(baseDocUri);
            String entryDocumentId = DocumentsContract.getDocumentId(entryUri);

            int currentSeparator = currentDocumentId.indexOf(':');
            int entrySeparator = entryDocumentId.indexOf(':');
            if (currentSeparator < 0 || entrySeparator < 0) {
                return null;
            }

            String currentVolume = currentDocumentId.substring(0, currentSeparator);
            String entryVolume = entryDocumentId.substring(0, entrySeparator);
            if (!currentVolume.equals(entryVolume)) {
                return null;
            }

            String currentPath = currentDocumentId.substring(currentSeparator + 1);
            String entryPath = entryDocumentId.substring(entrySeparator + 1);

            String entryParentPath = parentPath(entryPath);
            if (entryParentPath != null && entryParentPath.equals(currentPath)) {
                return fileName(entryPath);
            }
            return computeRelativePath(currentPath, entryPath);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Immediate parent folder name for each request, derived from its full relative path.
     * Used as a disambiguation hint when the fast full-path lookup misses and we fall back
     * to a recursive tree scan.
     */
    private String parentPath(String path) {
        if (path == null) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return "";
        }
        return path.substring(0, slash);
    }

    private String fileName(String path) {
        if (path == null || path.length() == 0) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return path;
        }
        if (slash + 1 >= path.length()) {
            return null;
        }
        return path.substring(slash + 1);
    }

    private String computeRelativePath(String fromDirPath, String targetPath) {
        if (fromDirPath == null || targetPath == null) {
            return null;
        }

        String normalizedFrom = trimSlashes(fromDirPath.replace('\\', '/'));
        String normalizedTo = trimSlashes(targetPath.replace('\\', '/'));
        if (normalizedTo.length() == 0) {
            return null;
        }

        String[] fromParts = normalizedFrom.length() == 0 ? new String[0] : normalizedFrom.split("/");
        String[] toParts = normalizedTo.split("/");

        int common = 0;
        int max = Math.min(fromParts.length, toParts.length);
        while (common < max && fromParts[common].equals(toParts[common])) {
            common++;
        }

        StringBuilder relative = new StringBuilder();
        for (int i = common; i < fromParts.length; i++) {
            if (relative.length() > 0) {
                relative.append('/');
            }
            relative.append("..");
        }
        for (int i = common; i < toParts.length; i++) {
            if (relative.length() > 0) {
                relative.append('/');
            }
            relative.append(toParts[i]);
        }

        return relative.length() == 0 ? null : relative.toString();
    }

    private String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    /** Fallback: list all audio via MediaStore when direct file access fails. */
    private void loadFromMediaStore() {

        fileEntriesVersion++;
        fileEntries.clear();

        String[] projection = {
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DISPLAY_NAME
        };
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                MediaStore.Audio.Media.DISPLAY_NAME + " ASC")) {

            if (cursor == null) {
                applyFileFilter();
                return;
            }
            int dataCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
            int nameCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);

            while (cursor.moveToNext()) {
                String path = cursor.getString(dataCol);
                String name = cursor.getString(nameCol);
                if (path != null) {
                    fileEntries.add(new FileEntry(new File(path), name, false));
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.load_audio_failed, Toast.LENGTH_LONG).show();
        }
        sortFileEntriesInPlace();
        applyFileFilter();
    }

    private void sortFileEntriesInPlace() {
        if (fileSortMode == SORT_YEAR) {
            applyFilenameYearsInPlace();
        }
        Collections.sort(fileEntries, this::compareFileEntries);
        resolveTagMetadataForVisibleFolderAsync();
    }

    private boolean isTagSortMode(int mode) {
        return mode == SORT_YEAR || mode == SORT_GENRE || mode == SORT_BPM || mode == SORT_ARTIST;
    }

    private void applyFilenameYearsInPlace() {
        for (FileEntry entry : fileEntries) {
            if (entry.isDirectory() || isPlaylistFile(entry.name) || entry.sortDateState == TagState.RESOLVED) {
                continue;
            }
            String filenameYear = MetadataExtractor.extractYearFromFileName(entry.name);
            if (filenameYear.length() == 0) {
                continue;
            }
            entry.sortDate = filenameYear;
            entry.sortDateState = TagState.RESOLVED;
        }
    }

    private int compareFileEntries(FileEntry left, FileEntry right) {
        // sortRank already encodes directory-before-playlist-before-audio (computed once per entry),
        // so we avoid recomputing isDirectory()/isPlaylistFile() on every comparison.
        if (left.sortRank != right.sortRank) {
            return left.sortRank < right.sortRank ? -1 : 1;
        }
        if (left.isDirectory()) {
            return left.name.compareToIgnoreCase(right.name);
        }
        int c;
        if (fileSortMode == SORT_YEAR) {
            c = compareNullableStr(left.sortDate, right.sortDate, false);
            if (c != 0) return c;
        } else if (fileSortMode == SORT_GENRE) {
            c = compareNullableStr(left.sortGenre, right.sortGenre, true);
            if (c != 0) return c;
            c = compareNullableStr(left.sortDate, right.sortDate, false);
            if (c != 0) return c;
        } else if (fileSortMode == SORT_BPM) {
            c = comparePositiveInt(left.sortBpm, right.sortBpm);
            if (c != 0) return c;
            c = compareNullableStr(left.sortDate, right.sortDate, false);
            if (c != 0) return c;
        } else if (fileSortMode == SORT_ARTIST) {
            c = compareNullableStr(left.sortArtist, right.sortArtist, true);
            if (c != 0) return c;
            c = compareNullableStr(left.sortGenre, right.sortGenre, true);
            if (c != 0) return c;
            c = compareNullableStr(left.sortDate, right.sortDate, false);
            if (c != 0) return c;
        }
        return left.name.compareToIgnoreCase(right.name);
    }

    private static int compareNullableStr(String a, String b, boolean ignoreCase) {
        boolean aHas = a != null && a.length() > 0;
        boolean bHas = b != null && b.length() > 0;
        if (aHas != bHas) return aHas ? -1 : 1;
        if (!aHas) return 0;
        return ignoreCase ? a.compareToIgnoreCase(b) : a.compareTo(b);
    }

    private static int comparePositiveInt(int a, int b) {
        boolean aHas = a > 0;
        boolean bHas = b > 0;
        if (aHas != bHas) return aHas ? -1 : 1;
        return Integer.compare(a, b);
    }

    private void resolveTagMetadataForVisibleFolderAsync() {
        final int versionAtStart = fileEntriesVersion;
        final ArrayList<FileEntry> pendingEntries = new ArrayList<>(fileEntries.size());
        boolean cacheApplied = false;
        for (FileEntry entry : fileEntries) {
            if (entry.isDirectory() || isPlaylistFile(entry.name) ||
                    (entry.sortDateState == TagState.RESOLVED && entry.sortGenreState == TagState.RESOLVED
                            && entry.sortArtistState == TagState.RESOLVED && entry.sortBpmState == TagState.RESOLVED)
                    || entry.sortDateState == TagState.LOADING || entry.sortGenreState == TagState.LOADING
                    || entry.sortArtistState == TagState.LOADING || entry.sortBpmState == TagState.LOADING) {
                continue;
            }

            if (applyCachedSortTags(entry)) {
                cacheApplied = true;
                continue;
            }

            String filenameYear = MetadataExtractor.extractYearFromFileName(entry.name);
            if (filenameYear.length() > 0) {
                entry.sortDate = filenameYear;
                entry.sortDateState = TagState.RESOLVED;
                cacheApplied = true;
            }
            entry.sortDateState   = TagState.LOADING;
            entry.sortGenreState  = TagState.LOADING;
            entry.sortArtistState = TagState.LOADING;
            entry.sortBpmState    = TagState.LOADING;
            pendingEntries.add(entry);
        }

        if (cacheApplied) {
            if (currentBrowsePlaylistEntry == null) {
                Collections.sort(fileEntries, this::compareFileEntries);
            }
            applyFileFilter();
        }

        if (pendingEntries.isEmpty()) {
            return;
        }

        if (activeTagReadJobs == 0) {
            tagReadProgressTotal = 0;
            tagReadProgressDone.set(0);
        }
        activeTagReadJobs++;
        tagReadProgressTotal += pendingEntries.size();
        applySortButtonLoadingState();

        int threadCount = Math.min(4, pendingEntries.size());
        AtomicInteger pending = new AtomicInteger(pendingEntries.size());
        MetadataExtractor.TagEntry[] results = new MetadataExtractor.TagEntry[pendingEntries.size()];
        AtomicInteger workQueue = new AtomicInteger(0);
        for (int t = 0; t < threadCount; t++) {
            tagReadExecutor.submit(() -> {
                int idx;
                while ((idx = workQueue.getAndIncrement()) < pendingEntries.size()) {
                    results[idx] = metadataExtractor.readSortTags(pendingEntries.get(idx).uri);
                    int done = tagReadProgressDone.incrementAndGet();
                    if (done % 8 == 0 && progressRedrawPending.compareAndSet(false, true)) {
                        runOnUiThread(() -> {
                            progressRedrawPending.set(false);
                            applySortButtonLoadingState();
                        });
                    }
                    if (pending.decrementAndGet() == 0) {
                        runOnUiThread(() -> {
                            activeTagReadJobs = Math.max(0, activeTagReadJobs - 1);
                            if (activeTagReadJobs == 0) {
                                tagReadProgressTotal = 0;
                                tagReadProgressDone.set(0);
                            }
                            applySortButtonLoadingState();

                            if (versionAtStart != fileEntriesVersion) {
                                for (FileEntry e : pendingEntries) {
                                    e.sortDateState   = TagState.UNKNOWN;
                                    e.sortGenreState  = TagState.UNKNOWN;
                                    e.sortArtistState = TagState.UNKNOWN;
                                    e.sortBpmState    = TagState.UNKNOWN;
                                }
                                return;
                            }

                            boolean changed = false;
                            for (int j = 0; j < pendingEntries.size(); j++) {
                                MetadataExtractor.TagEntry tag = results[j];
                                FileEntry e = pendingEntries.get(j);
                                e.sortDate   = tag.date;
                                e.sortGenre  = tag.genre;
                                e.sortArtist = tag.artist;
                                e.sortTitle  = tag.title;
                                e.sortBpm    = tag.bpm;
                                e.sortDateState   = TagState.RESOLVED;
                                e.sortGenreState  = TagState.RESOLVED;
                                e.sortArtistState = TagState.RESOLVED;
                                e.sortBpmState    = TagState.RESOLVED;
                                changed = true;
                            }

                            if (changed) {
                                if (isTagSortMode(fileSortMode) && currentBrowsePlaylistEntry == null) {
                                    Collections.sort(fileEntries, FileBrowserQueueActivity.this::compareFileEntries);
                                }
                                applyFileFilter();
                            }
                        });
                    }
                }
            });
        }
    }

    private void applySortButtonLoadingState() {
        if (sortButton == null) {
            return;
        }
        if (activeTagReadJobs > 0) {
            float progress = 0f;
            if (tagReadProgressTotal > 0) {
                progress = Math.min(1f, tagReadProgressDone.get() / (float) tagReadProgressTotal);
            }
            if (progressClipDrawable == null) {
                GradientDrawable base = new GradientDrawable();
                base.setColor(getColor(R.color.buttonBackground));
                GradientDrawable fill = new GradientDrawable();
                fill.setColor(getColor(R.color.stopButtonActive));
                progressClipDrawable = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
                sortButton.setBackground(new LayerDrawable(new Drawable[]{base, progressClipDrawable}));
            }
            progressClipDrawable.setLevel((int) (Math.max(0f, Math.min(1f, progress)) * PROGRESS_LEVEL_MAX));
            sortButton.setTextColor(getColor(R.color.stopButtonActiveText));
        } else {
            progressClipDrawable = null;
            sortButton.setBackgroundColor(getColor(R.color.buttonBackground));
            sortButton.setTextColor(getColor(R.color.foreground));
        }
    }

    private void applyProgressBackground(View target, float progress, int baseColor, int fillColor) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(baseColor);

        GradientDrawable progressFill = new GradientDrawable();
        progressFill.setColor(fillColor);
        ClipDrawable clippedProgress = new ClipDrawable(progressFill, Gravity.START, ClipDrawable.HORIZONTAL);
        clippedProgress.setLevel((int) (Math.max(0f, Math.min(1f, progress)) * PROGRESS_LEVEL_MAX));

        LayerDrawable layer = new LayerDrawable(new Drawable[]{base, clippedProgress});
        target.setBackground(layer);
    }

    private void showLyricsOverlayForQueueEntry(QueueEntry entry) {
        if (entry == null || entry.uri == null) {
            return;
        }
        if (metadataExtractor.isLyricsCached(entry.uri)) {
            showLyricsOverlay(entry.name, metadataExtractor.readLyricsTag(entry.uri));
            return;
        }

        new Thread(() -> {
            String lyrics = metadataExtractor.readLyricsTag(entry.uri);
            runOnUiThread(() -> showLyricsOverlay(entry.name, lyrics));
        }, "lyrics-loader").start();
    }

    private void showLyricsOverlay(String trackName, String lyrics) {
        if (lyrics == null || lyrics.trim().length() == 0) {
            Toast.makeText(this, R.string.lyrics_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        ScrollView scrollView = new ScrollView(this);
        int pad = (int) dp(16f);
        scrollView.setPadding(pad, pad, pad, pad);

        TextView lyricsView = new TextView(this);
        lyricsView.setText(lyrics);
        lyricsView.setTextIsSelectable(true);
        lyricsView.setTextSize(16f);
        scrollView.addView(lyricsView);

        TextView titleView = new TextView(this);
        titleView.setText(getString(R.string.lyrics_dialog_title, trackName));
        titleView.setTextSize(18f);
        titleView.setPadding(pad, pad, pad, pad / 2);
        titleView.setTextColor(getColor(R.color.foreground));
        titleView.setBackgroundColor(getColor(R.color.buttonBackground));

        new AlertDialog.Builder(this)
                .setCustomTitle(titleView)
                .setView(scrollView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void applyFileFilter() {
        filteredFileEntries.clear();

        // No toLowerCase(): entryMatchesQuery matches case-insensitively without allocating.
        String query = fileFilterQuery == null ? "" : fileFilterQuery.trim();
        if (query.length() == 0) {
            filteredFileEntries.addAll(fileEntries);
        } else {
            for (FileEntry entry : fileEntries) {
                if (entryMatchesQuery(entry, query)) {
                    filteredFileEntries.add(entry);
                }
            }
        }

        if (fileAdapter != null) {
            fileAdapter.notifyDataSetChanged();
        }
        updateStorageButtonState();
    }

    private static boolean entryMatchesQuery(FileEntry entry, String query) {
        return containsIgnoreCase(entry.name, query)
                || containsIgnoreCase(entry.sortArtist, query)
                || containsIgnoreCase(entry.sortTitle,  query)
                || containsIgnoreCase(entry.sortGenre,  query);
    }

    /** Case-insensitive {@code value.contains(query)} without allocating a lower-cased copy. */
    private static boolean containsIgnoreCase(String value, String query) {
        if (value == null) return false;
        int queryLen = query.length();
        if (queryLen == 0) return true;
        int max = value.length() - queryLen;
        for (int i = 0; i <= max; i++) {
            // regionMatches(ignoreCase=true, ...) folds case per char, allocating nothing.
            if (value.regionMatches(true, i, query, 0, queryLen)) {
                return true;
            }
        }
        return false;
    }

    private static void scrollTo(ListView list, int position) {
        int first = list.getFirstVisiblePosition();
        int last  = list.getLastVisiblePosition();
        if (position >= first && position <= last) return;
        if (Math.abs(position - first) > 8 && Math.abs(position - last) > 8)
            list.setSelection(position);
        else
            list.smoothScrollToPosition(position);
    }

    private boolean scrollToHighlightedFileEntry() {
        if (fileBrowserList == null) return false;
        if (pendingBackScrollUri != null) {
            Uri target = pendingBackScrollUri;
            pendingBackScrollUri = null;
            for (int i = 0; i < filteredFileEntries.size(); i++) {
                if (target.equals(filteredFileEntries.get(i).uri)) {
                    final int idx = i;
                    fileBrowserList.post(() -> fileBrowserList.setSelection(idx));
                    return true;
                }
            }
        }
        Uri highlightedUri = null;
        if (Service.sBrowseMode && browseFileUri != null) {
            highlightedUri = browseFileUri;
        } else if (fileBrowserPreviewingEntryUri != null) {
            highlightedUri = fileBrowserPreviewingEntryUri;
        }
        if (highlightedUri == null) return false;
        for (int i = 0; i < filteredFileEntries.size(); i++) {
            if (highlightedUri.equals(filteredFileEntries.get(i).uri)) {
                scrollTo(fileBrowserList, i);
                return true;
            }
        }
        return false;
    }

    static boolean isAudioFile(String name) {
        String lower = name.toLowerCase();
        for (String ext : AUDIO_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    static boolean isPlaylistFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".m3u") || lower.endsWith(".m3u8");
    }

    private List<String> readPlaylistLines(FileEntry playlistEntry) {
        List<String> lines = new ArrayList<>();
        try (InputStream stream = getContentResolver().openInputStream(playlistEntry.uri)) {
            if (stream == null) return lines;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && trimmed.charAt(0) == '\uFEFF') trimmed = trimmed.substring(1);
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    lines.add(trimmed);
                }
            }
        } catch (Exception ignored) {}
        return lines;
    }

    // Reads and resolves the playlist off the main thread (file I/O + per-line URI resolution can
    // be slow on SAF/content providers), then applies the result and shows toasts on the UI thread.
    private void addPlaylistToQueue(FileEntry playlistEntry) {
        tagReadExecutor.submit(() -> {
            List<String> lines = readPlaylistLines(playlistEntry);

            // Resolve each entry using same-directory logic (exact + different extension).
            ArrayList<QueueEntry> resolvedEntries = new ArrayList<>(lines.size());
            ArrayList<String> missingNamesToToast = new ArrayList<>();
            int missingCount = 0;
            for (String line : lines) {
                Uri uri = resolvePlaylistTargetUri(playlistEntry, line);
                if (uri == null) {
                    if (missingNamesToToast.size() < 2) {
                        missingNamesToToast.add(getDisplayNameForPlaylistItem(line, null));
                    }
                    missingCount++;
                } else {
                    resolvedEntries.add(new QueueEntry(getDisplayNameForPlaylistItem(line, uri), uri));
                }
            }

            final int totalMissing = missingCount;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                // Toast for first 2 still-missing files, then a summary.
                for (String name : missingNamesToToast) {
                    Toast.makeText(this, getString(R.string.requested_file_not_found, name), Toast.LENGTH_LONG).show();
                }
                if (totalMissing > 2) {
                    Toast.makeText(this, getString(R.string.requested_files_not_found, totalMissing), Toast.LENGTH_LONG).show();
                }

                addToQueue(resolvedEntries);
                if (resolvedEntries.isEmpty()) {
                    Toast.makeText(this, getString(R.string.no_playable_files_in_playlist, playlistEntry.name), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.added_files_from_playlist, resolvedEntries.size(), playlistEntry.name), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /**
     * True if a playlist line parses to an absolute URI we can actually open. Opaque URIs — a
     * scheme with no {@code //} authority, e.g. a Windows path "C:\..." that {@link Uri#parse}
     * reads as scheme "c" — have a null path, can never be played, and would crash File-based
     * title derivation when the queue is replayed on launch. Reject them so the caller falls back
     * to resolving the line as a relative/absolute file path instead.
     */
    private static boolean isUsableAbsoluteUri(Uri parsed) {
        return parsed.getScheme() != null && !parsed.isOpaque();
    }

    private Uri resolvePlaylistTargetUri(FileEntry playlistEntry, String pathValue) {
        Uri parsed = Uri.parse(pathValue);
        if (isUsableAbsoluteUri(parsed)) {
            return parsed;
        }

        if (playlistEntry.file != null) {
            File playlistDir = playlistEntry.file.getParentFile();
            File target = pathValue.startsWith("/")
                    ? new File(pathValue)
                    : new File(playlistDir, pathValue);
            if (target.exists() && target.isFile()) {
                return Uri.fromFile(target);
            }
            File fallback = StorageBrowser.findFileWithDifferentExtension(target);
            return fallback != null ? Uri.fromFile(fallback) : null;
        }

        return resolveDocumentPlaylistTargetUri(playlistEntry.uri, pathValue);
    }

    private Uri resolveDocumentPlaylistTargetUri(Uri playlistUri, String pathValue) {
        if (storageBrowser.getCurrentTreeUri() == null || playlistUri == null) {
            return null;
        }

        try {
            String playlistDocumentId = DocumentsContract.getDocumentId(playlistUri);
            int separator = playlistDocumentId.indexOf(':');
            if (separator < 0) {
                return null;
            }

            String volume = playlistDocumentId.substring(0, separator);
            String playlistPath = playlistDocumentId.substring(separator + 1).replace('\\', '/');
            int lastSlash = playlistPath.lastIndexOf('/');
            String baseDir = lastSlash >= 0 ? playlistPath.substring(0, lastSlash) : "";

            String normalizedInput = pathValue.replace('\\', '/');
            String combinedPath;
            if (normalizedInput.startsWith("/")) {
                combinedPath = normalizedInput.substring(1);
            } else {
                combinedPath = baseDir.isEmpty() ? normalizedInput : baseDir + "/" + normalizedInput;
            }
            String normalizedPath = normalizeRelativePath(combinedPath);
            if (normalizedPath == null || normalizedPath.length() == 0) {
                return null;
            }

            String targetDocumentId = volume + ":" + normalizedPath;
            Uri targetUri = DocumentsContract.buildDocumentUriUsingTree(storageBrowser.getCurrentTreeUri(), targetDocumentId);
            if (storageBrowser.documentExists(targetUri)) {
                return targetUri;
            }
            return storageBrowser.findDocumentWithDifferentExtension(volume, normalizedPath);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeRelativePath(String path) {
        String[] parts = path.split("/");
        ArrayList<String> stack = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.length() == 0 || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            } else {
                stack.add(part);
            }
        }
        if (stack.isEmpty()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(stack.get(i));
        }
        return builder.toString();
    }

    /** Existence test for a document id (against the tag cache or a fetched sibling listing). */
    private interface DocIdExists { boolean test(String docId); }

    /**
     * Resolves {@code docId} to an existing document id: the id itself if present, otherwise the same
     * base name with a different audio extension; null if nothing exists. Shared by the playlist
     * tag-cache pass and the SAF sibling-listing pass.
     */
    private static String resolveExistingDocId(String docId, DocIdExists exists) {
        if (exists.test(docId)) return docId;
        int dot = docId.lastIndexOf('.');
        String base = dot >= 0 ? docId.substring(0, dot) : docId;
        String originalExt = dot >= 0 ? docId.substring(dot) : "";
        for (String ext : StorageBrowser.AUDIO_EXTENSIONS_NO_PLAYLIST) {
            if (ext.equals(originalExt)) continue;
            if (exists.test(base + ext)) return base + ext;
        }
        return null;
    }

    private List<Uri> resolveDocumentPlaylistUrisBatch(Uri playlistUri, List<String> pathValues, Uri treeUri) {
        int n = pathValues.size();
        List<Uri> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) result.add(null);

        String volume;
        String baseDir;
        try {
            String playlistDocumentId = DocumentsContract.getDocumentId(playlistUri);
            int separator = playlistDocumentId.indexOf(':');
            if (separator < 0) return result;
            volume = playlistDocumentId.substring(0, separator);
            String playlistPath = playlistDocumentId.substring(separator + 1).replace('\\', '/');
            int lastSlash = playlistPath.lastIndexOf('/');
            baseDir = lastSlash >= 0 ? playlistPath.substring(0, lastSlash) : "";
        } catch (Exception e) {
            return result;
        }

        // Pass 1: compute target document IDs — pure string ops, no I/O
        String[] targetDocIds = new String[n];
        for (int i = 0; i < n; i++) {
            String pathValue = pathValues.get(i);
            Uri parsed = Uri.parse(pathValue);
            if (isUsableAbsoluteUri(parsed)) {
                result.set(i, parsed);
                continue;
            }
            String normalizedInput = pathValue.replace('\\', '/');
            String combinedPath = normalizedInput.startsWith("/")
                    ? normalizedInput.substring(1)
                    : baseDir.isEmpty() ? normalizedInput : baseDir + "/" + normalizedInput;
            String normalizedPath = normalizeRelativePath(combinedPath);
            if (normalizedPath != null && !normalizedPath.isEmpty())
                targetDocIds[i] = volume + ":" + normalizedPath;
        }

        // Cache pass: when the library has been enumerated, the target's existence can be checked
        // against the in-memory tag cache (whose keys are every enumerated file's URI), avoiding a
        // SAF query per directory. Entries not found here fall through to the batch query below.
        for (int i = 0; i < n; i++) {
            if (result.get(i) != null || targetDocIds[i] == null) continue;
            String hit = resolveExistingDocId(targetDocIds[i],
                    d -> metadataExtractor.containsUri(DocumentsContract.buildDocumentUriUsingTree(treeUri, d)));
            if (hit != null) result.set(i, DocumentsContract.buildDocumentUriUsingTree(treeUri, hit));
        }

        // Collect unique parent directories for entries that still need resolution
        Set<String> parentDocIds = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (result.get(i) != null || targetDocIds[i] == null) continue;
            String docId = targetDocIds[i];
            int colon = docId.indexOf(':');
            String path = colon >= 0 ? docId.substring(colon + 1) : docId;
            int slash = path.lastIndexOf('/');
            String parentPath = slash >= 0 ? path.substring(0, slash) : "";
            parentDocIds.add(volume + ":" + parentPath);
        }

        // Batch query: one ContentResolver query per distinct parent directory
        Map<String, Set<String>> dirContents = new HashMap<>();
        for (String parentDocId : parentDocIds) {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
            Set<String> children = new HashSet<>();
            try (Cursor cursor = getContentResolver().query(childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                if (cursor != null) {
                    int col = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                    if (col >= 0) {
                        while (cursor.moveToNext())
                            children.add(cursor.getString(col));
                    }
                }
            } catch (Exception ignored) {}
            dirContents.put(parentDocId, children);
        }

        // Pass 2: match each remaining entry against its fetched sibling listing.
        for (int i = 0; i < n; i++) {
            if (result.get(i) != null || targetDocIds[i] == null) continue;
            String docId = targetDocIds[i];
            int colon = docId.indexOf(':');
            String path = colon >= 0 ? docId.substring(colon + 1) : docId;
            int slash = path.lastIndexOf('/');
            String parentPath = slash >= 0 ? path.substring(0, slash) : "";
            Set<String> siblings = dirContents.getOrDefault(volume + ":" + parentPath, Collections.emptySet());
            String hit = resolveExistingDocId(docId, siblings::contains);
            if (hit != null) result.set(i, DocumentsContract.buildDocumentUriUsingTree(treeUri, hit));
        }

        return result;
    }

    private String getDisplayNameForPlaylistItem(String playlistValue, Uri resolvedUri) {
        String normalized = playlistValue.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            return normalized.substring(slash + 1);
        }
        return playlistValue;
    }

    // -- queue helpers -------------------------------------------------------

    private void addToQueue(String name, Uri uri) {
        ArrayList<QueueEntry> entries = new ArrayList<>(1);
        entries.add(new QueueEntry(name, uri));
        addToQueue(entries);
    }

    /**
     * Runs {@code work} for each index in [0, count) across up to 4 threads on the tag-read
     * executor, with workers pulling indices from a shared counter. When the final index
     * completes, {@code onAllDone} (if non-null) runs on whichever worker finished it.
     */
    private void runParallelTagReads(int count, IndexedTask work, Runnable onAllDone) {
        if (count <= 0) {
            if (onAllDone != null) onAllDone.run();
            return;
        }
        int threadCount = Math.min(4, count);
        AtomicInteger pending = new AtomicInteger(count);
        AtomicInteger workQueue = new AtomicInteger(0);
        for (int t = 0; t < threadCount; t++) {
            tagReadExecutor.submit(() -> {
                int idx;
                while ((idx = workQueue.getAndIncrement()) < count) {
                    work.run(idx);
                    if (pending.decrementAndGet() == 0 && onAllDone != null) {
                        onAllDone.run();
                    }
                }
            });
        }
    }

    private void ensureQueueTagsCachedAsync() {
        int uncachedCount = 0;
        for (QueueEntry entry : queueEntries) {
            if (!entry.tagsCached && entry.uri != null)
                uncachedCount++;
        }
        if (uncachedCount == 0) return;
        List<QueueEntry> uncached = new ArrayList<>(uncachedCount);
        for (QueueEntry entry : queueEntries) {
            if (!entry.tagsCached && entry.uri != null)
                uncached.add(entry);
        }
        MetadataExtractor.TagEntry[] results = new MetadataExtractor.TagEntry[uncached.size()];
        runParallelTagReads(uncached.size(),
                idx -> results[idx] = metadataExtractor.readSortTags(uncached.get(idx).uri),
                () -> runOnUiThread(() -> {
                    for (int j = 0; j < uncached.size(); j++) {
                        QueueEntry e = uncached.get(j);
                        MetadataExtractor.TagEntry tag = results[j];
                        e.title  = tag.title;
                        e.artist = tag.artist;
                        e.date   = tag.date;
                        e.genre  = tag.genre;
                        e.bpm    = tag.bpm;
                        e.tagsCached = true;
                    }
                    queueAdapter.notifyDataSetChanged();
                }));
    }

    private void addToQueue(List<QueueEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        int anchorIdx = anchorIndex();
        int insertAt;
        if (anchorIdx >= 0) {
            queueEntries.addAll(anchorIdx, entries);
            insertAt = anchorIdx;
            // Inserting at/before the playing row pushes it (and the service window) down.
            if (currentPlayingQueueIndex >= 0 && insertAt <= currentPlayingQueueIndex) {
                currentPlayingQueueIndex += entries.size();
                setPlaybackOffset(servicePlaybackOffset + entries.size());
            }
        } else {
            insertAt = queueEntries.size();
            queueEntries.addAll(entries);
        }
        queueAdapter.notifyDataSetChanged();
        // Scroll so the last newly-added track (and the anchor line just below it) is visible.
        scrollTo(queueList, insertAt + entries.size() - 1);
        updateQueueHint();
        persistQueue();
        ensureQueueTagsCachedAsync();

        // Keep the running service queue aligned with the visible queue.
        if (Service.sIsPlaying && !isStopFadeInProgress()) {
            syncServicePendingQueue();
        }
    }

    private boolean removeQueueAt(int position) {
        if (position < 0 || position >= queueEntries.size()) return false;
        if (position == currentPlayingQueueIndex && isPlaybackActiveOrFading()) {
            return false;
        }
        if (anchorEntryId > 0 && queueEntries.get(position).id == anchorEntryId) {
            anchorEntryId = 0;
            persistAnchor();
        }
        queueEntries.remove(position);
        boolean playbackActive = isPlaybackActiveOrFading();
        if (playbackActive && currentPlayingQueueIndex >= 0 && position <= currentPlayingQueueIndex) {
            if (currentPlayingQueueIndex == position) {
                currentPlayingQueueIndex = -1;
            } else {
                currentPlayingQueueIndex--;
            }
            if (servicePlaybackOffset > 0) {
                setPlaybackOffset(servicePlaybackOffset - 1);
            }
        } else if (currentPlayingQueueIndex == position) {
            currentPlayingQueueIndex = -1;
        } else if (currentPlayingQueueIndex > position) {
            currentPlayingQueueIndex--;
        }
        queueAdapter.notifyDataSetChanged();
        updateQueueHint();
        persistQueue();
        if (Service.sIsPlaying && !isStopFadeInProgress()) {
            syncServicePendingQueue();
        }
        return true;
    }

    // -- insert anchor -------------------------------------------------------

    /** Index of the insert anchor in queueEntries, or -1 if none / not present. */
    private int anchorIndex() {
        if (anchorEntryId <= 0) return -1;
        for (int i = 0; i < queueEntries.size(); i++) {
            if (queueEntries.get(i).id == anchorEntryId) return i;
        }
        return -1;
    }

    private void persistAnchor() {
        QueueStore.saveAnchor(this, anchorEntryId);
    }

    /** Sets ({@code entryId > 0}) or clears ({@code 0}) the insert anchor and repaints the queue. */
    private void setAnchor(int entryId) {
        anchorEntryId = entryId;
        persistAnchor();
        queueAdapter.notifyDataSetChanged();
    }

    /** Makes the swiped track the insert anchor, or clears it if it already is. */
    private void toggleAnchor(int position) {
        if (position < 0 || position >= queueEntries.size()) return;
        QueueEntry entry = queueEntries.get(position);
        boolean isAnchor = anchorEntryId > 0 && entry.id == anchorEntryId;
        setAnchor(isAnchor ? 0 : entry.id);
    }

    /**
     * Clears the insert anchor once playback has reached (or moved past) the anchored track —
     * whether started by a tap or by auto-advance. Inserting above an already-playing track makes
     * no sense, so the anchor is dropped. Called from every place that updates the playing index
     * (tap, playback broadcast, and the 1s poll) so auto-advance is covered even if a discrete
     * broadcast is missed or resolves a step late. Only repaints the row that lost the marker.
     */
    private void clearAnchorIfPlaybackReached() {
        if (anchorEntryId <= 0 || currentPlayingQueueIndex < 0) return;
        int ai = anchorIndex();
        if (ai < 0 || currentPlayingQueueIndex < ai) return;
        anchorEntryId = 0;
        persistAnchor();
        rebindQueueRow(ai);
    }

    private void moveQueueItem(int from, int to) {
        if (from == to || from < 0 || to < 0
                || from >= queueEntries.size() || to >= queueEntries.size()) return;
        queueEntries.add(to, queueEntries.remove(from));
        if (currentPlayingQueueIndex == from) {
            currentPlayingQueueIndex = to;
        } else if (from < to && currentPlayingQueueIndex > from && currentPlayingQueueIndex <= to) {
            currentPlayingQueueIndex--;
        } else if (from > to && currentPlayingQueueIndex >= to && currentPlayingQueueIndex < from) {
            currentPlayingQueueIndex++;
        }
        queueAdapter.notifyDataSetChanged();
    }

    private void syncServicePendingQueue() {
        if (currentPlayingQueueIndex < 0) {
            return;
        }

        // Replace the service's pending tracks (everything after the current one) in a single
        // atomic command. Splitting this into a CLEAR then a separate APPEND lets an auto-advance
        // track-completion callback slip in between the two and stop playback (it would see an
        // empty pending playlist); SET_PENDING_QUEUE clears and re-appends in one onStart pass.
        int nextIndex = currentPlayingQueueIndex + 1;
        ArrayList<Uri> pendingUris = new ArrayList<>(Math.max(0, queueEntries.size() - nextIndex));
        for (int i = nextIndex; i < queueEntries.size(); i++) {
            pendingUris.add(queueEntries.get(i).uri);
        }
        Intent intent = new Intent(this, Service.class);
        intent.putExtra(Launcher.TYPE, Launcher.SET_PENDING_QUEUE);
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, pendingUris);
        startService(intent);
    }

    private void restorePersistedQueue() {
        queueEntries.clear();
        ArrayList<QueueStore.Entry> persisted = QueueStore.load(this);
        for (QueueStore.Entry entry : persisted) {
            queueEntries.add(new QueueEntry(entry.name, entry.uri, entry.id));
        }
        anchorEntryId = QueueStore.loadAnchor(this);
        if (anchorIndex() < 0) anchorEntryId = 0;   // drop a stale anchor whose track is gone
        queueAdapter.notifyDataSetChanged();
        updateQueueHint();
        ensureQueueTagsCachedAsync();
    }

    private void persistQueue() {
        ArrayList<QueueStore.Entry> persisted = new ArrayList<>(queueEntries.size());
        for (QueueEntry entry : queueEntries) {
            persisted.add(new QueueStore.Entry(entry.name, entry.uri, entry.id));
        }
        QueueStore.save(this, persisted);
    }

    /** Converts density-independent pixels to px using the current display density. */
    private float dp(float dips) {
        return dips * getResources().getDisplayMetrics().density;
    }

    /** Initialises a swipe gesture on ACTION_DOWN: records the touch origin, resets the
     *  swipe state, and resolves the touched row + its swipe-content view (left null when
     *  the row is absent or {@code canSwipe} rejects it). */
    private void beginSwipeGesture(ListView list, SwipeState s, MotionEvent e, SwipePredicate canSwipe) {
        s.downX = e.getX();
        s.downY = e.getY();
        s.startPosition = list.pointToPosition((int) e.getX(), (int) e.getY());
        s.handled = false;
        s.swiping = false;
        s.swipingView = null;
        s.contentView = null;
        suppressItemClick = false;
        if (s.startPosition < 0) return;
        int childIndex = s.startPosition - list.getFirstVisiblePosition();
        if (childIndex >= 0 && childIndex < list.getChildCount()
                && (canSwipe == null || canSwipe.test(s.startPosition))) {
            s.swipingView = list.getChildAt(childIndex);
            s.contentView = s.swipingView.findViewById(R.id.swipe_content);
            if (s.contentView == null) s.contentView = s.swipingView;
        }
    }

    /** Translates the swiped row for one direction and fires {@code action} once it is
     *  dragged past half its width. Returns true if the touch was consumed as a swipe. */
    private boolean applySwipeMove(ListView list, SwipeState s, float dx, float slop,
                                   boolean toLeft, SwipeAction action) {
        if (s.swipingView == null || action == null) return false;
        if (toLeft ? dx >= -slop : dx <= slop) return false;
        s.swiping = true;
        float eff = toLeft ? dx + slop : dx - slop;
        int w = s.contentView.getWidth();
        s.contentView.setTranslationX(toLeft ? Math.max(eff, -w) : Math.min(eff, w));
        list.getParent().requestDisallowInterceptTouchEvent(true);
        if (w > 0 && Math.abs(eff) >= w / 2f) {
            s.handled = true;
            int pos = s.startPosition;
            s.resetView();
            action.onSwipe(pos);
        }
        return true;
    }

    /**
     * Re-binds a single visible queue row in place via the adapter's getView, leaving every other
     * row — and any in-progress swipe translation on it — untouched. No-op if the row is off-screen
     * or is the one currently being swiped (so a swipe is never clobbered).
     */
    private void rebindQueueRow(int position) {
        if (queueAdapter == null || queueList == null || position < 0) return;
        if (queueSwipeState.swiping && position == queueSwipeState.startPosition) return;
        int child = position - queueList.getFirstVisiblePosition();
        if (child < 0 || child >= queueList.getChildCount()) return;
        queueAdapter.getView(position, queueList.getChildAt(child), queueList);
    }

    /**
     * Refreshes only the rows a playback-state update can change: the row that just lost the
     * "now playing" highlight ({@code prevIndex}) and the current playing row (progress fill +
     * remaining time). Avoids a whole-list notifyDataSetChanged so an in-progress swipe on any
     * other row keeps its offset.
     */
    private void refreshQueuePlaybackRows(int prevIndex) {
        if (prevIndex != currentPlayingQueueIndex) rebindQueueRow(prevIndex);
        rebindQueueRow(currentPlayingQueueIndex);
    }

    private void installQueueGestureHandler(ListView list) {
        SwipeState swipeState = queueSwipeState;
        DragState dragState = new DragState();
        Runnable[] longPressRunnable = {null};

        list.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    dragState.reset();
                    if (longPressRunnable[0] != null) {
                        uiHandler.removeCallbacks(longPressRunnable[0]);
                        longPressRunnable[0] = null;
                    }
                    beginSwipeGesture(list, swipeState, event,
                            pos -> pos != currentPlayingQueueIndex || localQueueShownInRemoteMode);
                    if (swipeState.startPosition >= 0) {
                        if (swipeState.swipingView != null) {
                            TextView qHintStart = swipeState.swipingView.findViewById(R.id.swipe_hint_start);
                            if (qHintStart != null) {
                                if (localQueueShownInRemoteMode) qHintStart.setText(R.string.swipe_hint_send);
                                else qHintStart.setText(R.string.swipe_hint_anchor);
                            }
                            TextView qHintEnd = swipeState.swipingView.findViewById(R.id.swipe_hint_end);
                            if (qHintEnd != null) qHintEnd.setText(R.string.swipe_hint_remove);
                            int[] itemScreenPos = new int[2];
                            swipeState.swipingView.getLocationOnScreen(itemScreenPos);
                            dragState.touchOffsetX = event.getRawX() - itemScreenPos[0];
                            dragState.touchOffsetY = event.getRawY() - itemScreenPos[1];
                        }
                        if (swipeState.startPosition != currentPlayingQueueIndex) {
                            int pos = swipeState.startPosition;
                            longPressRunnable[0] = () -> {
                                if (!swipeState.handled && !dragState.active
                                        && swipeState.swipingView != null) {
                                    View src = swipeState.swipingView;
                                    Bitmap bmp = Bitmap.createBitmap(
                                            src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
                                    src.draw(new Canvas(bmp));
                                    ImageView ghost = new ImageView(FileBrowserQueueActivity.this);
                                    ghost.setImageBitmap(bmp);
                                    ghost.setAlpha(0.85f);
                                    ghost.setElevation(dp(8f));
                                    ViewGroup decor = (ViewGroup) getWindow().getDecorView();
                                    int[] decorPos = new int[2];
                                    decor.getLocationOnScreen(decorPos);
                                    int[] itemPos = new int[2];
                                    src.getLocationOnScreen(itemPos);
                                    decor.addView(ghost, new FrameLayout.LayoutParams(
                                            src.getWidth(), src.getHeight()));
                                    ghost.setX(itemPos[0] - decorPos[0]);
                                    ghost.setY(itemPos[1] - decorPos[1]);
                                    src.setAlpha(0f);
                                    dragState.ghostView = ghost;
                                    dragState.currentPosition = pos;
                                    dragState.active = true;
                                    draggingQueueIndex = pos;
                                    longPressRunnable[0] = null;
                                    list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                    list.getParent().requestDisallowInterceptTouchEvent(true);
                                }
                            };
                            uiHandler.postDelayed(longPressRunnable[0], 400L);
                        }
                    }
                    return false;
                }

                case MotionEvent.ACTION_MOVE: {
                    if (dragState.active) {
                        if (dragState.ghostView != null) {
                            ViewGroup decor = (ViewGroup) getWindow().getDecorView();
                            int[] decorPos = new int[2];
                            decor.getLocationOnScreen(decorPos);
                            dragState.ghostView.setX(event.getRawX() - dragState.touchOffsetX - decorPos[0]);
                            dragState.ghostView.setY(event.getRawY() - dragState.touchOffsetY - decorPos[1]);
                        }
                        int targetPos = list.pointToPosition((int) event.getX(), (int) event.getY());
                        if (targetPos >= 0 && targetPos < queueEntries.size()
                                && targetPos != dragState.currentPosition
                                && (targetPos != currentPlayingQueueIndex
                                        || currentPlayingQueueIndex == queueEntries.size() - 1
                                        || currentPlayingQueueIndex == 0)) {
                            moveQueueItem(dragState.currentPosition, targetPos);
                            dragState.currentPosition = targetPos;
                            draggingQueueIndex = targetPos;
                        }
                        return true;
                    }

                    if (swipeState.handled || swipeState.startPosition < 0) return swipeState.handled;
                    float dx = event.getX() - swipeState.downX;
                    float dy = event.getY() - swipeState.downY;

                    if (Math.abs(dx) > swipeHorizontalSlop || Math.abs(dy) > swipeVerticalSlop) {
                        if (longPressRunnable[0] != null) {
                            uiHandler.removeCallbacks(longPressRunnable[0]);
                            longPressRunnable[0] = null;
                        }
                    }
                    if (Math.abs(dy) > swipeVerticalSlop && Math.abs(dy) > Math.abs(dx)) {
                        swipeState.resetView();
                        swipeState.startPosition = -1;
                        return false;
                    }
                    // Left swipe removes; mirror the change to a connected client (no-op off-host).
                    SwipeAction removeAction = pos -> {
                        if (removeQueueAt(pos)) notifyRemoteQueueChanged();
                    };
                    if (applySwipeMove(list, swipeState, dx, swipeHorizontalSlop, true, removeAction)) {
                        return true;
                    }
                    // Right swipe: send to remote in remote mode, otherwise set/clear the insert anchor.
                    SwipeAction rightAction = localQueueShownInRemoteMode
                            ? this::sendQueueEntryToRemote
                            : pos -> { toggleAnchor(pos); notifyRemoteQueueChanged(); };
                    if (applySwipeMove(list, swipeState, dx, swipeHorizontalSlop, false, rightAction)) {
                        return true;
                    }
                    return false;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (longPressRunnable[0] != null) {
                        uiHandler.removeCallbacks(longPressRunnable[0]);
                        longPressRunnable[0] = null;
                    }
                    if (dragState.active) {
                        if (dragState.ghostView != null) {
                            ((ViewGroup) getWindow().getDecorView()).removeView(dragState.ghostView);
                        }
                        dragState.reset();
                        draggingQueueIndex = -1;
                        queueAdapter.notifyDataSetChanged();
                        persistQueue();
                        if (Service.sIsPlaying && !isStopFadeInProgress()) {
                            syncServicePendingQueue();
                        }
                        notifyRemoteQueueChanged();
                        return true;
                    }
                    if (swipeState.swiping) suppressItemClick = true;
                    if (!swipeState.handled) v.performClick();
                    swipeState.resetView();
                    boolean handled = swipeState.handled;
                    swipeState.startPosition = -1;
                    swipeState.handled = false;
                    return handled;
                }

                default:
                    return false;
            }
        });
    }

    private void installSwipeListener(ListView list, SwipePredicate canSwipe,
                                      String rightHint, String leftHint,
                                      SwipeAction onRightSwipe, SwipeAction onLeftSwipe) {
        SwipeState state = new SwipeState();
        list.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    beginSwipeGesture(list, state, event, canSwipe);
                    if (state.swipingView != null) {
                        if (rightHint != null) {
                            TextView tv = state.swipingView.findViewById(R.id.swipe_hint_start);
                            if (tv != null) tv.setText(rightHint);
                        }
                        if (leftHint != null) {
                            TextView tv = state.swipingView.findViewById(R.id.swipe_hint_end);
                            if (tv != null) tv.setText(leftHint);
                        }
                    }
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (state.handled || state.startPosition < 0) return state.handled;
                    float dx = event.getX() - state.downX;
                    float dy = event.getY() - state.downY;
                    if (Math.abs(dy) > swipeVerticalSlop && Math.abs(dy) > Math.abs(dx)) {
                        state.resetView();
                        state.startPosition = -1;
                        return false;
                    }
                    if (applySwipeMove(list, state, dx, swipeHorizontalSlop, false, onRightSwipe)) {
                        return true;
                    }
                    if (applySwipeMove(list, state, dx, swipeHorizontalSlop, true, onLeftSwipe)) {
                        return true;
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (state.swiping) suppressItemClick = true;
                    if (!state.handled) v.performClick();
                    state.resetView();
                    boolean handled = state.handled;
                    state.startPosition = -1;
                    state.handled = false;
                    return handled;

                default:
                    return false;
            }
        });
    }

    private void installFileBrowserSwipeAdd(ListView fileBrowserList) {
        installSwipeListener(fileBrowserList,
            pos -> pos < filteredFileEntries.size(),
            null, null,
            this::handleFileBrowserSwipe,
            null
        );
    }

    /** Swipe-to-add for a file-browser row: folders enqueue their tracks, playlists expand, files add. */
    private void handleFileBrowserSwipe(int position) {
        if (position >= filteredFileEntries.size()) return;
        FileEntry entry = filteredFileEntries.get(position);
        if (entry.isDirectory()) {
            addFolderToQueue(entry);
            return;
        }
        if (mode == Mode.REMOTE_SEND && !localQueueShownInRemoteMode) {
            if (isPlaylistFile(entry.name)) {
                sendPlaylistToRemote(entry);
            } else {
                sendTrackToRemote(entry);
            }
            return;
        }
        if (isPlaylistFile(entry.name)) {
            addPlaylistToQueue(entry);
        } else {
            addToQueue(entry.name, entry.uri);
        }
    }

    private void sendTrackToRemote(FileEntry entry) {
        btController.sendQueueRequest(entry.name, relativePathFromRoot(entry.uri),
                entry.sortTitle, entry.sortArtist, entry.sortDate);
    }

    /**
     * Swipe-to-enqueue for a folder row: appends the folder's immediate audio tracks (non-recursive,
     * playlists skipped) in the active sort order. In remote-send mode the tracks are forwarded as
     * queue requests instead, mirroring the per-file swipe.
     */
    private void addFolderToQueue(FileEntry folder) {
        List<FileEntry> tracks = immediateAudioTracksSorted(folder);
        if (tracks.isEmpty()) return;
        if (mode == Mode.REMOTE_SEND && !localQueueShownInRemoteMode) {
            for (FileEntry t : tracks) {
                sendTrackToRemote(t);
            }
            return;
        }
        List<QueueEntry> adds = new ArrayList<>(tracks.size());
        for (FileEntry t : tracks) {
            adds.add(new QueueEntry(t.name, t.uri));
        }
        addToQueue(adds);
    }

    /**
     * Immediate audio tracks of {@code folder} (playlists excluded), sorted by the active sort mode
     * using only already-cached tag metadata — it never blocks on a tag read.
     */
    private List<FileEntry> immediateAudioTracksSorted(FileEntry folder) {
        List<FileEntry> tracks = new ArrayList<>();
        for (FileEntry child : storageBrowser.listImmediateAudioChildren(folder)) {
            if (isPlaylistFile(child.name)) continue;
            applyCachedSortMetadata(child);
            tracks.add(child);
        }
        Collections.sort(tracks, this::compareFileEntries);
        return tracks;
    }

    /**
     * Fills an entry's sort fields from already-cached tags only (plus the cheap filename-year
     * heuristic). Never reads files, so entries whose tags aren't cached keep UNKNOWN sort fields
     * and fall back to name ordering in {@link #compareFileEntries}.
     */
    private void applyCachedSortMetadata(FileEntry e) {
        if (applyCachedSortTags(e)) {
            return;
        }
        String year = MetadataExtractor.extractYearFromFileName(e.name);
        if (year.length() > 0) {
            e.sortDate = year;
            e.sortDateState = TagState.RESOLVED;
        }
    }

    /**
     * Copies an entry's sort tags from the metadata cache and marks them RESOLVED, but only when the
     * full tag set is already cached (so it never blocks on a file read). Returns whether tags were
     * applied. Shared by the swipe-add path and the visible-folder background resolver.
     */
    private boolean applyCachedSortTags(FileEntry e) {
        if (e.uri == null || !metadataExtractor.isAllTagsCached(e.uri)) {
            return false;
        }
        MetadataExtractor.TagEntry t = metadataExtractor.readSortTags(e.uri);
        e.sortDate = t.date;
        e.sortGenre = t.genre;
        e.sortArtist = t.artist;
        e.sortTitle = t.title;
        e.sortBpm = t.bpm;
        e.sortDateState   = TagState.RESOLVED;
        e.sortGenreState  = TagState.RESOLVED;
        e.sortArtistState = TagState.RESOLVED;
        e.sortBpmState    = TagState.RESOLVED;
        return true;
    }

    /**
     * The filter EditText is the only focusable view, so once it has keyboard focus it never gives
     * it up and the cursor keeps blinking even after the user hides the keyboard. Bind the cursor's
     * visibility to the soft keyboard so it shows only while the keyboard is up: tying it directly
     * to IME visibility means the cursor vanishes the instant the keyboard is dismissed.
     */
    private void installSearchCursorKeyboardBinding() {
        View content = findViewById(android.R.id.content);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {          // API 30+: exact IME insets
            content.setOnApplyWindowInsetsListener((v, insets) -> {
                fileFilterInput.setCursorVisible(insets.isVisible(WindowInsets.Type.ime()));
                return v.onApplyWindowInsets(insets);
            });
        } else {                                                       // API 23-29: height heuristic
            content.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                Rect r = new Rect();
                content.getWindowVisibleDisplayFrame(r);
                int screenH = content.getRootView().getHeight();
                fileFilterInput.setCursorVisible(screenH - r.bottom > screenH * 0.15f);
            });
        }
    }

    private Uri resolveFirstPlaylistUri(FileEntry playlistEntry) {
        for (String line : readPlaylistLines(playlistEntry)) {
            Uri uri = resolvePlaylistTargetUri(playlistEntry, line);
            if (uri != null) return uri;
        }
        return null;
    }

    private void enterPlaylistAsBrowseFolder(FileEntry playlistEntry) {
        stopBrowsePlaybackForFolderSwitch();
        clearFileFilterInput();
        fileEntriesVersion++;
        fileEntries.clear();
        currentBrowsePlaylistEntry = playlistEntry;
        applyFileFilter();
        updateStorageButtonState();

        final int versionAtStart = fileEntriesVersion;
        final Uri treeUri = storageBrowser.getCurrentTreeUri();
        tagReadExecutor.submit(() -> {
            List<String> lines = readPlaylistLines(playlistEntry);
            List<FileEntry> tracks = new ArrayList<>(lines.size());
            if (playlistEntry.file == null && treeUri != null) {
                List<Uri> uris = resolveDocumentPlaylistUrisBatch(playlistEntry.uri, lines, treeUri);
                for (int i = 0; i < lines.size(); i++) {
                    Uri uri = uris.get(i);
                    if (uri != null)
                        tracks.add(new FileEntry(uri, getDisplayNameForPlaylistItem(lines.get(i), uri), false));
                }
            } else {
                for (String line : lines) {
                    Uri uri = resolvePlaylistTargetUri(playlistEntry, line);
                    if (uri != null)
                        tracks.add(new FileEntry(uri, getDisplayNameForPlaylistItem(line, uri), false));
                }
            }
            runOnUiThread(() -> {
                if (versionAtStart != fileEntriesVersion) return;
                if (tracks.isEmpty()) {
                    currentBrowsePlaylistEntry = null;
                    Toast.makeText(FileBrowserQueueActivity.this,
                            getString(R.string.no_playable_files_in_playlist, playlistEntry.name),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                fileEntries.addAll(tracks);
                resolveTagMetadataForVisibleFolderAsync();
                applyFileFilter();
                scrollToHighlightedFileEntry();
                updateStorageButtonState();
            });
        });
    }

    private void exitPlaylistBrowseFolder() {
        stopBrowsePlaybackForFolderSwitch();
        clearFileFilterInput();
        pendingBackScrollUri = currentBrowsePlaylistEntry.uri;
        currentBrowsePlaylistEntry = null;
        if (storageBrowser.isBrowsingDocumentTree()) {
            browseCurrentDocumentDirectory();
        } else {
            File dir = storageBrowser.getCurrentFileDirectory();
            if (dir != null) {
                navigateTo(dir);
            }
        }
    }

    private void updateQueueHint() {
        if (queueEntries.isEmpty()) {
            int hintRes;
            if (mode == Mode.REMOTE_SEND && !localQueueShownInRemoteMode) {
                hintRes = R.string.queue_hint_remote;
            } else if (mode == Mode.REMOTE_SEND || mode == Mode.BROWSE) {
                hintRes = R.string.queue_hint_browse;
            } else if (PreviewManager.isEnabled(this)) {
                hintRes = R.string.queue_hint_preview;
            } else {
                hintRes = R.string.queue_hint_normal;
            }
            queueEmptyHint.setText(hintRes);
            queueEmptyHint.setVisibility(View.VISIBLE);
        } else {
            queueEmptyHint.setVisibility(View.GONE);
        }
    }

    private void playQueue() {
        playQueueFrom(0);
    }

    private void playQueueFrom(int position) {
        playQueueFrom(position, false);
    }

    private void playQueueFrom(int position, boolean forceImmediateRestart) {
        if (queueEntries.isEmpty()) {
            Toast.makeText(this, R.string.save_queue_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (position < 0 || position >= queueEntries.size()) {
            return;
        }

        // Persist queue + start position so the Service's MediaSession onPlay callback can
        // read them. We then trigger playback two ways:
        //  1. startForegroundService(intent) — fast path; works when the app is in the
        //     foreground or the Service is already running.
        //  2. dispatchMediaPlayKey() — fallback for Android 14+ when the Activity is in the
        //     background. The OS routes the key through MediaSessionManager to our MediaSession
        //     callback; foreground-service starts originating from there are exempt from the
        //     background-FGS-start restrictions that defer (1) until the device is unlocked.
        // Whichever path arrives first wins; the second becomes a no-op once audioPlayer is set.
        persistQueue();
        setPlaybackOffset(position);

        int count = queueEntries.size() - position;
        ArrayList<Uri> uris = new ArrayList<>(count);
        int[] ids = new int[count];
        for (int i = position; i < queueEntries.size(); i++) {
            QueueEntry e = queueEntries.get(i);
            uris.add(e.uri);
            ids[i - position] = e.id;
        }
        Intent intent = new Intent(this, Service.class);
        intent.setAction(ACTION_SEND_MULTIPLE_COMPAT);
        intent.setType("audio/*");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.putExtra(Service.EXTRA_ENTRY_IDS, ids);
        intent.putExtra(Service.EXTRA_QUEUE_ALREADY_PERSISTED, true);

        queueTransitionActive = true;
        if (forceImmediateRestart) {
            sendStopNowCommand();
        }
        resetStopButtonState();
        startPlaybackService(intent);
        if (!forceImmediateRestart) {
            // Only dispatch the media-play key when starting from a stopped/idle state — that's
            // where the FGS-start exemption from a MediaSession callback actually matters. During
            // a fade-out the service is already alive, so the KILL + ACTION_SEND_MULTIPLE intents
            // above are sufficient and a racing media-key PLAY arriving before the KILL would
            // call cancelFadeOutAndResume() on the current (wrong) track.
            dispatchMediaPlayKey();
        }

        currentPlayingQueueIndex = position;
        currentTrackPositionMs = 0;
        currentTrackDurationMs = 0;
        // Starting at/after the anchor track clears its anchor status.
        clearAnchorIfPlaybackReached();
        queueAdapter.notifyDataSetChanged();
    }

    private void dispatchMediaPlayKey() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
    }

    private int currentPlayingEntryId() {
        if (currentPlayingQueueIndex >= 0 && currentPlayingQueueIndex < queueEntries.size())
            return queueEntries.get(currentPlayingQueueIndex).id;
        return -1;
    }

    /** Derives the canonical playback state string from the Service volatile fields,
     *  which are written immediately by the fade/playback thread and never lag. */
    private String remotePlaybackState() {
        boolean fading = Service.sFadeOutInProgress;
        // queueTransitionActive treated as "playing": mirrors the sFadeOutInProgress sync pattern
        // so pushPlayState() sends the correct state before the async service broadcast arrives.
        return fading ? "fading" : (Service.sIsPlaying || queueTransitionActive ? "playing" : "stopped");
    }

    private long fadeDurationMs() {
        return AudioOutputRouter.getFadeOutSeconds(this) * 1000L;
    }

    private void pushPlayState() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "play_state");
            String state = remotePlaybackState();
            msg.put("state", state);
            msg.put("current_id", currentPlayingEntryId());
            if ("fading".equals(state)) msg.put("fade_duration_ms", fadeDurationMs());
            btController.sendRaw(msg.toString());
            lastBroadcastPushedPlayKey = playStateKey();
        } catch (Exception ignored) {
        }
    }

    private String playStateKey() {
        return remotePlaybackState() + "|" + currentPlayingEntryId();
    }

    /**
     * Pushes the current play state to a connected client, but only when it differs from what we
     * last pushed. Called from both the playback broadcast and the 1s poll so a stop the host
     * reaches on its own — e.g. a fade-out finishing — still reaches the client even if the
     * discrete broadcast is missed. No-op unless we're the remote host.
     */
    private void pushPlayStateIfChanged() {
        if (mode == Mode.REMOTE_RECEIVE && btController != null
                && !playStateKey().equals(lastBroadcastPushedPlayKey)) {
            pushPlayState();
        }
    }

    /**
     * Tells a connected remote client that this host's queue changed locally (reorder, remove,
     * anchor) so it re-fetches the queue. The client re-requests with its known-id range, so the
     * reply is a normal delta — no need to push the full state here. No-op unless we're the host.
     */
    private void notifyRemoteQueueChanged() {
        if (mode == Mode.REMOTE_RECEIVE && btController != null) {
            btController.sendRaw("{\"type\":\"queue_changed\"}");
        }
    }

    private void handleRemoteSetVolume(JSONObject obj) {
        int value = obj.optInt("value", -1);
        if (value < 0) return;

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, value)), 0);
        pushVolumeState();
    }

    private void pushVolumeState() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            JSONObject msg = new JSONObject();
            msg.put("type",  "volume_state");
            msg.put("value", am.getStreamVolume(AudioManager.STREAM_MUSIC));
            msg.put("max",   am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            btController.sendRaw(msg.toString());
        } catch (Exception ignored) {
        }
    }

    /** Apply an equalizer change requested by the remote sender, then echo the new state back. The
     *  host's active backend (parametric on API 28+, graphic below) decides which settings store the
     *  command is routed to. */
    private void handleRemoteSetEq(JSONObject obj) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (obj.has("enabled")) {
                ParametricEqSettings.setEnabled(this,
                        obj.optBoolean("enabled", ParametricEqSettings.isEnabled(this)));
            }
            if (obj.has("band")) {
                int band = obj.optInt("band", -1);
                if (band >= 0 && band < ParametricEqSettings.numBands()) {
                    if (obj.has("value")) {
                        ParametricEqSettings.setGainMillibels(this, band, obj.optInt("value", 0));
                    }
                    if (obj.has("freq")) {
                        ParametricEqSettings.setFreqHz(this, band,
                                obj.optInt("freq", ParametricEqSettings.getFreqHz(this, band)));
                    }
                }
            }
        } else {
            EqualizerSettings.Caps caps = EqualizerSettings.queryCapabilities(this);
            if (obj.has("enabled")) {
                EqualizerSettings.setEnabled(this, obj.optBoolean("enabled", EqualizerSettings.isEnabled(this)));
            }
            if (caps != null && obj.has("band") && obj.has("value")) {
                int band = obj.optInt("band", -1);
                if (band >= 0 && band < caps.numBands) {
                    int value = Math.max(caps.minLevel, Math.min(caps.maxLevel, obj.optInt("value", 0)));
                    EqualizerSettings.setBandLevel(this, band, value);
                }
            }
        }
        applyEqToService();
        pushEqState();
    }

    private void pushEqState() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "eq_state");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Parametric (DynamicsProcessing) host: per-band freq (Hz) + gain (millibels), with
                // app-defined ranges. The remote renders adjustable-frequency rows from this.
                int n = ParametricEqSettings.numBands();
                msg.put("mode", "parametric");
                msg.put("enabled", ParametricEqSettings.isEnabled(this));
                msg.put("num_bands", n);
                msg.put("gain_min", ParametricEqSettings.GAIN_MIN_MILLIBELS);
                msg.put("gain_max", ParametricEqSettings.GAIN_MAX_MILLIBELS);
                msg.put("freq_min", ParametricEqSettings.FREQ_MIN_HZ);
                msg.put("freq_max", ParametricEqSettings.FREQ_MAX_HZ);
                JSONArray freqs = new JSONArray(); // Hz
                JSONArray gains = new JSONArray(); // millibels
                for (int b = 0; b < n; b++) {
                    freqs.put(ParametricEqSettings.getFreqHz(this, b));
                    gains.put(ParametricEqSettings.getGainMillibels(this, b));
                }
                msg.put("freqs", freqs);
                msg.put("gains", gains);
            } else {
                // Graphic (Equalizer) host: fixed bands, gain only.
                EqualizerSettings.Caps caps = EqualizerSettings.queryCapabilities(this);
                msg.put("mode", "graphic");
                msg.put("enabled", EqualizerSettings.isEnabled(this));
                if (caps == null) {
                    msg.put("num_bands", 0);
                } else {
                    msg.put("num_bands", caps.numBands);
                    msg.put("min", caps.minLevel);
                    msg.put("max", caps.maxLevel);
                    JSONArray freqs  = new JSONArray();
                    JSONArray levels = new JSONArray();
                    for (int b = 0; b < caps.numBands; b++) {
                        freqs.put(caps.centerFreq[b]);
                        levels.put((int) EqualizerSettings.getBandLevel(this, b));
                    }
                    msg.put("freqs",  freqs);
                    msg.put("levels", levels);
                }
            }
            btController.sendRaw(msg.toString());
        } catch (Exception ignored) {
        }
    }

    /** Push persisted EQ settings to the live player. Skipped when nothing is playing — the
     *  settings are already stored and {@link EqualizerController} applies them on the next track. */
    private void applyEqToService() {
        if (!Service.sIsPlaying) return;
        Intent intent = new Intent(this, Service.class);
        intent.putExtra(Launcher.TYPE, Launcher.APPLY_EQ);
        startService(intent);
    }

    /** Local-playback equalizer: persists settings and nudges the running Service to re-apply. */
    private static final class LocalEqSink implements EqualizerDialog.EqSink {
        private final FileBrowserQueueActivity activity;
        private final Context context;

        LocalEqSink(FileBrowserQueueActivity activity) {
            this.activity = activity;
            this.context = activity.getApplicationContext();
        }

        private EqualizerSettings.Caps caps() {
            return EqualizerSettings.queryCapabilities(context);
        }

        @Override public boolean isEnabled() { return EqualizerSettings.isEnabled(context); }

        @Override public void setEnabled(boolean enabled) {
            EqualizerSettings.setEnabled(context, enabled);
            activity.applyEqToService();
        }

        @Override public int numBands() {
            EqualizerSettings.Caps c = caps();
            return c == null ? 0 : c.numBands;
        }

        @Override public int centerFreqMilliHz(int band) {
            EqualizerSettings.Caps c = caps();
            return (c == null || band >= c.centerFreq.length) ? 0 : c.centerFreq[band];
        }

        @Override public short bandLevel(int band) { return EqualizerSettings.getBandLevel(context, band); }

        @Override public void nudgeBand(int band, int deltaMillibels) {
            EqualizerSettings.Caps c = caps();
            if (c == null || band < 0 || band >= c.numBands) return;
            int level = EqualizerSettings.getBandLevel(context, band) + deltaMillibels;
            level = Math.max(c.minLevel, Math.min(c.maxLevel, level));
            EqualizerSettings.setBandLevel(context, band, level);
            activity.applyEqToService();
        }

        @Override public CharSequence statusText() {
            return caps() == null ? context.getString(R.string.eq_unavailable) : null;
        }

        @Override public boolean freqAdjustable() { return false; }

        @Override public void nudgeFreq(int band, int direction) { /* graphic bands are fixed */ }

        // Edges are unused for graphic bands (freqAdjustable() is false); report the center.
        @Override public int lowerEdgeMilliHz(int band) { return centerFreqMilliHz(band); }

        @Override public int upperEdgeMilliHz(int band) { return centerFreqMilliHz(band); }
    }

    /** Local-playback parametric equalizer (API 28+, {@link DynamicsEqController}): per-band center
     *  frequency and gain are both adjustable. Persists to {@link ParametricEqSettings} and nudges
     *  the running Service to re-apply. */
    private static final class ParametricLocalEqSink implements EqualizerDialog.EqSink {
        private final FileBrowserQueueActivity activity;
        private final Context context;

        ParametricLocalEqSink(FileBrowserQueueActivity activity) {
            this.activity = activity;
            this.context = activity.getApplicationContext();
        }

        @Override public boolean isEnabled() { return ParametricEqSettings.isEnabled(context); }

        @Override public void setEnabled(boolean enabled) {
            ParametricEqSettings.setEnabled(context, enabled);
            activity.applyEqToService();
        }

        @Override public int numBands() { return ParametricEqSettings.numBands(); }

        @Override public int centerFreqMilliHz(int band) {
            return ParametricEqSettings.getFreqHz(context, band) * 1000;
        }

        @Override public short bandLevel(int band) {
            return (short) ParametricEqSettings.getGainMillibels(context, band);
        }

        @Override public void nudgeBand(int band, int deltaMillibels) {
            if (band < 0 || band >= ParametricEqSettings.numBands()) return;
            int level = ParametricEqSettings.getGainMillibels(context, band) + deltaMillibels;
            ParametricEqSettings.setGainMillibels(context, band, level);
            activity.applyEqToService();
        }

        @Override public CharSequence statusText() { return null; }

        @Override public boolean freqAdjustable() { return true; }

        @Override public void nudgeFreq(int band, int direction) {
            if (band < 0 || band >= ParametricEqSettings.numBands()) return;
            int cur = ParametricEqSettings.getFreqHz(context, band);
            ParametricEqSettings.setFreqHz(context, band, ParametricEqSettings.stepFreqHz(cur, direction));
            activity.applyEqToService();
        }

        @Override public int lowerEdgeMilliHz(int band) {
            return ParametricEqSettings.lowerEdgeHz(context, band) * 1000;
        }

        @Override public int upperEdgeMilliHz(int band) {
            return ParametricEqSettings.upperEdgeHz(context, band) * 1000;
        }
    }

    private void handleRemoteRequestQueue(JSONObject obj) {
        int maxKnownId = obj.optInt("max_known_id", 0);
        int minKnownId = obj.optInt("min_known_id", 0);
        try {
            JSONObject response = new JSONObject();
            response.put("type", "queue_state");
            response.put("current_id", currentPlayingEntryId());
            response.put("anchor_id", anchorEntryId);
            String state = remotePlaybackState();
            response.put("playback_state", state);
            if ("fading".equals(state)) response.put("fade_duration_ms", fadeDurationMs());
            JSONArray tracks = new JSONArray();
            for (QueueEntry entry : queueEntries) {
                JSONObject t = new JSONObject();
                t.put("id", entry.id);
                boolean clientKnows = maxKnownId > 0
                        && entry.id <= maxKnownId
                        && (minKnownId == 0 || entry.id >= minKnownId);
                if (!clientKnows) {
                    t.put("name", entry.name != null ? entry.name : "");
                    if (entry.tagsCached) {
                        if (entry.title  != null && !entry.title.isEmpty())  t.put("title",  entry.title);
                        if (entry.artist != null && !entry.artist.isEmpty()) t.put("artist", entry.artist);
                        if (entry.date   != null && !entry.date.isEmpty())   t.put("date",   entry.date);
                    }
                }
                tracks.put(t);
            }
            response.put("tracks", tracks);
            btController.sendRaw(response.toString());
        } catch (Exception ignored) {
        }
    }

    private void handleRemoteMoveTrack(JSONObject obj) {
        int id = obj.optInt("id", -1);
        int toPos = obj.optInt("to_position", -1);
        int fromPos = findQueueIndexById(id);
        if (fromPos < 0 || toPos < 0 || toPos >= queueEntries.size()) return;
        moveQueueItem(fromPos, toPos);
        persistQueue();
        if (Service.sIsPlaying && !isStopFadeInProgress()) syncServicePendingQueue();
    }

    private void handleRemoteRemoveTrack(JSONObject obj) {
        int id = obj.optInt("id", -1);
        int pos = findQueueIndexById(id);
        if (pos >= 0) removeQueueAt(pos);
    }

    private void handleRemoteSetAnchor(JSONObject obj) {
        int id = obj.optInt("id", -1);
        if (id <= 0 || id == currentPlayingEntryId()) return;
        if (findQueueIndexById(id) < 0) return;
        setAnchor(anchorEntryId == id ? 0 : id);   // toggle; also repaints the local queue
    }

    private void handleRemotePlayTrack(JSONObject obj) {
        if ((Service.sIsPlaying || queueTransitionActive) && !isStopFadeInProgress()) {
            pushPlayState();
            return;
        }
        int id = obj.optInt("id", -1);
        int pos = findQueueIndexById(id);
        if (pos >= 0) playQueueFrom(pos, isStopFadeInProgress());
        // Clear synchronously so pushPlayState() sees "playing" rather than "fading".
        // sIsPlaying is still true (the fading player is alive until the KILL intent is
        // processed), so the client flips Resume→Stop immediately rather than waiting for
        // the async KILL+new-track round-trip.  Mirrors the pattern in cancelFadeOutAndContinue().
        Service.sFadeOutInProgress = false;
        pushPlayState();
    }

    private int findQueueIndexById(int id) {
        for (int i = 0; i < queueEntries.size(); i++) {
            if (queueEntries.get(i).id == id) return i;
        }
        return -1;
    }

    private void stopPlaybackWithFadeout() {
        if (isStopFadeInProgress()) {
            return;
        }

        // Do not show fading UI when nothing is currently playing.
        if ((!Service.sIsPlaying && !queueTransitionActive) || (currentPlayingQueueIndex < 0 && !Service.sBrowseMode)) {
            resetStopButtonState();
            return;
        }

        Intent intent = new Intent(this, Service.class);
        intent.putExtra(Launcher.TYPE, Launcher.STOP);
        startService(intent);
        // Set synchronously so the immediately-following pushPlayState() sees "fading"
        // rather than "playing" — the Service does the same in its STOP handler, but
        // that runs asynchronously and would still show the old state at push time.
        Service.sFadeOutInProgress = true;
        showStopButtonFadingState();
    }

    private void cancelFadeOutAndContinue() {
        Intent intent = new Intent(this, Service.class);
        intent.putExtra(Launcher.TYPE, Launcher.PLAY);
        startService(intent);

        // Clear the flag synchronously so that pushPlayState() (called right after this
        // in the resume_playback handler) sees "playing" rather than "fading".  The Service
        // does the same thing in its PLAY branch of onStartCommand(), but that runs
        // asynchronously — the flag would still be true by the time we push state.
        Service.sFadeOutInProgress = false;
        resetStopButtonState();
        queueAdapter.notifyDataSetChanged();
    }

    private void sendStopNowCommand() {
        Intent intent = new Intent(this, Service.class);
        intent.putExtra(Launcher.TYPE, Launcher.KILL);
        startService(intent);
    }

    private void startPlaybackService(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopPlaybackImmediately() {
        sendStopNowCommand();
        applyStoppedState();
    }

    private void stopBrowsePlaybackForFolderSwitch() {
        if (mode == Mode.REMOTE_SEND && Service.sBrowseMode) {
            stopPlaybackImmediately();
        }
    }

    private void applyStoppedState() {
        queueTransitionActive = false;
        clearBrowseState();
        currentPlayingQueueIndex = -1;
        setPlaybackOffset(0);
        resetCurrentTrackProgress();
        applyStopButtonState();
        queueAdapter.notifyDataSetChanged();
        fileAdapter.notifyDataSetChanged();
    }

    private void clearQueueAndStopPlayback() {
        if (!Service.sBrowseMode) {
            stopPlaybackImmediately();
        }
        queueEntries.clear();
        anchorEntryId = 0;   // QueueStore.clear() drops the persisted key below
        queueAdapter.notifyDataSetChanged();
        updateQueueHint();
        QueueStore.clear(this);
    }

    private void playBrowseFile(FileEntry entry) {
        Uri uri = isPlaylistFile(entry.name) ? resolveFirstPlaylistUri(entry) : entry.uri;
        if (uri == null) return;

        ArrayList<Uri> uris = new ArrayList<>(1);
        uris.add(uri);

        Intent intent = new Intent(this, Service.class);
        intent.setAction(ACTION_SEND_MULTIPLE_COMPAT);
        intent.setType("audio/*");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.putExtra(Service.EXTRA_BROWSE_MODE, true);

        browseTransitionActive = true;
        sendStopNowCommand();
        resetStopButtonState();
        startPlaybackService(intent);

        browseFileUri = uri;
        browseNextQueued = false;
        browseNextUri = null;
        currentPlayingQueueIndex = -1;
        queueAdapter.notifyDataSetChanged();
        fileAdapter.notifyDataSetChanged();
    }

    private void queueRemainingBrowseTracks() {
        if (!Service.sBrowseMode || browseFileUri == null) return;
        int currentPos = -1;
        for (int i = 0; i < filteredFileEntries.size(); i++) {
            FileEntry e = filteredFileEntries.get(i);
            if (!e.isDirectory() && browseFileUri.equals(e.uri)) {
                currentPos = i;
                break;
            }
        }
        if (currentPos < 0) return;
        ArrayList<Uri> uris = new ArrayList<>(filteredFileEntries.size() - currentPos - 1);
        for (int i = currentPos + 1; i < filteredFileEntries.size(); i++) {
            FileEntry e = filteredFileEntries.get(i);
            if (!e.isDirectory() && !isPlaylistFile(e.name)) {
                uris.add(e.uri);
            }
        }
        if (uris.isEmpty()) return;
        Intent appendIntent = new Intent(this, Service.class);
        appendIntent.putExtra(Launcher.TYPE, Launcher.APPEND_QUEUE);
        appendIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        startService(appendIntent);
    }

    private void maybeQueueNextBrowseTrack() {
        if (!Service.sBrowseMode || browseNextQueued || browseFileUri == null) return;
        if (currentTrackDurationMs <= 0 || currentTrackDurationMs - currentTrackPositionMs > 5_000) return;
        int currentPos = -1;
        for (int i = 0; i < filteredFileEntries.size(); i++) {
            FileEntry e = filteredFileEntries.get(i);
            if (!e.isDirectory() && browseFileUri.equals(e.uri)) {
                currentPos = i;
                break;
            }
        }
        if (currentPos < 0) return;
        for (int i = currentPos + 1; i < filteredFileEntries.size(); i++) {
            FileEntry e = filteredFileEntries.get(i);
            if (!e.isDirectory() && !isPlaylistFile(e.name)) {
                ArrayList<Uri> uris = new ArrayList<>(1);
                uris.add(e.uri);
                Intent appendIntent = new Intent(this, Service.class);
                appendIntent.putExtra(Launcher.TYPE, Launcher.APPEND_QUEUE);
                appendIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                startService(appendIntent);
                browseNextQueued = true;
                browseNextUri = e.uri;
                scrollToHighlightedFileEntry();
                return;
            }
        }
    }

    private void onRemoteQueueRequestsReceived(List<BluetoothQueueBridge.TrackRequest> tracks) {
        if (!btController.isServerMode() || tracks == null || tracks.isEmpty()) return;
        new Thread(() -> {
            List<Uri> foundUris = findRequestedAudioUris(tracks);

            int nameCount = 0;
            for (Uri u : foundUris) if (u != null) nameCount++;

            int tagCount = 0, fuzzyCount = 0;
            String tagMatchedTitle = null, fuzzyMatchedTitle = null;
            if (nameCount < tracks.size()) {
                List<Map.Entry<String, MetadataExtractor.TagEntry>> cacheSnapshot =
                        metadataExtractor.snapshotCacheEntries();
                for (int i = 0; i < tracks.size(); i++) {
                    if (foundUris.get(i) != null) continue;
                    BluetoothQueueBridge.TrackRequest req = tracks.get(i);
                    if (!req.title.isEmpty()) {
                        TrackMatcher.TagMatch match =
                                TrackMatcher.findInTagCacheByTitleAndArtist(req.title, req.artist, cacheSnapshot);
                        if (match != null) {
                            foundUris.set(i, match.uri);
                            if (match.exact) {
                                tagCount++;
                                tagMatchedTitle = match.label;
                            } else {
                                fuzzyCount++;
                                fuzzyMatchedTitle = match.label;
                            }
                        }
                    }
                }
            }

            List<QueueEntry> toAdd = new ArrayList<>(tracks.size());
            List<String> notFound = new ArrayList<>();
            for (int i = 0; i < tracks.size(); i++) {
                Uri uri = foundUris.get(i);
                if (uri != null) {
                    toAdd.add(new QueueEntry(tracks.get(i).file, uri));
                } else {
                    notFound.add(tracks.get(i).file);
                }
            }

            final int fName = nameCount, fTag = tagCount, fFuzzy = fuzzyCount, fNone = notFound.size();
            final String fNoneName = notFound.size() == 1 ? notFound.get(0) : null;
            final String fTagName   = tagCount   == 1 ? tagMatchedTitle   : null;
            final String fFuzzyName = fuzzyCount == 1 ? fuzzyMatchedTitle : null;
            runOnUiThread(() -> {
                if (!toAdd.isEmpty()) {
                    addToQueue(toAdd);
                    Toast.makeText(this, getString(R.string.added_tracks_from_remote, toAdd.size()), Toast.LENGTH_SHORT).show();
                }
                if (notFound.size() == 1) {
                    Toast.makeText(this, getString(R.string.requested_file_not_found, notFound.get(0)), Toast.LENGTH_SHORT).show();
                } else if (notFound.size() > 1) {
                    Toast.makeText(this, getString(R.string.requested_files_not_found, notFound.size()), Toast.LENGTH_SHORT).show();
                }
                // Send after addToQueue so a client requestQueue triggered by match_result sees the new state.
                sendMatchResult(fName, fTag, fFuzzy, fNone, fTagName, fFuzzyName, fNoneName);
            });
        }).start();
    }

    private void sendMatchResult(int name, int tag, int fuzzy, int none, String tagName, String fuzzyName, String noneName) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type",  "match_result");
            obj.put("name",  name);
            obj.put("tag",   tag);
            obj.put("fuzzy", fuzzy);
            obj.put("none",  none);
            if (tagName   != null) obj.put("tag_name",   tagName);
            if (fuzzyName != null) obj.put("fuzzy_name", fuzzyName);
            if (noneName  != null) obj.put("none_name",  noneName);
            btController.sendRaw(obj.toString());
        } catch (Exception ignored) {
        }
    }

    private void showMatchResultToast(String jsonLine) {
        if (mode != Mode.REMOTE_SEND) return;
        try {
            JSONObject obj = new JSONObject(jsonLine);
            if (!"match_result".equals(obj.optString("type"))) return;
            if (remoteQueueController != null) remoteQueueController.refreshAndScrollToNewTrack();
            int name  = obj.optInt("name",  0);
            int tag   = obj.optInt("tag",   0);
            int fuzzy = obj.optInt("fuzzy", 0);
            int none  = obj.optInt("none",  0);
            StringBuilder sb = new StringBuilder();
            if (name  > 0) appendMatchCategory(sb, getString(R.string.match_name,  name));
            if (tag   > 0) {
                String tagName = obj.optString("tag_name", null);
                if (tag == 1 && tagName != null && !tagName.isEmpty())
                    appendMatchCategory(sb, getString(R.string.match_tag_name, tagName));
                else
                    appendMatchCategory(sb, getString(R.string.match_tag, tag));
            }
            if (fuzzy > 0) {
                String fuzzyName = obj.optString("fuzzy_name", null);
                if (fuzzy == 1 && fuzzyName != null && !fuzzyName.isEmpty())
                    appendMatchCategory(sb, getString(R.string.match_fuzzy_name, fuzzyName));
                else
                    appendMatchCategory(sb, getString(R.string.match_fuzzy, fuzzy));
            }
            if (none  > 0) {
                String noneName = obj.optString("none_name", null);
                if (none == 1 && noneName != null && !noneName.isEmpty())
                    appendMatchCategory(sb, getString(R.string.match_none_name, noneName));
                else
                    appendMatchCategory(sb, getString(R.string.match_none, none));
            }
            if (sb.length() > 0)
                Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
    }

    private static void appendMatchCategory(StringBuilder sb, String part) {
        if (sb.length() > 0) sb.append(" · ");
        sb.append(part);
    }

    private List<Uri> findRequestedAudioUris(List<BluetoothQueueBridge.TrackRequest> requests) {
        int n = requests.size();
        boolean isDocTree = storageBrowser.isBrowsingDocumentTree() && storageBrowser.hasDocumentLocation();
        Uri rootDocUri = isDocTree ? storageBrowser.getDocumentRootUri() : null;
        File fileRoot = storageBrowser.getCurrentFileRootDirectory();

        // Stage 1 — direct path: when the music folders are identical on both devices the full path
        // sent by the peer resolves directly, so we skip any scan entirely.
        Uri[] results = new Uri[n];
        int resolved = 0;
        for (int i = 0; i < n; i++) {
            String relPath = requests.get(i).path;
            if (relPath.isEmpty()) continue;
            Uri hit = isDocTree ? storageBrowser.resolveDirectDocumentPath(rootDocUri, relPath)
                                : storageBrowser.resolveDirectFilePath(fileRoot, relPath);
            if (hit != null) {
                results[i] = hit;
                resolved++;
            }
        }

        // Stage 2 — tag cache: the recursive tag scan already indexed every file's URI as a cache
        // key, so match the remaining requests by filename against that in-memory index instead of
        // re-walking the SAF tree (which costs a query() per folder). Misses fall through to stage 3.
        if (resolved < n) {
            List<Map.Entry<String, MetadataExtractor.TagEntry>> snapshot =
                    metadataExtractor.snapshotCacheEntries();
            if (!snapshot.isEmpty()) {
                List<Uri> cacheHits = findAllInTagCache(requests, snapshot);
                for (int i = 0; i < n; i++) {
                    if (results[i] == null && cacheHits.get(i) != null) {
                        results[i] = cacheHits.get(i);
                        resolved++;
                    }
                }
            }
        }


        // Stage 3 — tree walk: only for whatever stages 1-2 couldn't resolve (e.g. files added after
        // the scan, or a request that arrived before the scan finished).
        if (resolved < n) {
            List<Uri> walk = null;
            if (isDocTree) {
                walk = findAllInDocumentTree(rootDocUri, requests);
            } else if (fileRoot != null && fileRoot.exists()) {
                walk = findAllInFileDirectory(fileRoot, requests);
            }
            if (walk != null) {
                for (int i = 0; i < n; i++) {
                    if (results[i] == null && walk.get(i) != null) results[i] = walk.get(i);
                }
            }
        }

        List<Uri> out = new ArrayList<>(n);
        for (Uri u : results) out.add(u);
        return out;
    }

    /**
     * Resolves requests against the in-memory tag cache, whose keys are every scanned file's URI.
     * Mirrors {@link #findAllInDocumentTree}'s filename / parent-hint / extension matching but touches
     * no SAF. Returns one URI per request (null where unmatched).
     */
    private List<Uri> findAllInTagCache(List<BluetoothQueueBridge.TrackRequest> requests,
                                        List<Map.Entry<String, MetadataExtractor.TagEntry>> snapshot) {
        int n = requests.size();
        Uri[] hintMatches = new Uri[n];
        Uri[] nameMatches = new Uri[n];
        Uri[] extMatches  = new Uri[n];
        String[] hints = TrackMatcher.parentHints(requests);

        for (Map.Entry<String, MetadataExtractor.TagEntry> e : snapshot) {
            Uri childUri = Uri.parse(MetadataExtractor.keyToUri(e.getKey()));
            // For SAF the document id is a single path segment holding the whole "/"-separated
            // subtree path; for file:// it's the file path. Either way the last two "/" components
            // are the file name and its parent folder.
            String docPath = "content".equals(childUri.getScheme())
                    ? childUri.getLastPathSegment() : childUri.getPath();
            if (docPath == null) continue;
            int lastSlash = docPath.lastIndexOf('/');
            String childName = lastSlash >= 0 ? docPath.substring(lastSlash + 1) : docPath;
            if (childName.isEmpty()) continue;
            String dirName = "";
            if (lastSlash > 0) {
                int prevSlash = docPath.lastIndexOf('/', lastSlash - 1);
                dirName = docPath.substring(prevSlash + 1, lastSlash);
            }
            TrackMatcher.matchByNameAndHint(childName, dirName, childUri,
                    requests, hints, hintMatches, nameMatches, extMatches);
        }
        return TrackMatcher.mergeMatchResults(hintMatches, nameMatches, extMatches);
    }

    private void startRecursiveTagScanAsync() {
        final boolean isDocTree = storageBrowser.isBrowsingDocumentTree() && storageBrowser.hasDocumentLocation();
        final Uri rootDocUri = isDocTree ? storageBrowser.getDocumentRootUri() : null;
        final Uri treeUri = storageBrowser.getCurrentTreeUri();
        final File fileRoot = storageBrowser.getCurrentFileRootDirectory();
        if (!isDocTree && fileRoot == null) return;
        // Skip the expensive whole-tree walk if this library root was already scanned this session
        // (e.g. re-entering receive mode or an activity recreation). Cleared when the cache is.
        String rootKey = isDocTree
                ? (treeUri != null ? treeUri.toString() : null)
                : fileRoot.getAbsolutePath();
        if (!metadataExtractor.claimRootScan(rootKey)) return;
        new Thread(() -> {
            List<Uri> allUris = isDocTree
                    ? storageBrowser.collectAllAudioUrisFromDocumentTree(rootDocUri, treeUri)
                    : storageBrowser.collectAllAudioUrisFromFileDirectory(fileRoot);
            if (allUris.isEmpty()) return;
            List<Uri> toScan = new ArrayList<>(allUris.size());
            for (Uri uri : allUris) {
                if (!metadataExtractor.isAllTagsCached(uri)) toScan.add(uri);
            }
            if (toScan.isEmpty()) return;
            runParallelTagReads(toScan.size(),
                    idx -> metadataExtractor.readSortTags(toScan.get(idx)), null);
        }).start();
    }

    /**
     * Finds all requested files in the file-based directory tree with a single DFS pass.
     * Hint-matched results (parent folder name matches) take priority over plain name matches,
     * which take priority over extension-stripped matches.
     */
    private List<Uri> findAllInFileDirectory(File root, List<BluetoothQueueBridge.TrackRequest> requests) {
        int n = requests.size();
        Uri[] hintMatches = new Uri[n];
        Uri[] nameMatches = new Uri[n];
        Uri[] extMatches  = new Uri[n];
        String[] hints = TrackMatcher.parentHints(requests);

        ArrayList<File> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            File dir = stack.remove(stack.size() - 1);
            File[] children = dir.listFiles();
            if (children == null) continue;
            String dirName = dir.getName();
            for (File child : children) {
                if (child == null) continue;
                if (child.isDirectory()) { stack.add(child); continue; }
                String childName = child.getName();
                Uri childUri = Uri.fromFile(child);
                TrackMatcher.matchByNameAndHint(childName, dirName, childUri,
                        requests, hints, hintMatches, nameMatches, extMatches);
            }
        }

        return TrackMatcher.mergeMatchResults(hintMatches, nameMatches, extMatches);
    }

    /**
     * Finds all requested files in the SAF document tree with a single DFS pass.
     * Each directory is queried exactly once. Hint-matched results take priority over plain
     * name matches, which take priority over extension-stripped matches.
     */
    private List<Uri> findAllInDocumentTree(Uri rootDocumentUri, List<BluetoothQueueBridge.TrackRequest> requests) {
        int n = requests.size();
        Uri treeUri = storageBrowser.getCurrentTreeUri();
        Uri[] hintMatches = new Uri[n];
        Uri[] nameMatches = new Uri[n];
        Uri[] extMatches  = new Uri[n];
        String[] hints = TrackMatcher.parentHints(requests);

        String rootDocId;
        try {
            rootDocId = DocumentsContract.getDocumentId(rootDocumentUri);
        } catch (Exception ignored) {
            List<Uri> nulls = new ArrayList<>(n);
            for (int i = 0; i < n; i++) nulls.add(null);
            return nulls;
        }

        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        // Stack holds [docId, displayName] pairs; displayName used to check parentHint.
        ArrayList<String[]> stack = new ArrayList<>();
        stack.add(new String[]{rootDocId, ""});
        while (!stack.isEmpty()) {
            String[] current = stack.remove(stack.size() - 1);
            String dirDocId = current[0];
            String dirName = current[1];
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId);
            try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
                if (cursor == null) continue;
                while (cursor.moveToNext()) {
                    String childDocId = cursor.getString(0);
                    String childName = cursor.getString(1);
                    String mimeType = cursor.getString(2);
                    if (childDocId == null || childName == null) continue;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        stack.add(new String[]{childDocId, childName});
                        continue;
                    }
                    Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId);
                    TrackMatcher.matchByNameAndHint(childName, dirName, childUri,
                            requests, hints, hintMatches, nameMatches, extMatches);
                }
            } catch (Exception ignored) {
            }
        }

        return TrackMatcher.mergeMatchResults(hintMatches, nameMatches, extMatches);
    }

    private boolean isPlaybackActiveOrFading() {
        return Service.sIsPlaying || isStopFadeInProgress() || queueTransitionActive;
    }

    private boolean hasBrowseBehavior() {
        return mode == Mode.BROWSE || mode == Mode.REMOTE_SEND;
    }

    private void applyPlayButtonModeState() {
        if (clearButton != null) {
            clearButton.setVisibility(hasBrowseBehavior() ? View.VISIBLE : View.GONE);
        }
        applySaveButtonModeState();
        updateEqButtonVisibility();
    }

    /**
     * Enabling the equalizer breaks audio routing, so {@link AudioPlayer} skips it whenever a
     * second output is available for previewing. Hide the button in exactly that case. While a
     * track is loaded, mirror the snapshot the player locked in at track start so the button does
     * not flip when outputs change mid-track; when nothing is playing, reflect the live state.
     */
    private void updateEqButtonVisibility() {
        if (eqButton == null) return;
        boolean previewAvailable = Service.sCurrentUri != null
                ? AudioOutputRouter.sAudioPreviewAvailableAtTrackStart
                : AudioOutputRouter.canUseAudioPreview(this);
        eqButton.setVisibility(previewAvailable ? View.GONE : View.VISIBLE);
    }

    private void applySaveButtonModeState() {
        if (saveButton == null) return;
        if (mode == Mode.REMOTE_SEND) {
            saveButton.setText(R.string.send_queue_button);
            saveButton.setOnClickListener(v -> sendQueueToRemote());
        } else {
            saveButton.setText(R.string.save_queue_button);
            saveButton.setOnClickListener(v -> promptSaveQueueFilename());
        }
    }

    private void enterRemoteSendMode() {
        mode = Mode.REMOTE_SEND;
        if (localQueuePanel != null)  localQueuePanel.setVisibility(View.GONE);
        if (remoteQueuePanel != null) remoteQueuePanel.setVisibility(View.VISIBLE);
        if (remoteQueueController == null && remoteQueuePanel != null) {
            ListView list = findViewById(R.id.remote_queue_list);
            View refresh  = findViewById(R.id.btn_remote_refresh);
            View stop     = findViewById(R.id.btn_remote_stop);
            View play     = findViewById(R.id.btn_remote_play);
            View volume   = findViewById(R.id.btn_remote_volume);
            View eq       = findViewById(R.id.btn_remote_eq);
            remoteQueueController = new RemoteQueueController(this, btController, list, refresh, stop, play, volume, eq);
        }
        View remoteLabel = findViewById(R.id.remote_queue_label);
        if (remoteLabel != null) remoteLabel.setOnClickListener(v -> showLocalQueueInRemoteMode());
        View playQueueLabel = findViewById(R.id.play_queue_label);
        if (playQueueLabel != null) playQueueLabel.setOnClickListener(v -> showRemoteQueueInRemoteMode());
    }

    private void showLocalQueueInRemoteMode() {
        localQueueShownInRemoteMode = true;
        if (localQueuePanel  != null) localQueuePanel.setVisibility(View.VISIBLE);
        if (remoteQueuePanel != null) remoteQueuePanel.setVisibility(View.GONE);
        View label = findViewById(R.id.play_queue_label);
        if (label instanceof TextView) ((TextView) label).setText(R.string.staging_queue_title);
        if (stopButton != null) stopButton.setVisibility(View.GONE);
        updateQueueHint();
        if (fileAdapter != null) fileAdapter.notifyDataSetChanged();
    }

    private void showRemoteQueueInRemoteMode() {
        localQueueShownInRemoteMode = false;
        if (localQueuePanel  != null) localQueuePanel.setVisibility(View.GONE);
        if (remoteQueuePanel != null) remoteQueuePanel.setVisibility(View.VISIBLE);
        View label = findViewById(R.id.play_queue_label);
        if (label instanceof TextView) ((TextView) label).setText("Play Queue");
        if (stopButton != null) stopButton.setVisibility(View.VISIBLE);
        if (fileAdapter != null) fileAdapter.notifyDataSetChanged();
    }

    private void sendPlaylistToRemote(FileEntry playlistEntry) {
        List<BluetoothQueueBridge.TrackRequest> requests = new ArrayList<>();
        try (InputStream stream = getContentResolver().openInputStream(playlistEntry.uri)) {
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                        String normalized = trimmed.replace('\\', '/');
                        int lastSlash = normalized.lastIndexOf('/');
                        String filename = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
                        // Playlist entries are already paths relative to the playlist (typically the
                        // root) folder, so forward the whole thing as the full-path fast-match hint.
                        requests.add(new BluetoothQueueBridge.TrackRequest(filename, normalized));
                    }
                }
            }
        } catch (Exception ignored) {}

        if (requests.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_playable_files_in_playlist, playlistEntry.name), Toast.LENGTH_SHORT).show();
            return;
        }
        if (btController.sendQueueRequests(requests)) {
            Toast.makeText(this, getString(R.string.sent_tracks, requests.size()), Toast.LENGTH_SHORT).show();
        }
    }

    private void sendQueueEntryToRemote(int position) {
        if (position < 0 || position >= queueEntries.size()) return;
        QueueEntry entry = queueEntries.get(position);
        String title  = entry.tagsCached ? entry.title  : null;
        String artist = entry.tagsCached ? entry.artist : null;
        String date   = entry.tagsCached ? entry.date   : null;
        btController.sendQueueRequest(entry.name, relativePathFromRoot(entry.uri), title, artist, date);
    }

    /**
     * Returns the file's path relative to the currently selected root folder
     * (the one chosen via Browse), e.g. "Artist/Album/song.mp3", or "" if it can't be resolved.
     * The receiver tries this exact path first before falling back to a full tree scan.
     */
    private String relativePathFromRoot(Uri uri) {
        if (uri == null) return "";
        String rel;
        if (storageBrowser.isBrowsingDocumentTree()) {
            Uri rootDocUri = storageBrowser.getDocumentRootUri();
            rel = rootDocUri == null ? null
                    : resolveRelativeDocumentPath(uri, rootDocUri);
        } else {
            rel = resolveRelativeFilePath(uri, storageBrowser.getCurrentFileRootDirectory());
        }
        return rel != null ? rel : "";
    }

    private void sendQueueToRemote() {
        if (queueEntries.isEmpty()) {
            Toast.makeText(this, R.string.save_queue_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        List<BluetoothQueueBridge.TrackRequest> requests = new ArrayList<>(queueEntries.size());
        for (QueueEntry entry : queueEntries) {
            String title  = entry.tagsCached ? entry.title  : null;
            String artist = entry.tagsCached ? entry.artist : null;
            String date   = entry.tagsCached ? entry.date   : null;
            requests.add(new BluetoothQueueBridge.TrackRequest(entry.name,
                    relativePathFromRoot(entry.uri), title, artist, date));
        }
        if (btController.sendQueueRequests(requests)) {
            Toast.makeText(this, getString(R.string.sent_tracks, requests.size()), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Single source of truth for "is a fade-out currently in progress" — read directly from
     * the Service's static field. The UI follows whatever the Service reports, with the usual
     * one-broadcast / one-sync-tick of latency rather than maintaining a local optimistic copy.
     */
    private boolean isStopFadeInProgress() {
        return Service.sFadeOutInProgress;
    }

    private boolean isPlaybackPaused() {
        return !Service.sBrowseMode && !hasBrowseBehavior()
                && !Service.sIsPlaying && Service.sCurrentUri != null;
    }

    private void applyStopButtonState() {
        if (stopButton == null) return;
        if (isStopFadeInProgress() || isPlaybackPaused()) {
            stopButton.setBackgroundColor(getColor(R.color.stopButtonActive));
            stopButton.setTextColor(getColor(R.color.stopButtonActiveText));
            stopButton.setText(R.string.remote_queue_resume);
        } else {
            stopButton.setBackgroundColor(getColor(R.color.buttonBackground));
            stopButton.setTextColor(getColor(R.color.foreground));
            stopButton.setText(R.string.stop_button_text);
        }
        updateBrowserStopButtonVisibility();
    }

    private void updateBrowserStopButtonVisibility() {
        if (browserStopButton == null) return;
        boolean browseServicePlaying = Service.sIsPlaying && Service.sBrowseMode
                && (mode == Mode.DJ || mode == Mode.REMOTE_SEND);
        boolean previewPlaying = fileBrowserPreviewingUri != null && mode == Mode.DJ;
        browserStopButton.setVisibility(browseServicePlaying || previewPlaying ? View.VISIBLE : View.GONE);
    }

    private void showStopButtonFadingState() {
        // The button text/colour is read from Service.sFadeOutInProgress (via
        // isStopFadeInProgress()), so this just re-renders once the Service has flipped the
        // flag in response to the STOP intent.
        applyStopButtonState();
    }

    private void onFadeOutFinished() {
        resetFileBrowserPreview();
        applyStoppedState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        restorePersistedQueue();
        servicePlaybackOffset = QueueStore.loadPlaybackOffset(this);
        if (Service.sBrowseMode && Service.sCurrentUri != null) {
            browseFileUri = Service.sCurrentUri;
        }
        if (Service.sBrowseMode) {
            Intent clearIntent = new Intent(this, Service.class);
            clearIntent.putExtra(Launcher.TYPE, Launcher.CLEAR_QUEUE);
            startService(clearIntent);
            browseNextQueued = false;
            browseNextUri = null;
        }
        registerPlaybackStateReceiver();
        syncWithServiceState();
        scrollToHighlightedFileEntry();
        uiHandler.removeCallbacks(playbackStateSyncRunnable);
        uiHandler.postDelayed(playbackStateSyncRunnable, PLAYBACK_SYNC_INTERVAL_MS);
        ensureSilenceStreamer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyStopButtonState();
        scrollToHighlightedFileEntry();
        if (queueList != null && currentPlayingQueueIndex >= 0) {
            scrollTo(queueList, currentPlayingQueueIndex);
        }
        if (mode == Mode.REMOTE_SEND && remoteQueueController != null) {
            remoteQueueController.requestQueue();
        }
    }

    private void syncWithServiceState() {
        int prevPlayingIndex = currentPlayingQueueIndex;
        int serviceIndex = Service.sCurrentIndex;
        int entryId = Service.sCurrentEntryId;
        Uri serviceUri = Service.sCurrentUri;
        boolean serviceBrowseMode = Service.sBrowseMode;
        currentTrackPositionMs = Service.sPlaybackPositionMs;
        currentTrackDurationMs = Service.sPlaybackDurationMs;
        applyStopButtonState();
        if (serviceIndex < 0) {
            SilenceStreamer.reinitIfOutputChanged(this);
            if (browseTransitionActive && !isStopFadeInProgress()) {
                // Transient stop between sendStopNowCommand() and the browse track starting.
                return;
            }
            if (queueTransitionActive && !isStopFadeInProgress()) {
                return;
            }
            if (isStopFadeInProgress()) {
                onFadeOutFinished();
                return;
            }
            currentPlayingQueueIndex = -1;
            setPlaybackOffset(0);
            resetCurrentTrackProgress();
        } else {
            if (serviceBrowseMode) {
                currentPlayingQueueIndex = -1;
                if (browseNextUri != null && browseNextUri.equals(serviceUri)) {
                    browseFileUri = browseNextUri;
                    browseNextQueued = false;
                    browseNextUri = null;
                } else if (serviceUri != null && browseFileUri != null && !serviceUri.equals(browseFileUri)) {
                    // Service is playing a different URI than expected (e.g. activity recreated mid-transition)
                    browseFileUri = serviceUri;
                    browseNextQueued = false;
                    browseNextUri = null;
                }
            } else {
                queueTransitionActive = false;
                currentPlayingQueueIndex = resolvePlayingQueueIndex(entryId, serviceIndex, serviceUri);
            }
        }
        clearAnchorIfPlaybackReached();
        refreshQueuePlaybackRows(prevPlayingIndex);
        if (fileAdapter != null && (fileBrowserPreviewingUri != null || Service.sBrowseMode)) fileAdapter.notifyDataSetChanged();
        updateEqButtonVisibility();
        maybeQueueNextBrowseTrack();
        pushPlayStateIfChanged();
    }

    private void setPlaybackOffset(int offset) {
        servicePlaybackOffset = offset;
        QueueStore.savePlaybackOffset(this, offset);
    }

    private void resetCurrentTrackProgress() {
        currentTrackPositionMs = 0;
        currentTrackDurationMs = 0;
    }

    @Override
    protected void onStop() {
        if (Service.sBrowseMode) queueRemainingBrowseTracks();
        persistQueue();
        unregisterPlaybackStateReceiver();
        resetFileBrowserPreview();
        if (Service.sCurrentUri == null) {
            SilenceStreamer.fadeOutAndRelease();
        }
        super.onStop();
    }

    private void resetStopButtonState() {
        applyStopButtonState();
    }

    private void ensureSilenceStreamer() {
        if (AudioOutputRouter.canUseAudioPreview(this)) {
            SilenceStreamer.ensure(this);
        }
    }

    private void startAudioPreview(Uri uri) {
        if (uri == null || !PreviewManager.isEnabled(this) || mode == Mode.REMOTE_SEND) return;
        audioPreviewManager.startPreview(uri);
    }

    private void stopAudioPreview() {
        if (audioPreviewManager != null) audioPreviewManager.stopPreview();
    }

    private void resetFileBrowserPreview() {
        fileBrowserPreviewingUri = null;
        fileBrowserPreviewingEntryUri = null;
        stopAudioPreview();
        updateBrowserStopButtonVisibility();
    }

    private int resolvePlayingQueueIndex(int entryId, int serviceIndex, Uri currentUri) {
        if (queueEntries.isEmpty()) {
            return -1;
        }

        if (entryId > 0) {
            for (int i = 0; i < queueEntries.size(); i++) {
                if (queueEntries.get(i).id == entryId) return i;
            }
        }

        int candidate = servicePlaybackOffset + serviceIndex;
        if (currentUri != null && candidate >= 0 && candidate < queueEntries.size()) {
            Uri candidateUri = queueEntries.get(candidate).uri;
            if (currentUri.equals(candidateUri)) {
                return candidate;
            }
        } else if (currentUri == null && candidate >= 0 && candidate < queueEntries.size()) {
            return candidate;
        }

        if (currentUri != null) {
            int maxDistance = queueEntries.size();
            for (int distance = 1; distance <= maxDistance; distance++) {
                int lower = candidate - distance;
                if (lower >= 0 && lower < queueEntries.size() && currentUri.equals(queueEntries.get(lower).uri)) {
                    return lower;
                }
                int upper = candidate + distance;
                if (upper >= 0 && upper < queueEntries.size() && currentUri.equals(queueEntries.get(upper).uri)) {
                    return upper;
                }
            }
            for (int i = 0; i < queueEntries.size(); i++) {
                if (currentUri.equals(queueEntries.get(i).uri)) {
                    return i;
                }
            }
        }

        return (candidate >= 0 && candidate < queueEntries.size()) ? candidate : -1;
    }


    @Override
    public void onBackPressed() {
        if (canNavigateUpFromCurrentFolder()) {
            navigateUpFromCurrentFolder();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(playbackStateSyncRunnable);
        unregisterPlaybackStateReceiver();
        if (remoteQueueController != null) remoteQueueController.shutdown();
        // Preserve the app-scoped Bluetooth bridge across configuration changes (e.g. rotation);
        // only tear the connection down when the activity is genuinely finishing.
        btController.onActivityDestroyed(isChangingConfigurations());
        if (audioPreviewManager != null) audioPreviewManager.stopPreview();
        tagReadExecutor.shutdownNow();
        super.onDestroy();
    }

    private void registerPlaybackStateReceiver() {
        if (playbackReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(Service.ACTION_PLAYBACK_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(playbackStateReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(playbackStateReceiver, filter);
        }
        playbackReceiverRegistered = true;
    }

    private void unregisterPlaybackStateReceiver() {
        if (!playbackReceiverRegistered) return;
        try {
            unregisterReceiver(playbackStateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        playbackReceiverRegistered = false;
    }

    // -- data models ---------------------------------------------------------

    static final class FileEntry {
        final File   file;
        final Uri    uri;
        final String name;
        /** Primary sort bucket, precomputed once: 0 = directory, 1 = playlist, 2 = audio file. */
        final int sortRank;
        String sortDate;
        String sortGenre;
        String sortArtist;
        String sortTitle;
        int sortBpm;
        TagState sortDateState   = TagState.UNKNOWN;
        TagState sortGenreState  = TagState.UNKNOWN;
        TagState sortArtistState = TagState.UNKNOWN;
        TagState sortBpmState    = TagState.UNKNOWN;

        FileEntry(File file, String name, boolean isDirectory) {
            this.file        = file;
            this.uri         = Uri.fromFile(file);
            this.name        = name;
            this.sortRank    = computeSortRank(isDirectory, name);
        }

        FileEntry(Uri uri, String name, boolean isDirectory) {
            this.file        = null;
            this.uri         = uri;
            this.name        = name;
            this.sortRank    = computeSortRank(isDirectory, name);
        }

        boolean isDirectory() {
            return sortRank == 0;
        }

        private static int computeSortRank(boolean isDirectory, String name) {
            if (isDirectory) return 0;
            return isPlaylistFile(name) ? 1 : 2;
        }
    }

    static final class QueueEntry {
        private static int sNextId = 1;

        final int    id;
        final String name;
        final Uri    uri;
        boolean tagsCached;
        String  title;
        String  artist;
        String  date;
        String  genre;
        int     bpm;

        QueueEntry(String name, Uri uri) {
            this(name, uri, 0);
        }

        QueueEntry(String name, Uri uri, int storedId) {
            if (storedId > 0) {
                this.id = storedId;
                if (storedId >= sNextId) sNextId = storedId + 1;
            } else {
                this.id = sNextId++;
            }
            this.name = name;
            this.uri  = uri;
        }
    }

    // -- adapters ------------------------------------------------------------

    private static String formatRemainingMs(int positionMs, int durationMs) {
        int totalSecs = Math.max(0, durationMs - positionMs) / 1000;
        return String.format(Locale.US, "-%d:%02d", totalSecs / 60, totalSecs % 60);
    }

    private String buildMetaText(String date, String genre, int bpm) {
        String bpmText = bpm > 0 ? bpm + " BPM" : null;
        StringBuilder sb = new StringBuilder();
        if (fileSortMode == SORT_GENRE) {
            appendMeta(sb, date);
            appendMeta(sb, bpmText);
            appendMeta(sb, genre);
        } else if (fileSortMode == SORT_BPM) {
            appendMeta(sb, date);
            appendMeta(sb, genre);
            appendMeta(sb, bpmText);
        } else { // SORT_YEAR, SORT_ARTIST, SORT_FILENAME
            appendMeta(sb, bpmText);
            appendMeta(sb, genre);
            appendMeta(sb, date);
        }
        return sb.toString();
    }

    private static void appendMeta(StringBuilder sb, String part) {
        if (part == null || part.isEmpty()) return;
        if (sb.length() > 0) sb.append("  ");
        sb.append(part);
    }

    private static final class ViewHolder {
        final View content;
        final TextView icon;
        final TextView name;
        final TextView remainingTime;
        final View metaRow;
        final TextView artist;
        final TextView meta;
        final TextView hintStart;
        final View anchorMarker;
        ViewHolder(View v) {
            content = v.findViewById(R.id.swipe_content);
            icon = v.findViewById(R.id.file_icon);
            name = v.findViewById(R.id.file_name);
            remainingTime = v.findViewById(R.id.remaining_time);
            metaRow = v.findViewById(R.id.file_meta_row);
            artist = v.findViewById(R.id.file_artist);
            meta = v.findViewById(R.id.file_meta);
            hintStart = v.findViewById(R.id.swipe_hint_start);
            anchorMarker = v.findViewById(R.id.anchor_marker);
        }
    }

    private final class FileAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(FileBrowserQueueActivity.this);

        @Override public int  getCount()              { return filteredFileEntries.size(); }
        @Override public FileEntry getItem(int pos)   { return filteredFileEntries.get(pos); }
        @Override public long getItemId(int pos)      { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder vh;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_file_entry, parent, false);
                vh = new ViewHolder(convertView);
                convertView.setTag(vh);
            } else {
                vh = (ViewHolder) convertView.getTag();
            }

            FileEntry entry = filteredFileEntries.get(position);
            if (vh.anchorMarker != null) vh.anchorMarker.setVisibility(View.GONE);
            if (vh.hintStart != null) {
                if (entry.isDirectory()) {
                    vh.hintStart.setText("");
                } else {
                    vh.hintStart.setText((mode == Mode.REMOTE_SEND && !localQueueShownInRemoteMode)
                            ? R.string.swipe_hint_send : R.string.swipe_hint_queue);
                }
            }
            vh.icon.setText(entry.isDirectory() ? "\uD83D\uDCC1" : "\uD83C\uDFB5");
            vh.name.setText(fileSortMode != SORT_FILENAME && entry.sortTitle != null && !entry.sortTitle.isEmpty() ? entry.sortTitle : entry.name);
            String metaText = entry.isDirectory() ? "" :
                    buildMetaText(entry.sortDate, entry.sortGenre, entry.sortBpm);
            String artistText = entry.isDirectory() ? "" :
                    (entry.sortArtist != null ? entry.sortArtist : "");
            vh.artist.setText(artistText);
            vh.meta.setText(metaText);
            boolean hasSubtext = artistText.length() > 0 || metaText.length() > 0;
            vh.metaRow.setVisibility(hasSubtext ? View.VISIBLE : View.GONE);
            vh.name.setGravity(entry.isDirectory() && !hasSubtext ? Gravity.CENTER : Gravity.START);

            boolean isBrowseEntry = Service.sBrowseMode
                    && !entry.isDirectory()
                    && browseFileUri != null
                    && browseFileUri.equals(entry.uri);
            boolean isPreviewEntry = !entry.isDirectory()
                    && fileBrowserPreviewingEntryUri != null
                    && fileBrowserPreviewingEntryUri.equals(entry.uri);
            boolean hasProgress = isPreviewEntry
                    && fileBrowserPreviewingUri != null
                    && fileBrowserPreviewingUri.equals(entry.uri);
            if (isBrowseEntry && currentTrackDurationMs > 0) {
                vh.remainingTime.setText(formatRemainingMs(currentTrackPositionMs, currentTrackDurationMs));
                vh.remainingTime.setVisibility(View.VISIBLE);
            } else {
                vh.remainingTime.setVisibility(View.GONE);
            }
            if (isBrowseEntry) {
                float progress = 0f;
                if (currentTrackDurationMs > 0) {
                    progress = Math.min(1f, Math.max(0f,
                            currentTrackPositionMs / (float) currentTrackDurationMs));
                }
                applyProgressBackground(vh.content, progress, progressTrackColor, progressFillColor);
            } else if (isPreviewEntry) {
                float progress = 0f;
                if (hasProgress) {
                    long dur = SilenceStreamer.previewDurationMs;
                    if (dur > 0) {
                        progress = Math.min(1f, Math.max(0f,
                                SilenceStreamer.previewPositionMs / (float) dur));
                    }
                }
                applyProgressBackground(vh.content, progress, progressTrackColor, progressFillColor);
            } else {
                vh.content.setBackgroundColor(themeBackgroundColor);
            }

            return convertView;
        }
    }

    private final class QueueAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(FileBrowserQueueActivity.this);

        @Override public int  getCount()              { return queueEntries.size(); }
        @Override public QueueEntry getItem(int pos)  { return queueEntries.get(pos); }
        @Override public long getItemId(int pos)      { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder vh;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_file_entry, parent, false);
                vh = new ViewHolder(convertView);
                convertView.setTag(vh);
            } else {
                vh = (ViewHolder) convertView.getTag();
            }

            QueueEntry entry = queueEntries.get(position);
            vh.content.setTranslationX(0);
            convertView.setAlpha(draggingQueueIndex >= 0 && position == draggingQueueIndex ? 0f : 1.0f);

            boolean isAnchor = anchorEntryId > 0 && entry.id == anchorEntryId;
            if (vh.anchorMarker != null) {
                vh.anchorMarker.setVisibility(isAnchor ? View.VISIBLE : View.GONE);
                if (isAnchor) vh.anchorMarker.setBackgroundColor(progressFillColor);
            }

            boolean isCurrentTrack = position == currentPlayingQueueIndex
                    && isPlaybackActiveOrFading();
            if (isCurrentTrack) {
                float progress = 0f;
                if (currentTrackDurationMs > 0) {
                    progress = Math.min(1f,
                            Math.max(0f, currentTrackPositionMs / (float) currentTrackDurationMs));
                }
                applyProgressBackground(vh.content, progress, progressTrackColor, progressFillColor);
            } else {
                vh.content.setBackgroundColor(themeBackgroundColor);
            }
            if (isCurrentTrack && currentTrackDurationMs > 0) {
                vh.remainingTime.setText(formatRemainingMs(currentTrackPositionMs, currentTrackDurationMs));
                vh.remainingTime.setVisibility(View.VISIBLE);
            } else {
                vh.remainingTime.setVisibility(View.GONE);
            }
            vh.icon.setText("\uD83C\uDFB5");
            String displayName = entry.tagsCached && entry.title != null && !entry.title.isEmpty()
                    ? entry.title : entry.name;
            String artistText = entry.tagsCached && entry.artist != null ? entry.artist : "";
            String metaText   = entry.tagsCached
                    ? buildMetaText(entry.date, entry.genre, entry.bpm) : "";
            boolean hasSubtext = !artistText.isEmpty() || !metaText.isEmpty();
            vh.name.setText(displayName);
            vh.name.setGravity(Gravity.START);
            vh.artist.setText(artistText);
            vh.meta.setText(metaText);
            vh.metaRow.setVisibility(hasSubtext ? View.VISIBLE : View.GONE);
            return convertView;
        }
    }
}

