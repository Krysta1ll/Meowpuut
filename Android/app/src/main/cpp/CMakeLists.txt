cmake_minimum_required(VERSION 3.22)
project(meowpuut_jni C)

# scripts/build-android.sh builds the Rust engine for each ABI into
# .build/rust/android/<abi>/ before Gradle configures this project.
set(MEOWPUUT_FFI "${MEOWPUUT_ROOT}/.build/rust/android/${ANDROID_ABI}/libmeowpuut_ffi.a")
if(NOT EXISTS "${MEOWPUUT_FFI}")
    message(FATAL_ERROR "Missing ${MEOWPUUT_FFI}; run: bash scripts/build-android.sh --prepare")
endif()

add_library(meowpuut_ffi STATIC IMPORTED)
set_target_properties(meowpuut_ffi PROPERTIES IMPORTED_LOCATION "${MEOWPUUT_FFI}")

add_library(meowpuut_jni SHARED meowpuut_jni.c)
target_include_directories(meowpuut_jni PRIVATE "${MEOWPUUT_ROOT}/Engine/crates/meowpuut-ffi/include")
target_compile_options(meowpuut_jni PRIVATE -Wall -Wextra -O2)
target_link_libraries(meowpuut_jni PRIVATE meowpuut_ffi log m dl)
# Keep only the JNI entry points exported; 16 KB pages for Android 15 devices.
target_link_options(meowpuut_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
