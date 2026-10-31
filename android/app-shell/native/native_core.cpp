#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include "libretro.h"

#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <map>
#include <mutex>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {
constexpr const char *kTag = "AppleWinDroid";
ANativeWindow *g_window = nullptr;
std::mutex g_mutex;
std::mutex g_diagnostics_mutex;
std::string g_save_directory;
std::string g_system_directory;
std::string g_diagnostics;
std::map<std::string, std::string> g_options;
std::atomic<uint16_t> g_buttons{0};
bool g_initialized = false;
bool g_game_loaded = false;
unsigned g_width = 0;
unsigned g_height = 0;

void log_message(int priority, const char *format, ...) {
    va_list args;
    va_start(args, format);
    __android_log_vprint(priority, kTag, format, args);
    va_end(args);
}

void append_diagnostic(const std::string &message) {
    std::lock_guard<std::mutex> lock(g_diagnostics_mutex);
    g_diagnostics += message;
    if (g_diagnostics.size() > 12000) {
        const size_t trim_to = g_diagnostics.size() - 12000;
        const size_t newline = g_diagnostics.find('\n', trim_to);
        g_diagnostics.erase(0, newline == std::string::npos ? trim_to : newline + 1);
    }
}

void core_log(enum retro_log_level level, const char *format, ...) {
    int priority = ANDROID_LOG_INFO;
    if (level == RETRO_LOG_WARN) priority = ANDROID_LOG_WARN;
    if (level == RETRO_LOG_ERROR) priority = ANDROID_LOG_ERROR;
    if (level == RETRO_LOG_DEBUG) priority = ANDROID_LOG_DEBUG;
    char message[2048];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    __android_log_write(priority, kTag, message);
    append_diagnostic(message);
}

std::string trim(std::string value) {
    const auto first = value.find_first_not_of(" \t");
    if (first == std::string::npos) return {};
    const auto last = value.find_last_not_of(" \t");
    return value.substr(first, last - first + 1);
}

bool environment(unsigned command, void *data) {
    switch (command) {
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            if (data) static_cast<retro_log_callback *>(data)->log = core_log;
            return data != nullptr;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            return data && *static_cast<retro_pixel_format *>(data) == RETRO_PIXEL_FORMAT_XRGB8888;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            if (!data) return false;
            *static_cast<const char **>(data) = g_save_directory.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            if (!data) return false;
            *static_cast<const char **>(data) = g_system_directory.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            if (!data) return false;
            *static_cast<unsigned *>(data) = 0;
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            if (!data) return false;
            const auto *variables = static_cast<const retro_variable *>(data);
            for (size_t i = 0; variables[i].key && variables[i].value; ++i) {
                std::string definition(variables[i].value);
                const auto separator = definition.find(';');
                if (separator == std::string::npos) continue;
                std::string values = definition.substr(separator + 1);
                const auto next = values.find('|');
                g_options[variables[i].key] = trim(values.substr(0, next));
            }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            if (!data) return false;
            auto *variable = static_cast<retro_variable *>(data);
            if (!variable->key) return false;
            const auto it = g_options.find(variable->key);
            if (it == g_options.end()) return false;
            variable->value = it->second.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            if (data) *static_cast<bool *>(data) = false;
            return data != nullptr;
        case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION:
            if (data) *static_cast<unsigned *>(data) = 0;
            return data != nullptr;
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE:
            if (data) log_message(ANDROID_LOG_INFO, "%s", static_cast<retro_message *>(data)->msg);
            return data != nullptr;
        case RETRO_ENVIRONMENT_SHUTDOWN:
            return true;
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
            return false;
        default:
            return false;
    }
}

void refresh_video(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data || !width || !height) return;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_window) return;
    if (width != g_width || height != g_height) {
        if (ANativeWindow_setBuffersGeometry(g_window, static_cast<int32_t>(width),
                static_cast<int32_t>(height), WINDOW_FORMAT_RGBA_8888) != 0) return;
        g_width = width;
        g_height = height;
    }
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(g_window, &buffer, nullptr) != 0) return;
    const auto *source = static_cast<const uint8_t *>(data);
    auto *destination = static_cast<uint32_t *>(buffer.bits);
    const unsigned copy_width = std::min(width, static_cast<unsigned>(buffer.width));
    const unsigned copy_height = std::min(height, static_cast<unsigned>(buffer.height));
    for (unsigned y = 0; y < copy_height; ++y) {
        const auto *src = reinterpret_cast<const uint32_t *>(source + y * pitch);
        auto *dst = destination + y * buffer.stride;
        for (unsigned x = 0; x < copy_width; ++x) {
            const uint32_t pixel = src[x]; // libretro XRGB8888: 0x00RRGGBB
            dst[x] = 0xff000000u
                    | ((pixel & 0x000000ffu) << 16)
                    | (pixel & 0x0000ff00u)
                    | ((pixel & 0x00ff0000u) >> 16);
        }
    }
    ANativeWindow_unlockAndPost(g_window);
}

