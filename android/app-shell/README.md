# Android app shell

This plain Java activity provides the first Android-facing shell for AppleWinDroid. It opens Android's system document picker and copies a selected disk image into the app's private storage, ready for the native frontend to consume.

The Android JNI host adapter now connects core lifecycle, disk loading, video frames, and joypad input to Android native APIs. CI compiles both the core and adapter. The simple Java APK builder does not yet package those native libraries, and the activity does not yet start the adapter; audio, touch controls, and app packaging integration remain future work.

Build with the workspace Android app skill:

```sh
bash /root/.buddy/work/.claude/skills/android-app/build-apk.sh /root/.buddy/work/AppleWinDroid/android/app-shell
```
