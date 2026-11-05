#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
APP="$HOME/Library/Input Methods/Meowpuut.app"
if [ ! -d "$APP" ]; then
  APP="$PWD/.build/Meowpuut.app"
  test -x "$APP/Contents/MacOS/Meowpuut" || bash scripts/build.sh
fi
open "$APP" --args --settings
