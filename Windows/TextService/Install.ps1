# 安裝或移除喵撲輸入法。由 Install.cmd、Uninstall.cmd 和 Steam 的 installscript 呼叫。
#
#   Install.ps1             安裝：複製到 Program Files 並註冊（會要求系統管理員權限），
#                           再把喵撲拼音、喵撲注音加入目前使用者的中文鍵盤（優先用已經有的中文語言）。
#   Install.ps1 -Uninstall  從使用者的鍵盤清單拿掉，取消註冊，刪除 Program Files 裡的檔案，
#                           也刪除 %LOCALAPPDATA%\Meowpuut 裡的打字紀錄和「時間花在哪裡」的紀錄。
#   Install.ps1 -Steam      給 Steam 的 installscript：不問任何事、不彈視窗。必須已經是系統管理員
#                           （Steam 的 Run Process 就是），否則以結束代碼 5 離開，不會自己提權。
#                           把這個資料夾（Steam 的 depot）的檔案複製到 Program Files\Meowpuut\<雜湊>，從那裡註冊，
#                           所以 Steam 更新或驗證檔案時不會碰到被各程式載著的 DLL。
#                           成功結束代碼 0；任何失敗非 0（Steam 下次啟動遊戲時會再跑一次）。重複執行不會改變任何東西。
#   Install.ps1 -Steam -Uninstall   同上，但是移除。
#
# 桌面寵物「貓窩」MeowpuutDesk.exe（只有 64 位元，放在 x64\ 或 arm64\，和 DLL 同一個資料夾）：
# 安裝後從 Program Files 裡的那一份設成目前使用者的登入項目（HKCU\...\Run\MeowpuutDesk），Install.cmd 時馬上開啟；
# -Steam 只設登入項目，不從 Steam 的系統管理員程序開啟它（下次登入時開啟）。使用者關掉了桌面貓（HKCU\Software\Meowpuut
# 的 DeskPets = 0）就不設。換版時先關掉舊資料夾裡的貓窩，舊資料夾才刪得掉；移除時關掉貓窩並拿掉登入項目。
#
# 套件的資料夾：x64\、x86\、arm64\ 各放一份 MeowpuutTextService.dll（x64 套件有 x64 和 x86，Arm64 套件只有 arm64），
# 加上 Assets\、lexicon\、Licenses\、THIRD_PARTY.md、BUILD-INFO.md。兩個 DLL 用同一個 CLSID：64 位元的用
# System32\regsvr32.exe 註冊，32 位元的用 SysWOW64\regsvr32.exe 註冊。
#
# 結束代碼：0 成功；1 失敗；4 套件和 Windows 的架構不同；5（-Steam）沒有系統管理員權限。
param([switch]$Uninstall, [switch]$Elevated, [switch]$Steam)

$ErrorActionPreference = 'Stop'
# Any terminating error ends the script with a non-zero code and a line on stderr
# (Steam reads only the exit code; Install.cmd shows the message).
trap { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }

# A 32-bit host (Steam's own process starts the script) sees System32 and the
# registry through the WOW64 redirection. Run again in the native PowerShell.
if (-not [Environment]::Is64BitProcess -and [Environment]::Is64BitOperatingSystem) {
    $native = Join-Path $env:WINDIR 'Sysnative\WindowsPowerShell\v1.0\powershell.exe'
    $again = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath)
    if ($Uninstall) { $again += '-Uninstall' }
    if ($Elevated) { $again += '-Elevated' }
    if ($Steam) { $again += '-Steam' }
    & $native @again
    exit $LASTEXITCODE
}

