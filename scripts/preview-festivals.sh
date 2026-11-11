#!/bin/bash
# Development only: still images of every festival theme on the candidate bar
# (scripts/FestivalPreview.swift), light and dark, all three glass shapes,
# landscape, a 320 pt phone and iPad. Written to .build/festival-previews/.
#
#   bash scripts/preview-festivals.sh            every theme
#   bash scripts/preview-festivals.sh --only ID  one theme
set -euo pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"
BIN=.build/FestivalPreview
mkdir -p .build/festival-previews
swiftc -parse-as-library Sources/Appearance.swift Sources/CandidatePalette.swift Sources/GlassShapes.swift \
  Sources/Festivals.swift Sources/FestivalTrim.swift scripts/FestivalPreview.swift \
  -target "$(uname -m)-apple-macosx27.0" -framework AppKit -framework SwiftUI -framework Metal -framework QuartzCore -o "$BIN"
"$BIN" "$PWD/assets/festivals" "$PWD/.build/festival-previews" "$@"
