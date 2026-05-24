package com.shaforostoff.livequeueplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.Editable;
import android.text.TextWatcher;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final class SwipeState {
        float downX, downY;
        int startPosition = -1;
        boolean handled;
        View swipingView;
        View contentView;

        void resetView() {
            if (contentView != null) {
                contentView.setTranslationX(0);
                contentView = null;
            }
            swipingView = null;
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
    private static final String MUSIC_DIRECTORY_NAME = "Music";
    private static final String BROWSER_PREFS = "browser_prefs";
    private static final String PREF_LAST_TREE_URI = "last_tree_uri";
    private static final String PREF_SORT_MODE = "sort_mode";
    private static final long PLAYBACK_SYNC_INTERVAL_MS = 1_000L;
    private static final int PROGRESS_LEVEL_MAX = 10_000;
    private static final int SORT_FILENAME = 0;
    private static final int SORT_YEAR = 1;
    private static final int SORT_GENRE = 2;
    private static final int SORT_BPM = 3;
    private static final int SORT_ARTIST = 4;

    private static final String[] AUDIO_EXTENSIONS = {
            ".mp3", ".mp4", ".m4a", ".aac", ".ogg", ".flac",
            ".wav", ".wma", ".opus", ".m3u", ".m3u8", ".3gp", ".aiff", ".aif"
    };

    // -- file browser state -------------------------------------------------
    private final List<FileEntry> fileEntries = new ArrayList<>();
    private final List<FileEntry> filteredFileEntries = new ArrayList<>();
    private FileAdapter fileAdapter;
    private EditText fileFilterInput;
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
    private boolean playbackStopped = true;
    private boolean stopFadeInProgress;
    private boolean queueTransitionActive;
    private boolean browsingDocumentTree;
    private int currentPlayingQueueIndex = -1;
    private int draggingQueueIndex = -1;
    private int servicePlaybackOffset = 0;
    private Button stopButton;
    private Button sortButton;
    private Button openStorageButton;
    private Mode mode = Mode.DJ;

    private Uri browseFileUri;
    private boolean browseNextQueued;
    private Uri browseNextUri;
    private boolean browseTransitionActive;
    private FileEntry currentBrowsePlaylistEntry;
    private BluetoothController btController;
    private Button playButton;
    private Button saveButton;
    private CharSequence defaultPlayButtonText;
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
            playbackStopped = !isPlaying;
            boolean serviceFade = intent.getBooleanExtra(Service.EXTRA_FADE_OUT_IN_PROGRESS, false);
            if (serviceFade && !stopFadeInProgress) {
                stopFadeInProgress = true;
                applyStopButtonState();
            }

            if (serviceIndex < 0) {
                SilenceStreamer.reinitIfOutputChanged(FileBrowserQueueActivity.this);
                if (browseTransitionActive && !stopFadeInProgress) {
                    // Transient stop between sendStopNowCommand() and the new browse track starting.
                    // Keep browse state intact; the next broadcast will update us.
                    return;
                }
                if (queueTransitionActive && !stopFadeInProgress) {
                    return;
                }
                clearBrowseState();
                currentPlayingQueueIndex = -1;
                if (stopFadeInProgress) {
                    onFadeOutFinished();
                    return;
                }
                setPlaybackOffset(0);
                resetCurrentTrackProgress();
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
                int prevIndex = currentPlayingQueueIndex;
                currentPlayingQueueIndex = resolvePlayingQueueIndex(entryId, serviceIndex, currentUri);
                if (currentPlayingQueueIndex >= 0 && currentPlayingQueueIndex != prevIndex) {
                    queueList.smoothScrollToPosition(currentPlayingQueueIndex);
                }
            }

            if (queueAdapter != null) {
                queueAdapter.notifyDataSetChanged();
            }
            maybeQueueNextBrowseTrack();
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
    private Uri currentTreeUri;
    private final ArrayList<Uri> documentUriStack = new ArrayList<>();
    private File currentFileDirectory;
    private File currentFileRootDirectory;

    // ---------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser_queue);

        // -- initialize drag preview manager --------------------------------
        audioPreviewManager = new PreviewManager(this);

        // -- view references -------------------------------------------------
        fileFilterInput = findViewById(R.id.file_filter_input);
        queueEmptyHint  = findViewById(R.id.queue_empty_hint);

        fileBrowserList = findViewById(R.id.file_browser_list);
        queueList                = findViewById(R.id.queue_list);
        View     queueContainer  = findViewById(R.id.queue_container);
        if (getIntent().getBooleanExtra(EXTRA_BROWSE_MODE, false)) {
            mode = Mode.BROWSE;
        }

        playButton = findViewById(R.id.btn_play_queue);
        defaultPlayButtonText = playButton.getText();
        openStorageButton = findViewById(R.id.btn_open_storage);
        sortButton = findViewById(R.id.btn_sort_files);
        saveButton = findViewById(R.id.btn_save_queue);
        stopButton = findViewById(R.id.btn_stop_queue);
        metadataExtractor = new MetadataExtractor(getContentResolver());
        btController = new BluetoothController(this, new BluetoothController.Callback() {
            @Override
            public void onModeSelected() {
                if (getIntent().getBooleanExtra(EXTRA_REMOTE_QUEUE_FILL_MODE, false) && mode == Mode.DJ) {
                    mode = btController.isServerMode() ? Mode.REMOTE_RECEIVE : Mode.REMOTE_SEND;
                }
                applyPlayButtonModeState();
            }
            @Override
            public void onQueueRequestsReceived(List<BluetoothQueueBridge.TrackRequest> tracks) {
                onRemoteQueueRequestsReceived(tracks);
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
                fileBrowserList.setSelection(0);
            }
        });

        // -- file browser: tap to preview (when secondary output active) or add
        fileBrowserList.setOnItemClickListener((parent, view, position, id) -> {
            FileEntry entry = filteredFileEntries.get(position);
            if (entry.isDirectory) {
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
                }
            } else {
                boolean queueTrackPlaying = !playbackStopped && !Service.sBrowseMode && currentPlayingQueueIndex >= 0;
                if (hasBrowseBehavior() && !stopFadeInProgress && !(mode == Mode.BROWSE && queueTrackPlaying)) {
                    playBrowseFile(entry);
                } else {
                    addToQueue(entry.name, entry.uri);
                }
            }
        });
        installFileBrowserSwipeAdd(fileBrowserList);

        // -- queue: tap item to play when stopped ----------------------------
        queueList.setOnItemClickListener((parent, view, position, id) -> {
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
            if (stopFadeInProgress) {
                playQueueFrom(position, true);
            } else if (playbackStopped) {
                playQueueFrom(position);
            } else {
                Toast.makeText(this, R.string.stop_playback_first, Toast.LENGTH_SHORT).show();
                //Toast.makeText(this, "Swipe right: remove. Stop playback to play this track", Toast.LENGTH_SHORT).show();
            }
        });
        installQueueGestureHandler(queueList);

        // -- navigation & playback buttons -----------------------------------
        playButton.setOnClickListener(v -> {
            if (hasBrowseBehavior()) {
                clearQueueAndStopPlayback();
                return;
            }
            if (stopFadeInProgress) {
                cancelFadeOutAndContinue();
            } else if (!Service.sIsPlaying && Service.sCurrentUri != null) {
                // A track is paused (e.g. after audio focus loss): resume it
                Intent resumeIntent = new Intent(this, Service.class);
                resumeIntent.putExtra(Launcher.TYPE, Launcher.PLAY);
                startService(resumeIntent);
            } else {
                playQueue();
            }
        });
        applyPlayButtonModeState();

        if (getIntent().getBooleanExtra(EXTRA_REMOTE_QUEUE_FILL_MODE, false)) {
            int serverMode = getIntent().getIntExtra(EXTRA_REMOTE_QUEUE_SERVER_MODE, -1);
            if (serverMode == 1) {
                mode = Mode.REMOTE_RECEIVE;
                btController.startRemoteSetupAsServer();
            } else if (serverMode == 0) {
                mode = Mode.REMOTE_SEND;
                btController.startRemoteSetupAsClient();
            } else {
                btController.startRemoteSetup(); // mode set in onModeSelected callback
            }
        }

        openStorageButton.setOnClickListener(v -> handleStorageButtonPressed());
        sortButton.setOnClickListener(v -> showSortDialog());
        fileSortMode = getSharedPreferences(BROWSER_PREFS, MODE_PRIVATE).getInt(PREF_SORT_MODE, SORT_FILENAME);
        applySaveButtonModeState();
        applySortButtonLoadingState();

        stopButton.setOnClickListener(v -> {
            if (hasBrowseBehavior() || Service.sBrowseMode) {
                stopPlaybackImmediately();
            } else {
                stopPlaybackWithFadeout();
            }
        });


        // -- kick off storage permission + browse ----------------------------
        if (!restorePersistedDocumentTree()) {
            requestPermissionsAndBrowse();
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

        rememberLastTreeUri(treeUri);

        


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
        Uri rememberedTreeUri = getRememberedTreeUri();
        if (rememberedTreeUri != null
                && hasReadPermissionForUri(rememberedTreeUri)
                && openDocumentTree(rememberedTreeUri)) {
            return true;
        }

        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            Uri uri = permission.getUri();
            if (permission.isReadPermission() && openDocumentTree(uri)) {
                rememberLastTreeUri(uri);
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
        clearDocumentBrowsingState();

        // Prefer the Music folder, fall back to root of external storage
        File musicDir   = getMusicDirectoryCompat();
        File storageDir = Environment.getExternalStorageDirectory();

        if (musicDir != null && musicDir.exists() && musicDir.canRead()) {
            currentFileRootDirectory = musicDir;
            navigateTo(musicDir);
        } else if (storageDir != null && storageDir.exists() && storageDir.canRead()) {
            currentFileRootDirectory = storageDir;
            navigateTo(storageDir);
        } else {
            currentFileRootDirectory = null;
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
        if (browsingDocumentTree) {
            return documentUriStack.size() > 1;
        }
        if (currentFileDirectory == null || currentFileRootDirectory == null) {
            return false;
        }
        return !sameFileLocation(currentFileDirectory, currentFileRootDirectory);
    }

    private void navigateUpFromCurrentFolder() {
        if (currentBrowsePlaylistEntry != null) { exitPlaylistBrowseFolder(); return; }
        clearFileFilterInput();
        if (browsingDocumentTree) {
            navigateDocumentUp();
            return;
        }
        if (currentFileDirectory == null || currentFileRootDirectory == null) {
            return;
        }
        if (sameFileLocation(currentFileDirectory, currentFileRootDirectory)) {
            return;
        }
        File parent = currentFileDirectory.getParentFile();
        if (parent != null) {
            navigateTo(parent);
        }
    }

    private void navigateTo(File dir) {
        currentBrowsePlaylistEntry = null;
        resetFileBrowserPreview();
        clearFileFilterInput();
        browsingDocumentTree = false;
        currentFileDirectory = dir;
        if (currentFileRootDirectory == null) {
            currentFileRootDirectory = dir;
        }
        fileEntriesVersion++;
        fileEntries.clear();

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            applyFileFilter();
            scrollToHighlightedFileEntry();
            return;
        }

        // Directories first, then files; both sorted alphabetically
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory())
                return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (File f : files) {
            if (f.getName().startsWith(".")) continue; // skip hidden
            if (f.isDirectory()) {
                fileEntries.add(new FileEntry(f, f.getName(), true));
            } else if (isAudioFile(f.getName())) {
                fileEntries.add(new FileEntry(f, f.getName(), false));
            }
        }
        sortFileEntriesInPlace();
        applyFileFilter();
        scrollToHighlightedFileEntry();
    }

    private void openStoragePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        Uri initialTreeUri = getRememberedTreeUri();
        if (initialTreeUri != null && hasReadPermissionForUri(initialTreeUri)) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialTreeUri);
        } else {
            // First use: point picker at SD card so the user can find it easily.
            Uri sdCardUri = findSdCardDocumentUri();
            if (sdCardUri != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, sdCardUri);
            }
        }
        startActivityForResult(intent, TREE_REQUEST_CODE);
    }

    private Uri findSdCardDocumentUri() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;
        StorageManager sm = getSystemService(StorageManager.class);
        if (sm == null) return null;
        for (StorageVolume vol : sm.getStorageVolumes()) {
            if (!vol.isRemovable()) continue;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    Intent volIntent = vol.createOpenDocumentTreeIntent();
                    return volIntent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI);
                } catch (Exception ignored) {}
            } else {
                String uuid = vol.getUuid();
                if (uuid != null) {
                    return Uri.parse("content://com.android.externalstorage.documents/tree/"
                            + Uri.encode(uuid + ":"));
                }
            }
        }
        return null;
    }

    private boolean hasReadPermissionForUri(Uri treeUri) {
        if (treeUri == null) {
            return false;
        }
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission() && treeUri.equals(permission.getUri())) {
                return true;
            }
        }
        return false;
    }

    private void rememberLastTreeUri(Uri treeUri) {
        if (treeUri == null) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(BROWSER_PREFS, MODE_PRIVATE);
        prefs.edit().putString(PREF_LAST_TREE_URI, treeUri.toString()).apply();
    }

    private void clearBrowseState() {
        browseTransitionActive = false;
        browseFileUri = null;
        browseNextQueued = false;
        browseNextUri = null;
    }

    private Uri getRememberedTreeUri() {
        SharedPreferences prefs = getSharedPreferences(BROWSER_PREFS, MODE_PRIVATE);
        String uriString = prefs.getString(PREF_LAST_TREE_URI, null);
        if (uriString == null || uriString.isEmpty()) {
            return null;
        }
        try {
            return Uri.parse(uriString);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean openDocumentTree(Uri treeUri) {
        if (treeUri == null) {
            return false;
        }

        try {
            Uri rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            clearDocumentBrowsingState();
            currentTreeUri = treeUri;
            browsingDocumentTree = true;
            documentUriStack.add(rootDocumentUri);
            return browseCurrentDocumentDirectory();
        } catch (Exception ignored) {
            clearDocumentBrowsingState();
            return false;
        }
    }

    private void navigateToDocumentEntry(Uri documentUri, String documentName) {
        if (!browsingDocumentTree || documentUri == null) {
            return;
        }

        resetFileBrowserPreview();
        clearFileFilterInput();
        documentUriStack.add(documentUri);
        if (!browseCurrentDocumentDirectory()) {
            documentUriStack.remove(documentUriStack.size() - 1);
        }
    }

    private void navigateDocumentUp() {
        if (!browsingDocumentTree) {
            return;
        }

        resetFileBrowserPreview();
        clearFileFilterInput();
        if (documentUriStack.size() > 1) {
            documentUriStack.remove(documentUriStack.size() - 1);
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
    }

    private boolean browseCurrentDocumentDirectory() {
        currentBrowsePlaylistEntry = null;
        if (currentTreeUri == null || documentUriStack.isEmpty()) {
            return false;
        }

        fileEntriesVersion++;
        fileEntries.clear();

        Uri currentDocumentUri = documentUriStack.get(documentUriStack.size() - 1);
        String documentId = DocumentsContract.getDocumentId(currentDocumentUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(currentTreeUri, documentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                applyFileFilter();
                return true;
            }

            while (cursor.moveToNext()) {
                String childDocumentId = cursor.getString(0);
                String childName = cursor.getString(1);
                String mimeType = cursor.getString(2);
                if (childName == null || childName.startsWith(".")) {
                    continue;
                }

                boolean isDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
                if (!isDirectory && !isAudioDocument(childName, mimeType)) {
                    continue;
                }

                Uri childDocumentUri = DocumentsContract.buildDocumentUriUsingTree(currentTreeUri, childDocumentId);
                fileEntries.add(new FileEntry(childDocumentUri, childName, isDirectory));
            }

            sortFileEntriesInPlace();
            applyFileFilter();
            scrollToHighlightedFileEntry();
            return true;
        } catch (Exception ignored) {
            fileEntries.clear();
            applyFileFilter();
            return false;
        }
    }

    private boolean isAudioDocument(String name, String mimeType) {
        if (mimeType != null) {
            if (mimeType.startsWith("audio/")) {
                return true;
            }
            if ("application/vnd.apple.mpegurl".equals(mimeType)
                    || "audio/x-mpegurl".equals(mimeType)
                    || "application/x-mpegurl".equals(mimeType)) {
                return true;
            }
        }
        return isAudioFile(name);
    }

    private void clearDocumentBrowsingState() {
        browsingDocumentTree = false;
        currentTreeUri = null;
        documentUriStack.clear();
        currentFileDirectory = null;
        currentFileRootDirectory = null;
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
                        (dialog, which) -> saveQueueAsM3u8(input.getText() == null
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
        if (browsingDocumentTree) {
            return !documentUriStack.isEmpty() && currentTreeUri != null;
        }
        return currentFileDirectory != null;
    }

    private void saveQueueAsM3u8(String rawName) {
        String fileName = normalizePlaylistFileName(rawName);
        if (fileName == null) {
            Toast.makeText(this, R.string.save_queue_invalid_name, Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder playlist = new StringBuilder("#EXTM3U\n");
        int exportedCount = 0;
        for (QueueEntry entry : queueEntries) {
            String relativePath = resolveQueueEntryRelativePath(entry.uri);
            if (relativePath == null || relativePath.length() == 0) {
                continue;
            }
            playlist.append(relativePath).append('\n');
            exportedCount++;
        }

        if (exportedCount == 0) {
            Toast.makeText(this,
                    "No queue entries can be written as relative paths from this folder",
                    Toast.LENGTH_LONG).show();
            return;
        }

        boolean saved = browsingDocumentTree
                ? writePlaylistToDocumentFolder(fileName, playlist.toString())
                : writePlaylistToFileFolder(fileName, playlist.toString());
        if (saved) {
            refreshCurrentFolderListing();
            Toast.makeText(this,
                    getString(R.string.save_queue_success) + ": " + fileName,
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.save_queue_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void refreshCurrentFolderListing() {
        if (browsingDocumentTree) {
            browseCurrentDocumentDirectory();
            return;
        }
        if (currentFileDirectory != null) {
            navigateTo(currentFileDirectory);
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

    private boolean writePlaylistToFileFolder(String fileName, String content) {
        if (currentFileRootDirectory == null) {
            return false;
        }

        File target = new File(currentFileRootDirectory, fileName);
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(target, false), "UTF-8"))) {
            writer.write(content);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean writePlaylistToDocumentFolder(String fileName, String content) {
        if (currentTreeUri == null || documentUriStack.isEmpty()) {
            return false;
        }

        Uri currentFolderUri = documentUriStack.get(0);
        Uri targetUri = findDocumentChildByName(currentFolderUri, fileName);
        if (targetUri == null) {
            try {
                targetUri = DocumentsContract.createDocument(
                        getContentResolver(),
                        currentFolderUri,
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

    private Uri findDocumentChildByName(Uri parentDocumentUri, String childName) {
        if (currentTreeUri == null) {
            return null;
        }

        try {
            String parentDocumentId = DocumentsContract.getDocumentId(parentDocumentUri);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(currentTreeUri, parentDocumentId);
            String[] projection = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
            };
            try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
                if (cursor == null) {
                    return null;
                }
                while (cursor.moveToNext()) {
                    String documentId = cursor.getString(0);
                    String displayName = cursor.getString(1);
                    if (displayName != null && displayName.equals(childName)) {
                        return DocumentsContract.buildDocumentUriUsingTree(currentTreeUri, documentId);
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String resolveQueueEntryRelativePath(Uri entryUri) {
        if (entryUri == null) {
            return null;
        }
        return browsingDocumentTree
                ? resolveRelativeDocumentPath(entryUri)
                : resolveRelativeFilePath(entryUri);
    }

    private String resolveRelativeFilePath(Uri entryUri) {
        if (currentFileRootDirectory == null) {
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
            basePath = currentFileRootDirectory.getCanonicalPath();
            resolvedTargetPath = new File(targetPath).getCanonicalPath();
        } catch (Exception ignored) {
            basePath = currentFileRootDirectory.getAbsolutePath();
            resolvedTargetPath = targetPath;
        }

        File targetFile = new File(resolvedTargetPath);
        File parent = targetFile.getParentFile();
        if (parent != null && parent.getAbsolutePath().equals(basePath)) {
            return targetFile.getName();
        }

        return computeRelativePath(basePath, resolvedTargetPath);
    }

    private String resolveRelativeDocumentPath(Uri entryUri) {
        if (documentUriStack.isEmpty() || currentTreeUri == null) {
            return null;
        }

        try {
            Uri currentFolderUri = documentUriStack.get(0);
            String currentDocumentId = DocumentsContract.getDocumentId(currentFolderUri);
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

    private boolean sameFileLocation(File left, File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (Exception ignored) {
            return left.getAbsolutePath().equals(right.getAbsolutePath());
        }
    }

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
            if (entry.isDirectory || entry.sortDateState == TagState.RESOLVED) {
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
        if (left.isDirectory != right.isDirectory) {
            return left.isDirectory ? -1 : 1;
        }
        if (left.isDirectory) {
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
        final ArrayList<FileEntry> pendingEntries = new ArrayList<>();
        boolean cacheApplied = false;
        for (FileEntry entry : fileEntries) {
            if (entry.isDirectory ||
                    (entry.sortDateState == TagState.RESOLVED && entry.sortGenreState == TagState.RESOLVED
                            && entry.sortArtistState == TagState.RESOLVED && entry.sortBpmState == TagState.RESOLVED)
                    || entry.sortDateState == TagState.LOADING || entry.sortGenreState == TagState.LOADING
                    || entry.sortArtistState == TagState.LOADING || entry.sortBpmState == TagState.LOADING) {
                continue;
            }

            if (entry.uri != null && metadataExtractor.isAllTagsCached(entry.uri)) {
                MetadataExtractor.TagEntry tags = metadataExtractor.readSortTags(entry.uri);
                entry.sortDate = tags.date;
                entry.sortGenre = tags.genre;
                entry.sortArtist = tags.artist;
                entry.sortTitle = tags.title;
                entry.sortBpm = tags.bpm;
                entry.sortDateState   = TagState.RESOLVED;
                entry.sortGenreState  = TagState.RESOLVED;
                entry.sortArtistState = TagState.RESOLVED;
                entry.sortBpmState    = TagState.RESOLVED;
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
            Collections.sort(fileEntries, this::compareFileEntries);
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
                                if (isTagSortMode(fileSortMode)) {
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
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
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

        String query = fileFilterQuery == null ? "" : fileFilterQuery.trim().toLowerCase();
        if (query.length() == 0) {
            filteredFileEntries.addAll(fileEntries);
        } else {
            for (FileEntry entry : fileEntries) {
                if (entry.name.toLowerCase().contains(query)) {
                    filteredFileEntries.add(entry);
                }
            }
        }

        if (fileAdapter != null) {
            fileAdapter.notifyDataSetChanged();
        }
        updateStorageButtonState();
    }

    private boolean scrollToHighlightedFileEntry() {
        if (fileBrowserList == null) return false;
        Uri highlightedUri = null;
        if (Service.sBrowseMode && browseFileUri != null) {
            highlightedUri = browseFileUri;
        } else if (fileBrowserPreviewingEntryUri != null) {
            highlightedUri = fileBrowserPreviewingEntryUri;
        }
        if (highlightedUri == null) return false;
        for (int i = 0; i < filteredFileEntries.size(); i++) {
            if (highlightedUri.equals(filteredFileEntries.get(i).uri)) {
                int first = fileBrowserList.getFirstVisiblePosition();
                int last = fileBrowserList.getLastVisiblePosition();
                if (Math.abs(i - first) > 16 && Math.abs(i - last) > 16) {
                    fileBrowserList.setSelection(i);
                } else {
                    fileBrowserList.smoothScrollToPosition(i);
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isAudioFile(String name) {
        String lower = name.toLowerCase();
        for (String ext : AUDIO_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static boolean isPlaylistFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".m3u") || lower.endsWith(".m3u8");
    }

    private int addPlaylistToQueue(FileEntry playlistEntry) {
        ArrayList<QueueEntry> resolvedEntries = new ArrayList<>();
        try (InputStream stream = getContentResolver().openInputStream(playlistEntry.uri)) {
            if (stream == null) {
                return 0;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.length() == 0 || trimmed.startsWith("#")) {
                        continue;
                    }
                    Uri resolvedUri = resolvePlaylistTargetUri(playlistEntry, trimmed);
                    if (resolvedUri == null) {
                        continue;
                    }
                    String displayName = getDisplayNameForPlaylistItem(trimmed, resolvedUri);
                    resolvedEntries.add(new QueueEntry(displayName, resolvedUri));
                }
            }
        } catch (Exception ignored) {
            return 0;
        }

        addToQueue(resolvedEntries);
        return resolvedEntries.size();
    }

    private Uri resolvePlaylistTargetUri(FileEntry playlistEntry, String pathValue) {
        Uri parsed = Uri.parse(pathValue);
        if (parsed.getScheme() != null) {
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
            return null;
        }

        return resolveDocumentPlaylistTargetUri(playlistEntry.uri, pathValue);
    }

    private Uri resolveDocumentPlaylistTargetUri(Uri playlistUri, String pathValue) {
        if (currentTreeUri == null || playlistUri == null) {
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
            Uri targetUri = DocumentsContract.buildDocumentUriUsingTree(currentTreeUri, targetDocumentId);
            return documentExists(targetUri) ? targetUri : null;
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

    private boolean documentExists(Uri documentUri) {
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID};
        try (Cursor cursor = getContentResolver().query(documentUri, projection, null, null, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
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

    private void ensureQueueTagsCachedAsync() {
        List<QueueEntry> uncached = new ArrayList<>();
        for (QueueEntry entry : queueEntries) {
            if (!entry.tagsCached && entry.uri != null)
                uncached.add(entry);
        }
        if (uncached.isEmpty()) return;
        int threadCount = Math.min(4, uncached.size());
        AtomicInteger pending = new AtomicInteger(uncached.size());
        MetadataExtractor.TagEntry[] results = new MetadataExtractor.TagEntry[uncached.size()];
        AtomicInteger workQueue = new AtomicInteger(0);
        for (int t = 0; t < threadCount; t++) {
            tagReadExecutor.submit(() -> {
                int idx;
                while ((idx = workQueue.getAndIncrement()) < uncached.size()) {
                    results[idx] = metadataExtractor.readSortTags(uncached.get(idx).uri);
                    if (pending.decrementAndGet() == 0) {
                        runOnUiThread(() -> {
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
                        });
                    }
                }
            });
        }
    }

    private void addToQueue(List<QueueEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        queueEntries.addAll(entries);
        queueAdapter.notifyDataSetChanged();
        queueList.smoothScrollToPosition(queueEntries.size() - 1);
        updateQueueHint();
        persistQueue();
        ensureQueueTagsCachedAsync();

        // Keep the running service queue aligned with the visible queue.
        if (!playbackStopped && !stopFadeInProgress) {
            syncServicePendingQueue();
        }
    }

    private boolean removeQueueAt(int position) {
        if (position < 0 || position >= queueEntries.size()) return false;
        if (position == currentPlayingQueueIndex && isPlaybackActiveOrFading()) {
            return false;
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
        if (!playbackStopped && !stopFadeInProgress) {
            syncServicePendingQueue();
        }
        return true;
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

        Intent clearIntent = new Intent(this, Service.class);
        clearIntent.putExtra(Launcher.TYPE, Launcher.CLEAR_QUEUE);
        startService(clearIntent);

        int nextIndex = currentPlayingQueueIndex + 1;
        if (nextIndex >= queueEntries.size()) {
            return;
        }

        ArrayList<Uri> pendingUris = new ArrayList<>(queueEntries.size() - nextIndex);
        for (int i = nextIndex; i < queueEntries.size(); i++) {
            pendingUris.add(queueEntries.get(i).uri);
        }
        Intent appendIntent = new Intent(this, Service.class);
        appendIntent.putExtra(Launcher.TYPE, Launcher.APPEND_QUEUE);
        appendIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, pendingUris);
        startService(appendIntent);
    }

    private void restorePersistedQueue() {
        queueEntries.clear();
        ArrayList<QueueStore.Entry> persisted = QueueStore.load(this);
        for (QueueStore.Entry entry : persisted) {
            queueEntries.add(new QueueEntry(entry.name, entry.uri, entry.id));
        }
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

    private void installQueueGestureHandler(ListView list) {
        SwipeState swipeState = new SwipeState();
        DragState dragState = new DragState();
        float verticalSlop = 40f * getResources().getDisplayMetrics().density;
        float horizontalSlop = 10f * getResources().getDisplayMetrics().density;
        Runnable[] longPressRunnable = {null};

        list.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    swipeState.downX = event.getX();
                    swipeState.downY = event.getY();
                    swipeState.startPosition = list.pointToPosition((int) event.getX(), (int) event.getY());
                    swipeState.handled = false;
                    swipeState.swipingView = null;
                    swipeState.contentView = null;
                    dragState.reset();
                    if (longPressRunnable[0] != null) {
                        uiHandler.removeCallbacks(longPressRunnable[0]);
                        longPressRunnable[0] = null;
                    }
                    if (swipeState.startPosition >= 0) {
                        int firstVisible = list.getFirstVisiblePosition();
                        int childIndex = swipeState.startPosition - firstVisible;
                        if (childIndex >= 0 && childIndex < list.getChildCount()
                                && swipeState.startPosition != currentPlayingQueueIndex) {
                            swipeState.swipingView = list.getChildAt(childIndex);
                            swipeState.contentView = swipeState.swipingView.findViewById(R.id.swipe_content);
                            if (swipeState.contentView == null) swipeState.contentView = swipeState.swipingView;
                            TextView qHintStart = swipeState.swipingView.findViewById(R.id.swipe_hint_start);
                            if (qHintStart != null) qHintStart.setText(R.string.swipe_hint_remove);
                            TextView qHintEnd = swipeState.swipingView.findViewById(R.id.swipe_hint_end);
                            if (qHintEnd != null) qHintEnd.setText(mode == Mode.REMOTE_SEND ? getString(R.string.swipe_hint_send) : "");
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
                                    ghost.setElevation(8f * getResources().getDisplayMetrics().density);
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

                    if (Math.abs(dx) > horizontalSlop || Math.abs(dy) > verticalSlop) {
                        if (longPressRunnable[0] != null) {
                            uiHandler.removeCallbacks(longPressRunnable[0]);
                            longPressRunnable[0] = null;
                        }
                    }
                    if (Math.abs(dy) > verticalSlop && Math.abs(dy) > Math.abs(dx)) {
                        swipeState.resetView();
                        swipeState.startPosition = -1;
                        return false;
                    }
                    if (dx > 0 && swipeState.swipingView != null) {
                        swipeState.contentView.setTranslationX(Math.min(dx, swipeState.contentView.getWidth()));
                        list.getParent().requestDisallowInterceptTouchEvent(true);
                        if (swipeState.contentView.getWidth() > 0 && dx >= swipeState.contentView.getWidth() / 2f) {
                            swipeState.handled = true;
                            swipeState.resetView();
                            removeQueueAt(swipeState.startPosition);
                        }
                        return true;
                    }
                    if (dx < 0 && swipeState.swipingView != null && mode == Mode.REMOTE_SEND) {
                        swipeState.contentView.setTranslationX(Math.max(dx, -swipeState.contentView.getWidth()));
                        list.getParent().requestDisallowInterceptTouchEvent(true);
                        if (swipeState.contentView.getWidth() > 0 && Math.abs(dx) >= swipeState.contentView.getWidth() / 2f) {
                            swipeState.handled = true;
                            swipeState.resetView();
                            int pos = swipeState.startPosition;
                            if (pos >= 0 && pos < queueEntries.size()) {
                                QueueEntry entry = queueEntries.get(pos);
                                if (btController.sendQueueRequest(entry.name, getParentFolderName(entry.uri))) {
                                    Toast.makeText(this, R.string.track_request_sent, Toast.LENGTH_SHORT).show();
                                }
                            }
                        }
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
                        if (!playbackStopped && !stopFadeInProgress) {
                            syncServicePendingQueue();
                        }
                        return true;
                    }
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
        float verticalSlop = 40f * getResources().getDisplayMetrics().density;
        list.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    state.downX = event.getX();
                    state.downY = event.getY();
                    state.startPosition = list.pointToPosition((int) event.getX(), (int) event.getY());
                    state.handled = false;
                    state.swipingView = null;
                    state.contentView = null;
                    if (state.startPosition >= 0) {
                        int firstVisible = list.getFirstVisiblePosition();
                        int childIndex = state.startPosition - firstVisible;
                        if (childIndex >= 0 && childIndex < list.getChildCount()
                                && (canSwipe == null || canSwipe.test(state.startPosition))) {
                            state.swipingView = list.getChildAt(childIndex);
                            state.contentView = state.swipingView.findViewById(R.id.swipe_content);
                            if (state.contentView == null) state.contentView = state.swipingView;
                            if (rightHint != null) {
                                TextView tv = state.swipingView.findViewById(R.id.swipe_hint_start);
                                if (tv != null) tv.setText(rightHint);
                            }
                            if (leftHint != null) {
                                TextView tv = state.swipingView.findViewById(R.id.swipe_hint_end);
                                if (tv != null) tv.setText(leftHint);
                            }
                        }
                    }
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (state.handled || state.startPosition < 0) return state.handled;
                    float dx = event.getX() - state.downX;
                    float dy = event.getY() - state.downY;
                    if (Math.abs(dy) > verticalSlop && Math.abs(dy) > Math.abs(dx)) {
                        state.resetView();
                        state.startPosition = -1;
                        return false;
                    }
                    if (dx > 0 && state.swipingView != null) {
                        state.contentView.setTranslationX(Math.min(dx, state.contentView.getWidth()));
                        list.getParent().requestDisallowInterceptTouchEvent(true);
                        if (state.contentView.getWidth() > 0 && dx >= state.contentView.getWidth() / 2f) {
                            state.handled = true;
                            state.resetView();
                            onRightSwipe.onSwipe(state.startPosition);
                        }
                        return true;
                    }
                    if (dx < 0 && onLeftSwipe != null && state.swipingView != null) {
                        state.contentView.setTranslationX(Math.max(dx, -state.contentView.getWidth()));
                        list.getParent().requestDisallowInterceptTouchEvent(true);
                        if (state.contentView.getWidth() > 0 && Math.abs(dx) >= state.contentView.getWidth() / 2f) {
                            state.handled = true;
                            state.resetView();
                            onLeftSwipe.onSwipe(state.startPosition);
                        }
                        return true;
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
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
            pos -> pos < filteredFileEntries.size() && !filteredFileEntries.get(pos).isDirectory,
            "Queue →", null,
            position -> {
                if (position >= filteredFileEntries.size()) return;
                FileEntry entry = filteredFileEntries.get(position);
                if (entry.isDirectory) return;
                if (isPlaylistFile(entry.name)) {
                    int addedCount = addPlaylistToQueue(entry);
                    if (addedCount > 0)
                        Toast.makeText(this, getString(R.string.added_files_from_playlist, addedCount, entry.name), Toast.LENGTH_SHORT).show();
                    else
                        Toast.makeText(this, getString(R.string.no_playable_files_in_playlist, entry.name), Toast.LENGTH_SHORT).show();
                } else {
                    addToQueue(entry.name, entry.uri);
                }
            },
            null
        );
    }

    private Uri resolveFirstPlaylistUri(FileEntry playlistEntry) {
        try (InputStream stream = getContentResolver().openInputStream(playlistEntry.uri)) {
            if (stream == null) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    Uri uri = resolvePlaylistTargetUri(playlistEntry, trimmed);
                    if (uri != null) return uri;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void enterPlaylistAsBrowseFolder(FileEntry playlistEntry) {
        List<FileEntry> tracks = new ArrayList<>();
        try (InputStream stream = getContentResolver().openInputStream(playlistEntry.uri)) {
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                        Uri uri = resolvePlaylistTargetUri(playlistEntry, trimmed);
                        if (uri == null) continue;
                        tracks.add(new FileEntry(uri, getDisplayNameForPlaylistItem(trimmed, uri), false));
                    }
                }
            }
        } catch (Exception ignored) {}

        if (tracks.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_playable_files_in_playlist, playlistEntry.name), Toast.LENGTH_SHORT).show();
            return;
        }

        clearFileFilterInput();
        fileEntriesVersion++;
        fileEntries.clear();
        fileEntries.addAll(tracks);
        currentBrowsePlaylistEntry = playlistEntry;
        applyFileFilter();
        scrollToHighlightedFileEntry();
        updateStorageButtonState();
    }

    private void exitPlaylistBrowseFolder() {
        currentBrowsePlaylistEntry = null;
        if (browsingDocumentTree) {
            browseCurrentDocumentDirectory();
        } else if (currentFileDirectory != null) {
            navigateTo(currentFileDirectory);
        }
    }

    private void updateQueueHint() {
        if (queueEntries.isEmpty()) {
            int hintRes;
            if (mode == Mode.REMOTE_SEND) {
                hintRes = R.string.queue_hint_remote;
            } else if (mode == Mode.BROWSE) {
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

        int count = queueEntries.size() - position;
        ArrayList<Uri> uris = new ArrayList<>(count);
        int[] ids = new int[count];
        for (int i = position; i < queueEntries.size(); i++) {
            QueueEntry e = queueEntries.get(i);
            uris.add(e.uri);
            ids[i - position] = e.id;
        }

        Intent intent = new Intent(this, Launcher.class);
        intent.setAction(ACTION_SEND_MULTIPLE_COMPAT);
        intent.setType("audio/*");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.putExtra(Service.EXTRA_ENTRY_IDS, ids);
        if (forceImmediateRestart) {
            queueTransitionActive = true;
            sendStopNowCommand();
        }
        resetStopButtonState();
        startActivity(intent);
        playbackStopped = false;
        setPlaybackOffset(position);
        currentPlayingQueueIndex = position;
        currentTrackPositionMs = 0;
        currentTrackDurationMs = 0;
        queueAdapter.notifyDataSetChanged();
    }

    private void stopPlaybackWithFadeout() {
        if (stopFadeInProgress) {
            return;
        }

        // Do not show fading UI when nothing is currently playing.
        if (playbackStopped || (currentPlayingQueueIndex < 0 && !Service.sBrowseMode)) {
            resetStopButtonState();
            return;
        }

        Intent intent = new Intent(this, Service.class);
        intent.putExtra(Launcher.TYPE, Launcher.STOP);
        startService(intent);
        playbackStopped = true;
        showStopButtonFadingState();
    }

    private void cancelFadeOutAndContinue() {
        Intent intent = new Intent(this, Service.class);
        intent.putExtra(Launcher.TYPE, Launcher.PLAY);
        startService(intent);

        playbackStopped = false;
        resetStopButtonState();
        queueAdapter.notifyDataSetChanged();
    }

    private void sendStopNowCommand() {
        Intent intent = new Intent(this, Service.class);
        intent.putExtra(Launcher.TYPE, Launcher.KILL);
        startService(intent);
    }

    private void stopPlaybackImmediately() {
        sendStopNowCommand();
        applyStoppedState();
    }

    private void applyStoppedState() {
        playbackStopped = true;
        stopFadeInProgress = false;
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
        queueAdapter.notifyDataSetChanged();
        updateQueueHint();
        QueueStore.clear(this);
    }

    private void playBrowseFile(FileEntry entry) {
        Uri uri = isPlaylistFile(entry.name) ? resolveFirstPlaylistUri(entry) : entry.uri;
        if (uri == null) return;

        ArrayList<Uri> uris = new ArrayList<>(1);
        uris.add(uri);

        Intent intent = new Intent(this, Launcher.class);
        intent.setAction(ACTION_SEND_MULTIPLE_COMPAT);
        intent.setType("audio/*");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.putExtra(Service.EXTRA_BROWSE_MODE, true);

        browseTransitionActive = true;
        sendStopNowCommand();
        resetStopButtonState();
        startActivity(intent);

        browseFileUri = uri;
        browseNextQueued = false;
        browseNextUri = null;
        playbackStopped = false;
        currentPlayingQueueIndex = -1;
        queueAdapter.notifyDataSetChanged();
        fileAdapter.notifyDataSetChanged();
    }

    private void maybeQueueNextBrowseTrack() {
        if (!Service.sBrowseMode || browseNextQueued || browseFileUri == null) return;
        if (currentTrackDurationMs <= 0 || currentTrackDurationMs - currentTrackPositionMs > 5_000) return;
        int currentPos = -1;
        for (int i = 0; i < filteredFileEntries.size(); i++) {
            FileEntry e = filteredFileEntries.get(i);
            if (!e.isDirectory && browseFileUri.equals(e.uri)) {
                currentPos = i;
                break;
            }
        }
        if (currentPos < 0) return;
        for (int i = currentPos + 1; i < filteredFileEntries.size(); i++) {
            FileEntry e = filteredFileEntries.get(i);
            if (!e.isDirectory && !isPlaylistFile(e.name)) {
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

    private String getParentFolderName(Uri uri) {
        if (uri == null) return "";
        if ("content".equals(uri.getScheme())) {
            // For SAF URIs, the document ID encodes the real path (e.g. "primary:Music/Artist/Song.mp3").
            // uri.getPath() decodes %2F separators and prepends the tree segment, making naive
            // slash-splitting unreliable for files at the tree root.
            try {
                String docId = DocumentsContract.getDocumentId(uri);
                if (docId != null) {
                    int colonIdx = docId.indexOf(':');
                    String docPath = colonIdx >= 0 ? docId.substring(colonIdx + 1) : docId;
                    int lastSlash = docPath.lastIndexOf('/');
                    if (lastSlash > 0) {
                        int prevSlash = docPath.lastIndexOf('/', lastSlash - 1);
                        return prevSlash >= 0
                                ? docPath.substring(prevSlash + 1, lastSlash)
                                : docPath.substring(0, lastSlash);
                    }
                }
            } catch (Exception ignored) {
            }
            return "";
        }
        String path = uri.getPath();
        if (path == null || path.length() == 0) return "";
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) return "";
        int prevSlash = path.lastIndexOf('/', lastSlash - 1);
        if (prevSlash < 0 || prevSlash + 1 >= lastSlash) return "";
        return path.substring(prevSlash + 1, lastSlash);
    }

    private void onRemoteQueueRequestsReceived(List<BluetoothQueueBridge.TrackRequest> tracks) {
        if (!btController.isServerMode() || tracks == null || tracks.isEmpty()) return;
        new Thread(() -> {
            List<Uri> foundUris = findRequestedAudioUris(tracks);
            List<QueueEntry> toAdd = new ArrayList<>();
            List<String> notFound = new ArrayList<>();
            for (int i = 0; i < tracks.size(); i++) {
                Uri uri = foundUris.get(i);
                if (uri != null) {
                    toAdd.add(new QueueEntry(tracks.get(i).file, uri));
                } else {
                    notFound.add(tracks.get(i).file);
                }
            }
            runOnUiThread(() -> {
                if (!toAdd.isEmpty()) {
                    addToQueue(toAdd);
                    Toast.makeText(this, getString(R.string.added_tracks_from_remote, toAdd.size()), Toast.LENGTH_SHORT).show();
                }
                for (String name : notFound) {
                    Toast.makeText(this, getString(R.string.requested_file_not_found, name), Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private List<Uri> findRequestedAudioUris(List<BluetoothQueueBridge.TrackRequest> requests) {
        if (browsingDocumentTree && !documentUriStack.isEmpty() && currentTreeUri != null) {
            return findAllInDocumentTree(documentUriStack.get(0), requests);
        }
        if (currentFileRootDirectory != null && currentFileRootDirectory.exists()) {
            return findAllInFileDirectory(currentFileRootDirectory, requests);
        }
        List<Uri> nulls = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) nulls.add(null);
        return nulls;
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
                for (int i = 0; i < n; i++) {
                    if (hintMatches[i] != null) continue;
                    if (childName.equalsIgnoreCase(requests.get(i).file)) {
                        String hint = requests.get(i).parent;
                        if (hint.length() > 0 && dirName.equalsIgnoreCase(hint)) {
                            hintMatches[i] = childUri;
                        } else if (nameMatches[i] == null) {
                            nameMatches[i] = childUri;
                        }
                    }
                }
                applyExtFallbackMatch(stripExtension(childName), childUri, requests, hintMatches, nameMatches, extMatches);
            }
        }

        return mergeMatchResults(hintMatches, nameMatches, extMatches);
    }

    /**
     * Finds all requested files in the SAF document tree with a single DFS pass.
     * Each directory is queried exactly once. Hint-matched results take priority over plain
     * name matches, which take priority over extension-stripped matches.
     */
    private List<Uri> findAllInDocumentTree(Uri rootDocumentUri, List<BluetoothQueueBridge.TrackRequest> requests) {
        int n = requests.size();
        Uri[] hintMatches = new Uri[n];
        Uri[] nameMatches = new Uri[n];
        Uri[] extMatches  = new Uri[n];

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
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(currentTreeUri, dirDocId);
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
                    Uri childUri = DocumentsContract.buildDocumentUriUsingTree(currentTreeUri, childDocId);
                    for (int i = 0; i < n; i++) {
                        if (hintMatches[i] != null) continue;
                        if (!childName.equalsIgnoreCase(requests.get(i).file)) continue;
                        String hint = requests.get(i).parent;
                        if (hint.length() > 0 && dirName.equalsIgnoreCase(hint)) {
                            hintMatches[i] = childUri;
                        } else if (nameMatches[i] == null) {
                            nameMatches[i] = childUri;
                        }
                    }
                    applyExtFallbackMatch(stripExtension(childName), childUri, requests, hintMatches, nameMatches, extMatches);
                }
            } catch (Exception ignored) {
            }
        }

        return mergeMatchResults(hintMatches, nameMatches, extMatches);
    }

    private boolean isPlaybackActiveOrFading() {
        return !playbackStopped || stopFadeInProgress;
    }

    private boolean hasBrowseBehavior() {
        return mode == Mode.BROWSE || mode == Mode.REMOTE_SEND;
    }

    private void applyPlayButtonModeState() {
        if (playButton == null) {
            return;
        }
        if (hasBrowseBehavior()) {
            playButton.setText(R.string.clear_button_text);
        } else if (defaultPlayButtonText != null) {
            playButton.setText(defaultPlayButtonText);
        }
        applySaveButtonModeState();
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

    private void sendQueueToRemote() {
        if (queueEntries.isEmpty()) {
            Toast.makeText(this, R.string.save_queue_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        List<BluetoothQueueBridge.TrackRequest> requests = new ArrayList<>(queueEntries.size());
        for (QueueEntry entry : queueEntries) {
            requests.add(new BluetoothQueueBridge.TrackRequest(entry.name, getParentFolderName(entry.uri)));
        }
        if (btController.sendQueueRequests(requests)) {
            Toast.makeText(this, getString(R.string.sent_tracks, requests.size()), Toast.LENGTH_SHORT).show();
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void applyExtFallbackMatch(String childNoExt, Uri childUri,
            List<BluetoothQueueBridge.TrackRequest> requests, Uri[] hintMatches, Uri[] nameMatches, Uri[] extMatches) {
        for (int i = 0; i < requests.size(); i++) {
            if (hintMatches[i] != null || nameMatches[i] != null || extMatches[i] != null) continue;
            if (childNoExt.equalsIgnoreCase(stripExtension(requests.get(i).file))) {
                extMatches[i] = childUri;
            }
        }
    }

    private static List<Uri> mergeMatchResults(Uri[] hintMatches, Uri[] nameMatches, Uri[] extMatches) {
        List<Uri> results = new ArrayList<>(hintMatches.length);
        for (int i = 0; i < hintMatches.length; i++) {
            Uri r = hintMatches[i];
            if (r == null) r = nameMatches[i];
            if (r == null) r = extMatches[i];
            results.add(r);
        }
        return results;
    }

    private void applyStopButtonState() {
        if (stopButton == null) return;
        if (stopFadeInProgress) {
            stopButton.setBackgroundColor(getColor(R.color.stopButtonActive));
            stopButton.setTextColor(getColor(R.color.stopButtonActiveText));
            stopButton.setText(R.string.stop_button_fading_text);
        } else {
            stopButton.setBackgroundColor(getColor(R.color.buttonBackground));
            stopButton.setTextColor(getColor(R.color.foreground));
            stopButton.setText(R.string.stop_button_text);
        }
    }

    private void showStopButtonFadingState() {
        stopFadeInProgress = true;
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
        if (queueList != null && currentPlayingQueueIndex >= 0)
            queueList.smoothScrollToPosition(currentPlayingQueueIndex);
    }

    private void syncWithServiceState() {
        int serviceIndex = Service.sCurrentIndex;
        int entryId = Service.sCurrentEntryId;
        Uri serviceUri = Service.sCurrentUri;
        boolean serviceBrowseMode = Service.sBrowseMode;
        currentTrackPositionMs = Service.sPlaybackPositionMs;
        currentTrackDurationMs = Service.sPlaybackDurationMs;
        if (Service.sFadeOutInProgress && !stopFadeInProgress) {
            stopFadeInProgress = true;
            applyStopButtonState();
        }
        if (serviceIndex < 0) {
            SilenceStreamer.reinitIfOutputChanged(this);
            if (browseTransitionActive && !stopFadeInProgress) {
                // Transient stop between sendStopNowCommand() and the browse track starting.
                return;
            }
            if (queueTransitionActive && !stopFadeInProgress) {
                return;
            }
            if (stopFadeInProgress) {
                onFadeOutFinished();
                return;
            }
            playbackStopped = true;
            currentPlayingQueueIndex = -1;
            setPlaybackOffset(0);
            resetCurrentTrackProgress();
        } else {
            playbackStopped = false;
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
        if (queueAdapter != null) queueAdapter.notifyDataSetChanged();
        if (fileAdapter != null && (fileBrowserPreviewingUri != null || Service.sBrowseMode)) fileAdapter.notifyDataSetChanged();
        maybeQueueNextBrowseTrack();
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
        persistQueue();
        unregisterPlaybackStateReceiver();
        resetFileBrowserPreview();
        if (Service.sCurrentUri == null) {
            SilenceStreamer.fadeOutAndRelease();
        }
        super.onStop();
    }

    private void resetStopButtonState() {
        stopFadeInProgress = false;
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

    private File getMusicDirectoryCompat() {
        return Environment.getExternalStoragePublicDirectory(MUSIC_DIRECTORY_NAME);
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
        btController.shutdown();
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
        final boolean isDirectory;
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
            this.isDirectory = isDirectory;
        }

        FileEntry(Uri uri, String name, boolean isDirectory) {
            this.file        = null;
            this.uri         = uri;
            this.name        = name;
            this.isDirectory = isDirectory;
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
        StringBuilder sb = new StringBuilder();
        if (fileSortMode == SORT_YEAR) {
            if (bpm > 0) sb.append(bpm).append(" BPM");
            if (genre != null && !genre.isEmpty()) { if (sb.length() > 0) sb.append("  "); sb.append(genre); }
            if (date  != null && !date.isEmpty())  { if (sb.length() > 0) sb.append("  "); sb.append(date); }
        } else if (fileSortMode == SORT_GENRE) {
            if (date  != null && !date.isEmpty()) sb.append(date);
            if (bpm > 0) { if (sb.length() > 0) sb.append("  "); sb.append(bpm).append(" BPM"); }
            if (genre != null && !genre.isEmpty()) { if (sb.length() > 0) sb.append("  "); sb.append(genre); }
        } else if (fileSortMode == SORT_BPM) {
            if (date  != null && !date.isEmpty()) sb.append(date);
            if (genre != null && !genre.isEmpty()) { if (sb.length() > 0) sb.append("  "); sb.append(genre); }
            if (bpm > 0) { if (sb.length() > 0) sb.append("  "); sb.append(bpm).append(" BPM"); }
        } else if (fileSortMode == SORT_ARTIST || fileSortMode == SORT_FILENAME) {
            if (bpm > 0) sb.append(bpm).append(" BPM");
            if (genre != null && !genre.isEmpty()) { if (sb.length() > 0) sb.append("  "); sb.append(genre); }
            if (date  != null && !date.isEmpty())  { if (sb.length() > 0) sb.append("  "); sb.append(date); }
        }
        return sb.toString();
    }

    private static final class ViewHolder {
        final View content;
        final TextView icon;
        final TextView name;
        final TextView remainingTime;
        final View metaRow;
        final TextView artist;
        final TextView meta;
        ViewHolder(View v) {
            content = v.findViewById(R.id.swipe_content);
            icon = v.findViewById(R.id.file_icon);
            name = v.findViewById(R.id.file_name);
            remainingTime = v.findViewById(R.id.remaining_time);
            metaRow = v.findViewById(R.id.file_meta_row);
            artist = v.findViewById(R.id.file_artist);
            meta = v.findViewById(R.id.file_meta);
        }
    }

    private final class FileAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(FileBrowserQueueActivity.this);
        private final int colorBackground;
        private final int colorPreviewBase;
        private final int colorPreviewFill;

        FileAdapter() {
            TypedValue out = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.colorBackground, out, true);
            colorBackground = out.data;
            colorPreviewBase = getColor(R.color.queueProgressBackground);
            colorPreviewFill = getColor(R.color.queueProgressFill);
        }

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
            vh.icon.setText(entry.isDirectory ? "\uD83D\uDCC1" : "\uD83C\uDFB5");
            vh.name.setText(fileSortMode != SORT_FILENAME && entry.sortTitle != null && !entry.sortTitle.isEmpty() ? entry.sortTitle : entry.name);
            String metaText = entry.isDirectory ? "" :
                    buildMetaText(entry.sortDate, entry.sortGenre, entry.sortBpm);
            String artistText = entry.isDirectory ? "" :
                    (entry.sortArtist != null ? entry.sortArtist : "");
            vh.artist.setText(artistText);
            vh.meta.setText(metaText);
            boolean hasSubtext = artistText.length() > 0 || metaText.length() > 0;
            vh.metaRow.setVisibility(hasSubtext ? View.VISIBLE : View.GONE);
            vh.name.setGravity(hasSubtext ? Gravity.START : Gravity.CENTER);

            boolean isBrowseEntry = Service.sBrowseMode
                    && !entry.isDirectory
                    && browseFileUri != null
                    && browseFileUri.equals(entry.uri);
            boolean isPreviewEntry = !entry.isDirectory
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
                applyProgressBackground(vh.content, progress, colorPreviewBase, colorPreviewFill);
            } else if (isPreviewEntry) {
                float progress = 0f;
                if (hasProgress) {
                    long dur = SilenceStreamer.previewDurationMs;
                    if (dur > 0) {
                        progress = Math.min(1f, Math.max(0f,
                                SilenceStreamer.previewPositionMs / (float) dur));
                    }
                }
                applyProgressBackground(vh.content, progress, colorPreviewBase, colorPreviewFill);
            } else {
                vh.content.setBackgroundColor(colorBackground);
            }

            return convertView;
        }
    }

    private final class QueueAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(FileBrowserQueueActivity.this);
        private final int colorBackground;
        private final int colorQueueBase;
        private final int colorQueueFill;

        QueueAdapter() {
            TypedValue out = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.colorBackground, out, true);
            colorBackground = out.data;
            colorQueueBase = getColor(R.color.queueProgressBackground);
            colorQueueFill = getColor(R.color.queueProgressFill);
        }

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

            boolean isCurrentTrack = position == currentPlayingQueueIndex
                    && isPlaybackActiveOrFading();
            if (isCurrentTrack) {
                float progress = 0f;
                if (currentTrackDurationMs > 0) {
                    progress = Math.min(1f,
                            Math.max(0f, currentTrackPositionMs / (float) currentTrackDurationMs));
                }
                applyProgressBackground(vh.content, progress, colorQueueBase, colorQueueFill);
            } else {
                vh.content.setBackgroundColor(colorBackground);
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
            vh.name.setGravity(hasSubtext ? Gravity.START : Gravity.CENTER);
            vh.artist.setText(artistText);
            vh.meta.setText(metaText);
            vh.metaRow.setVisibility(hasSubtext ? View.VISIBLE : View.GONE);
            return convertView;
        }
    }
}