$clsid = '{961C0ADA-4DC6-488F-9563-4FC144EFAE27}'
# The profiles are registered under both Chinese languages (繁體 0404, 简体 0804; TextService.cpp kLanguages).
$pinyinProfile = '{8FDE097D-3513-486A-9AB5-2D1B2C67C359}'   # 喵撲拼音
$zhuyinProfile = '{1F5D9D62-F4AF-4AAF-82A6-A482D7EC3AE2}'   # 喵撲注音
$languageIds = @{ 'zh-Hant-TW' = '0404'; 'zh-Hans-CN' = '0804' }
$tips = foreach ($id in '0404', '0804') { "${id}:$clsid$pinyinProfile"; "${id}:$clsid$zhuyinProfile" }
$root = Join-Path $env:ProgramFiles 'Meowpuut'
$dllName = 'MeowpuutTextService.dll'
$deskName = 'MeowpuutDesk.exe'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$runValue = 'MeowpuutDesk'
$regsvr64 = Join-Path $env:WINDIR 'System32\regsvr32.exe'
$regsvr32 = Join-Path $env:WINDIR 'SysWOW64\regsvr32.exe'
# The elevated part runs in a hidden window; what it has to say goes here and
# the user's window prints it. (With -Steam there is no second window.)
$messages = Join-Path $env:TEMP 'meowpuut-setup.txt'

if ($Steam -and -not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    [Console]::Error.WriteLine('Meowpuut: -Steam needs administrator rights (Steam runs the installscript elevated).')
    exit 5
}

function Write-Note([string]$text) {
    if ($Elevated) { Add-Content -LiteralPath $messages -Encoding UTF8 $text } else { Write-Host $text }
}

# The DLL a view of the registry points at: Registry64 for the 64-bit server, Registry32 for the 32-bit one.
function Get-RegisteredDll([string]$view) {
    $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine, [Microsoft.Win32.RegistryView]::$view)
    try {
        $key = $base.OpenSubKey("SOFTWARE\Classes\CLSID\$clsid\InprocServer32")
        if ($key) { try { return $key.GetValue('') } finally { $key.Dispose() } }
    } finally { $base.Dispose() }
}

function Invoke-Regsvr32([string]$tool, [string[]]$arguments) {
    $process = Start-Process -FilePath $tool -ArgumentList $arguments -Wait -PassThru -WindowStyle Hidden
    return $process.ExitCode
}

