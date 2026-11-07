param(
    [string] $SdkRoot = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $repo '.build/android-tools'
if (!$SdkRoot) { $SdkRoot = Join-Path $tools 'sdk' }
$env:ANDROID_HOME = (Resolve-Path -LiteralPath $SdkRoot).Path
$env:ANDROID_AVD_HOME = Join-Path $tools 'avd'
New-Item -ItemType Directory -Path $env:ANDROID_AVD_HOME -Force | Out-Null

if (!$env:JAVA_HOME -and (Test-Path -LiteralPath $tools)) {
    $jdk = Get-ChildItem -LiteralPath $tools -Directory -Filter 'jdk-*' | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}

$emulator = Join-Path $env:ANDROID_HOME 'emulator/emulator.exe'
$avdmanager = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/avdmanager.bat'
if (!(Test-Path -LiteralPath $avdmanager)) {
    $avdmanager = Join-Path $env:ANDROID_HOME 'cmdline-tools/bin/avdmanager.bat'
}
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$image = 'system-images;android-35;default;x86_64'
$device = 'Meowpuut_API_35'
$port = 5568
$serial = "emulator-$port"

if (!((& $emulator -list-avds) -contains $device)) {
    'no' | & $avdmanager create avd --name $device --package $image --device pixel_6
    if ($LASTEXITCODE -ne 0) { throw "Unable to create $device. Install $image in the SDK." }
}

$connected = (& $adb devices) | Where-Object { $_ -match "^$serial\s" }
if (!$connected) {
    Start-Process -FilePath $emulator -ArgumentList '-avd', $device, '-port', "$port",
        '-gpu', 'swiftshader', '-no-snapshot-load'
}

$deadline = (Get-Date).AddMinutes(5)
do {
    Start-Sleep 3
    $booted = & $adb -s $serial shell getprop sys.boot_completed 2>$null
} until ($booted -eq '1' -or (Get-Date) -ge $deadline)
if ($booted -ne '1') { throw "The Android emulator did not finish booting." }

$apk = Get-ChildItem (Join-Path $PSScriptRoot 'build/outputs/apk/debug') -Filter '*.apk' |
    Select-Object -First 1
if (!$apk) { throw "Build the Android debug APK before running this script." }

function Run-Adb {
    & $adb -s $serial @args
    if ($LASTEXITCODE -ne 0) { throw "adb command failed: $args" }
}

Run-Adb install -r $apk.FullName
$ime = 'app.meowpuut.android/.MeowpuutKeyboard'
$deadline = (Get-Date).AddSeconds(20)
do {
    $registered = (& $adb -s $serial shell ime list -a -s) -contains $ime
    if (!$registered) { Start-Sleep -Milliseconds 500 }
} until ($registered -or (Get-Date) -ge $deadline)
if (!$registered) { throw "The emulator did not register $ime." }

Run-Adb shell ime enable $ime
Run-Adb shell ime set $ime
Run-Adb shell settings put secure show_ime_with_hard_keyboard 1
Run-Adb shell am start -n app.meowpuut.android/.SetupActivity
