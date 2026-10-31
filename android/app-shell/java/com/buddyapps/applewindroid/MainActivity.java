package com.buddyapps.applewindroid;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final int PICK_DISK_IMAGE = 1001;
    private TextView diskStatus;

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
        root.setPadding(dp(24), dp(18), dp(24), dp(18));
        root.setBackgroundColor(Color.rgb(16, 19, 25));

        TextView title = new TextView(this);
        title.setText("AppleWinDroid");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Apple II emulator for Android");
        subtitle.setTextColor(Color.rgb(172, 184, 201));
        subtitle.setTextSize(14);
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        TextView display = new TextView(this);
        display.setText("Android display is ready\n\nThe Apple II core is not connected yet.");
        display.setTextColor(Color.rgb(191, 204, 220));
        display.setTextSize(18);
        display.setGravity(Gravity.CENTER);
        display.setBackgroundColor(Color.rgb(4, 7, 10));
        LinearLayout.LayoutParams displayParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        displayParams.topMargin = dp(16);
        displayParams.bottomMargin = dp(16);
        root.addView(display, displayParams);

        diskStatus = new TextView(this);
        diskStatus.setTextColor(Color.WHITE);
        diskStatus.setTextSize(14);
        root.addView(diskStatus, new LinearLayout.LayoutParams(-1, -2));

        Button chooseDisk = new Button(this);
        chooseDisk.setText("Choose disk image");
        chooseDisk.setOnClickListener(view -> openDiskPicker());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, -2);
        buttonParams.topMargin = dp(8);
        root.addView(chooseDisk, buttonParams);

        TextView note = new TextView(this);
        note.setText("Supports selecting .dsk, .do, and .po images. Emulation and controls are not connected yet.");
        note.setTextColor(Color.rgb(172, 184, 201));
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));
        return root;
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
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
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
        String uniqueName = UUID.randomUUID().toString().substring(0, 8) + "-" + safeName;
        File destination = new File(directory, uniqueName);
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IllegalStateException("No data returned for disk image");
            }
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        return destination;
    }

    private void showSavedDisk() {
        String path = getPreferences(MODE_PRIVATE).getString("disk_path", null);
        String name = getPreferences(MODE_PRIVATE).getString("disk_name", null);
        if (path == null || name == null) {
            diskStatus.setText("No disk image selected");
            return;
        }
        File file = new File(path);
        if (file.isFile()) {
            showDisk(name, file);
        } else {
            diskStatus.setText("No disk image selected");
        }
    }

    private void showDisk(String name, File file) {
        String size = String.format(Locale.getDefault(), "%.1f MB", file.length() / (1024.0 * 1024.0));
        diskStatus.setText("Selected: " + name + " (" + size + ")");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
