#!/bin/bash
# Builds the Windows release packages of the text service on a machine
# without Windows:
#
#   MINGW_BIN=/path/to/llvm-mingw/bin bash scripts/package-windows.sh
#
#   .build/windows-package/Meowpuut-Porch-<build>-x64.zip
#   .build/windows-package/Meowpuut-Porch-<build>-arm64.zip
#   .build/windows-package/steam-depot-x64/    (the same files, unzipped, as a Steam depot content root)
#   .build/windows-package/steam-depot-arm64/
#   .build/windows-package/SHA256SUMS.txt
#
# The x64 package carries x64\ and x86\ DLLs (same CLSID; 32-bit programs load the x86 one),
# the Arm64 package an arm64\ DLL only (x86 programs on Arm64 are emulated; no x86 DLL).
# Each also carries the desk-pet process, 64-bit only, beside its native DLL:
# x64\MeowpuutDesk.exe or arm64\MeowpuutDesk.exe (scripts/build-windows-desk.sh); Assets\ then holds
# every clay figure it draws as well as the candidate window's.
# Both carry Windows/steam/installscript.vdf for Steam; see Windows/steam/README.md.
#
# Needs llvm-mingw (all three DLLs; Homebrew's GCC cannot build x86) and a generated lexicon folder with
# mimo_base.bin, mimo_pairs.bin and native-japanese.tsv
# (MEOWPUUT_LEXICON_DIR, default Engine/.build/lexicon). The packages are not signed.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$PATH"
BUILD_ID=26A003
: "${MINGW_BIN:?set MINGW_BIN to the bin directory of llvm-mingw}"
TOOLCHAIN=$(cd "$MINGW_BIN/.." && pwd)
LEX="${MEOWPUUT_LEXICON_DIR:-Engine/.build/lexicon}"
OUT=.build/windows-package
rm -rf "$OUT"
mkdir -p "$OUT"

if [ -z "${MEOWPUUT_LEXICON_DIR:-}" ] && [ ! -f "$LEX/mimo_base.bin" ]; then
  bash Engine/scripts/build-lexicon.sh
fi
python3 scripts/export-windows-lexicon.py "$LEX" "$OUT/export" --runtime-only >/dev/null
COMMIT=$(git rev-parse HEAD)
BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then CHANGES="有（建置時工作目錄有未提交的修改）"; else CHANGES="無"; fi
CLANG=$("$MINGW_BIN/x86_64-w64-mingw32-clang++" --version | head -1)
RUSTC=$(rustc --version)
RUST_DOCS="$(rustc --print sysroot)/share/doc/rust"

