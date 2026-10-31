# Android app shell

This plain Java activity provides the first Android-facing shell for AppleWinDroid. It opens Android's system document picker and copies a selected disk image into the app's private storage, ready for the native frontend to consume.

The libretro ARM64 core currently builds in CI, but this shell does not yet load the core or emulate a disk. The next native frontend work must connect libretro callbacks to Android video, audio, input, and lifecycle APIs.

Build with the workspace Android app skill:

```sh
bash /root/.buddy/work/.claude/skills/android-app/build-apk.sh /root/.buddy/work/AppleWinDroid/android/app-shell
```
