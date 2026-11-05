package com.buddyapps.applewindroid;

/** JNI surface for the Android libretro host. Loaded when the native library is packaged. */
final class NativeCore {
    static {
        System.loadLibrary("applewindroid_jni");
    }

    private NativeCore() {}

    static native boolean nativeInitialize(String saveDirectory, String systemDirectory);
    static native boolean nativeLoadGame(String path);
    static native String nativeGetDiagnostics();
    static native void nativeRunFrame();
    static native void nativeSetSurface(android.view.Surface surface);
    static native void nativeSetButton(int libretroButton, boolean pressed);
    static native void nativeStop();

    // RetroAchievements (casual/softcore only). Call these on the core thread.
    static native boolean nativeRaInit();
    static native String nativeRaUserAgentClause();
    static native void nativeRaLoginPassword(String user, String password);
    static native void nativeRaLoginToken(String user, String token);
    /** {username, token, display name}, or null when not logged in. */
    static native String[] nativeRaUser();
    static native void nativeRaLogout();
    static native void nativeRaHttpResponse(int id, int httpStatus, byte[] body);
    static native String nativeRaSummary();

    /** Receives RetroAchievements callbacks from native code (always on the core thread). */
    interface RaListener {
        void onHttpRequest(int id, String url, String postData, String contentType);
        void onEvent(int type, String title, String description, int points);
        void onResult(int kind, int result, String message);
    }

    static volatile RaListener raListener;

    static void onRaHttpRequest(int id, String url, String postData, String contentType) {
        RaListener l = raListener;
        if (l != null) l.onHttpRequest(id, url, postData, contentType);
        else nativeRaHttpResponse(id, -2, null);
    }

    static void onRaEvent(int type, String title, String description, int points) {
        RaListener l = raListener;
        if (l != null) l.onEvent(type, title, description, points);
    }

    static void onRaResult(int kind, int result, String message) {
        RaListener l = raListener;
        if (l != null) l.onResult(kind, result, message);
    }
}
