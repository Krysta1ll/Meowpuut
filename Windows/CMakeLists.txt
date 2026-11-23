cmake_minimum_required(VERSION 3.16)
project(MeowpuutWindows LANGUAGES CXX)
if(WIN32 AND NOT MSVC)
    # The Rust library name, its target directory and the system libraries
    # below are the MSVC ones.
    message(FATAL_ERROR "This CMake build supports MSVC only. For MinGW use scripts/build-windows-check.sh.")
endif()
find_program(CARGO_EXECUTABLE cargo REQUIRED)

# Same query as scripts/build-windows-ime.sh / build-windows-check.sh:
# `cargo rustc -- --print native-static-libs`. Flags such as
# /defaultlib:msvcrt are skipped so Debug can keep MSVC's /MDd CRT.
function(meowpuut_ffi_native_libs out_var)
    set(_fallback kernel32 ntdll userenv ws2_32 dbghelp synchronization)
    execute_process(
        COMMAND "${CARGO_EXECUTABLE}" rustc --locked
            --manifest-path "${CMAKE_CURRENT_SOURCE_DIR}/../Engine/Cargo.toml"
            --target-dir "${CMAKE_CURRENT_SOURCE_DIR}/../target"
            --release -p meowpuut-ffi
            -- --print native-static-libs
        WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/.."
        RESULT_VARIABLE _rc
        OUTPUT_VARIABLE _out
        ERROR_VARIABLE _err
    )
    set(_text "${_out}\n${_err}")
    set(_libs)
    if(_rc EQUAL 0)
        string(REGEX MATCHALL "native-static-libs: [^\r\n]*" _matches "${_text}")
        if(_matches)
            list(GET _matches -1 _line)
            string(REGEX REPLACE "^native-static-libs: " "" _line "${_line}")
            separate_arguments(_toks NATIVE_COMMAND "${_line}")
            foreach(_t IN LISTS _toks)
                if(NOT _t MATCHES "^[/-]")
                    string(REGEX REPLACE "\\.lib$" "" _t "${_t}")
                    list(APPEND _libs "${_t}")
                endif()
            endforeach()
        endif()
    endif()
    if(NOT _libs)
        message(WARNING "Could not parse cargo native-static-libs (exit ${_rc}); using ${_fallback}")
        set(_libs ${_fallback})
    endif()
    message(STATUS "meowpuut-ffi native libs: ${_libs}")
    set(${out_var} "${_libs}" PARENT_SCOPE)
endfunction()

