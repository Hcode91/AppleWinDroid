package com.buddyapps.applewindroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * RetroAchievements for AppleWinDroid (casual/softcore only).
 *
 * <p>rcheevos runs on the core thread and asks for HTTP requests through {@link NativeCore}; they are
 * made here on a network thread and the response is handed back on the core thread. Only the
 * username and the login token are stored; the password is sent once and never saved.
 */
final class RetroAchievements implements NativeCore.RaListener {
    static final int RESULT_LOGIN = 1, RESULT_GAME = 2;
    static final int EVENT_ACHIEVEMENT_TRIGGERED = 1, EVENT_GAME_COMPLETED = 15, EVENT_SERVER_ERROR = 16;

    interface Ui {
        void onLogin(boolean ok, String message);
        void onGame(boolean ok, String message);
        void onUnlock(String title, String description, int points);
        void onNotice(String message);
    }

    private static final String PREFS = "retroachievements", KEY_USER = "user", KEY_TOKEN = "token";

    private final SharedPreferences prefs;
    private final ScheduledThreadPoolExecutor core;
    private final Handler main;
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private final String appVersion;
    private volatile Ui ui;
    private volatile boolean ready;
    private volatile String userAgent = "AppleWinDroid";

    RetroAchievements(Context context, ScheduledThreadPoolExecutor core, Handler main, String appVersion) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.core = core;
        this.main = main;
        this.appVersion = appVersion;
    }

    void setUi(Ui ui) { this.ui = ui; }

    /** Creates the client and logs back in with a saved token. Call once, any thread. */
    void start() {
        core.execute(() -> {
            NativeCore.raListener = this;
            ready = NativeCore.nativeRaInit();
            if (!ready) return;
            userAgent = "AppleWinDroid/" + appVersion + " " + NativeCore.nativeRaUserAgentClause();
            String user = prefs.getString(KEY_USER, null), token = prefs.getString(KEY_TOKEN, null);
            if (user != null && token != null) NativeCore.nativeRaLoginToken(user, token);
        });
    }

    boolean isReady() { return ready; }

    String savedUser() { return prefs.getString(KEY_USER, null); }

    void login(String user, String password) {
        core.execute(() -> { if (ready) NativeCore.nativeRaLoginPassword(user, password); });
    }

    void logout() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER).apply();
        core.execute(() -> { if (ready) NativeCore.nativeRaLogout(); });
    }

    /** Runs on the core thread and returns {display name or null, summary text} to the main thread. */
    void status(java.util.function.BiConsumer<String, String> out) {
        core.execute(() -> {
            String[] user = ready ? NativeCore.nativeRaUser() : null;
            String summary = ready ? NativeCore.nativeRaSummary() : "";
            String name = user == null ? null : user[2];
            main.post(() -> out.accept(name, summary));
        });
    }

    @Override
    public void onHttpRequest(int id, String url, String postData, String contentType) {
        network.execute(() -> {
            int status;
            byte[] body = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", userAgent);
                if (postData != null) {
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type",
                            contentType == null || contentType.isEmpty() ? "application/x-www-form-urlencoded" : contentType);
                    try (OutputStream o = connection.getOutputStream()) {
                        o.write(postData.getBytes(StandardCharsets.UTF_8));
                    }
                }
                status = connection.getResponseCode();
                InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                body = in == null ? new byte[0] : readAll(in);
            } catch (Exception error) {
                status = -2; // retryable client error: rcheevos tries again later
            } finally {
                if (connection != null) connection.disconnect();
            }
            final int s = status;
            final byte[] b = body;
            try {
                core.execute(() -> NativeCore.nativeRaHttpResponse(id, s, b));
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // App closing.
            }
        });
    }

    @Override
    public void onEvent(int type, String title, String description, int points) {
        Ui u = ui;
        if (u == null) return;
        if (type == EVENT_ACHIEVEMENT_TRIGGERED) main.post(() -> u.onUnlock(title, description, points));
        else if (type == EVENT_GAME_COMPLETED) main.post(() -> u.onNotice("Mastered " + title + "!"));
        else if (type == EVENT_SERVER_ERROR) main.post(() -> u.onNotice("RetroAchievements: " + description));
    }

    @Override
    public void onResult(int kind, int result, String message) {
        if (kind == RESULT_LOGIN && result == 0) {
            String[] user = NativeCore.nativeRaUser(); // already on the core thread
            if (user != null) prefs.edit().putString(KEY_USER, user[0]).putString(KEY_TOKEN, user[1]).apply();
        } else if (kind == RESULT_LOGIN) {
            prefs.edit().remove(KEY_TOKEN).apply();
        }
        Ui u = ui;
        if (u == null) return;
        boolean ok = result == 0;
        if (kind == RESULT_LOGIN) main.post(() -> u.onLogin(ok, message));
        else main.post(() -> u.onGame(ok, message));
    }

    void shutdown() { network.shutdownNow(); }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
