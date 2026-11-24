
cmake_minimum_required(VERSION 3.22.1)

project(as_audio_dlms_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(
    as_audio_dsp
    SHARED
    native-lib.cpp
)

target_link_libraries(
    as_audio_dsp
    oboe::oboe
    android
    log
)
