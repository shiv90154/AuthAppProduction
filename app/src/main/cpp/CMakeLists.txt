cmake_minimum_required(VERSION 3.22.1)
project("myapplication")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(
        oboe
        GIT_REPOSITORY https://github.com/google/oboe
        GIT_TAG 1.9.0
)
FetchContent_MakeAvailable(oboe)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        MidiProcessor.cpp
        AudioEngine.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${oboe_SOURCE_DIR}/include)

target_link_libraries(${CMAKE_PROJECT_NAME}
        oboe
        android
        log)

# 16 KB page-size support — Google Play requires all native code in apps
# targeting Android 15+ to load on devices with a 16 KB memory page size
# (mandatory for new submissions / updates from Nov 2025). NDK 27 already
# builds our own .so aligned, but Oboe is fetched + built here via
# FetchContent, so set the flag on our link line explicitly to be certain
# every ELF segment in the final library is 16 KB-aligned.
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE
        "-Wl,-z,max-page-size=16384")
