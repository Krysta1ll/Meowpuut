#!/bin/bash
# Assembles and optionally Authenticode-signs the Windows Steam depots under .build/windows-package/:
#   - .build/windows-package/steam-depot-x64/   (x64 + x86 IME DLLs, x64 Desk, Companion exe + steam_api64.dll, installscript.vdf)
#   - .build/windows-package/steam-depot-arm64/ (arm64 IME DLL, arm64 Desk)
#
# Usage:
#   # 1. Build Windows IME + Desk depots on macOS (or Windows) with llvm-mingw:
#   MINGW_BIN=/path/to/llvm-mingw/bin bash scripts/package-windows.sh
#   # 2. Build Companion on Windows (or provide MEOWPUUT_WIN_COMPANION_DIR containing meowpuut-companion.exe & steam_api64.dll):
#   #    (Windows) cd Companion && npm run tauri build -- --bundles none --features steam --config src-tauri/tauri.steam-windows.conf.json
#   # 3. Stage Companion + optionally Authenticode sign every PE binary & Install.ps1:
#   MEOWPUUT_WIN_COMPANION_DIR=/path/to/win-companion-release \
#   [SIGN_CMD="osslsigncode sign ... -in {IN} -out {OUT}"] \
#   bash scripts/package-steam-windows.sh [--check]
set -euo pipefail
cd "$(dirname "$0")/.."

CHECK_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    *) echo "usage: $0 [--check]" >&2; exit 2 ;;
  esac
done

PKG_DIR="${MEOWPUUT_WIN_PKG:-$PWD/.build/windows-package}"
X64_DEPOT="$PKG_DIR/steam-depot-x64"
ARM64_DEPOT="$PKG_DIR/steam-depot-arm64"
COMP_DIR="${MEOWPUUT_WIN_COMPANION_DIR:-$PWD/Companion/src-tauri/target/x86_64-pc-windows-msvc/release}"
BUILD_ID="$(sed -n 's/^BUILD_ID=//p' scripts/package-windows.sh | head -1)"

if [ "$CHECK_ONLY" = 1 ]; then
  echo "檢查設定：BUILD_ID=$BUILD_ID"
  grep -q "InstalledBuild_$BUILD_ID" Windows/steam/installscript.vdf || {
    echo "error: Windows/steam/installscript.vdf 缺少 InstalledBuild_$BUILD_ID" >&2
    exit 1
  }
  echo "installscript.vdf 與 package-windows.sh BUILD_ID ($BUILD_ID) 一致。"
  exit 0
fi

for d in "$X64_DEPOT" "$ARM64_DEPOT"; do
  [ -d "$d" ] || { echo "error: 找不到 $d，請先執行 MINGW_BIN=... bash scripts/package-windows.sh" >&2; exit 1; }
done
[ -f "$COMP_DIR/meowpuut-companion.exe" ] || {
  echo "error: 找不到 $COMP_DIR/meowpuut-companion.exe（請在 Windows 建置 Companion --features steam 或設定 MEOWPUUT_WIN_COMPANION_DIR）" >&2
  exit 1
}
[ -f "$COMP_DIR/steam_api64.dll" ] || {
  echo "error: 找不到 $COMP_DIR/steam_api64.dll（Companion 需啟用 --features steam 建置）" >&2
  exit 1
}

cp "$COMP_DIR/meowpuut-companion.exe" "$X64_DEPOT/meowpuut-companion.exe"
cp "$COMP_DIR/steam_api64.dll" "$X64_DEPOT/steam_api64.dll"
rm -f "$X64_DEPOT/steam_appid.txt" "$ARM64_DEPOT/steam_appid.txt"

if [ -n "${SIGN_CMD:-}" ]; then
  echo "== Authenticode 簽章（SIGN_CMD）"
  sign_one() {
    local target="$1"
    [ -f "$target" ] || return 0
    local tmp="${target}.signed"
    local cmd="${SIGN_CMD//\{IN\}/$target}"
    cmd="${cmd//\{OUT\}/$tmp}"
    eval "$cmd"
    mv "$tmp" "$target"
    echo "  signed: $target"
  }
  for f in \
    "$X64_DEPOT/x64/MeowpuutTextService.dll" \
    "$X64_DEPOT/x86/MeowpuutTextService.dll" \
    "$X64_DEPOT/x64/MeowpuutDesk.exe" \
    "$X64_DEPOT/meowpuut-companion.exe" \
    "$X64_DEPOT/Install.ps1" \
    "$ARM64_DEPOT/arm64/MeowpuutTextService.dll" \
    "$ARM64_DEPOT/arm64/MeowpuutDesk.exe" \
    "$ARM64_DEPOT/Install.ps1"; do
    sign_one "$f"
  done
else
  echo "注意：未設定 SIGN_CMD，產出之 depot 尚未 Authenticode 簽章（上架前必須簽章）。"
fi

grep -q "InstalledBuild_$BUILD_ID" "$X64_DEPOT/installscript.vdf" || {
  echo "error: $X64_DEPOT/installscript.vdf 缺少 InstalledBuild_$BUILD_ID" >&2
  exit 1
}

echo "已完成 Windows Steam depot 組裝："
echo "  $X64_DEPOT"
echo "  $ARM64_DEPOT"
