# AppleWinDroid Android port plan

## Current status

This fork is based on `RetroAchievements/AppleWin`. It now has an Android Java app shell, a disk image picker, and a JNI host bridge for core lifecycle, disk loading, video surfaces, and joypad input. GitHub Actions successfully compiles the ARM64 libretro core and JNI bridge. The APK builder and activity do not yet package or call the native libraries. The original AppleWin host layer is tightly coupled to Windows APIs: the source tree contains 94 C++ files and hundreds of uses of Win32 window, input, sound, and drawing APIs. `source/StdAfx.h` includes Windows multimedia and UI headers for the MSVC build.

The RetroAchievements path is also Windows-specific. AppleWin's `RAInterface` submodule loads `RA_Integration.dll` with `LoadLibraryW`, exposes `HWND` and Win32 menu/render APIs, and downloads the integration DLL. That interface cannot be used as-is by an Android app.

## Porting approach

Keep the Apple II emulation core and replace the Windows host layer with Android-native components. Build the native emulator with the Android NDK and CMake; use an Android activity for app lifecycle, file selection, settings, touch/controller input, and overlays. Preserve existing AppleWin file formats and core behavior where the host APIs allow it.

There is already an Android AppleWin-based project, [FujiNet Go Apple2](https://github.com/FujiNetWIFI/fujinet-go-apple2), which builds an AppleWin libretro core into an Android native library and drives video, audio, and input from an Android host. It is a useful implementation reference, but it uses a different Linux AppleWin fork and does not include this fork's RetroAchievements integration. Before copying code, compare source revisions, licenses, and the core feature set.

The native-core build pins the related [FujiNetWIFI/AppleWin `linux` branch](https://github.com/FujiNetWIFI/AppleWin/tree/linux) as `android/applewin-core` at commit `25e8ddd1`. This gives the Android build a CMake/libretro target while leaving the Windows solution and sources at the repository root untouched. The Java shell APK builds at `android/app-shell`. The JNI bridge and core still need to be packaged and connected to the activity.

### Milestones

1. **Build inventory:** map the emulation core's dependencies on Windows, DirectDraw, DirectSound, DirectInput, MFC, and registry/configuration APIs. Record existing AppleWin and RetroAchievements features so parity is measurable.
2. **Android build shell:** partial. The Java activity and disk image picker build as an APK. The NDK/CMake workflow builds the ARM64 core and a JNI adapter for lifecycle, disk loading, video, and joypad input. Remaining work is audio, touch/keyboard input, native library packaging, and connecting the frontend to the activity.
3. **First boot:** connect the Apple II CPU, memory, disk, video, and audio core to Android. Support user-selected ROM and disk images without bundling copyrighted media. Add touch controls and physical keyboard/controller input.
4. **Core features:** port configuration, save states, disk management, machine variants, cards, debugger, and remaining input/audio/video features. Track each feature against the Windows app before calling the port complete.
5. **RetroAchievements:** replace the DLL bridge with the portable `rcheevos` client/runtime, implement Android HTTP transport and credential storage, expose the Apple II memory map, and draw an Android achievements overlay. Carry over AppleWin's game identification, hard-core restrictions, reset/media-change behavior, and achievement lifecycle.
6. **Parity and release:** test supported machine types, disk formats, save-state behavior, controllers, and RetroAchievements online/offline flows on Android devices. Package repeatable signed builds without putting account credentials or signing keys in the repository.

## RetroAchievements constraints

`rcheevos` provides achievement and leaderboard processing, hashing, and client/runtime helpers, but it does not perform HTTP requests. The Android port therefore needs its own network transport and UI. The current `RAInterface` DLL loader remains useful as a reference for AppleWin's existing lifecycle behavior, but not as the Android implementation. See the official [rcheevos integration overview](https://github.com/RetroAchievements/rcheevos#runtime-support).

## First implementation target

Next connect the ARM64 libretro core to the Android activity. The first playable slice should boot one Apple II model from user-provided system ROM and floppy images, render video, produce sound, accept touch and physical input, and support save/load states. Once that slice works, add RetroAchievements through `rcheevos` and expand feature parity in small, reviewable pieces.

## Toolchain note

The workspace's simple APK script builds the Java shell but does not package native libraries. GitHub Actions uses a pinned NDK/CMake toolchain to compile the core and JNI adapter; the Android package still needs native library packaging and activity integration.
