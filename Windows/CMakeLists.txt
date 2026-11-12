cmake_minimum_required(VERSION 3.16)
project(MeowpuutWindows LANGUAGES CXX)
if(WIN32 AND NOT MSVC)
    # The Rust library name, its target directory and the system libraries
    # below are the MSVC ones.
    message(FATAL_ERROR "This CMake build supports MSVC only. For MinGW use scripts/build-windows-check.sh.")
endif()
find_program(CARGO_EXECUTABLE cargo REQUIRED)
if(WIN32)
    set(MEOWPUUT_FFI_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../target/release/meowpuut_ffi.lib")
else()
    set(MEOWPUUT_FFI_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../target/release/libmeowpuut_ffi.a")
endif()
add_custom_command(
    OUTPUT "${MEOWPUUT_FFI_LIB}"
    COMMAND "${CARGO_EXECUTABLE}" build --release -p meowpuut-ffi
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/.."
    COMMENT "Building meowpuut-ffi Rust static library"
)
add_custom_target(meowpuut_ffi_build DEPENDS "${MEOWPUUT_FFI_LIB}")
add_library(MeowpuutWindowsSession STATIC Session.cpp Keyboard.cpp)
add_dependencies(MeowpuutWindowsSession meowpuut_ffi_build)
target_include_directories(MeowpuutWindowsSession PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/../crates/meowpuut-ffi/include")
target_link_libraries(MeowpuutWindowsSession PUBLIC "${MEOWPUUT_FFI_LIB}")
if(WIN32)
    target_link_libraries(MeowpuutWindowsSession PUBLIC ws2_32 userenv ntdll bcrypt advapi32)
endif()
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

    # The TSF text service. Its icon, Meowpuut.ico, is made from
    # assets/brand/AppIcon.png by scripts/build-windows-ime.sh; put one in the
    # build directory before building this target.
    add_library(MeowpuutTextService SHARED TextService/TextService.cpp TextService/TextService.def TextService/TextService.rc)
    target_link_libraries(MeowpuutTextService PRIVATE MeowpuutWindowsSession MeowpuutCandidateWindow oleaut32)
    target_compile_definitions(MeowpuutTextService PRIVATE
        MEOWPUUT_VERSION="0.8.0-alpha.1" MEOWPUUT_VERSION_NUMBERS=0,8,0,1)
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
    set(MEOWPUUT_LEXICON_DIR "" CACHE PATH "Directory containing native-pinyin.tsv, native-zhuyin.tsv and native-japanese.tsv")
    if(MEOWPUUT_LEXICON_DIR)
        add_test(NAME session COMMAND meowpuut_session_test "${MEOWPUUT_LEXICON_DIR}/native-pinyin.tsv" "${MEOWPUUT_LEXICON_DIR}/native-zhuyin.tsv" "${MEOWPUUT_LEXICON_DIR}/native-japanese.tsv")
    endif()

    add_executable(meowpuut_layout_test UI/tests/layout_test.cpp)
    target_link_libraries(meowpuut_layout_test PRIVATE MeowpuutCandidateLayout)
    if(MSVC)
        target_compile_options(meowpuut_layout_test PRIVATE /utf-8 /W4)
    endif()
    add_test(NAME candidate_layout COMMAND meowpuut_layout_test)
endif()
