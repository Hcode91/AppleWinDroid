#pragma once
#include <jni.h>

// RetroAchievements (casual/softcore only) for the Android host. All calls run on the core thread.
namespace achievements {
void on_game_loaded(const char *path);  // after retro_load_game succeeded
void on_frame();                        // after each retro_run
void on_game_unloaded();                // before retro_unload_game
}