# The desk-pet processes running from Program Files\Meowpuut, except those under $keep (the build just installed).
# Without administrator rights only this user's are seen.
function Stop-Desk([string]$keep = '') {
    foreach ($process in @(Get-Process -Name ([IO.Path]::GetFileNameWithoutExtension($deskName)) -ErrorAction SilentlyContinue)) {
        $path = $null
        try { $path = $process.Path } catch { }
        if (-not $path -or -not $path.StartsWith("$root\", [StringComparison]::OrdinalIgnoreCase)) { continue }
        if ($keep -and $path.StartsWith("$keep\", [StringComparison]::OrdinalIgnoreCase)) { continue }
        try { $process.Kill(); [void]$process.WaitForExit(3000) } catch { }
    }
}

# Schedules a locked file or empty directory for deletion at the next reboot (MOVEFILE_DELAY_UNTIL_REBOOT = 4).
# Requires administrator rights (always true inside Invoke-MachineInstall / Invoke-MachineUninstall).
if (-not ([Management.Automation.PSTypeName]'Meowpuut.FileOps').Type) {
    Add-Type -Namespace Meowpuut -Name FileOps -MemberDefinition @'
[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
public static extern bool MoveFileExW(string existingFileName, string newFileName, uint flags);
'@
}

function Schedule-DeleteOnReboot([string]$targetPath) {
    $delayFlag = [uint32]4  # MOVEFILE_DELAY_UNTIL_REBOOT
    # Files first, then directories deepest-first, then the folder itself (Windows removes directories only when empty).
    $items = @(Get-ChildItem -LiteralPath $targetPath -Recurse -Force -ErrorAction SilentlyContinue)
    foreach ($file in ($items | Where-Object { -not $_.PSIsContainer })) {
        [void][Meowpuut.FileOps]::MoveFileExW($file.FullName, $null, $delayFlag)
    }
    foreach ($dir in ($items | Where-Object { $_.PSIsContainer } | Sort-Object { $_.FullName.Length } -Descending)) {
        [void][Meowpuut.FileOps]::MoveFileExW($dir.FullName, $null, $delayFlag)
    }
    [void][Meowpuut.FileOps]::MoveFileExW($targetPath, $null, $delayFlag)
}

# Folders under Program Files\Meowpuut other than $keep. A DLL still loaded
# by a running program cannot be deleted immediately; schedule it for deletion
# on next reboot via MOVEFILE_DELAY_UNTIL_REBOOT so uninstall/updates stay clean.
function Remove-OldFolders([string]$keep) {
    if (-not (Test-Path -LiteralPath $root)) { return }
    foreach ($folder in Get-ChildItem -LiteralPath $root -Directory) {
        if ($folder.FullName -eq $keep) { continue }
        try { Remove-Item -LiteralPath $folder.FullName -Recurse -Force }
        catch {
            Schedule-DeleteOnReboot $folder.FullName
            Write-Note "仍有程式在使用 $($folder.FullName)，已排程在下次重新開機時自動刪除。"
        }
    }
}

# Which DLLs this Windows takes from the package, in a fixed order (the hash depends on it): the native one
# (x64 on x64 Windows, arm64 on Arm64 Windows), plus the 32-bit one on x64 Windows. A package may carry
# more than it needs (a Steam depot with both architectures); only these are copied. Arm64 Windows runs
# 32-bit programs emulated and has no x86 DLL.
function Get-WindowsArchitecture {
    (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment').PROCESSOR_ARCHITECTURE
}
function Get-WantedArchitectures {
    $wanted = if ((Get-WindowsArchitecture) -eq 'ARM64') { @('arm64') } else { @('x64', 'x86') }
    $wanted | Where-Object { Test-Path -LiteralPath (Join-Path $PSScriptRoot "$_\$dllName") }
}

# --- the part that needs administrator rights ---------------------------------
function Invoke-MachineUninstall {
    Stop-Desk
    $failed = @()
    foreach ($entry in @(@('Registry64', $regsvr64), @('Registry32', $regsvr32))) {
        $dll = Get-RegisteredDll $entry[0]
        if ($dll -and (Test-Path -LiteralPath $dll) -and (Test-Path -LiteralPath $entry[1])) {
            $code = Invoke-Regsvr32 $entry[1] @('/u', '/s', "`"$dll`"")
            if ($code -ne 0) { $failed += "取消註冊 $dll 失敗（regsvr32 結束代碼 $code）。" }
        }
    }
    if ($failed) { throw ($failed -join ' ') }
    Remove-OldFolders ''
    if (Test-Path -LiteralPath $root) {
        if (-not (Get-ChildItem -LiteralPath $root)) {
            Remove-Item -LiteralPath $root
        } else {
            [void][Meowpuut.FileOps]::MoveFileExW($root, $null, [uint32]4)
        }
    }
}

function Invoke-MachineInstall {
    $source = $PSScriptRoot
    $archs = @(Get-WantedArchitectures)
    $native = if ((Get-WindowsArchitecture) -eq 'ARM64') { 'arm64' } else { 'x64' }
    if ($archs -notcontains $native) { throw "套件裡找不到 $native\$dllName。" }
    foreach ($needed in 'Assets', 'lexicon') {
        if (-not (Test-Path -LiteralPath (Join-Path $source $needed))) { throw "套件裡少了 $needed。" }
    }
    # Each build gets its own folder: programs that are running keep the DLL
    # they loaded, and the next install removes the folder once it is free.
    # The name is a hash of every DLL and desk program of the package, so a new 32-bit DLL or a new
    # MeowpuutDesk.exe alone also gets a new folder.
    $files = foreach ($arch in $archs) {
        Join-Path $source "$arch\$dllName"
        $desk = Join-Path $source "$arch\$deskName"
        if (Test-Path -LiteralPath $desk) { $desk }
    }
    $sums = ($files | ForEach-Object { (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }) -join ''
    $sha = [Security.Cryptography.SHA256]::Create()
    $hash = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($sums))) -replace '-', '').Substring(0, 12).ToLowerInvariant()
    $target = Join-Path $root $hash
    # The same build installed again: its DLLs may be in use, and the files are the same.
    # A folder only gets its final name once every file is in it, so a half-copied one is never taken for a good one.
    if (-not (Test-Path -LiteralPath $target)) {
        $partial = "$target.partial"
        if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Recurse -Force }
        New-Item -ItemType Directory -Force $partial | Out-Null
        foreach ($item in ($archs + 'Assets', 'lexicon', 'Licenses', 'THIRD_PARTY.md', 'BUILD-INFO.md')) {
            $from = Join-Path $source $item
            if (Test-Path -LiteralPath $from) { Copy-Item -LiteralPath $from -Destination $partial -Recurse -Force }
        }
        Move-Item -LiteralPath $partial -Destination $target
    }
    # The 32-bit server first, so the profile's icon path (the last registration wins) names the 64-bit DLL.
    if ($archs -contains 'x86') {
        $code = Invoke-Regsvr32 $regsvr32 @('/s', "`"$(Join-Path $target "x86\$dllName")`"")
        if ($code -ne 0) { throw "註冊 32 位元的 DLL 失敗（regsvr32 結束代碼 $code）。" }
    }
    $code = Invoke-Regsvr32 $regsvr64 @('/s', "`"$(Join-Path $target "$native\$dllName")`"")
    if ($code -ne 0) { throw "註冊失敗（regsvr32 結束代碼 $code）。" }
    # An older build's desk keeps its folder in use. Install.cmd starts the new one right after; under Steam
    # (elevated, nobody to start it as) the old one keeps running and its folder goes at the next install.
    if (-not $Steam) { Stop-Desk $target }
    Remove-OldFolders $target
}

if ($Elevated) {
    try {
        if ($Uninstall) { Invoke-MachineUninstall } else { Invoke-MachineInstall }
        exit 0
    } catch {
        Add-Content -LiteralPath $messages -Encoding UTF8 $_.Exception.Message
        exit 1
    }
}

# --- run as the user: elevate for the machine part, then set up this user ----
function Invoke-Elevated {
    if ($Steam) {
        # Already elevated (checked above): do it in this process.
        if ($Uninstall) { Invoke-MachineUninstall } else { Invoke-MachineInstall }
        return
    }
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"", '-Elevated')
    if ($Uninstall) { $arguments += '-Uninstall' }
    Remove-Item -LiteralPath $messages -ErrorAction SilentlyContinue
    $process = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList $arguments -Verb RunAs -Wait -PassThru -WindowStyle Hidden
    $said = if (Test-Path -LiteralPath $messages) { (Get-Content -LiteralPath $messages -Encoding UTF8) -join "`n" } else { '' }
    Remove-Item -LiteralPath $messages -ErrorAction SilentlyContinue
    if ($process.ExitCode -ne 0) { throw "需要系統管理員權限的步驟失敗（結束代碼 $($process.ExitCode)）。$said" }
    if ($said) { Write-Host $said }
}

Add-Type -Namespace Meowpuut -Name Input -MemberDefinition @'
[DllImport("input.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
public static extern bool InstallLayoutOrTip(string profile, uint flags);
[DllImport("kernel32.dll")]
public static extern int GetUserGeoID(int geoClass);
[DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
public static extern int GetGeoInfoW(int geoId, int geoType, System.Text.StringBuilder data, int length, int language);
'@

if ($Uninstall) {
    # 貓窩：拿掉登入項目、關掉它，Program Files 裡的檔案才刪得掉。
    Remove-ItemProperty -LiteralPath $runKey -Name $runValue -ErrorAction SilentlyContinue
    Stop-Desk
    $list = Get-WinUserLanguageList
    foreach ($language in $list) {
        foreach ($tip in $tips) { [void]$language.InputMethodTips.Remove($tip) }
    }
    Set-WinUserLanguageList $list -Force
    foreach ($tip in $tips) { [void][Meowpuut.Input]::InstallLayoutOrTip($tip, 1) }  # ILOT_UNINSTALL
    Invoke-Elevated
    Remove-Item -LiteralPath 'HKCU:\Software\Meowpuut' -Recurse -ErrorAction SilentlyContinue
    # 打字紀錄（只有數量）和貓窩的「時間花在哪裡」（只有類別和分鐘數）：檔案、鎖檔、沒寫完的暫存檔，
    # 資料夾空了就一起刪。
    $journal = Join-Path $env:LOCALAPPDATA 'Meowpuut'
    if (Test-Path -LiteralPath $journal) {
        foreach ($name in 'PetJournal.bin', 'PetJournal.lock', 'PetJournal.bin.tmp*', 'Activity.bin', 'Activity.lock', 'Activity.bin.tmp*', 'DeskCounters.json', 'DeskCounters.json.tmp*') {
            Remove-Item -Path (Join-Path $journal $name) -Force -ErrorAction SilentlyContinue
        }
        if (-not (Get-ChildItem -LiteralPath $journal -Force -ErrorAction SilentlyContinue)) {
            Remove-Item -LiteralPath $journal -ErrorAction SilentlyContinue
        }
    }
    Write-Host '喵撲輸入法和貓窩已移除。'
    exit 0
}

# The DLL must match Windows: an x64 DLL does not load in Arm64 programs, nor the other way round.
$windowsArch = Get-WindowsArchitecture
$nativeDir = if ($windowsArch -eq 'ARM64') { 'arm64' } else { 'x64' }
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot "$nativeDir\$dllName"))) {
    $wanted = if ($windowsArch -eq 'ARM64') { 'windows-arm64' } else { 'windows-x64' }
    [Console]::Error.WriteLine("這個套件沒有給 $windowsArch 的 Windows 用的 DLL。請下載檔名有 $wanted 的套件。")
    exit 4
}

# An install from before the 中日混打 switch existed (the service is registered, or its folder is there)
# keeps today's behaviour: mixed Japanese stays on and the script stays Traditional unless one was chosen.
# The 0.1.x versions wrote nothing for a person who never opened the menu, so the files tell it apart.
# Written once, before the new DLL can run, so a later change is never overwritten.
$hadEarlier = [bool](Get-RegisteredDll 'Registry64') -or (Test-Path -LiteralPath $root)

Invoke-Elevated

if ($hadEarlier) {
    $settings = 'HKCU:\Software\Meowpuut'
    if (-not (Test-Path -LiteralPath $settings)) { New-Item -Path $settings -Force | Out-Null }
    $have = (Get-Item -LiteralPath $settings).Property
    if ($have -notcontains 'MixedJapanese') { New-ItemProperty -Path $settings -Name 'MixedJapanese' -Value 1 -PropertyType DWord | Out-Null }
    if ($have -notcontains 'Simplified') { New-ItemProperty -Path $settings -Name 'Simplified' -Value 0 -PropertyType DWord | Out-Null }
}

# 貓窩: a login item for this user, from the folder just registered (the Run value names that build), and
# started now unless Steam runs this (elevated, maybe as another account: the desk must not run elevated
# or from Steam; it starts at the next sign-in). Not when this user turned the desk pets off.
$deskOff = (Get-ItemProperty -LiteralPath 'HKCU:\Software\Meowpuut' -Name DeskPets -ErrorAction SilentlyContinue).DeskPets -eq 0
$installed = Get-RegisteredDll 'Registry64'
if ($installed -and -not $deskOff) {
    $desk = Join-Path (Split-Path -Parent $installed) $deskName
    if (Test-Path -LiteralPath $desk) {
        $registered = Start-Process -FilePath $desk -ArgumentList '--register-login' -Wait -PassThru
        if ($registered.ExitCode -ne 0) { Write-Warning '貓窩沒有設成登入時開啟。' }
        if (-not $Steam) { Start-Process -FilePath $desk }  # a second one sees the first and exits
    }
}

# 0.1.x started a separate glass renderer at sign-in; it is gone now.
$startup = Join-Path ([Environment]::GetFolderPath('Startup')) 'Meowpuut Candidate Renderer.lnk'
if (Test-Path -LiteralPath $startup) { Remove-Item -LiteralPath $startup }
Get-Process MeowpuutGlassPreview -ErrorAction SilentlyContinue | Stop-Process -ErrorAction SilentlyContinue

# What a fresh install starts with, the same table as Windows/InputDefaults.h chooseStart (keep them in step):
# region TW, or a Traditional language tagged TW, starts on 注音 and Traditional script; every other case
# starts on 拼音 (Simplified for regions CN, SG and MY, for a Simplified language, or when nothing is known;
# Traditional for regions HK and MO or a Traditional-only language list).
# The region is read here, used once and not stored.
function Get-Region {
    $text = New-Object System.Text.StringBuilder 8
    $geo = [Meowpuut.Input]::GetUserGeoID(16)  # GEOCLASS_NATION
    if ($geo -gt 0 -and [Meowpuut.Input]::GetGeoInfoW($geo, 4, $text, 8, 0) -gt 0) { return $text.ToString().ToUpperInvariant() }  # GEO_ISO2
    return ''
}
function Get-Start($list) {
    $region = Get-Region
    $scripts = @()
    $taiwanLanguage = $false
    foreach ($language in $list) {
        $tag = $language.LanguageTag
        if ($tag -notmatch '^zh([-_]|$)') { continue }
        $parts = $tag -split '[-_]'
        $scriptName = ($parts | Where-Object { $_.Length -eq 4 } | Select-Object -First 1)
        $part = ($parts | Select-Object -Skip 1 | Where-Object { $_.Length -eq 2 } | Select-Object -First 1)
        $code = if ($part) { $part.ToUpperInvariant() } else { '' }
        $kind = if ($scriptName -ieq 'Hans') { 'Hans' } elseif ($scriptName -ieq 'Hant') { 'Hant' }
                elseif ($code -in 'CN', 'SG', 'MY') { 'Hans' } elseif ($code -in 'HK', 'MO', 'TW') { 'Hant' } else { 'None' }
        $scripts += $kind
        if ($code -eq 'TW' -and $kind -ne 'Hans') { $taiwanLanguage = $true }
    }
    if ($region -eq 'TW' -or $taiwanLanguage) { return @{ Zhuyin = $true; Simplified = $false } }
    if ($region -in 'CN', 'SG', 'MY' -or $scripts -contains 'Hans') { return @{ Zhuyin = $false; Simplified = $true } }
    if ($region -in 'HK', 'MO') { return @{ Zhuyin = $false; Simplified = $false } }
    foreach ($kind in $scripts) { if ($kind -ne 'None') { return @{ Zhuyin = $false; Simplified = ($kind -ne 'Hant') } } }
    return @{ Zhuyin = $false; Simplified = $true }
}

$list = Get-WinUserLanguageList
$start = Get-Start $list
# The keyboards go under a Chinese language the person already has (the one for the start's script first),
# and a language is added only when there is none.
$wantedTag = if ($start.Simplified) { 'zh-Hans-CN' } else { 'zh-Hant-TW' }
# An earlier install keeps the language that already holds the keyboards.
$language = $null
if ($hadEarlier) { $language = $list | Where-Object { $tag = $_; [bool]($tips | Where-Object { $tag.InputMethodTips -contains $_ }) } | Select-Object -First 1 }
if (-not $language) { $language = $list | Where-Object LanguageTag -eq $wantedTag | Select-Object -First 1 }
if (-not $language) { $language = $list | Where-Object { $languageIds.ContainsKey($_.LanguageTag) } | Select-Object -First 1 }
if (-not $language) {
    $language = (New-WinUserLanguageList $wantedTag)[0]
    $list.Add($language)
}
$id = $languageIds[$language.LanguageTag]
# The first keyboard of a language is its default: 注音 or 拼音 by the start above, unless this is an earlier install.
$ours = if ($start.Zhuyin) { @("${id}:$clsid$zhuyinProfile", "${id}:$clsid$pinyinProfile") } else { @("${id}:$clsid$pinyinProfile", "${id}:$clsid$zhuyinProfile") }
if (-not $hadEarlier) {
    foreach ($tip in $ours) { [void]$language.InputMethodTips.Remove($tip) }
    for ($n = $ours.Count - 1; $n -ge 0; $n--) { $language.InputMethodTips.Insert(0, $ours[$n]) }
} else {
    foreach ($tip in $ours) { if ($language.InputMethodTips -notcontains $tip) { $language.InputMethodTips.Add($tip) } }
}
Set-WinUserLanguageList $list -Force
foreach ($tip in $ours) {
    if (-not [Meowpuut.Input]::InstallLayoutOrTip($tip, 0)) { Write-Warning "Windows 沒有啟用 $tip，登出再登入後再試一次。" }
}
Write-Host '喵撲拼音和喵撲注音已加入你的中文鍵盤。按 Windows 鍵 + 空白鍵切換。已經開著的程式要重新開啟才會載入新版。'
if ($installed -and -not $deskOff) { Write-Host '米米莫和莫莫也從系統匣的喵撲圖示出門了。不想要桌面貓：在貓或圖示上按右鍵，「設定 › 關閉桌面貓」。' }
