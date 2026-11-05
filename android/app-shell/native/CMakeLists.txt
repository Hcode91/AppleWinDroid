cmake_minimum_required(VERSION 3.22.1)

# RetroAchievements: the portable rcheevos client (MIT), pinned as the android/rcheevos submodule.
set(RCHEEVOS_DIR "${CMAKE_CURRENT_LIST_DIR}/../../rcheevos")
file(GLOB RCHEEVOS_SOURCES
    "${RCHEEVOS_DIR}/src/*.c"
    "${RCHEEVOS_DIR}/src/rapi/*.c"
    "${RCHEEVOS_DIR}/src/rcheevos/*.c"
    "${RCHEEVOS_DIR}/src/rhash/*.c")
# Windows-only RAIntegration DLL bridge and the RetroArch-specific helper are not used on Android.
list(FILTER RCHEEVOS_SOURCES EXCLUDE REGEX ".*/rc_client_raintegration\\.c$")
list(FILTER RCHEEVOS_SOURCES EXCLUDE REGEX ".*/rc_libretro\\.c$")
add_library(rcheevos STATIC ${RCHEEVOS_SOURCES})
target_include_directories(rcheevos PUBLIC "${RCHEEVOS_DIR}/include")
target_compile_definitions(rcheevos PUBLIC RC_CLIENT_SUPPORTS_HASH)
set_target_properties(rcheevos PROPERTIES POSITION_INDEPENDENT_CODE ON C_STANDARD 99)

add_library(applewindroid_jni SHARED native_core.cpp achievements.cpp)
target_include_directories(applewindroid_jni PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../applewin-core/source/frontends/libretro/libretro-common/include")
target_link_libraries(applewindroid_jni PRIVATE applewin_libretro rcheevos android log)
