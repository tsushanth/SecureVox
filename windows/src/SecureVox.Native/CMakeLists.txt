cmake_minimum_required(VERSION 3.22)
project(whisper_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Windows-specific settings
if(MSVC)
    # MSVC optimization flags
    set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} /O2 /DNDEBUG")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} /O2 /DNDEBUG")

    # Enable AVX2 for better performance on modern CPUs
    add_compile_options(/arch:AVX2)

    # Suppress some common warnings
    add_compile_options(/wd4244 /wd4267 /wd4996)
else()
    # GCC/Clang flags (for MinGW or cross-compilation)
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")
endif()

# whisper.cpp source directory
set(WHISPER_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# Check if whisper.cpp exists
if(NOT EXISTS ${WHISPER_CPP_DIR}/src/whisper.cpp)
    message(FATAL_ERROR "whisper.cpp not found! Run: git submodule update --init --recursive")
endif()

# ggml sources
set(GGML_SOURCES
    ${WHISPER_CPP_DIR}/ggml/src/ggml.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-quants.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu.c
)

# Add ggml library
add_library(ggml STATIC ${GGML_SOURCES})

target_include_directories(ggml PUBLIC
    ${WHISPER_CPP_DIR}/ggml/include
    ${WHISPER_CPP_DIR}/ggml/src
)

target_compile_definitions(ggml PUBLIC
    GGML_USE_CPU
    _CRT_SECURE_NO_WARNINGS
)

# Add whisper library
add_library(whisper STATIC
    ${WHISPER_CPP_DIR}/src/whisper.cpp
)

target_include_directories(whisper PUBLIC
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR}/src
    ${WHISPER_CPP_DIR}/ggml/include
    ${WHISPER_CPP_DIR}/ggml/src
)

target_link_libraries(whisper ggml)

# Windows native wrapper (DLL for P/Invoke)
add_library(whisper_native SHARED
    whisper_wrapper.cpp
)

target_include_directories(whisper_native PRIVATE
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR}/ggml/include
)

target_link_libraries(whisper_native
    whisper
    ggml
)

# Export all symbols for P/Invoke
if(MSVC)
    target_compile_definitions(whisper_native PRIVATE WHISPER_NATIVE_EXPORTS)
endif()

# Set output directory
set_target_properties(whisper_native PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib
)

# Install rules
install(TARGETS whisper_native
    RUNTIME DESTINATION bin
    LIBRARY DESTINATION lib
)
