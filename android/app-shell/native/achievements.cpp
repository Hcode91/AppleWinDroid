// RetroAchievements for AppleWinDroid, through the portable rcheevos rc_client.
//
// Casual/softcore only (see ANDROID_PORTING_PLAN.md): rc_client starts in its stricter mode, so it
// is switched off once at creation and nothing else ever touches it.
//
// rcheevos does no networking: each request goes to Java (NativeCore.onRaHttpRequest), which
// performs it with HttpURLConnection and returns the response through nativeRaHttpResponse on the
// core thread. Events and async results go to NativeCore.onRaEvent / onRaResult.
#include "achievements.h"

#include "libretro.h"
#include "rc_client.h"
#include "rc_consoles.h"

#include <android/log.h>

#include <algorithm>
#include <cstring>
#include <map>
#include <mutex>
#include <sstream>
#include <string>

namespace {
constexpr const char *kTag = "AppleWinDroidRA";
enum { RESULT_LOGIN = 1, RESULT_GAME = 2 };

JavaVM *g_vm = nullptr;
jclass g_native = nullptr;
jmethodID g_on_http = nullptr, g_on_event = nullptr, g_on_result = nullptr;
rc_client_t *g_client = nullptr;
const uint8_t *g_ram = nullptr;
size_t g_ram_size = 0;
std::string g_game_path; // the disk running now, so a login made mid-game can still load its set

struct PendingCall { rc_client_server_callback_t callback; void *data; };
std::mutex g_calls_mutex;
std::map<int, PendingCall> g_calls;
int g_next_call = 1;

JNIEnv *env() {
    JNIEnv *e = nullptr;
    if (!g_vm) return nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&e), JNI_VERSION_1_6) == JNI_OK) return e;
#ifdef __ANDROID__
    return g_vm->AttachCurrentThread(&e, nullptr) == JNI_OK ? e : nullptr;
#else
    return g_vm->AttachCurrentThread(reinterpret_cast<void **>(&e), nullptr) == JNI_OK ? e : nullptr;
#endif
}

jstring jstr(JNIEnv *e, const char *s) { return e->NewStringUTF(s ? s : ""); }

void result_to_java(int kind, int result, const char *message) {
    JNIEnv *e = env();
    if (!e || !g_native) return;
    jstring m = jstr(e, message);
    e->CallStaticVoidMethod(g_native, g_on_result, kind, result, m);
    e->DeleteLocalRef(m);
}

uint32_t RC_CCONV read_memory(uint32_t address, uint8_t *buffer, uint32_t num_bytes, rc_client_t *) {
    // Main Apple II RAM ($0000-$FFFF) from the core's RETRO_MEMORY_SYSTEM_RAM. Auxiliary RAM is not
    // exposed by the core yet, so reads there return 0 bytes.
    if (!g_ram || address >= g_ram_size) return 0;
    const uint32_t count = static_cast<uint32_t>(std::min<size_t>(num_bytes, g_ram_size - address));
    std::memcpy(buffer, g_ram + address, count);
    return count;
}

void RC_CCONV server_call(const rc_api_request_t *request, rc_client_server_callback_t callback, void *callback_data, rc_client_t *) {
    int id;
    {
        std::lock_guard<std::mutex> lock(g_calls_mutex);
        id = g_next_call++;
        g_calls[id] = {callback, callback_data};
    }
    JNIEnv *e = env();
    if (!e || !g_native) {
        rc_api_server_response_t failed{"", 0, RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR};
        std::lock_guard<std::mutex> lock(g_calls_mutex);
        g_calls.erase(id);
        callback(&failed, callback_data);
        return;
    }
    jstring url = jstr(e, request->url);
    jstring post = request->post_data ? jstr(e, request->post_data) : nullptr;
    jstring type = jstr(e, request->content_type);
    e->CallStaticVoidMethod(g_native, g_on_http, id, url, post, type);
    e->DeleteLocalRef(url);
    if (post) e->DeleteLocalRef(post);
    e->DeleteLocalRef(type);
}

void RC_CCONV log_message(const char *message, const rc_client_t *) {
    __android_log_write(ANDROID_LOG_INFO, kTag, message);
}

