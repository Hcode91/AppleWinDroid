cmake_minimum_required(VERSION 3.22.1)

add_library(applewindroid_jni SHARED native_core.cpp)
target_include_directories(applewindroid_jni PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../applewin-core/source/frontends/libretro/libretro-common/include")
target_link_libraries(applewindroid_jni PRIVATE applewin_libretro android log)
