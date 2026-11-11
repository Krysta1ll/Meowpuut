#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"
SDK_VERSION="$(xcrun --sdk iphonesimulator --show-sdk-version)"
case "${1:-}" in
  --check-with-installed-sdk)
    echo "仅做已安装 SDK $SDK_VERSION 的编译诊断，不作为 iOS 27 验收。" ;;
  "")
    if [ "${SDK_VERSION%%.*}" -lt 27 ]; then
      echo "当前 SDK 为 $SDK_VERSION。请安装含 iOS 27 SDK 的 Xcode；尚未开始 iOS 27 编译。"
      exit 2
    fi ;;
  *) echo "用法：bash scripts/build-ios.sh [--check-with-installed-sdk]"; exit 2 ;;
esac
bash scripts/prepare-ios.sh
xcodebuild -project iOS/Meowpuut.xcodeproj -scheme Meowpuut -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath .build/iOS \
  CODE_SIGNING_ALLOWED=NO build