if(WIN32)
    set(MEOWPUUT_FFI_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../target/release/meowpuut_ffi.lib")
    meowpuut_ffi_native_libs(MEOWPUUT_FFI_NATIVE_LIBS)
else()
    set(MEOWPUUT_FFI_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../target/release/libmeowpuut_ffi.a")
    set(MEOWPUUT_FFI_NATIVE_LIBS)
endif()
add_custom_target(meowpuut_ffi_build
    BYPRODUCTS "${MEOWPUUT_FFI_LIB}"
    COMMAND "${CARGO_EXECUTABLE}" build --locked --release --manifest-path "${CMAKE_CURRENT_SOURCE_DIR}/../Engine/Cargo.toml" --target-dir "${CMAKE_CURRENT_SOURCE_DIR}/../target" -p meowpuut-ffi
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/.."
    COMMENT "Building meowpuut-ffi Rust static library"
)
add_library(MeowpuutWindowsSession STATIC Session.cpp Keyboard.cpp)
add_dependencies(MeowpuutWindowsSession meowpuut_ffi_build)
target_include_directories(MeowpuutWindowsSession PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/../Engine/crates/meowpuut-ffi/include")
target_link_libraries(MeowpuutWindowsSession PUBLIC "${MEOWPUUT_FFI_LIB}" ${MEOWPUUT_FFI_NATIVE_LIBS})
target_compile_features(MeowpuutWindowsSession PUBLIC cxx_std_17)
if(MSVC)
    target_compile_options(MeowpuutWindowsSession PRIVATE /utf-8 /W4)
endif()

# Candidate window layout: portable C++17, no Windows headers. Shared by the
# Direct2D window, the SVG snapshots and the layout test.
add_library(MeowpuutCandidateLayout STATIC
    UI/Blur.cpp UI/CandidateLayout.cpp UI/Festival.cpp UI/GlassShape.cpp UI/Scene.cpp UI/SvgSnapshot.cpp UI/Theme.cpp)
target_include_directories(MeowpuutCandidateLayout PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/UI")
target_compile_features(MeowpuutCandidateLayout PUBLIC cxx_std_17)
if(MSVC)
    target_compile_options(MeowpuutCandidateLayout PRIVATE /utf-8 /W4)
endif()

if(WIN32)
    add_library(MeowpuutCandidateWindow STATIC UI/CandidateWindow.cpp)
    target_link_libraries(MeowpuutCandidateWindow PUBLIC MeowpuutCandidateLayout d2d1 dwrite windowscodecs ole32 uuid
                          user32 gdi32 advapi32)
    target_compile_definitions(MeowpuutCandidateWindow PUBLIC UNICODE _UNICODE NOMINMAX _WIN32_WINNT=0x0A00 WINVER=0x0A00)
    if(MSVC)
        target_compile_options(MeowpuutCandidateWindow PRIVATE /utf-8 /W4)
    endif()

    # The TSF text service. Its icon is generated from assets/brand/AppIcon.png
    # into the build directory, which is already on TextService.rc's include path.
    set(MEOWPUUT_APPICON "${CMAKE_CURRENT_SOURCE_DIR}/../assets/brand/AppIcon.png")
    set(MEOWPUUT_ICO "${CMAKE_CURRENT_BINARY_DIR}/Meowpuut.ico")
    find_package(Python3 COMPONENTS Interpreter)
    set(_ico_ok FALSE)
    if(Python3_Interpreter_FOUND)
        execute_process(
            COMMAND "${Python3_EXECUTABLE}" -c "from PIL import Image"
            RESULT_VARIABLE _pil_rc
            OUTPUT_QUIET ERROR_QUIET
        )
        if(_pil_rc EQUAL 0)
            add_custom_command(
                OUTPUT "${MEOWPUUT_ICO}"
                COMMAND "${Python3_EXECUTABLE}"
                    "${CMAKE_CURRENT_SOURCE_DIR}/../scripts/generate-windows-ico.py"
                    "${MEOWPUUT_APPICON}" "${MEOWPUUT_ICO}"
                DEPENDS "${MEOWPUUT_APPICON}"
                    "${CMAKE_CURRENT_SOURCE_DIR}/../scripts/generate-windows-ico.py"
                COMMENT "Generating Meowpuut.ico from assets/brand/AppIcon.png"
                VERBATIM
            )
            set(_ico_ok TRUE)
        endif()
    endif()
    if(NOT _ico_ok)
        if(EXISTS "${MEOWPUUT_ICO}")
            message(STATUS "Python/Pillow not found; using existing ${MEOWPUUT_ICO}")
        else()
            message(FATAL_ERROR
                "MeowpuutTextService needs Meowpuut.ico in the build directory. "
                "Install Python with Pillow and re-run CMake, or write the icon "
                "with: python scripts/generate-windows-ico.py assets/brand/AppIcon.png <build>/Meowpuut.ico")
        endif()
    endif()
    add_library(MeowpuutTextService SHARED TextService/TextService.cpp TextService/TextService.def TextService/TextService.rc)
    add_custom_target(MeowpuutIco DEPENDS "${MEOWPUUT_ICO}")
    add_dependencies(MeowpuutTextService MeowpuutIco)
    target_link_libraries(MeowpuutTextService PRIVATE MeowpuutWindowsSession MeowpuutCandidateWindow oleaut32)
    target_compile_definitions(MeowpuutTextService PRIVATE
        MEOWPUUT_VERSION="門廊 · 26A003" MEOWPUUT_VERSION_NUMBERS=0,9,0,3)
    target_include_directories(MeowpuutTextService PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
    target_compile_options(MeowpuutTextService PRIVATE $<$<COMPILE_LANGUAGE:CXX>:/utf-8 /W4>)

    add_executable(MeowpuutCandidatePreview WIN32 Preview/PreviewApp.cpp Preview/Preview.rc)
    target_link_libraries(MeowpuutCandidatePreview PRIVATE MeowpuutWindowsSession MeowpuutCandidateWindow shell32 imm32)
    target_compile_options(MeowpuutCandidatePreview PRIVATE /utf-8 /W4)
    # The manifest comes from Preview.rc.
    set_target_properties(MeowpuutCandidatePreview PROPERTIES LINK_FLAGS "/MANIFEST:NO")
    # Cat bitmaps and the festival manifest sit next to the executable. Copied
    # afresh on every build: the art is regenerated without touching any source,
    # and an outfit removed from the manifest must not stay behind.
    add_custom_target(MeowpuutPreviewAssets ALL
        COMMAND ${CMAKE_COMMAND} -E remove_directory "$<TARGET_FILE_DIR:MeowpuutCandidatePreview>/Assets"
        COMMAND ${CMAKE_COMMAND} -E copy_directory "${CMAKE_CURRENT_SOURCE_DIR}/UI/Assets"
                "$<TARGET_FILE_DIR:MeowpuutCandidatePreview>/Assets")
    add_dependencies(MeowpuutPreviewAssets MeowpuutCandidatePreview)
endif()

include(CTest)
if(BUILD_TESTING)
    add_executable(meowpuut_session_test tests/session_test.cpp)
    target_link_libraries(meowpuut_session_test PRIVATE MeowpuutWindowsSession)
    if(MSVC)
        target_compile_options(meowpuut_session_test PRIVATE /utf-8 /W4)
    endif()
    set(MEOWPUUT_LEXICON_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../Engine/.build/lexicon" CACHE PATH
        "Directory containing native-pinyin.tsv, native-zhuyin.tsv and native-japanese.tsv")
    set(_session_pinyin "${MEOWPUUT_LEXICON_DIR}/native-pinyin.tsv")
    set(_session_zhuyin "${MEOWPUUT_LEXICON_DIR}/native-zhuyin.tsv")
    set(_session_japanese "${MEOWPUUT_LEXICON_DIR}/native-japanese.tsv")
    if(EXISTS "${_session_pinyin}" AND EXISTS "${_session_zhuyin}" AND EXISTS "${_session_japanese}")
        set(_session_args "${_session_pinyin}" "${_session_zhuyin}" "${_session_japanese}")
        if(EXISTS "${MEOWPUUT_LEXICON_DIR}/mimo_base.bin")
            list(APPEND _session_args "${MEOWPUUT_LEXICON_DIR}/mimo_base.bin")
        endif()
        add_test(NAME session COMMAND meowpuut_session_test ${_session_args})
    else()
        message(STATUS "session test not registered: no lexicon TSV files in MEOWPUUT_LEXICON_DIR=${MEOWPUUT_LEXICON_DIR}")
    endif()

    add_executable(meowpuut_input_defaults_test tests/input_defaults_test.cpp)
    target_compile_features(meowpuut_input_defaults_test PRIVATE cxx_std_17)
    if(MSVC)
        target_compile_options(meowpuut_input_defaults_test PRIVATE /utf-8 /W4)
    endif()
    add_test(NAME input_defaults COMMAND meowpuut_input_defaults_test)

    add_executable(meowpuut_layout_test UI/tests/layout_test.cpp)
    target_link_libraries(meowpuut_layout_test PRIVATE MeowpuutCandidateLayout)
    if(MSVC)
        target_compile_options(meowpuut_layout_test PRIVATE /utf-8 /W4)
    endif()
    add_test(NAME candidate_layout COMMAND meowpuut_layout_test)
endif()
