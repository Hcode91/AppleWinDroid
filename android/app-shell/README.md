# Android app shell

This plain Java activity provides the first Android-facing shell for AppleWinDroid. It opens Android's system document picker and copies a selected disk image into the app's private storage, ready for the native frontend to consume.

The Android JNI host adapter connects core lifecycle, disk loading, video frames, and joypad input to Android native APIs. The activity starts the core after a disk is selected, renders frames to a `SurfaceView`, and maps touch and physical joypad input. Audio and save states are not connected yet.

Future RetroAchievements support is casual/softcore only. The Android app must not expose or implement hardcore mode.

To build locally, place `libapplewindroid_jni.so` and `libapplewin_libretro.so` under `native-libs/arm64-v8a/`, then run:

```sh
bash /root/.buddy/work/AppleWinDroid/android/app-shell/build-apk-with-native.sh
```

The workflow compiles the ARM64 libraries, assembles and verifies the signed APK, then uploads it as the `AppleWinDroid-Android-arm64-apk` Actions artifact for 30 days. The APK includes the core's AppleWin ROM resources.
