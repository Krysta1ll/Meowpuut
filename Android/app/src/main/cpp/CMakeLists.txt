cmake_minimum_required(VERSION 3.22)
project(meowpuut_jni C)

# scripts/build-android.sh builds the Rust engine for each ABI into
# .build/rust/android/<variant>/<abi>/ before Gradle configures this project.
# The variant (debug or release) decides which custom-skin keys the library
# trusts: debug is built with the `skin-test-keys` feature, release without it
# and with MEOWPUUT_RELEASE_BUILD set (a release library refuses every test- kid).
if(NOT MEOWPUUT_RUST_VARIANT MATCHES "^(debug|release)$")
    message(FATAL_ERROR "Set MEOWPUUT_RUST_VARIANT to debug or release")
endif()
set(MEOWPUUT_FFI "${MEOWPUUT_ROOT}/.build/rust/android/${MEOWPUUT_RUST_VARIANT}/${ANDROID_ABI}/libmeowpuut_ffi.a")
if(NOT EXISTS "${MEOWPUUT_FFI}")
    message(FATAL_ERROR "Missing ${MEOWPUUT_FFI}; run: bash scripts/build-android.sh --prepare ${MEOWPUUT_RUST_VARIANT}")
endif()

add_library(meowpuut_ffi STATIC IMPORTED)
set_target_properties(meowpuut_ffi PROPERTIES IMPORTED_LOCATION "${MEOWPUUT_FFI}")

add_library(meowpuut_jni SHARED meowpuut_jni.c)
target_include_directories(meowpuut_jni PRIVATE "${MEOWPUUT_ROOT}/Engine/crates/meowpuut-ffi/include")
# The custom-skin functions (mps_*) are in the Rust library's `skin` feature, which both variants enable.
target_compile_definitions(meowpuut_jni PRIVATE MEOWPUUT_SKIN)
target_compile_options(meowpuut_jni PRIVATE -Wall -Wextra -O2)
target_link_libraries(meowpuut_jni PRIVATE meowpuut_ffi log m dl)
# Keep only the JNI entry points exported; 16 KB pages for Android 15 devices.
target_link_options(meowpuut_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
