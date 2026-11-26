cmake_minimum_required(VERSION 3.16)
project(MeowpuutWindows LANGUAGES CXX)
if(WIN32 AND NOT MSVC)
    # The Rust library name, its target directory and the system libraries
    # below are the MSVC ones.
    message(FATAL_ERROR "This CMake build supports MSVC only. For MinGW use scripts/build-windows-check.sh.")
endif()
find_program(CARGO_EXECUTABLE cargo REQUIRED)
# A 32-bit build (cmake -A Win32) needs the Rust library for i686-pc-windows-msvc
# (rustup target add i686-pc-windows-msvc); it lands in its own target folder.
set(MEOWPUUT_RUST_TARGET_ARGS "")
set(MEOWPUUT_RUST_OUT "release")
if(MSVC AND CMAKE_SIZEOF_VOID_P EQUAL 4)
    set(MEOWPUUT_RUST_TARGET_ARGS --target i686-pc-windows-msvc)
    set(MEOWPUUT_RUST_OUT "i686-pc-windows-msvc/release")
endif()
if(WIN32)
    set(MEOWPUUT_FFI_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../target/${MEOWPUUT_RUST_OUT}/meowpuut_ffi.lib")
else()
    set(MEOWPUUT_FFI_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../target/release/libmeowpuut_ffi.a")
endif()
add_custom_target(meowpuut_ffi_build
    BYPRODUCTS "${MEOWPUUT_FFI_LIB}"
    COMMAND "${CARGO_EXECUTABLE}" build --locked --release --manifest-path "${CMAKE_CURRENT_SOURCE_DIR}/../Engine/Cargo.toml" --target-dir "${CMAKE_CURRENT_SOURCE_DIR}/../target" -p meowpuut-ffi --features glyph ${MEOWPUUT_RUST_TARGET_ARGS}
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/.."
    COMMENT "Building meowpuut-ffi Rust static library"
)
add_library(MeowpuutWindowsSession STATIC Session.cpp Keyboard.cpp PetLedger.cpp)
add_dependencies(MeowpuutWindowsSession meowpuut_ffi_build)
target_include_directories(MeowpuutWindowsSession PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/../Engine/crates/meowpuut-ffi/include")
target_link_libraries(MeowpuutWindowsSession PUBLIC "${MEOWPUUT_FFI_LIB}")
# The Rust library above is built with --features glyph: PetLedger.cpp records the day's journey place.
target_compile_definitions(MeowpuutWindowsSession PRIVATE MEOWPUUT_GLYPH=1)
if(WIN32)
    target_link_libraries(MeowpuutWindowsSession PUBLIC ws2_32 userenv ntdll bcrypt advapi32 shell32 ole32)
endif()
target_compile_features(MeowpuutWindowsSession PUBLIC cxx_std_17)
if(MSVC)
    target_compile_options(MeowpuutWindowsSession PRIVATE /utf-8 /W4 $<$<CONFIG:Release>:/GL /Gw /Gy>)
endif()

# Candidate window layout: portable C++17, no Windows headers. Shared by the
# Direct2D window, the SVG snapshots and the layout test.
add_library(MeowpuutCandidateLayout STATIC
    UI/Blur.cpp UI/CandidateLayout.cpp UI/Festival.cpp UI/GlassShape.cpp UI/Scene.cpp UI/Theme.cpp)
target_include_directories(MeowpuutCandidateLayout PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/UI")
target_compile_features(MeowpuutCandidateLayout PUBLIC cxx_std_17)
if(MSVC)
    target_compile_options(MeowpuutCandidateLayout PRIVATE /utf-8 /W4 $<$<CONFIG:Release>:/GL /Gw /Gy>)
endif()

# The desk-pet process's portable parts (pulse channel, world, clay art, loop,
# settings, smoke run, the activity tracker and its ledger): no Windows headers
# outside _WIN32. The text service uses Desk/PulseChannel.cpp to send pulses.
add_library(MeowpuutDeskCore STATIC
    Desk/PulseChannel.cpp Desk/DeskWorld.cpp Desk/DeskArt.cpp Desk/DeskLoop.cpp Desk/DeskSettings.cpp Desk/DeskSmoke.cpp
    Desk/ActivityLedger.cpp Desk/ForegroundWatcher.cpp)
target_link_libraries(MeowpuutDeskCore PUBLIC MeowpuutWindowsSession)
target_compile_features(MeowpuutDeskCore PUBLIC cxx_std_17)
if(WIN32)
    target_compile_definitions(MeowpuutDeskCore PUBLIC UNICODE _UNICODE NOMINMAX _WIN32_WINNT=0x0A00 WINVER=0x0A00)
    target_link_libraries(MeowpuutDeskCore PUBLIC advapi32 user32)
endif()
if(MSVC)
    target_compile_options(MeowpuutDeskCore PRIVATE /utf-8 /W4)
endif()

if(WIN32)
    # UI/GlyphStrip.cpp and GlyphText.cpp are the 字景 strip (mp_glyph_strip_*: meowpuut-ffi built with --features glyph above).
    add_library(MeowpuutCandidateWindow STATIC UI/CandidateWindow.cpp UI/GlyphStrip.cpp UI/GlyphText.cpp)
    target_link_libraries(MeowpuutCandidateWindow PUBLIC MeowpuutCandidateLayout MeowpuutWindowsSession d2d1 dwrite windowscodecs ole32 uuid
                          user32 gdi32 advapi32)
    target_compile_definitions(MeowpuutCandidateWindow PUBLIC UNICODE _UNICODE NOMINMAX _WIN32_WINNT=0x0A00 WINVER=0x0A00 MEOWPUUT_GLYPH=1)
    if(MSVC)
        target_compile_options(MeowpuutCandidateWindow PRIVATE /utf-8 /W4 $<$<CONFIG:Release>:/GL /Gw /Gy>)
    endif()

    # The TSF text service. Its icon, Meowpuut.ico, is made from
    # assets/brand/AppIcon.png by scripts/build-windows-ime.sh; put one in the
    # build directory before building this target.
    add_library(MeowpuutTextService SHARED TextService/TextService.cpp TextService/TextService.def TextService/TextService.rc
                Desk/PulseChannel.cpp)
    target_link_libraries(MeowpuutTextService PRIVATE MeowpuutWindowsSession MeowpuutCandidateWindow oleaut32)
    target_compile_definitions(MeowpuutTextService PRIVATE
        MEOWPUUT_VERSION="門廊 · 26A003" MEOWPUUT_VERSION_NUMBERS=0,9,0,3)
    target_include_directories(MeowpuutTextService PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
    target_compile_options(MeowpuutTextService PRIVATE $<$<COMPILE_LANGUAGE:CXX>:/utf-8 /W4 $<$<CONFIG:Release>:/GL /Gw /Gy>>)
    target_link_options(MeowpuutTextService PRIVATE $<$<CONFIG:Release>:/LTCG /OPT:REF /OPT:ICF>)

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

    # 喵撲貓窩, the desk-pet process (64-bit only). Its icon, Meowpuut.ico, as for
    # the text service; the manifest (Per-Monitor V2) comes from Desk.rc.
    add_executable(MeowpuutDesk WIN32 Desk/DeskApp.cpp Desk/Desk.rc)
    target_link_libraries(MeowpuutDesk PRIVATE MeowpuutDeskCore dwmapi shcore windowscodecs ole32 oleaut32 uuid user32
                          gdi32 advapi32 shell32)
    target_compile_definitions(MeowpuutDesk PRIVATE MEOWPUUT_VERSION="門廊 · 26A003" MEOWPUUT_VERSION_NUMBERS=0,9,0,3)
    target_include_directories(MeowpuutDesk PRIVATE "${CMAKE_CURRENT_BINARY_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/Desk")
    target_compile_options(MeowpuutDesk PRIVATE $<$<COMPILE_LANGUAGE:CXX>:/utf-8 /W4>)
    set_target_properties(MeowpuutDesk PROPERTIES LINK_FLAGS "/MANIFEST:NO")
    # Every clay figure it draws, beside the executable (as scripts/build-windows-desk.sh lays it out).
    set(MEOWPUUT_DESK_ASSETS "$<TARGET_FILE_DIR:MeowpuutDesk>/Assets")
    set(MEOWPUUT_CAT_SPRITES "${CMAKE_CURRENT_SOURCE_DIR}/../assets/brand/cats/sprites")
    add_custom_target(MeowpuutDeskAssets ALL
        COMMAND ${CMAKE_COMMAND} -E remove_directory "${MEOWPUUT_DESK_ASSETS}"
        COMMAND ${CMAKE_COMMAND} -E copy_directory "${CMAKE_CURRENT_SOURCE_DIR}/UI/Assets" "${MEOWPUUT_DESK_ASSETS}"
        COMMAND ${CMAKE_COMMAND} -E copy "${MEOWPUUT_CAT_SPRITES}/mimimo_idle@3x.png" "${MEOWPUUT_DESK_ASSETS}/mimimo_idle.png"
        COMMAND ${CMAKE_COMMAND} -E copy "${MEOWPUUT_CAT_SPRITES}/mimimo_press@3x.png" "${MEOWPUUT_DESK_ASSETS}/mimimo_press.png"
        COMMAND ${CMAKE_COMMAND} -E copy "${MEOWPUUT_CAT_SPRITES}/mimimo_happy@3x.png" "${MEOWPUUT_DESK_ASSETS}/mimimo_happy.png"
        COMMAND ${CMAKE_COMMAND} -E copy "${MEOWPUUT_CAT_SPRITES}/mimimo_sleep@3x.png" "${MEOWPUUT_DESK_ASSETS}/mimimo_sleep.png"
        COMMAND ${CMAKE_COMMAND} -E copy "${MEOWPUUT_CAT_SPRITES}/momo_idle@3x.png" "${MEOWPUUT_DESK_ASSETS}/momo_idle.png"
        COMMAND ${CMAKE_COMMAND} -E copy "${MEOWPUUT_CAT_SPRITES}/momo_wave@3x.png" "${MEOWPUUT_DESK_ASSETS}/momo_wave.png"
        COMMAND ${CMAKE_COMMAND} -E copy "${MEOWPUUT_CAT_SPRITES}/momo_happy@3x.png" "${MEOWPUUT_DESK_ASSETS}/momo_happy.png"
        COMMAND ${CMAKE_COMMAND} -E copy "${MEOWPUUT_CAT_SPRITES}/momo_jump@3x.png" "${MEOWPUUT_DESK_ASSETS}/momo_jump.png")
    add_dependencies(MeowpuutDeskAssets MeowpuutDesk)
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

    add_executable(meowpuut_input_defaults_test tests/input_defaults_test.cpp)
    target_compile_features(meowpuut_input_defaults_test PRIVATE cxx_std_17)
    if(MSVC)
        target_compile_options(meowpuut_input_defaults_test PRIVATE /utf-8 /W4)
    endif()
    add_test(NAME input_defaults COMMAND meowpuut_input_defaults_test)

    if(NOT WIN32)
        add_executable(meowpuut_pet_ledger_test tests/pet_ledger_test.cpp)
        target_link_libraries(meowpuut_pet_ledger_test PRIVATE MeowpuutWindowsSession)
        add_test(NAME pet_ledger COMMAND meowpuut_pet_ledger_test)
    endif()

    add_executable(meowpuut_desk_test Desk/tests/desk_test.cpp)
    target_link_libraries(meowpuut_desk_test PRIVATE MeowpuutDeskCore)
    if(MSVC)
        target_compile_options(meowpuut_desk_test PRIVATE /utf-8 /W4)
    endif()
    add_test(NAME desk COMMAND meowpuut_desk_test)

    add_executable(meowpuut_layout_test UI/tests/layout_test.cpp UI/SvgSnapshot.cpp)
    target_link_libraries(meowpuut_layout_test PRIVATE MeowpuutCandidateLayout)
    if(MSVC)
        target_compile_options(meowpuut_layout_test PRIVATE /utf-8 /W4)
    endif()
    add_test(NAME candidate_layout COMMAND meowpuut_layout_test)
endif()
