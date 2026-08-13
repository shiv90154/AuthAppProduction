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