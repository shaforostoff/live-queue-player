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
import android.text.Editable;
import android.text.TextWatcher;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.BaseAdapter;
import android.widget.Button;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static final int PERMISSION_REQUEST_CODE = 2001;
    private static final int TREE_REQUEST_CODE = 2002;
    private static final String ACTION_SEND_MULTIPLE_COMPAT = "android.intent.action.SEND_MULTIPLE";
    private static final String MUSIC_DIRECTORY_NAME = "Music";
    private static final String BROWSER_PREFS = "browser_prefs";
    private static final String PREF_LAST_TREE_URI = "last_tree_uri";
    private static final String PREF_BROWSE_FILE_PLAYING = "browse_file_playing";
    private static final String PREF_BROWSE_FILE_URI = "browse_file_uri";
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
    private int tagReadProgressDone;

    // -- queue state --------------------------------------------------------
    private final List<QueueEntry> queueEntries = new ArrayList<>();
    private QueueAdapter queueAdapter;
    private ListView queueList;
    private TextView queueEmptyHint;
    private boolean playbackStopped = true;
    private boolean stopFadeInProgress;
    private boolean browsingDocumentTree;
    private int currentPlayingQueueIndex = -1;
    private int servicePlaybackOffset = 0;
    private float queueSwipeDownX;
    private float queueSwipeDownY;
    private int queueSwipeStartPosition = -1;
    private boolean queueSwipeHandled;
    private View queueSwipingView;
    private Button stopButton;
    private Button sortButton;
    private Button openStorageButton;
    private Mode mode = Mode.DJ;
    private boolean browseFilePlaying;
    private Uri browseFileUri;
    private boolean browseNextQueued;
    private Uri browseNextUri;
    private boolean browseTransitionActive;
    private BluetoothController btController;
    private Button playButton;
    private Button saveButton;
    private CharSequence defaultPlayButtonText;
    private int currentTrackPositionMs;
    private int currentTrackDurationMs;
    private PreviewManager audioPreviewManager;
    private Uri fileBrowserPreviewingUri;
    private Uri fileBrowserPreviewingEntryUri;
    private float fileBrowserSwipeDownX;
    private float fileBrowserSwipeDownY;
    private int fileBrowserSwipeStartPosition = -1;
    private boolean fileBrowserSwipeHandled;
    private View fileBrowserSwipingView;
    private ListView fileBrowserList;
    private final BroadcastReceiver playbackStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if (!Service.ACTION_PLAYBACK_STATE.equals(intent.getAction())) return;

            boolean isPlaying = intent.getBooleanExtra(Service.EXTRA_IS_PLAYING, false);
            int serviceIndex = intent.getIntExtra(Service.EXTRA_CURRENT_INDEX, -1);
            Uri currentUri = intent.getParcelableExtra(Service.EXTRA_CURRENT_URI);
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
                clearBrowseState();
                currentPlayingQueueIndex = -1;
                if (stopFadeInProgress) {
                    onFadeOutFinished();
                    return;
                }
                servicePlaybackOffset = 0;
                resetCurrentTrackProgress();
                QueueStore.savePlaybackOffset(FileBrowserQueueActivity.this, 0);
            } else if (browseFilePlaying) {
                browseTransitionActive = false;
                currentPlayingQueueIndex = -1;
                if (browseNextUri != null && browseNextUri.equals(currentUri)) {
                    browseFileUri = browseNextUri;
                    browseNextQueued = false;
                    browseNextUri = null;
                    saveBrowseState();
                    if (fileAdapter != null) fileAdapter.notifyDataSetChanged();
                }
            } else {
                currentPlayingQueueIndex = resolvePlayingQueueIndex(serviceIndex, currentUri);
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
            if (PreviewManager.isEnabled(this) && !hasBrowseBehavior()) {
                Uri previewUri = isPlaylistFile(entry.name)
                        ? resolveFirstPlaylistUri(entry) : entry.uri;
                if (previewUri != null && previewUri.equals(fileBrowserPreviewingUri)) {
                    resetFileBrowserPreview();
                } else {
                    fileBrowserPreviewingUri = previewUri;
                    fileBrowserPreviewingEntryUri = entry.uri;
                    startAudioPreview(previewUri);
                }
            } else {
                boolean queueTrackPlaying = !playbackStopped && !browseFilePlaying && currentPlayingQueueIndex >= 0;
                if (hasBrowseBehavior() && !stopFadeInProgress && !(mode == Mode.BROWSE && queueTrackPlaying)) {
                    playBrowseFile(entry);
                } else {
                    if (isPlaylistFile(entry.name)) {
                        int addedCount = addPlaylistToQueue(entry);
                        if (addedCount > 0)
                            Toast.makeText(this, "Added " + addedCount + " files from " + entry.name, Toast.LENGTH_SHORT).show();
                        else
                            Toast.makeText(this, "No playable files found in " + entry.name, Toast.LENGTH_SHORT).show();
                    } else {
                        addToQueue(entry.name, entry.uri);
                    }
                }
            }
        });
        installFileBrowserSwipeAdd(fileBrowserList);

        // -- queue: tap item to play when stopped ----------------------------
        queueList.setOnItemClickListener((parent, view, position, id) -> {
            if (mode == Mode.REMOTE_SEND || browseFilePlaying) {
                clearBrowseState();
                fileAdapter.notifyDataSetChanged();
                playQueueFrom(position, !playbackStopped || stopFadeInProgress);
                return;
            }
            if (position == currentPlayingQueueIndex && (!playbackStopped || stopFadeInProgress)) {
                showLyricsOverlayForQueueEntry(queueEntries.get(position));
                return;
            }
            if (stopFadeInProgress) {
                playQueueFrom(position, true);
            } else if (playbackStopped) {
                playQueueFrom(position);
            } else {
                Toast.makeText(this, "Stop playback first", Toast.LENGTH_SHORT).show();
                //Toast.makeText(this, "Swipe right: remove. Stop playback to play this track", Toast.LENGTH_SHORT).show();
            }
        });
        installQueueSwipeRemove(queueList);

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
        applySaveButtonModeState();
        applySortButtonLoadingState();

        stopButton.setOnClickListener(v -> {
            if (mode == Mode.REMOTE_SEND || browseFilePlaying) {
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
        if (browsingDocumentTree) {
            return documentUriStack.size() > 1;
        }
        if (currentFileDirectory == null || currentFileRootDirectory == null) {
            return false;
        }
        return !sameFileLocation(currentFileDirectory, currentFileRootDirectory);
    }

    private void navigateUpFromCurrentFolder() {
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
    }

    private void openStoragePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        Uri initialTreeUri = getRememberedTreeUri();
        if (initialTreeUri != null && hasReadPermissionForUri(initialTreeUri)) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialTreeUri);
        }
        startActivityForResult(intent, TREE_REQUEST_CODE);
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
        browseFilePlaying = false;
        browseFileUri = null;
        browseNextQueued = false;
        browseNextUri = null;
        saveBrowseState();
    }

    private void saveBrowseState() {
        SharedPreferences.Editor ed = getSharedPreferences(BROWSER_PREFS, MODE_PRIVATE).edit();
        ed.putBoolean(PREF_BROWSE_FILE_PLAYING, browseFilePlaying);
        if (browseFilePlaying && browseFileUri != null)
            ed.putString(PREF_BROWSE_FILE_URI, browseFileUri.toString());
        else
            ed.remove(PREF_BROWSE_FILE_URI);
        ed.apply();
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
            currentTreeUri = treeUri;
            browsingDocumentTree = true;
            currentFileDirectory = null;
            currentFileRootDirectory = null;
            documentUriStack.clear();
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
                        sortFileEntriesInPlace();
                        applyFileFilter();
                        scrollToHighlightedFileEntry();
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
            Toast.makeText(this, "Failed to load audio files.", Toast.LENGTH_LONG).show();
        }
        sortFileEntriesInPlace();
        applyFileFilter();
    }

    private void sortFileEntriesInPlace() {
        if (fileSortMode == SORT_YEAR) {
            applyFilenameYearsInPlace();
        }
        Collections.sort(fileEntries, this::compareFileEntries);
        if (isTagSortMode(fileSortMode)) {
            resolveTagMetadataForVisibleFolderAsync();
        }
    }

    private boolean isTagSortMode(int mode) {
        return mode == SORT_YEAR || mode == SORT_GENRE || mode == SORT_BPM || mode == SORT_ARTIST;
    }

    private void applyFilenameYearsInPlace() {
        for (FileEntry entry : fileEntries) {
            if (entry.isDirectory || entry.sortDateResolved) {
                continue;
            }
            String filenameYear = MetadataExtractor.extractYearFromFileName(entry.name);
            if (filenameYear.length() == 0) {
                continue;
            }
            entry.sortDate = filenameYear;
            entry.sortDateResolved = true;
        }
    }

    private int compareFileEntries(FileEntry left, FileEntry right) {
        if (left.isDirectory != right.isDirectory) {
            return left.isDirectory ? -1 : 1;
        }
        if (left.isDirectory) {
            return left.name.compareToIgnoreCase(right.name);
        }
        if (fileSortMode == SORT_YEAR) {
            String leftDate = left.sortDate == null ? "" : left.sortDate;
            String rightDate = right.sortDate == null ? "" : right.sortDate;
            boolean leftHasDate = leftDate.length() > 0;
            boolean rightHasDate = rightDate.length() > 0;
            if (leftHasDate != rightHasDate) {
                return leftHasDate ? -1 : 1;
            }
            int dateCompare = leftDate.compareTo(rightDate);
            if (dateCompare != 0) {
                return dateCompare;
            }
        } else if (fileSortMode == SORT_GENRE) {
            String leftGenre = left.sortGenre == null ? "" : left.sortGenre;
            String rightGenre = right.sortGenre == null ? "" : right.sortGenre;
            boolean leftHasGenre = leftGenre.length() > 0;
            boolean rightHasGenre = rightGenre.length() > 0;
            if (leftHasGenre != rightHasGenre) {
                return leftHasGenre ? -1 : 1;
            }
            int genreCompare = leftGenre.compareToIgnoreCase(rightGenre);
            if (genreCompare != 0) {
                return genreCompare;
            }
            String leftDate = left.sortDate == null ? "" : left.sortDate;
            String rightDate = right.sortDate == null ? "" : right.sortDate;
            boolean leftHasDate = leftDate.length() > 0;
            boolean rightHasDate = rightDate.length() > 0;
            if (leftHasDate != rightHasDate) {
                return leftHasDate ? -1 : 1;
            }
            int dateCompare = leftDate.compareTo(rightDate);
            if (dateCompare != 0) {
                return dateCompare;
            }
        } else if (fileSortMode == SORT_BPM) {
            int leftBpm = left.sortBpm;
            int rightBpm = right.sortBpm;
            boolean leftHasBpm = leftBpm > 0;
            boolean rightHasBpm = rightBpm > 0;
            if (leftHasBpm != rightHasBpm) {
                return leftHasBpm ? -1 : 1;
            }
            int bpmCompare = Integer.compare(leftBpm, rightBpm);
            if (bpmCompare != 0) {
                return bpmCompare;
            }

            String leftDate = left.sortDate == null ? "" : left.sortDate;
            String rightDate = right.sortDate == null ? "" : right.sortDate;
            boolean leftHasDate = leftDate.length() > 0;
            boolean rightHasDate = rightDate.length() > 0;
            if (leftHasDate != rightHasDate) {
                return leftHasDate ? -1 : 1;
            }
            int dateCompare = leftDate.compareTo(rightDate);
            if (dateCompare != 0) {
                return dateCompare;
            }
        } else if (fileSortMode == SORT_ARTIST) {
            String leftArtist = left.sortArtist == null ? "" : left.sortArtist;
            String rightArtist = right.sortArtist == null ? "" : right.sortArtist;
            boolean leftHasArtist = leftArtist.length() > 0;
            boolean rightHasArtist = rightArtist.length() > 0;
            if (leftHasArtist != rightHasArtist) {
                return leftHasArtist ? -1 : 1;
            }
            int artistCompare = leftArtist.compareToIgnoreCase(rightArtist);
            if (artistCompare != 0) {
                return artistCompare;
            }
            String leftGenre = left.sortGenre == null ? "" : left.sortGenre;
            String rightGenre = right.sortGenre == null ? "" : right.sortGenre;
            boolean leftHasGenre = leftGenre.length() > 0;
            boolean rightHasGenre = rightGenre.length() > 0;
            if (leftHasGenre != rightHasGenre) {
                return leftHasGenre ? -1 : 1;
            }
            int genreCompare = leftGenre.compareToIgnoreCase(rightGenre);
            if (genreCompare != 0) {
                return genreCompare;
            }
            String leftDate = left.sortDate == null ? "" : left.sortDate;
            String rightDate = right.sortDate == null ? "" : right.sortDate;
            boolean leftHasDate = leftDate.length() > 0;
            boolean rightHasDate = rightDate.length() > 0;
            if (leftHasDate != rightHasDate) {
                return leftHasDate ? -1 : 1;
            }
            int dateCompare = leftDate.compareTo(rightDate);
            if (dateCompare != 0) {
                return dateCompare;
            }
        }
        return left.name.compareToIgnoreCase(right.name);
    }

    private void resolveTagMetadataForVisibleFolderAsync() {
        final int versionAtStart = fileEntriesVersion;
        final ArrayList<FileEntry> pendingEntries = new ArrayList<>();
        boolean cacheApplied = false;
        for (FileEntry entry : fileEntries) {
            if (entry.isDirectory ||
                    ((entry.sortDateResolved && entry.sortGenreResolved && entry.sortArtistResolved && entry.sortBpmResolved)
                            || entry.sortDateLoading || entry.sortGenreLoading || entry.sortArtistLoading || entry.sortBpmLoading)) {
                continue;
            }

            String filenameYear = MetadataExtractor.extractYearFromFileName(entry.name);
            if (filenameYear.length() > 0) {
                entry.sortDate = filenameYear;
                entry.sortDateResolved = true;
                cacheApplied = true;
            }

            if (entry.uri != null && metadataExtractor.isAllTagsCached(entry.uri)) {
                MetadataExtractor.TagEntry tags = metadataExtractor.readSortTags(entry.uri);
                entry.sortDate = tags.date;
                entry.sortGenre = tags.genre;
                entry.sortArtist = tags.artist;
                entry.sortBpm = tags.bpm;
                entry.sortDateResolved = true;
                entry.sortGenreResolved = true;
                entry.sortArtistResolved = true;
                entry.sortBpmResolved = true;
                cacheApplied = true;
                continue;
            }
            entry.sortDateLoading = true;
            entry.sortGenreLoading = true;
            entry.sortArtistLoading = true;
            entry.sortBpmLoading = true;
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
            tagReadProgressDone = 0;
        }
        activeTagReadJobs++;
        tagReadProgressTotal += pendingEntries.size();
        applySortButtonLoadingState();

        int threadCount = Math.min(4, pendingEntries.size());
        AtomicInteger pending = new AtomicInteger(pendingEntries.size());
        List<SortTagResult> results = Collections.synchronizedList(new ArrayList<>(pendingEntries.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (FileEntry entry : pendingEntries) {
            pool.submit(() -> {
                MetadataExtractor.TagEntry tags = metadataExtractor.readSortTags(entry.uri);
                results.add(new SortTagResult(entry, tags.date, tags.genre, tags.artist, tags.bpm));
                runOnUiThread(() -> {
                    tagReadProgressDone = Math.min(tagReadProgressTotal, tagReadProgressDone + 1);
                    applySortButtonLoadingState();
                });
                if (pending.decrementAndGet() == 0) {
                    runOnUiThread(() -> {
                        activeTagReadJobs = Math.max(0, activeTagReadJobs - 1);
                        if (activeTagReadJobs == 0) {
                            tagReadProgressTotal = 0;
                            tagReadProgressDone = 0;
                        }
                        applySortButtonLoadingState();

                        if (versionAtStart != fileEntriesVersion) {
                            for (SortTagResult result : results) {
                                result.entry.sortDateLoading = false;
                                result.entry.sortGenreLoading = false;
                                result.entry.sortArtistLoading = false;
                                result.entry.sortBpmLoading = false;
                            }
                            return;
                        }

                        boolean changed = false;
                        for (SortTagResult result : results) {
                            result.entry.sortDate = result.date;
                            result.entry.sortGenre = result.genre;
                            result.entry.sortArtist = result.artist;
                            result.entry.sortBpm = result.bpm;
                            result.entry.sortDateResolved = true;
                            result.entry.sortGenreResolved = true;
                            result.entry.sortArtistResolved = true;
                            result.entry.sortBpmResolved = true;
                            result.entry.sortDateLoading = false;
                            result.entry.sortGenreLoading = false;
                            result.entry.sortArtistLoading = false;
                            result.entry.sortBpmLoading = false;
                            changed = true;
                        }

                        if (changed && isTagSortMode(fileSortMode)) {
                            Collections.sort(fileEntries, FileBrowserQueueActivity.this::compareFileEntries);
                            applyFileFilter();
                        }
                    });
                }
            });
        }
        pool.shutdown();
    }

    private void applySortButtonLoadingState() {
        if (sortButton == null) {
            return;
        }
        if (activeTagReadJobs > 0) {
            float progress = 0f;
            if (tagReadProgressTotal > 0) {
                progress = Math.min(1f, tagReadProgressDone / (float) tagReadProgressTotal);
            }
            applyProgressBackground(
                    sortButton,
                    progress,
                    getColor(R.color.buttonBackground),
                    getColor(R.color.stopButtonActive));
            sortButton.setTextColor(getColor(R.color.stopButtonActiveText));
        } else {
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

    private void scrollToHighlightedFileEntry() {
        if (fileBrowserList == null) return;
        Uri highlightedUri = null;
        if (browseFilePlaying && browseFileUri != null) {
            highlightedUri = browseFileUri;
        } else if (fileBrowserPreviewingEntryUri != null) {
            highlightedUri = fileBrowserPreviewingEntryUri;
        }
        if (highlightedUri == null) return;
        for (int i = 0; i < filteredFileEntries.size(); i++) {
            if (highlightedUri.equals(filteredFileEntries.get(i).uri)) {
                fileBrowserList.setSelection(i);
                return;
            }
        }
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

    private void addToQueue(List<QueueEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        queueEntries.addAll(entries);
        queueAdapter.notifyDataSetChanged();
        queueList.smoothScrollToPosition(queueEntries.size() - 1);
        updateQueueHint();
        persistQueue();

        // Keep the running service queue aligned with the visible queue.
        if (!playbackStopped && !stopFadeInProgress) {
            syncServicePendingQueue();
        }
    }

    private boolean removeQueueAt(int position) {
        if (position < 0 || position >= queueEntries.size()) return false;
        if (position == currentPlayingQueueIndex && (!playbackStopped || stopFadeInProgress)) {
            return false;
        }
        queueEntries.remove(position);
        boolean playbackActive = !playbackStopped || stopFadeInProgress;
        if (playbackActive && currentPlayingQueueIndex >= 0 && position <= currentPlayingQueueIndex) {
            if (currentPlayingQueueIndex == position) {
                currentPlayingQueueIndex = -1;
            } else {
                currentPlayingQueueIndex--;
            }
            if (servicePlaybackOffset > 0) {
                servicePlaybackOffset--;
                QueueStore.savePlaybackOffset(this, servicePlaybackOffset);
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
            queueEntries.add(new QueueEntry(entry.name, entry.uri));
        }
        queueAdapter.notifyDataSetChanged();
        updateQueueHint();
    }

    private void persistQueue() {
        ArrayList<QueueStore.Entry> persisted = new ArrayList<>(queueEntries.size());
        for (QueueEntry entry : queueEntries) {
            persisted.add(new QueueStore.Entry(entry.name, entry.uri));
        }
        QueueStore.save(this, persisted);
    }

    private void installQueueSwipeRemove(ListView queueList) {
        final float verticalSlop = 40f * getResources().getDisplayMetrics().density;

        queueList.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    queueSwipeDownX = event.getX();
                    queueSwipeDownY = event.getY();
                    queueSwipeStartPosition = queueList.pointToPosition((int) event.getX(), (int) event.getY());
                    queueSwipeHandled = false;
                    queueSwipingView = null;
                    if (queueSwipeStartPosition >= 0) {
                        int firstVisible = queueList.getFirstVisiblePosition();
                        int childIndex = queueSwipeStartPosition - firstVisible;
                        if (childIndex >= 0 && childIndex < queueList.getChildCount()) {
                            queueSwipingView = queueList.getChildAt(childIndex);
                        }
                    }
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (queueSwipeHandled || queueSwipeStartPosition < 0) {
                        return queueSwipeHandled;
                    }

                    float dx = event.getX() - queueSwipeDownX;
                    float dy = event.getY() - queueSwipeDownY;

                    // vertical scroll started - abandon swipe
                    if (Math.abs(dy) > verticalSlop && Math.abs(dy) > Math.abs(dx)) {
                        resetSwipingView();
                        queueSwipeStartPosition = -1;
                        return false;
                    }

                    if (dx > 0 && queueSwipingView != null) {
                        // clamp so the view can't slide past its own right edge
                        float clampedDx = Math.min(dx, queueSwipingView.getWidth());
                        queueSwipingView.setTranslationX(clampedDx);
                        // disallow list from intercepting a horizontal swipe
                        queueList.getParent().requestDisallowInterceptTouchEvent(true);

                        int viewWidth = queueSwipingView.getWidth();
                        if (viewWidth > 0 && dx >= viewWidth / 2f) {
                            // reached midpoint -> remove
                            queueSwipeHandled = true;
                            queueSwipingView.setTranslationX(0);
                            queueSwipingView = null;
                            if (removeQueueAt(queueSwipeStartPosition)) {
                                ;//Toast.makeText(this, "Removed from queue", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "Cannot remove currently playing track", Toast.LENGTH_SHORT).show();
                            }
                            return true;
                        }
                        return true; // consume: prevent list scrolling while swiping right
                    }

                    if (dx < 0 && (mode == Mode.REMOTE_SEND || mode == Mode.REMOTE_RECEIVE) && queueSwipingView != null) {
                        float clampedDx = Math.max(dx, -queueSwipingView.getWidth());
                        queueSwipingView.setTranslationX(clampedDx);
                        queueList.getParent().requestDisallowInterceptTouchEvent(true);

                        int viewWidth = queueSwipingView.getWidth();
                        if (viewWidth > 0 && Math.abs(dx) >= viewWidth / 2f) {
                            queueSwipeHandled = true;
                            queueSwipingView.setTranslationX(0);
                            queueSwipingView = null;
                            if (mode == Mode.REMOTE_SEND
                                    && queueSwipeStartPosition >= 0
                                    && queueSwipeStartPosition < queueEntries.size()) {
                                QueueEntry swipeEntry = queueEntries.get(queueSwipeStartPosition);
                                if (btController.sendQueueRequest(swipeEntry.name, getParentFolderName(swipeEntry.uri))) {
                                    Toast.makeText(this, "Track request sent", Toast.LENGTH_SHORT).show();
                                }
                            }
                            return true;
                        }
                        return true;
                    }
                    return false; // dx <= 0: let the list handle normally

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!queueSwipeHandled) {
                        v.performClick();
                    }
                    resetSwipingView();
                    boolean handled = queueSwipeHandled;
                    queueSwipeStartPosition = -1;
                    queueSwipeHandled = false;
                    return handled;

                default:
                    return false;
            }
        });
    }

    private void resetSwipingView() {
        if (queueSwipingView != null) {
            queueSwipingView.setTranslationX(0);
            queueSwipingView = null;
        }
    }

    private void installFileBrowserSwipeAdd(ListView fileBrowserList) {
        final float verticalSlop = 40f * getResources().getDisplayMetrics().density;
        fileBrowserList.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    fileBrowserSwipeDownX = event.getX();
                    fileBrowserSwipeDownY = event.getY();
                    fileBrowserSwipeStartPosition = fileBrowserList.pointToPosition((int) event.getX(), (int) event.getY());
                    fileBrowserSwipeHandled = false;
                    fileBrowserSwipingView = null;
                    if (fileBrowserSwipeStartPosition >= 0) {
                        int firstVisible = fileBrowserList.getFirstVisiblePosition();
                        int childIndex = fileBrowserSwipeStartPosition - firstVisible;
                        if (childIndex >= 0 && childIndex < fileBrowserList.getChildCount()) {
                            fileBrowserSwipingView = fileBrowserList.getChildAt(childIndex);
                        }
                    }
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (fileBrowserSwipeHandled || fileBrowserSwipeStartPosition < 0) {
                        return fileBrowserSwipeHandled;
                    }
                    float dx = event.getX() - fileBrowserSwipeDownX;
                    float dy = event.getY() - fileBrowserSwipeDownY;
                    if (Math.abs(dy) > verticalSlop && Math.abs(dy) > Math.abs(dx)) {
                        resetFileBrowserSwipingView();
                        fileBrowserSwipeStartPosition = -1;
                        return false;
                    }
                    if (dx > 0 && fileBrowserSwipingView != null) {
                        float clampedDx = Math.min(dx, fileBrowserSwipingView.getWidth());
                        fileBrowserSwipingView.setTranslationX(clampedDx);
                        fileBrowserList.getParent().requestDisallowInterceptTouchEvent(true);
                        int viewWidth = fileBrowserSwipingView.getWidth();
                        if (viewWidth > 0 && dx >= viewWidth / 2f) {
                            fileBrowserSwipeHandled = true;
                            fileBrowserSwipingView.setTranslationX(0);
                            fileBrowserSwipingView = null;
                            if (fileBrowserSwipeStartPosition < filteredFileEntries.size()) {
                                FileEntry entry = filteredFileEntries.get(fileBrowserSwipeStartPosition);
                                if (!entry.isDirectory) {
                                    if (isPlaylistFile(entry.name)) {
                                        int addedCount = addPlaylistToQueue(entry);
                                        if (addedCount > 0)
                                            Toast.makeText(FileBrowserQueueActivity.this, "Added " + addedCount + " files from " + entry.name, Toast.LENGTH_SHORT).show();
                                        else
                                            Toast.makeText(FileBrowserQueueActivity.this, "No playable files found in " + entry.name, Toast.LENGTH_SHORT).show();
                                    } else {
                                        addToQueue(entry.name, entry.uri);
                                    }
                                }
                            }
                            return true;
                        }
                        return true;
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!fileBrowserSwipeHandled) v.performClick();
                    resetFileBrowserSwipingView();
                    boolean handled = fileBrowserSwipeHandled;
                    fileBrowserSwipeStartPosition = -1;
                    fileBrowserSwipeHandled = false;
                    return handled;

                default:
                    return false;
            }
        });
    }

    private void resetFileBrowserSwipingView() {
        if (fileBrowserSwipingView != null) {
            fileBrowserSwipingView.setTranslationX(0);
            fileBrowserSwipingView = null;
        }
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
            Toast.makeText(this, "Queue is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        if (position < 0 || position >= queueEntries.size()) {
            return;
        }

        ArrayList<Uri> uris = new ArrayList<>();
        uris.ensureCapacity(queueEntries.size());
        for (int i = position; i < queueEntries.size(); i++) {
            uris.add(queueEntries.get(i).uri);
        }

        Intent intent = new Intent(this, Launcher.class);
        intent.setAction(ACTION_SEND_MULTIPLE_COMPAT);
        intent.setType("audio/*");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        if (forceImmediateRestart) {
            sendStopNowCommand();
        }
        resetStopButtonState();
        startActivity(intent);
        playbackStopped = false;
        servicePlaybackOffset = position;
        QueueStore.savePlaybackOffset(this, servicePlaybackOffset);
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
        if (playbackStopped || (currentPlayingQueueIndex < 0 && !browseFilePlaying)) {
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
        playbackStopped = true;
        stopFadeInProgress = false;
        clearBrowseState();
        currentPlayingQueueIndex = -1;
        servicePlaybackOffset = 0;
        QueueStore.savePlaybackOffset(this, 0);
        resetCurrentTrackProgress();
        applyStopButtonState();
        queueAdapter.notifyDataSetChanged();
        fileAdapter.notifyDataSetChanged();
    }

    private void clearQueueAndStopPlayback() {
        if (!browseFilePlaying) {
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

        browseFilePlaying = true;
        browseFileUri = uri;
        browseNextQueued = false;
        browseNextUri = null;
        saveBrowseState();
        playbackStopped = false;
        currentPlayingQueueIndex = -1;
        queueAdapter.notifyDataSetChanged();
        fileAdapter.notifyDataSetChanged();
    }

    private void maybeQueueNextBrowseTrack() {
        if (!browseFilePlaying || browseNextQueued || browseFileUri == null) return;
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
                    Toast.makeText(this, "Added " + toAdd.size() + " track(s) from remote", Toast.LENGTH_SHORT).show();
                }
                for (String name : notFound) {
                    Toast.makeText(this, "Requested file not found: " + name, Toast.LENGTH_SHORT).show();
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
     * Finds all requested files in the file-based directory tree with a single DFS pass per phase.
     * Hint-matched results (parent folder name matches) take priority over plain name matches.
     */
    private List<Uri> findAllInFileDirectory(File root, List<BluetoothQueueBridge.TrackRequest> requests) {
        int n = requests.size();
        Uri[] hintMatches = new Uri[n];
        Uri[] nameMatches = new Uri[n];

        // Phase 1+2: single DFS — one query per directory for all requests simultaneously.
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
                for (int i = 0; i < n; i++) {
                    if (hintMatches[i] != null) continue;
                    if (!childName.equalsIgnoreCase(requests.get(i).file)) continue;
                    String hint = requests.get(i).parent;
                    if (hint.length() > 0 && dirName.equalsIgnoreCase(hint)) {
                        hintMatches[i] = Uri.fromFile(child);
                    } else if (nameMatches[i] == null) {
                        nameMatches[i] = Uri.fromFile(child);
                    }
                }
            }
        }

        // Phase 3: extension-stripped fallback for still-unresolved requests.
        if (anyUnresolved(hintMatches, nameMatches)) {
            ArrayList<File> extStack = new ArrayList<>();
            extStack.add(root);
            while (!extStack.isEmpty()) {
                File dir = extStack.remove(extStack.size() - 1);
                File[] children = dir.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    if (child == null) continue;
                    if (child.isDirectory()) { extStack.add(child); continue; }
                    applyExtFallbackMatch(stripExtension(child.getName()), Uri.fromFile(child),
                            requests, hintMatches, nameMatches);
                }
            }
        }

        return mergeMatchResults(hintMatches, nameMatches);
    }

    /**
     * Finds all requested files in the SAF document tree with a single DFS pass per phase.
     * Each directory is queried exactly once. Hint-matched results take priority.
     */
    private List<Uri> findAllInDocumentTree(Uri rootDocumentUri, List<BluetoothQueueBridge.TrackRequest> requests) {
        int n = requests.size();
        Uri[] hintMatches = new Uri[n];
        Uri[] nameMatches = new Uri[n];

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

        // Phase 1+2: single DFS — stack holds [docId, displayName] pairs.
        // displayName of the current dir is used to check parentHint.
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
                    for (int i = 0; i < n; i++) {
                        if (hintMatches[i] != null) continue;
                        if (!childName.equalsIgnoreCase(requests.get(i).file)) continue;
                        String hint = requests.get(i).parent;
                        if (hint.length() > 0 && dirName.equalsIgnoreCase(hint)) {
                            hintMatches[i] = DocumentsContract.buildDocumentUriUsingTree(currentTreeUri, childDocId);
                        } else if (nameMatches[i] == null) {
                            nameMatches[i] = DocumentsContract.buildDocumentUriUsingTree(currentTreeUri, childDocId);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Phase 3: extension-stripped fallback for still-unresolved requests.
        if (anyUnresolved(hintMatches, nameMatches)) {
            ArrayList<String[]> extStack = new ArrayList<>();
            extStack.add(new String[]{rootDocId, ""});
            while (!extStack.isEmpty()) {
                String[] current = extStack.remove(extStack.size() - 1);
                String dirDocId = current[0];
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(currentTreeUri, dirDocId);
                try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
                    if (cursor == null) continue;
                    while (cursor.moveToNext()) {
                        String childDocId = cursor.getString(0);
                        String childName = cursor.getString(1);
                        String mimeType = cursor.getString(2);
                        if (childDocId == null || childName == null) continue;
                        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                            extStack.add(new String[]{childDocId, childName});
                            continue;
                        }
                        applyExtFallbackMatch(stripExtension(childName),
                                DocumentsContract.buildDocumentUriUsingTree(currentTreeUri, childDocId),
                                requests, hintMatches, nameMatches);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return mergeMatchResults(hintMatches, nameMatches);
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
            Toast.makeText(this, "Sent " + requests.size() + " track(s)", Toast.LENGTH_SHORT).show();
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean anyUnresolved(Uri[] hintMatches, Uri[] nameMatches) {
        for (int i = 0; i < hintMatches.length; i++) {
            if (hintMatches[i] == null && nameMatches[i] == null) return true;
        }
        return false;
    }

    private static void applyExtFallbackMatch(String childNoExt, Uri childUri,
            List<BluetoothQueueBridge.TrackRequest> requests, Uri[] hintMatches, Uri[] nameMatches) {
        for (int i = 0; i < requests.size(); i++) {
            if (hintMatches[i] != null || nameMatches[i] != null) continue;
            if (childNoExt.equalsIgnoreCase(stripExtension(requests.get(i).file))) {
                nameMatches[i] = childUri;
            }
        }
    }

    private static List<Uri> mergeMatchResults(Uri[] hintMatches, Uri[] nameMatches) {
        List<Uri> results = new ArrayList<>(hintMatches.length);
        for (int i = 0; i < hintMatches.length; i++) {
            results.add(hintMatches[i] != null ? hintMatches[i] : nameMatches[i]);
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
        stopFadeInProgress = false;
        clearBrowseState();
        playbackStopped = true;
        servicePlaybackOffset = 0;
        QueueStore.savePlaybackOffset(this, 0);
        currentPlayingQueueIndex = -1;
        resetCurrentTrackProgress();
        applyStopButtonState();
        queueAdapter.notifyDataSetChanged();
        fileAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onStart() {
        super.onStart();
        restorePersistedQueue();
        servicePlaybackOffset = QueueStore.loadPlaybackOffset(this);
        SharedPreferences browsePrefs = getSharedPreferences(BROWSER_PREFS, MODE_PRIVATE);
        if (browsePrefs.getBoolean(PREF_BROWSE_FILE_PLAYING, false)) {
            browseFilePlaying = true;
            String uriStr = browsePrefs.getString(PREF_BROWSE_FILE_URI, null);
            browseFileUri = uriStr != null ? Uri.parse(uriStr) : null;
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
    }

    private void syncWithServiceState() {
        int serviceIndex = Service.sCurrentIndex;
        Uri serviceUri = Service.sCurrentUri;
        currentTrackPositionMs = Service.sPlaybackPositionMs;
        currentTrackDurationMs = Service.sPlaybackDurationMs;
        if (Service.sFadeOutInProgress && !stopFadeInProgress) {
            stopFadeInProgress = true;
            applyStopButtonState();
        }
        if (serviceIndex < 0) {
            SilenceStreamer.reinitIfOutputChanged(this);
            if (browseFilePlaying) {
                // Transient stop between sendStopNowCommand() and the browse track starting.
                // The broadcast receiver is authoritative for actual browse-track completion.
                return;
            }
            if (stopFadeInProgress) {
                onFadeOutFinished();
                return;
            }
            playbackStopped = true;
            currentPlayingQueueIndex = -1;
            servicePlaybackOffset = 0;
            resetCurrentTrackProgress();
            QueueStore.savePlaybackOffset(this, 0);
        } else {
            playbackStopped = false;
            if (browseFilePlaying) {
                currentPlayingQueueIndex = -1;
                if (browseNextUri != null && browseNextUri.equals(serviceUri)) {
                    browseFileUri = browseNextUri;
                    browseNextQueued = false;
                    browseNextUri = null;
                    saveBrowseState();
                } else if (serviceUri != null && browseFileUri != null && !serviceUri.equals(browseFileUri)) {
                    // Activity was recreated mid-transition; accept what the service is playing
                    browseFileUri = serviceUri;
                    browseNextQueued = false;
                    browseNextUri = null;
                    saveBrowseState();
                }
            } else {
                currentPlayingQueueIndex = resolvePlayingQueueIndex(serviceIndex, serviceUri);
            }
        }
        if (queueAdapter != null) queueAdapter.notifyDataSetChanged();
        if (fileAdapter != null && (fileBrowserPreviewingUri != null || browseFilePlaying)) fileAdapter.notifyDataSetChanged();
        maybeQueueNextBrowseTrack();
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

    private int resolvePlayingQueueIndex(int serviceIndex, Uri currentUri) {
        if (queueEntries.isEmpty()) {
            return -1;
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
        int sortBpm;
        boolean sortDateResolved;
        boolean sortGenreResolved;
        boolean sortArtistResolved;
        boolean sortBpmResolved;
        boolean sortDateLoading;
        boolean sortGenreLoading;
        boolean sortArtistLoading;
        boolean sortBpmLoading;

        FileEntry(File file, String name, boolean isDirectory) {
            this.file        = file;
            this.uri         = Uri.fromFile(file);
            this.name        = name;
            this.isDirectory = isDirectory;
            this.sortDate    = "";
            this.sortGenre   = "";
            this.sortArtist  = "";
            this.sortBpm     = 0;
            this.sortDateResolved = false;
            this.sortGenreResolved = false;
            this.sortArtistResolved = false;
            this.sortBpmResolved = false;
            this.sortDateLoading = false;
            this.sortGenreLoading = false;
            this.sortArtistLoading = false;
            this.sortBpmLoading = false;
        }

        FileEntry(Uri uri, String name, boolean isDirectory) {
            this.file        = null;
            this.uri         = uri;
            this.name        = name;
            this.isDirectory = isDirectory;
            this.sortDate    = "";
            this.sortGenre   = "";
            this.sortArtist  = "";
            this.sortBpm     = 0;
            this.sortDateResolved = false;
            this.sortGenreResolved = false;
            this.sortArtistResolved = false;
            this.sortBpmResolved = false;
            this.sortDateLoading = false;
            this.sortGenreLoading = false;
            this.sortArtistLoading = false;
            this.sortBpmLoading = false;
        }
    }

    static final class SortTagResult {
        final FileEntry entry;
        final String date;
        final String genre;
        final String artist;
        final int bpm;

        SortTagResult(FileEntry entry, String date, String genre, String artist, int bpm) {
            this.entry = entry;
            this.date = date;
            this.genre = genre;
            this.artist = artist;
            this.bpm = bpm;
        }
    }

    static final class QueueEntry {
        final String name;
        final Uri    uri;

        QueueEntry(String name, Uri uri) {
            this.name = name;
            this.uri  = uri;
        }
    }

    // -- adapters ------------------------------------------------------------

    private static final class ViewHolder {
        final TextView icon;
        final TextView name;
        final TextView meta;
        ViewHolder(View v) {
            icon = v.findViewById(R.id.file_icon);
            name = v.findViewById(R.id.file_name);
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
            vh.name.setText(entry.name);
            String metaText = "";
            if (!entry.isDirectory && fileSortMode == SORT_YEAR
                    && entry.sortDate != null && entry.sortDate.length() > 0) {
                metaText = entry.sortDate;
                if (entry.sortGenre != null && entry.sortGenre.length() > 0) {
                    metaText = metaText + "  " + entry.sortGenre;
                }
                if (entry.sortBpm > 0) {
                    metaText = metaText + "  " + entry.sortBpm + " BPM";
                }
            } else if (!entry.isDirectory && fileSortMode == SORT_GENRE
                    && entry.sortGenre != null && entry.sortGenre.length() > 0) {
                metaText = entry.sortGenre;
                if (entry.sortBpm > 0) {
                    metaText = metaText + "  " + entry.sortBpm + " BPM";
                }
                if (entry.sortDate != null && entry.sortDate.length() > 0) {
                    metaText = metaText + "  " + entry.sortDate;
                }
            } else if (!entry.isDirectory && fileSortMode == SORT_BPM && entry.sortBpm > 0) {
                metaText = entry.sortBpm + " BPM";
                if (entry.sortGenre != null && entry.sortGenre.length() > 0) {
                    metaText = metaText + "  " + entry.sortGenre;
                }
                if (entry.sortDate != null && entry.sortDate.length() > 0) {
                    metaText = metaText + "  " + entry.sortDate;
                }
            } else if (!entry.isDirectory && fileSortMode == SORT_ARTIST
                    && entry.sortArtist != null && entry.sortArtist.length() > 0) {
                metaText = entry.sortArtist;
                if (entry.sortBpm > 0) {
                    metaText = metaText + "  " + entry.sortBpm + " BPM";
                }
                else if (entry.sortGenre != null && entry.sortGenre.length() > 0) {
                    metaText = metaText + "  " + entry.sortGenre;
                }
            }
            if (metaText.length() > 0) {
                vh.meta.setText(metaText);
                vh.meta.setVisibility(View.VISIBLE);
            } else {
                vh.meta.setText("");
                vh.meta.setVisibility(View.GONE);
            }

            boolean isBrowseEntry = browseFilePlaying
                    && !entry.isDirectory
                    && browseFileUri != null
                    && browseFileUri.equals(entry.uri);
            boolean isPreviewEntry = !entry.isDirectory
                    && fileBrowserPreviewingEntryUri != null
                    && fileBrowserPreviewingEntryUri.equals(entry.uri);
            boolean hasProgress = isPreviewEntry
                    && fileBrowserPreviewingUri != null
                    && fileBrowserPreviewingUri.equals(entry.uri);
            if (isBrowseEntry) {
                float progress = 0f;
                if (currentTrackDurationMs > 0) {
                    progress = Math.min(1f, Math.max(0f,
                            currentTrackPositionMs / (float) currentTrackDurationMs));
                }
                applyProgressBackground(convertView, progress, colorPreviewBase, colorPreviewFill);
            } else if (isPreviewEntry) {
                float progress = 0f;
                if (hasProgress) {
                    long dur = SilenceStreamer.previewDurationMs;
                    if (dur > 0) {
                        progress = Math.min(1f, Math.max(0f,
                                SilenceStreamer.previewPositionMs / (float) dur));
                    }
                }
                applyProgressBackground(convertView, progress, colorPreviewBase, colorPreviewFill);
            } else {
                convertView.setBackgroundColor(colorBackground);
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
            // reset translation in case this view was recycled mid-swipe
            convertView.setTranslationX(0);

            boolean isCurrentTrack = position == currentPlayingQueueIndex
                    && (!playbackStopped || stopFadeInProgress);
            if (isCurrentTrack) {
                float progress = 0f;
                if (currentTrackDurationMs > 0) {
                    progress = Math.min(1f,
                            Math.max(0f, currentTrackPositionMs / (float) currentTrackDurationMs));
                }
                applyProgressBackground(convertView, progress, colorQueueBase, colorQueueFill);
            } else {
                convertView.setBackgroundColor(colorBackground);
            }
            vh.icon.setText("\uD83C\uDFB5");
            vh.name.setText(entry.name);
            vh.meta.setText("");
            vh.meta.setVisibility(View.GONE);
            return convertView;
        }
    }
}