void RC_CCONV event_handler(const rc_client_event_t *event, rc_client_t *) {
    JNIEnv *e = env();
    if (!e || !g_native) return;
    const char *title = "", *description = "";
    int points = 0;
    if (event->achievement) { title = event->achievement->title; description = event->achievement->description; points = static_cast<int>(event->achievement->points); }
    else if (event->server_error) { title = event->server_error->api; description = event->server_error->error_message; }
    else if (event->type == RC_CLIENT_EVENT_GAME_COMPLETED) {
        const rc_client_game_t *game = rc_client_get_game_info(g_client);
        title = game ? game->title : "";
    }
    jstring t = jstr(e, title), d = jstr(e, description);
    e->CallStaticVoidMethod(g_native, g_on_event, static_cast<jint>(event->type), t, d, points);
    e->DeleteLocalRef(t);
    e->DeleteLocalRef(d);
}

void RC_CCONV game_done(int result, const char *error_message, rc_client_t *client, void *);

void RC_CCONV login_done(int result, const char *error_message, rc_client_t *client, void *) {
    if (result == RC_OK) {
        const rc_client_user_t *user = rc_client_get_user_info(client);
        result_to_java(RESULT_LOGIN, result, user ? user->display_name : "");
        if (!g_game_path.empty() && !rc_client_get_game_info(client))
            rc_client_begin_identify_and_load_game(client, RC_CONSOLE_APPLE_II, g_game_path.c_str(), nullptr, 0, game_done, nullptr);
    } else {
        result_to_java(RESULT_LOGIN, result, error_message ? error_message : rc_error_str(result));
    }
}

void RC_CCONV game_done(int result, const char *error_message, rc_client_t *client, void *) {
    if (result == RC_OK) {
        const rc_client_game_t *game = rc_client_get_game_info(client);
        rc_client_user_game_summary_t summary{};
        rc_client_get_user_game_summary(client, &summary);
        std::ostringstream text;
        text << (game && game->title ? game->title : "Game") << ": " << summary.num_unlocked_achievements << " of "
             << summary.num_core_achievements << " achievements unlocked";
        result_to_java(RESULT_GAME, result, text.str().c_str());
    } else {
        result_to_java(RESULT_GAME, result, error_message ? error_message : rc_error_str(result));
    }
}

std::string from_java(JNIEnv *e, jstring value) {
    if (!value) return {};
    const char *utf = e->GetStringUTFChars(value, nullptr);
    if (!utf) return {};
    std::string s(utf);
    e->ReleaseStringUTFChars(value, utf);
    return s;
}
} // namespace

namespace achievements {
void on_game_loaded(const char *path) {
    g_ram = static_cast<const uint8_t *>(retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM));
    g_ram_size = retro_get_memory_size(RETRO_MEMORY_SYSTEM_RAM);
    g_game_path = path ? path : "";
    if (!g_client || !path) return;
    rc_client_unload_game(g_client);
    if (!rc_client_get_user_info(g_client)) return; // not logged in: no achievements this session
    rc_client_begin_identify_and_load_game(g_client, RC_CONSOLE_APPLE_II, path, nullptr, 0, game_done, nullptr);
}
void on_frame() { if (g_client) rc_client_do_frame(g_client); }
void on_game_unloaded() {
    if (g_client) rc_client_unload_game(g_client);
    g_ram = nullptr;
    g_ram_size = 0;
    g_game_path.clear();
}
} // namespace achievements

