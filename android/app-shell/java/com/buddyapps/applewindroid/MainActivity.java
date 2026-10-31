package com.buddyapps.applewindroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final int PICK_DISK_IMAGE = 1001;
    private static final int BUTTON_B = 0;
    private static final int BUTTON_A = 8;
    private static final int BUTTON_DOWN = 13;
    private static final int BUTTON_LEFT = 14;
    private static final int BUTTON_RIGHT = 15;
    private static final int BUTTON_UP = 12;

    private final Handler mainHandler = new Handler();
    private final ScheduledThreadPoolExecutor coreExecutor = new ScheduledThreadPoolExecutor(1);
    private TextView diskStatus;
    private SurfaceView emulatorSurface;
    private volatile boolean coreInitialized;
    private volatile boolean gameLoaded;
    private volatile String lastJavaError = "";
    private ScheduledFuture<?> frameTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        setContentView(createContent());
        showSavedDisk();
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(10), dp(20), dp(10));
        root.setBackgroundColor(Color.rgb(16, 19, 25));

        TextView title = new TextView(this);
        title.setText("AppleWinDroid");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout display = new FrameLayout(this);
        display.setBackgroundColor(Color.BLACK);
        emulatorSurface = new SurfaceView(this);
        emulatorSurface.getHolder().addCallback(this);
        display.addView(emulatorSurface, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(8), dp(4), dp(8), dp(4));
        controls.setBackgroundColor(0x99000000);
        addControl(controls, "↑", BUTTON_UP);
        addControl(controls, "←", BUTTON_LEFT);
        addControl(controls, "↓", BUTTON_DOWN);
        addControl(controls, "→", BUTTON_RIGHT);
        View spacer = new View(this);
        controls.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        addControl(controls, "B", BUTTON_B);
        addControl(controls, "A", BUTTON_A);
        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                -1, -2, Gravity.BOTTOM);
        display.addView(controls, controlsParams);
        LinearLayout.LayoutParams displayParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        displayParams.topMargin = dp(8);
        displayParams.bottomMargin = dp(8);
        root.addView(display, displayParams);

        diskStatus = new TextView(this);
        diskStatus.setTextColor(Color.WHITE);
        diskStatus.setTextSize(14);
        diskStatus.setOnClickListener(view -> showDiagnostics());
        root.addView(diskStatus, new LinearLayout.LayoutParams(-1, -2));

        Button chooseDisk = new Button(this);
        chooseDisk.setText("Choose disk image");
        chooseDisk.setOnClickListener(view -> openDiskPicker());
        root.addView(chooseDisk, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText("Touch controls are shown over the screen. Audio and save states are not connected yet.");
        note.setTextColor(Color.rgb(172, 184, 201));
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    private void addControl(LinearLayout parent, String label, int button) {
        Button control = new Button(this);
        control.setText(label);
        control.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                setButton(button, true);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                setButton(button, false);
                return true;
            }
            return true;
        });
        parent.addView(control, new LinearLayout.LayoutParams(dp(52), dp(48)));
    }

    private void setButton(int button, boolean pressed) {
        if (coreInitialized) NativeCore.nativeSetButton(button, pressed);
    }

    private void openDiskPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/octet-stream", "application/x-apple-diskimage", "application/zip"
        });
        startActivityForResult(intent, PICK_DISK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_DISK_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            String displayName = getDisplayName(uri);
            File savedFile = copyDiskImage(uri, displayName);
            getPreferences(MODE_PRIVATE).edit()
                    .putString("disk_path", savedFile.getAbsolutePath())
                    .putString("disk_name", displayName)
                    .apply();
            showDisk(displayName, savedFile);
            startGame(savedFile);
        } catch (Exception error) {
            Toast.makeText(this, "Could not open that disk image", Toast.LENGTH_LONG).show();
        }
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        }
        return "disk-image";
    }

    private File copyDiskImage(Uri uri, String displayName) throws Exception {
        File directory = new File(getFilesDir(), "disk-images");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create disk image storage");
        }
        String safeName = displayName.replaceAll("[^A-Za-z0-9._-]", "_");
        File destination = new File(directory, UUID.randomUUID().toString().substring(0, 8) + "-" + safeName);
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IllegalStateException("No data returned for disk image");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        return destination;
    }

    private void startGame(File diskImage) {
        diskStatus.setText("Starting " + diskImage.getName() + "…");
        coreExecutor.execute(() -> {
            try {
                if (!coreInitialized) {
                    File saves = new File(getFilesDir(), "saves");
                    File system = new File(getFilesDir(), "system");
                    saves.mkdirs();
                    system.mkdirs();
                    if (!NativeCore.nativeInitialize(saves.getAbsolutePath(), system.getAbsolutePath())) {
                        throw new IllegalStateException("Native core initialization failed");
                    }
                    coreInitialized = true;
                    Surface surface = emulatorSurface.getHolder().getSurface();
                    if (surface != null && surface.isValid()) NativeCore.nativeSetSurface(surface);
                }
                gameLoaded = NativeCore.nativeLoadGame(diskImage.getAbsolutePath());
                mainHandler.post(() -> {
                    if (gameLoaded) {
                        diskStatus.setText("Running: " + diskImage.getName());
                        startFrames();
                    } else {
                        diskStatus.setText("Could not load: " + diskImage.getName() + " · tap for debug log");
                    }
                });
            } catch (Throwable error) {
                lastJavaError = error.getClass().getSimpleName() + ": " + error.getMessage();
                mainHandler.post(() -> diskStatus.setText("Core startup error · tap for debug log"));
            }
        });
    }

    private void showDiagnostics() {
        String details;
        try {
            details = NativeCore.nativeGetDiagnostics();
        } catch (Throwable error) {
            details = "Could not read native diagnostics: " + error;
        }
        if (!lastJavaError.isEmpty()) details += "\nJava error: " + lastJavaError;
        final String debugLog = details;
        TextView content = new TextView(this);
        content.setText(debugLog.isEmpty() ? "No diagnostics recorded yet." : debugLog);
        content.setTextIsSelectable(true);
        content.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle("AppleWin debug log")
                .setView(scroll)
                .setPositiveButton("Copy log", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("AppleWin debug log", debugLog));
                    Toast.makeText(this, "Debug log copied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void startFrames() {
        if (frameTask != null && !frameTask.isCancelled()) return;
        frameTask = coreExecutor.scheduleAtFixedRate(
                () -> {
                    if (gameLoaded) NativeCore.nativeRunFrame();
                }, 0, 16, TimeUnit.MILLISECONDS);
    }

    private void showSavedDisk() {
        String path = getPreferences(MODE_PRIVATE).getString("disk_path", null);
        String name = getPreferences(MODE_PRIVATE).getString("disk_name", null);
        if (path == null || name == null || !new File(path).isFile()) {
            diskStatus.setText("No disk image selected");
            return;
        }
        File file = new File(path);
        String size = String.format(Locale.getDefault(), "%.1f MB", file.length() / (1024.0 * 1024.0));
        diskStatus.setText("Selected: " + name + " (" + size + ")");
    }

    private void showDisk(String name, File file) {
        String size = String.format(Locale.getDefault(), "%.1f MB", file.length() / (1024.0 * 1024.0));
        diskStatus.setText("Selected: " + name + " (" + size + ")");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gameLoaded) startFrames();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int button = buttonForKey(event.getKeyCode());
        if (button >= 0) {
            setButton(button, event.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private int buttonForKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return BUTTON_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return BUTTON_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return BUTTON_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return BUTTON_DOWN;
            case KeyEvent.KEYCODE_BUTTON_A: return BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B: return BUTTON_B;
            default: return -1;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (coreInitialized) NativeCore.nativeSetSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (coreInitialized) NativeCore.nativeSetSurface(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (coreInitialized) NativeCore.nativeSetSurface(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (frameTask != null) {
            frameTask.cancel(false);
            frameTask = null;
        }
    }

    @Override
    protected void onDestroy() {
        coreExecutor.execute(() -> {
            if (coreInitialized) NativeCore.nativeStop();
        });
        coreExecutor.shutdown();
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
