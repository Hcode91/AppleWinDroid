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
}