extern "C" JNIEXPORT jboolean JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeRaInit(JNIEnv *e, jclass cls) {
    if (g_client) return JNI_TRUE;
    e->GetJavaVM(&g_vm);
    g_native = static_cast<jclass>(e->NewGlobalRef(cls));
    g_on_http = e->GetStaticMethodID(cls, "onRaHttpRequest", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    g_on_event = e->GetStaticMethodID(cls, "onRaEvent", "(ILjava/lang/String;Ljava/lang/String;I)V");
    g_on_result = e->GetStaticMethodID(cls, "onRaResult", "(IILjava/lang/String;)V");
    if (!g_on_http || !g_on_event || !g_on_result) return JNI_FALSE;
    g_client = rc_client_create(read_memory, server_call);
    if (!g_client) return JNI_FALSE;
    rc_client_set_hardcore_enabled(g_client, 0);
    rc_client_enable_logging(g_client, RC_CLIENT_LOG_LEVEL_INFO, log_message);
    rc_client_set_event_handler(g_client, event_handler);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeRaUserAgentClause(JNIEnv *e, jclass) {
    char clause[128] = {0};
    if (g_client) rc_client_get_user_agent_clause(g_client, clause, sizeof(clause));
    return e->NewStringUTF(clause);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeRaLoginPassword(JNIEnv *e, jclass, jstring user, jstring password) {
    if (!g_client) return;
    const std::string u = from_java(e, user), p = from_java(e, password);
    rc_client_begin_login_with_password(g_client, u.c_str(), p.c_str(), login_done, nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeRaLoginToken(JNIEnv *e, jclass, jstring user, jstring token) {
    if (!g_client) return;
    const std::string u = from_java(e, user), t = from_java(e, token);
    rc_client_begin_login_with_token(g_client, u.c_str(), t.c_str(), login_done, nullptr);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeRaUser(JNIEnv *e, jclass) {
    const rc_client_user_t *user = g_client ? rc_client_get_user_info(g_client) : nullptr;
    if (!user) return nullptr;
    jobjectArray out = e->NewObjectArray(3, e->FindClass("java/lang/String"), nullptr);
    e->SetObjectArrayElement(out, 0, jstr(e, user->username));
    e->SetObjectArrayElement(out, 1, jstr(e, user->token));
    e->SetObjectArrayElement(out, 2, jstr(e, user->display_name));
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeRaLogout(JNIEnv *, jclass) {
    if (!g_client) return;
    rc_client_unload_game(g_client);
    rc_client_logout(g_client);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeRaHttpResponse(JNIEnv *e, jclass, jint id, jint status, jbyteArray body) {
    PendingCall call{};
    {
        std::lock_guard<std::mutex> lock(g_calls_mutex);
        auto it = g_calls.find(id);
        if (it == g_calls.end()) return;
        call = it->second;
        g_calls.erase(it);
    }
    std::string data;
    if (body) {
        const jsize n = e->GetArrayLength(body);
        data.resize(static_cast<size_t>(n));
        if (n > 0) e->GetByteArrayRegion(body, 0, n, reinterpret_cast<jbyte *>(&data[0]));
    }
    rc_api_server_response_t response{data.c_str(), data.size(), static_cast<int>(status)};
    call.callback(&response, call.data);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_buddyapps_applewindroid_NativeCore_nativeRaSummary(JNIEnv *e, jclass) {
    if (!g_client) return e->NewStringUTF("");
    const rc_client_game_t *game = rc_client_get_game_info(g_client);
    if (!game || !game->id) return e->NewStringUTF("");
    std::ostringstream text;
    rc_client_user_game_summary_t summary{};
    rc_client_get_user_game_summary(g_client, &summary);
    text << game->title << "\n" << summary.num_unlocked_achievements << " of " << summary.num_core_achievements
         << " unlocked, " << summary.points_unlocked << " of " << summary.points_core << " points\n";
    rc_client_achievement_list_t *list = rc_client_create_achievement_list(g_client,
            RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_LOCK_STATE);
    if (list) {
        for (uint32_t b = 0; b < list->num_buckets; ++b) {
            const rc_client_achievement_bucket_t &bucket = list->buckets[b];
            text << "\n" << (bucket.label ? bucket.label : "") << "\n";
            for (uint32_t i = 0; i < bucket.num_achievements; ++i) {
                const rc_client_achievement_t *a = bucket.achievements[i];
                text << (a->unlocked ? "✓ " : "• ") << a->title << " (" << a->points << ")";
                if (a->measured_progress[0]) text << " [" << a->measured_progress << "]";
                text << "\n   " << (a->description ? a->description : "") << "\n";
            }
        }
        rc_client_destroy_achievement_list(list);
    }
    return e->NewStringUTF(text.str().c_str());
}
