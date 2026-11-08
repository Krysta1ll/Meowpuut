#!/bin/bash
# Platform-independent keyboard logic; UIKit rendering still needs an iOS build/device.
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d "${TMPDIR:-/tmp}/meowpuut-ios-input.XXXXXX")
trap 'rm -rf "$work"' EXIT

for component in CompositionDelivery CapitalizationState KeyboardLayoutMetrics KeyboardSymbols KeyPreview; do
    swiftc "iOS/Keyboard/$component.swift" "iOS/tests/${component}Tests.swift" -o "$work/check"
    "$work/check"
done
for component in RepeatDelete SpaceCursor; do
    swiftc "iOS/Keyboard/${component}Button.swift" "iOS/tests/${component}Tests.swift" -o "$work/check"
    "$work/check"
done
swiftc iOS/SharedKeyboardSettings.swift iOS/tests/SharedKeyboardSettingsTests.swift -o "$work/check"
"$work/check"
