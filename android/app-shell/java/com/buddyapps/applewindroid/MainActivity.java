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
    private RetroAchievements achievements;

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
        startAchievements();
        showSavedDisk();
    }

    // RetroAchievements (casual/softcore only): unlocks are shown as toasts, the 🏆 button logs in
    // or shows progress for the running disk.
    private void startAchievements() {
        String version = "1.0";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        achievements = new RetroAchievements(this, coreExecutor, mainHandler, version);
        achievements.setUi(new RetroAchievements.Ui() {
            @Override public void onLogin(boolean ok, String message) {
                Toast.makeText(MainActivity.this, ok ? "RetroAchievements: logged in as " + message
                        : "RetroAchievements login failed: " + message, Toast.LENGTH_LONG).show();
            }
            @Override public void onGame(boolean ok, String message) {
                if (ok) Toast.makeText(MainActivity.this, "🏆 " + message, Toast.LENGTH_LONG).show();
                else Toast.makeText(MainActivity.this, "RetroAchievements: " + message, Toast.LENGTH_LONG).show();
            }
            @Override public void onUnlock(String title, String description, int points) {
                Toast.makeText(MainActivity.this, "🏆 Achievement unlocked: " + title + " (" + points + ")\n" + description,
                        Toast.LENGTH_LONG).show();
            }
            @Override public void onNotice(String message) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
        try {
            achievements.start();
        } catch (Throwable error) {
            lastJavaError = "RetroAchievements: " + error;
        }
    }

    private void showAchievements() {
        achievements.status((name, summary) -> {
            if (name == null) {
                showRaLogin();
                return;
            }
            String body = summary.isEmpty()
                    ? "Load a disk to see its achievements. Not every Apple II disk has a set; the disk image must match the one RetroAchievements lists for the game."
                    : summary;
            TextView content = new TextView(this);
            content.setText("Logged in as " + name + " (casual mode)\n\n" + body);
            content.setPadding(dp(16), dp(12), dp(16), dp(12));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(content);
            new AlertDialog.Builder(this).setTitle("RetroAchievements").setView(scroll)
                    .setPositiveButton("OK", null)
                    .setNegativeButton("Log out", (d, w) -> {
                        achievements.logout();
                        Toast.makeText(this, "Logged out of RetroAchievements", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        });
    }

    private void showRaLogin() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), 0);
        TextView note = new TextView(this);
        note.setText("Log in with your retroachievements.org account. Your password is sent once and not saved; the app keeps a login token.");
        android.widget.EditText user = new android.widget.EditText(this);
        user.setHint("Username");
        user.setSingleLine(true);
        String saved = achievements.savedUser();
        if (saved != null) user.setText(saved);
        android.widget.EditText password = new android.widget.EditText(this);
        password.setHint("Password");
        password.setSingleLine(true);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(note);
        form.addView(user);
        form.addView(password);
        new AlertDialog.Builder(this).setTitle("RetroAchievements").setView(form)
                .setPositiveButton("Log in", (d, w) -> {
                    String u = user.getText().toString().trim(), p = password.getText().toString();
                    if (u.isEmpty() || p.isEmpty()) return;
                    Toast.makeText(this, "Logging in…", Toast.LENGTH_SHORT).show();
                    achievements.login(u, p);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // Laid out like AppleWin on Windows: the Apple screen on the left and AppleWin's vertical
    // toolbar on the right (Help, Run, Drive 1, Full screen, Debug, Setup) with the drive light.
    private static final int BAR = 0xff2b2b2b, FACE = 0xff3c3c3c, EDGE = 0xff1a1a1a, LABEL = 0xffe6e6e6;
    private View toolbar;
    private View driveLight;
    private boolean fullscreen;

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);

        FrameLayout display = new FrameLayout(this);
        display.setBackgroundColor(Color.BLACK);
        emulatorSurface = new SurfaceView(this);
        emulatorSurface.getHolder().addCallback(this);
        display.addView(emulatorSurface, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(8), dp(4), dp(8), dp(4));
        controls.setBackgroundColor(0x66000000);
        addControl(controls, "↑", BUTTON_UP);
        addControl(controls, "←", BUTTON_LEFT);
        addControl(controls, "↓", BUTTON_DOWN);
        addControl(controls, "→", BUTTON_RIGHT);
        View spacer = new View(this);
        controls.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        addControl(controls, "B", BUTTON_B);
        addControl(controls, "A", BUTTON_A);
        display.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(display, new LinearLayout.LayoutParams(0, -1, 1f));

        ScrollView barScroll = new ScrollView(this);
        barScroll.setBackgroundColor(BAR);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.addView(toolButton("?", "Help", v -> showHelp()));
        bar.addView(toolButton("▶", "Run", v -> runOrReboot()));
        bar.addView(toolButton("1", "Drive 1", v -> openDiskPicker()));
        bar.addView(toolButton("□", "Full screen", v -> setFullscreen(true)));
        bar.addView(toolButton("🐞", "Debug", v -> showDiagnostics()));
        bar.addView(toolButton("⚙", "Setup", v -> showSetup()));
        bar.addView(toolButton("🏆", "Achievements", v -> showAchievements()));
        LinearLayout lights = new LinearLayout(this);
        lights.setGravity(Gravity.CENTER);
        lights.setPadding(0, dp(10), 0, dp(4));
        TextView driveLabel = new TextView(this);
        driveLabel.setText("D1 ");
        driveLabel.setTextColor(LABEL);
        driveLabel.setTextSize(11);
        lights.addView(driveLabel);
        driveLight = new View(this);
        lights.addView(driveLight, new LinearLayout.LayoutParams(dp(14), dp(8)));
        bar.addView(lights);
        diskStatus = new TextView(this);
        diskStatus.setTextColor(0xffa8b0bc);
        diskStatus.setTextSize(10);
        diskStatus.setGravity(Gravity.CENTER);
        diskStatus.setOnClickListener(view -> showDiagnostics());
        bar.addView(diskStatus, new LinearLayout.LayoutParams(-1, -2));
        barScroll.addView(bar);
        toolbar = barScroll;
        root.addView(barScroll, new LinearLayout.LayoutParams(dp(92), -1));
        setDriveLight(false);
        return root;
    }

    /** One AppleWin toolbar button: a symbol over its name, on a raised dark face. */
    private View toolButton(String symbol, String name, View.OnClickListener action) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setContentDescription(name);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[] { android.R.attr.state_pressed }, face(0xff5a5a5a, EDGE));
        states.addState(new int[] { android.R.attr.state_focused }, face(FACE, 0xff3d8ee6));
        states.addState(new int[0], face(FACE, EDGE));
        b.setBackground(states);
        TextView icon = new TextView(this);
        icon.setText(symbol);
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(20);
        icon.setGravity(Gravity.CENTER);
        b.addView(icon);
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(LABEL);
        label.setTextSize(10);
        label.setGravity(Gravity.CENTER);
        b.addView(label);
        b.setOnClickListener(action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.setMargins(0, 0, 0, dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private android.graphics.drawable.GradientDrawable face(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(3));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private void setDriveLight(boolean on) {
        if (driveLight == null) return;
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(on ? 0xff22dd44 : 0xff1f3a24);
        d.setCornerRadius(dp(2));
        driveLight.setBackground(d);
    }

    /** Run (AppleWin's F2): restart with the disk in drive 1, or ask for one. */
    private void runOrReboot() {
        String path = getPreferences(MODE_PRIVATE).getString("disk_path", null);
        if (path == null || !new File(path).isFile()) { openDiskPicker(); return; }
        startGame(new File(path));
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        toolbar.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) Toast.makeText(this, "Press Back to show the toolbar", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) setFullscreen(false);
        else super.onBackPressed();
    }

    private void showHelp() {
        info("AppleWin help",
                "Run: restart the Apple with the disk in drive 1.\n"
                + "Drive 1: insert a disk image (.dsk, .do, .po, .nib, .woz or .zip).\n"
                + "Full screen: hide this toolbar; press Back to bring it back.\n"
                + "Debug: the emulator's diagnostic log, for bug reports.\n"
                + "Setup: the AppleWin configuration.\n"
                + "🏆: RetroAchievements log in and progress (casual mode).\n\n"
                + "The arrows and A/B over the screen are the joystick; a Bluetooth controller works too.");
    }

    /** AppleWin's Configuration dialog, with its tabs. */
    private void showSetup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout tabs = new LinearLayout(this);
        TextView body = new TextView(this);
        body.setTextSize(14);
        body.setPadding(0, dp(12), 0, 0);
        String[] names = { "Configuration", "Input", "Sound", "Disk" };
        TextView[] tabViews = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int which = i;
            TextView tab = new TextView(this);
            tab.setText(names[i]);
            tab.setTextSize(13);
            tab.setPadding(dp(10), dp(6), dp(10), dp(6));
            tab.setOnClickListener(v -> setupTab(which, tabViews, body));
            tabViews[i] = tab;
            tabs.addView(tab);
        }
        android.widget.HorizontalScrollView tabScroll = new android.widget.HorizontalScrollView(this);
        tabScroll.addView(tabs);
        box.addView(tabScroll);
        box.addView(body);
        setupTab(0, tabViews, body);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("AppleWin Configuration")
                .setView(scroll)
                .setPositiveButton("OK", null)
                .setNeutralButton("Insert disk", (d, w) -> openDiskPicker())
                .show();
    }

    private void setupTab(int which, TextView[] tabs, TextView body) {
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTypeface(Typeface.DEFAULT, i == which ? Typeface.BOLD : Typeface.NORMAL);
            tabs[i].setBackgroundColor(i == which ? 0x223d8ee6 : Color.TRANSPARENT);
        }
        String name = getPreferences(MODE_PRIVATE).getString("disk_name", null);
        switch (which) {
            case 1: body.setText("Joystick: the on-screen arrows with A (button 0) and B (button 1), or a Bluetooth controller's d-pad and A/B."); break;
            case 2: body.setText("Speaker and Mockingboard sound aren't connected on Android yet."); break;
            case 3: body.setText("Drive 1: " + (name == null ? "empty" : name) + "\nDrive 2: not available on Android yet.\n\nSave states aren't connected yet."); break;
            default: body.setText("Emulated computer: the AppleWin core's default model.\nVideo: shown at the Apple's own resolution, scaled to fit the screen.\nCore: " + (coreInitialized ? "running" : "not started yet") + ".");
        }
    }

    private void info(String title, String text) {
        TextView content = new TextView(this);
        content.setText(text);
        content.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("OK", null).show();
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
                    setDriveLight(gameLoaded);
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
        if (achievements != null) achievements.shutdown();
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