for ARCH in x64 arm64; do
  case "${ARCH}" in
    x64) TRIPLE=x86_64-w64-mingw32; RUST_TARGET=x86_64-pc-windows-gnullvm; WHO="x64 的 Windows 10、11" ;;
    arm64) TRIPLE=aarch64-w64-mingw32; RUST_TARGET=aarch64-pc-windows-gnullvm; WHO="Arm64 的 Windows 11" ;;
  esac
  MINGW_BIN="$MINGW_BIN" bash scripts/build-windows-ime.sh "${ARCH}" "$OUT/build/${ARCH}"
  DLL_ARCHS=("${ARCH}")
  if [ "${ARCH}" = x64 ]; then
    MINGW_BIN="$MINGW_BIN" bash scripts/build-windows-ime.sh x86 "$OUT/build/x86"
    DLL_ARCHS+=(x86)
  fi
  MINGW_BIN="$MINGW_BIN" bash scripts/build-windows-desk.sh "${ARCH}" "$OUT/build/desk-${ARCH}"
  NAME="Meowpuut-Porch-${BUILD_ID}-${ARCH}"
  STAGE="$OUT/$NAME"
  mkdir -p "$STAGE/lexicon"
  for dll_arch in "${DLL_ARCHS[@]}"; do
    mkdir -p "$STAGE/$dll_arch"
    cp "$OUT/build/$dll_arch/MeowpuutTextService.dll" "$STAGE/$dll_arch/"
  done
  cp "$OUT/build/desk-${ARCH}/MeowpuutDesk.exe" "$STAGE/${ARCH}/"
  cp -R Windows/UI/Assets "$STAGE/Assets"
  cp "$OUT/build/desk-${ARCH}/Assets/"*.png "$STAGE/Assets/"
  for table in mimo_base.bin mimo_pairs.bin mimo_trigrams.bin mimo_assoc.bin native-japanese.tsv; do cp "$OUT/export/$table" "$STAGE/lexicon/"; done
  cp -R "$OUT/export/Licenses" "$STAGE/Licenses"
  # The 字景 candidate window's pixel fonts (Assets\fonts\pixel_a.ttf, pixel_b.otf, loaded privately by the DLL, never
  # installed): SIL OFL 1.1, whose text and notices ship with them (THIRD_PARTY.md §10).
  cp Windows/UI/Assets/fonts/OFL-pixel-fonts.txt "$STAGE/Licenses/OFL-pixel-fonts.txt"
  # Linked into the DLL: libc++, libunwind and compiler-rt (LLVM), the
  # MinGW-w64 runtime, and the Rust standard library.
  cp "$TOOLCHAIN/LICENSE.TXT" "$STAGE/Licenses/llvm-mingw-LLVM.txt"
  for notice in COPYING.MinGW-w64.txt COPYING.MinGW-w64-runtime.txt COPYING.winpthreads.txt; do
    cp "$TOOLCHAIN/${TRIPLE}/share/mingw32/$notice" "$STAGE/Licenses/$notice"
  done
  cp "$RUST_DOCS/COPYRIGHT-library.html" "$STAGE/Licenses/Rust-std-COPYRIGHT.html"
  cp "$RUST_DOCS/licenses/MIT.txt" "$STAGE/Licenses/Rust-std-MIT.txt"
  cp "$RUST_DOCS/licenses/Apache-2.0.txt" "$STAGE/Licenses/Rust-std-Apache-2.0.txt"
  cp THIRD_PARTY.md "$STAGE/"
  cp Windows/TextService/Install.cmd Windows/TextService/Uninstall.cmd Windows/TextService/Install.ps1 "$STAGE/"
  cp Windows/steam/installscript.vdf "$STAGE/"

  ARM_NOTE=""
  X86_NOTE=""
  if [ "${ARCH}" = x64 ]; then X86_NOTE="；32 位元的 DLL 目標 \`i686-w64-mingw32\`，Rust \`i686-pc-windows-gnullvm\`"; fi
  if [ "${ARCH}" = arm64 ]; then
    ARM_NOTE="
Arm64 版只在 Arm64 的程式裡有作用（記事本、Edge、檔案總管、Windows 終端機等內建程式都是）。用 x64 模擬執行的程式載入不了這個 DLL，在那些程式裡切到喵撲，打字會直接出英文字母。這一版沒有 32 位元的 DLL。
"
  else
    ARM_NOTE="
