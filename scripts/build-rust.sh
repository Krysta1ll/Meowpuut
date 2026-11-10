#!/bin/bash
# Builds the Rust engine (crates/meowpuut-ffi) as a static library.
#
#   bash scripts/build-rust.sh macos              -> .build/rust/macosx/libmeowpuut_ffi.a
#   bash scripts/build-rust.sh iphoneos           -> .build/rust/iphoneos/libmeowpuut_ffi.a
#   bash scripts/build-rust.sh iphonesimulator    -> .build/rust/iphonesimulator/libmeowpuut_ffi.a (arm64 + x86_64)
#   bash scripts/build-rust.sh xcode              -> uses $PLATFORM_NAME / $ARCHS / $CONFIGURATION from Xcode
#   bash scripts/build-rust.sh windows            -> target/x86_64-pc-windows-msvc/release/meowpuut_ffi.lib
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$PATH"
if ! command -v cargo >/dev/null; then
  echo "error: cargo not found. Install Rust from https://rustup.rs" >&2
  exit 1
fi

mode="${1:-macos}"
profile="release"
archs=""
case "$mode" in
  xcode)
    platform="${PLATFORM_NAME:?PLATFORM_NAME is set by Xcode}"
    archs="${ARCHS:-arm64}"
    ;;
  macos) platform="macosx"; archs="$(uname -m)" ;;
  iphoneos) platform="iphoneos"; archs="arm64" ;;
  iphonesimulator) platform="iphonesimulator"; archs="arm64 x86_64" ;;
  windows)
    cargo rustc --release -p meowpuut-ffi --target x86_64-pc-windows-msvc --crate-type staticlib
    exit 0 ;;
  *) echo "usage: $0 macos|iphoneos|iphonesimulator|xcode|windows" >&2; exit 2 ;;
esac

triple_for() {
  case "$platform:$1" in
    macosx:arm64) echo aarch64-apple-darwin ;;
    macosx:x86_64) echo x86_64-apple-darwin ;;
    iphoneos:arm64) echo aarch64-apple-ios ;;
    iphonesimulator:arm64) echo aarch64-apple-ios-sim ;;
    iphonesimulator:x86_64) echo x86_64-apple-ios ;;
    *) echo "error: unsupported $platform/$1" >&2; return 1 ;;
  esac
}

# Rust reads these to stamp the same minimum OS version Xcode links with.
export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-27.0}"
export MACOSX_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-27.0}"
# Xcode exports SDKROOT/LIBRARY_PATH for the app's platform; they must not leak
# into host build scripts or cross targets.
unset SDKROOT LIBRARY_PATH

out=".build/rust/$platform"
mkdir -p "$out"
libs=()
for arch in $archs; do
  triple="$(triple_for "$arch")"
  rustup target list --installed | grep -qx "$triple" || rustup target add "$triple"
  cargo rustc --profile "$profile" -p meowpuut-ffi --target "$triple" --crate-type staticlib
  libs+=("target/$triple/$profile/libmeowpuut_ffi.a")
done
if [ "${#libs[@]}" -gt 1 ]; then
  lipo -create "${libs[@]}" -output "$out/libmeowpuut_ffi.a.tmp"
else
  cp "${libs[0]}" "$out/libmeowpuut_ffi.a.tmp"
fi
# Replace atomically and only when changed, so Xcode does not relink needlessly.
if ! cmp -s "$out/libmeowpuut_ffi.a.tmp" "$out/libmeowpuut_ffi.a"; then
  mv "$out/libmeowpuut_ffi.a.tmp" "$out/libmeowpuut_ffi.a"
else
  rm "$out/libmeowpuut_ffi.a.tmp"
fi
echo "Rust engine: $out/libmeowpuut_ffi.a ($archs)"
