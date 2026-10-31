RAppleWin
========

Modified version of [AppleWin](https://github.com/AppleWin/AppleWin) supporting RetroAchievements.

Android port work is tracked in [ANDROID_PORTING_PLAN.md](ANDROID_PORTING_PLAN.md). The ARM64 libretro core and JNI host bridge build through GitHub Actions. The Android activity loads selected disk images, displays native video, and accepts touch and physical joypad input. Future RetroAchievements support is casual/softcore only; Android will have no hardcore mode. `android/app-shell/build-apk-with-native.sh` packages the two local ARM64 libraries into a signed APK.
