cmake_minimum_required(VERSION 3.31.4)
project("androidsurround")

add_library(
    androidsurround_native
    SHARED
    upmix_dsp.cpp
)

target_compile_features(
    androidsurround_native
    PUBLIC
    cxx_std_17
)

find_library(
    log-lib
    log
)

target_link_libraries(
    androidsurround_native
    ${log-lib}
    aaudio
)