void input_poll() {}

int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0 || device != RETRO_DEVICE_JOYPAD || index != 0) return 0;
    const uint16_t buttons = g_buttons.load(std::memory_order_relaxed);
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<int16_t>(buttons);
    return id < 16 && (buttons & (1u << id)) ? 1 : 0;
}

void audio_sample(int16_t, int16_t) {}
size_t audio_batch(const int16_t *, size_t frames) { return frames; }

std::string from_java(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *utf = env->GetStringUTFChars(value, nullptr);
    if (!utf) return {};
    std::string result(utf);
    env->ReleaseStringUTFChars(value, utf);
    return result;
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeInitialize(
        JNIEnv *env, jclass, jstring save_directory, jstring system_directory) {
    if (g_initialized) return JNI_TRUE;
    g_save_directory = from_java(env, save_directory);
    g_system_directory = from_java(env, system_directory);
    // AppleWin's Linux frontend resolves its configuration directory from
    // HOME. Android does not guarantee HOME is set, so Game construction can
    // otherwise throw before any disk image is examined.
    const size_t separator = g_save_directory.find_last_of('/');
    const std::string app_files_directory = separator == std::string::npos
            ? g_save_directory : g_save_directory.substr(0, separator);
    const std::string config_directory = app_files_directory + "/config";
    if (app_files_directory.empty()
            || setenv("HOME", app_files_directory.c_str(), 1) != 0
            || setenv("XDG_CONFIG_HOME", config_directory.c_str(), 1) != 0) {
        log_message(ANDROID_LOG_ERROR, "Unable to configure app-private HOME for AppleWin");
        return JNI_FALSE;
    }
    retro_set_environment(environment);
    retro_set_video_refresh(refresh_video);
    retro_set_input_poll(input_poll);
    retro_set_input_state(input_state);
    retro_set_audio_sample(audio_sample);
    retro_set_audio_sample_batch(audio_batch);
    retro_init();
    retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    g_initialized = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeLoadGame(JNIEnv *env, jclass, jstring path) {
    if (!g_initialized || !path) return JNI_FALSE;
    const std::string game_path = from_java(env, path);
    struct stat file_info{};
    const bool exists = stat(game_path.c_str(), &file_info) == 0;
    std::ostringstream diagnostics;
    diagnostics << "Input file: exists=" << (exists ? "yes" : "no")
                << ", readable=" << (access(game_path.c_str(), R_OK) == 0 ? "yes" : "no")
                << ", bytes=" << (exists ? static_cast<long long>(file_info.st_size) : -1) << "\n";
    {
        std::lock_guard<std::mutex> lock(g_diagnostics_mutex);
        g_diagnostics = diagnostics.str();
    }
    log_message(ANDROID_LOG_INFO, "Loading disk image (%lld bytes, exists=%d)",
            exists ? static_cast<long long>(file_info.st_size) : -1LL, exists ? 1 : 0);
    retro_game_info game{};
    game.path = game_path.c_str();
    g_game_loaded = retro_load_game(&game);
    append_diagnostic(std::string("retro_load_game returned ") + (g_game_loaded ? "true\n" : "false\n"));
    if (g_game_loaded) {
        retro_system_av_info av_info{};
        retro_get_system_av_info(&av_info);
        g_width = av_info.geometry.base_width;
        g_height = av_info.geometry.base_height;
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_window) {
            ANativeWindow_setBuffersGeometry(g_window, static_cast<int32_t>(g_width),
                    static_cast<int32_t>(g_height), WINDOW_FORMAT_RGBA_8888);
        }
    }
    return g_game_loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeGetDiagnostics(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_diagnostics_mutex);
    return env->NewStringUTF(g_diagnostics.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeRunFrame(JNIEnv *, jclass) {
    if (g_initialized && g_game_loaded) retro_run();
}

extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeSetSurface(JNIEnv *env, jclass, jobject surface) {
    ANativeWindow *next = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_window) ANativeWindow_release(g_window);
    g_window = next;
    if (g_window && g_width && g_height) {
        ANativeWindow_setBuffersGeometry(g_window, static_cast<int32_t>(g_width),
                static_cast<int32_t>(g_height), WINDOW_FORMAT_RGBA_8888);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeSetButton(JNIEnv *, jclass, jint button, jboolean pressed) {
    if (button < 0 || button >= 16) return;
    const uint16_t bit = static_cast<uint16_t>(1u << button);
    if (pressed) g_buttons.fetch_or(bit, std::memory_order_relaxed);
    else g_buttons.fetch_and(static_cast<uint16_t>(~bit), std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeStop(JNIEnv *, jclass) {
    if (g_game_loaded) retro_unload_game();
    g_game_loaded = false;
    if (g_initialized) retro_deinit();
    g_initialized = false;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_window) ANativeWindow_release(g_window);
    g_window = nullptr;
    g_width = g_height = 0;
}
