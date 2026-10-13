cmake_minimum_required(VERSION 3.14)
project(wt_harness CXX)
set(CMAKE_CXX_STANDARD 17)
# -DWHISPER_CPP_DIR=<android/app/src/main/cpp/whisper.cpp>  (the git-ignored vendored copy)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_CPP_DIR} whisper EXCLUDE_FROM_ALL)
add_executable(wt_harness wt_harness.cpp)
target_include_directories(wt_harness PRIVATE
    ${WHISPER_CPP_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../android/app/src/main/cpp)
target_link_libraries(wt_harness whisper)
