#!/usr/bin/env python3
"""Check the built iOS app/keyboard privacy setup, without installing it.

Usage: python3 scripts/check-ios-privacy.py /path/to/Meowpuut.app [--release]
These checks do not replace Apple's archive validation or a privacy review.
"""
import argparse
import plistlib
import subprocess
import sys
from pathlib import Path


def read_plist(path):
    with path.open('rb') as file:
        return plistlib.load(file)


def entitlements(bundle):
    result = subprocess.run(['/usr/bin/codesign', '-d', '--xml', '--entitlements', ':-', str(bundle)],
                            capture_output=True, check=True)
    return plistlib.loads(result.stdout)


def check(condition, message):
    if not condition:
        raise ValueError(message)
    print('PASS:', message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('app', type=Path)
    parser.add_argument('--release', action='store_true', help='reject development get-task-allow')
    args = parser.parse_args()
    app = args.app
    keyboard = app / 'PlugIns' / 'MeowpuutKeyboard.appex'
    check(app.is_dir() and keyboard.is_dir(), 'host app and keyboard extension exist')
    info = read_plist(keyboard / 'Info.plist')
    extension = info['NSExtension']
    check(extension['NSExtensionPointIdentifier'] == 'com.apple.keyboard-service', 'keyboard extension point')
    check(extension['NSExtensionAttributes'].get('RequestsOpenAccess') is False, 'full access is not requested')

    manifest = read_plist(keyboard / 'PrivacyInfo.xcprivacy')
    check(manifest.get('NSPrivacyTracking') is False, 'keyboard manifest declares no tracking')
    check(manifest.get('NSPrivacyCollectedDataTypes') == [], 'keyboard manifest declares no collected data types')
    reasons = {}
    for item in manifest.get('NSPrivacyAccessedAPITypes', []):
        category = item['NSPrivacyAccessedAPIType']
        check(category not in reasons, f'no duplicate reason category: {category}')
        reasons[category] = set(item['NSPrivacyAccessedAPITypeReasons'])
    expected = {
        'NSPrivacyAccessedAPICategoryUserDefaults': 'CA92.1',
        'NSPrivacyAccessedAPICategoryFileTimestamp': 'C617.1',
        'NSPrivacyAccessedAPICategorySystemBootTime': '35F9.1',
    }
    for category, reason in expected.items():
        check(reason in reasons.get(category, set()), f'{category} declares {reason}')

    host_entitlements, keyboard_entitlements = entitlements(app), entitlements(keyboard)
    group_key = 'com.apple.security.application-groups'
    host_groups = set(host_entitlements.get(group_key, []))
    keyboard_groups = set(keyboard_entitlements.get(group_key, []))
    check(bool({'group.com.krysta1ll.meowpuut.shared', 'group.app.meowpuut.shared'} & host_groups & keyboard_groups), 'host and keyboard share the configured app group')
    if args.release:
        check(not host_entitlements.get('get-task-allow', False) and not keyboard_entitlements.get('get-task-allow', False),
              'release is not signed for debugging')
    else:
        print('INFO: development build checked; release signing was not assessed')
    print('INFO: review actual API usage, host manifest, privacy policy, archive and on-device behavior separately')


if __name__ == '__main__':
    try:
        main()
    except (KeyError, OSError, ValueError, plistlib.InvalidFileException, subprocess.CalledProcessError) as error:
        print('FAIL:', error, file=sys.stderr)
        sys.exit(1)