這一版有 64 位元（資料夾 \`x64\`）和 32 位元（資料夾 \`x86\`）兩個 DLL，同一個 CLSID，32 位元的程式（許多舊遊戲和工具）也能打字。
"
  fi
  {
    cat <<EOF
# 喵撲 Windows 門廊 · ${BUILD_ID}（${ARCH}）

適用於 ${WHO}。
${ARM_NOTE}
## 安裝與移除

1. 把 zip 整個解壓。
2. 雙擊 \`Install.cmd\`。Windows 會要求系統管理員權限：檔案會複製到 \`C:\\Program Files\\Meowpuut\`，並註冊成系統輸入法。
3. 按 Windows 鍵 + 空白鍵，選「喵撲拼音」或「喵撲注音」（在你的中文語言底下）。已經開著的程式要重新開啟才會載入。

安裝也會開啟桌面寵物「貓窩」（\`${ARCH}\\MeowpuutDesk.exe\`），並設成登入 Windows 時自動開啟（目前使用者的 \`HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\`，名稱 \`MeowpuutDesk\`）。米米莫和莫莫從系統匣的喵撲圖示出門，在視窗上緣和工作列上生活；游標在貓身上停一下才能點。不想要時，在貓或系統匣圖示上按右鍵，選「設定 › 關閉桌面貓」。

移除：雙擊同一個資料夾裡的 \`Uninstall.cmd\`。它也會關掉貓窩、拿掉登入項目。

DLL、貓窩和安裝腳本都沒有數位簽章。SmartScreen 可能先擋下 \`Install.cmd\`，要按「其他資訊」→「仍要執行」；公司管理的電腦可能完全不允許。

## 建置方式

- 原始碼：commit \`${COMMIT}\`（分支 \`${BRANCH}\`），建置時未提交的修改：${CHANGES}
- 在 macOS 上交叉編譯：\`MINGW_BIN=…/llvm-mingw/bin bash scripts/package-windows.sh\`
- C++：${CLANG}（llvm-mingw $(basename "$TOOLCHAIN" | sed -E 's/^llvm-mingw-([0-9]+).*/\1/')），目標 \`${TRIPLE}\`${X86_NOTE}，靜態連結 libc++、libunwind
- 貓窩：\`scripts/build-windows-desk.sh ${ARCH}\`，只有 64 位元；同一個 Rust 核心（\`mp_desk_*\`、\`mp_pulse_*\`）
- 核心與詞庫：\`$(git -C Engine rev-parse HEAD)\`
- Rust：${RUSTC}，目標 \`${RUST_TARGET}\`
- 詞庫：\`scripts/export-windows-lexicon.py\` 匯出；Windows 用 \`mimo_base.bin\`、\`mimo_pairs.bin\`（直接映射檔案，不複製到記憶體）和 \`native-japanese.tsv\`
- 建置日期：$(date -u +%Y-%m-%d)

## 檔案的 SHA-256

| 檔案 | SHA-256 |
| --- | --- |
EOF
    (cd "$STAGE" && find . -type f ! -name BUILD-INFO.md | sed 's|^\./||' | LC_ALL=C sort | while read -r file; do
      echo "| \`$file\` | \`$(shasum -a 256 "$file" | cut -d' ' -f1)\` |"
    done)
    cat <<EOF

## 授權

- 程式碼：MIT，見 \`Licenses/Meowpuut-MIT.txt\`。
- \`Assets\` 裡米米莫、莫莫和節日的圖：保留所有權利，見 \`Licenses/Meowpuut-Artwork.txt\`。\`festivals.json\` 不算圖像，照 MIT。
- \`Assets\\fonts\` 裡字景候選窗的像素字型（俐方體 11 號、Fusion Pixel Font 的子集）：SIL OFL 1.1，見 \`Licenses/OFL-pixel-fonts.txt\`。它們只由 DLL 私下載入，不會安裝到系統字型。
- 詞庫的資料來源：\`THIRD_PARTY.md\` 和 \`Licenses\` 裡的各份聲明。
- 編進 DLL 和貓窩的執行庫：LLVM（\`Licenses/llvm-mingw-LLVM.txt\`）、MinGW-w64（\`Licenses/COPYING.*\`）、Rust 標準庫（\`Licenses/Rust-std-*\`）。
EOF
  } > "$STAGE/BUILD-INFO.md"
  rm -rf "$OUT/steam-depot-${ARCH}"
  cp -R "$STAGE" "$OUT/steam-depot-${ARCH}"
  (cd "$OUT" && rm -f "$NAME.zip" && zip -9qrX "$NAME.zip" "$NAME")
  echo "Packed $OUT/$NAME.zip ($(du -h "$OUT/$NAME.zip" | cut -f1))"
done
(cd "$OUT" && shasum -a 256 Meowpuut-*.zip > SHA256SUMS.txt && cat SHA256SUMS.txt)
