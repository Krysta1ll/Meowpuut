#!/bin/bash
# Builds the Windows release packages of the text service on a machine
# without Windows:
#
#   MINGW_BIN=/path/to/llvm-mingw/bin bash scripts/package-windows.sh
#
#   .build/windows-package/Meowpuut-<version>-windows-x64.zip
#   .build/windows-package/Meowpuut-<version>-windows-arm64.zip
#   .build/windows-package/SHA256SUMS.txt
#
# Needs llvm-mingw (both architectures) and a generated lexicon folder with
# mimo_base.bin, mimo_pairs.bin and the native-*.tsv tables
# (MEOWPUUT_LEXICON_DIR, default iOS/Generated). The packages are not signed.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$PATH"
VERSION=0.8.0-alpha.1
: "${MINGW_BIN:?set MINGW_BIN to the bin directory of llvm-mingw}"
TOOLCHAIN=$(cd "$MINGW_BIN/.." && pwd)
LEX="${MEOWPUUT_LEXICON_DIR:-iOS/Generated}"
OUT=.build/windows-package
rm -rf "$OUT"
mkdir -p "$OUT"

python3 scripts/export-windows-lexicon.py "$LEX" "$OUT/export" >/dev/null
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
  NAME="Meowpuut-${VERSION}-windows-${ARCH}"
  STAGE="$OUT/$NAME"
  mkdir -p "$STAGE/lexicon"
  cp "$OUT/build/${ARCH}/MeowpuutTextService.dll" "$STAGE/"
  cp -R Windows/UI/Assets "$STAGE/Assets"
  for table in mimo_base.bin mimo_pairs.bin native-japanese.tsv; do cp "$OUT/export/$table" "$STAGE/lexicon/"; done
  cp -R "$OUT/export/Licenses" "$STAGE/Licenses"
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

  ARM_NOTE=""
  if [ "${ARCH}" = arm64 ]; then
    ARM_NOTE="
Arm64 版只在 Arm64 的程式裡有作用（記事本、Edge、檔案總管、Windows 終端機等內建程式都是）。用 x64 模擬執行的程式載入不了這個 DLL，在那些程式裡切到喵撲，打字會直接出英文字母。
"
  fi
  {
    cat <<EOF
# 喵撲 Windows ${VERSION}（${ARCH}）

給 ${WHO}。這是 alpha 版。
${ARM_NOTE}
## 安裝與移除

1. 把 zip 整個解壓。
2. 雙擊 \`Install.cmd\`。Windows 會要求系統管理員權限：檔案會複製到 \`C:\\Program Files\\Meowpuut\`，並註冊成系統輸入法。
3. 按 Windows 鍵 + 空白鍵，選「喵撲拼音」或「喵撲注音」（在「中文（台灣）」底下）。已經開著的程式要重新開啟才會載入。

移除：雙擊同一個資料夾裡的 \`Uninstall.cmd\`。

DLL 和安裝腳本都沒有數位簽章。SmartScreen 可能先擋下 \`Install.cmd\`，要按「其他資訊」→「仍要執行」；公司管理的電腦可能完全不允許。

## 建置方式

- 原始碼：commit \`${COMMIT}\`（分支 \`${BRANCH}\`），建置時未提交的修改：${CHANGES}
- 在 macOS 上交叉編譯：\`MINGW_BIN=…/llvm-mingw/bin bash scripts/package-windows.sh\`
- C++：${CLANG}（llvm-mingw $(basename "$TOOLCHAIN" | sed -E 's/^llvm-mingw-([0-9]+).*/\1/')），目標 \`${TRIPLE}\`，靜態連結 libc++、libunwind
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
- 詞庫的資料來源：\`THIRD_PARTY.md\` 和 \`Licenses\` 裡的各份聲明。
- 編進 DLL 的執行庫：LLVM（\`Licenses/llvm-mingw-LLVM.txt\`）、MinGW-w64（\`Licenses/COPYING.*\`）、Rust 標準庫（\`Licenses/Rust-std-*\`）。
EOF
  } > "$STAGE/BUILD-INFO.md"
  (cd "$OUT" && rm -f "$NAME.zip" && zip -qrX "$NAME.zip" "$NAME")
  echo "Packed $OUT/$NAME.zip ($(du -h "$OUT/$NAME.zip" | cut -f1))"
done
(cd "$OUT" && shasum -a 256 Meowpuut-*.zip > SHA256SUMS.txt && cat SHA256SUMS.txt)
