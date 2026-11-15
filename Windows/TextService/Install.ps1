# 安裝或移除喵撲輸入法。由 Install.cmd、Uninstall.cmd 呼叫。
#
#   Install.ps1             安裝：複製到 Program Files 並註冊（會要求系統管理員權限），
#                           再把喵撲拼音、喵撲注音加入目前使用者的中文鍵盤（優先用已經有的中文語言）。
#   Install.ps1 -Uninstall  從使用者的鍵盤清單拿掉，取消註冊，刪除 Program Files 裡的檔案。
param([switch]$Uninstall, [switch]$Elevated)

$ErrorActionPreference = 'Stop'
$clsid = '{961C0ADA-4DC6-488F-9563-4FC144EFAE27}'
# The profiles are registered under both Chinese languages (繁體 0404, 简体 0804; TextService.cpp kLanguages).
$pinyinProfile = '{8FDE097D-3513-486A-9AB5-2D1B2C67C359}'   # 喵撲拼音
$zhuyinProfile = '{1F5D9D62-F4AF-4AAF-82A6-A482D7EC3AE2}'   # 喵撲注音
$languageIds = @{ 'zh-Hant-TW' = '0404'; 'zh-Hans-CN' = '0804' }
$tips = foreach ($id in '0404', '0804') { "${id}:$clsid$pinyinProfile"; "${id}:$clsid$zhuyinProfile" }
$root = Join-Path $env:ProgramFiles 'Meowpuut'
$serverKey = "HKLM:\SOFTWARE\Classes\CLSID\$clsid\InprocServer32"
$regsvr32 = Join-Path $env:WINDIR 'System32\regsvr32.exe'
# The elevated part runs in a hidden window; what it has to say goes here and
# the user's window prints it.
$messages = Join-Path $env:TEMP 'meowpuut-setup.txt'

function Get-RegisteredDll {
    $key = Get-Item -LiteralPath $serverKey -ErrorAction SilentlyContinue
    if ($key) { $key.GetValue('') }
}

function Invoke-Regsvr32([string[]]$arguments) {
    $process = Start-Process -FilePath $regsvr32 -ArgumentList $arguments -Wait -PassThru -WindowStyle Hidden
    return $process.ExitCode
}

# Folders under Program Files\Meowpuut other than $keep. A DLL still loaded
# by a running program cannot be deleted; that folder stays until the next
# install or uninstall after those programs close.
function Remove-OldFolders([string]$keep) {
    if (-not (Test-Path -LiteralPath $root)) { return }
    foreach ($folder in Get-ChildItem -LiteralPath $root -Directory) {
        if ($folder.FullName -eq $keep) { continue }
        try { Remove-Item -LiteralPath $folder.FullName -Recurse -Force }
        catch { Add-Content -LiteralPath $messages -Encoding UTF8 "仍有程式在使用 $($folder.FullName)，關閉那些程式或重新開機後可以手動刪除。" }
    }
}

# --- the part that needs administrator rights ---------------------------------
if ($Elevated) {
  try {
    if ($Uninstall) {
        $dll = Get-RegisteredDll
        if ($dll -and (Test-Path -LiteralPath $dll)) {
            $code = Invoke-Regsvr32 @('/u', '/s', "`"$dll`"")
            if ($code -ne 0) { throw "取消註冊失敗（regsvr32 結束代碼 $code）。" }
        }
        Remove-OldFolders ''
        if ((Test-Path -LiteralPath $root) -and -not (Get-ChildItem -LiteralPath $root)) { Remove-Item -LiteralPath $root }
        exit 0
    }

    $source = $PSScriptRoot
    $dll = Join-Path $source 'MeowpuutTextService.dll'
    # Each build gets its own folder: programs that are running keep the DLL
    # they loaded, and the next install removes the folder once it is free.
    $hash = (Get-FileHash -LiteralPath $dll -Algorithm SHA256).Hash.Substring(0, 12).ToLowerInvariant()
    $target = Join-Path $root $hash
    # The same build installed again: its DLL may be in use, and the files are the same.
    if (-not (Test-Path -LiteralPath (Join-Path $target 'MeowpuutTextService.dll'))) {
        New-Item -ItemType Directory -Force $target | Out-Null
        foreach ($item in 'MeowpuutTextService.dll', 'Assets', 'lexicon', 'Licenses', 'THIRD_PARTY.md', 'BUILD-INFO.md') {
            Copy-Item -LiteralPath (Join-Path $source $item) -Destination $target -Recurse -Force
        }
    }
    $code = Invoke-Regsvr32 @('/s', "`"$(Join-Path $target 'MeowpuutTextService.dll')`"")
    if ($code -ne 0) { throw "註冊失敗（regsvr32 結束代碼 $code）。" }
    Remove-OldFolders $target
    exit 0
  } catch {
    Add-Content -LiteralPath $messages -Encoding UTF8 $_.Exception.Message
    exit 1
  }
}

# --- run as the user: elevate for the machine part, then set up this user ----
function Invoke-Elevated {
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
    $list = Get-WinUserLanguageList
    foreach ($language in $list) {
        foreach ($tip in $tips) { [void]$language.InputMethodTips.Remove($tip) }
    }
    Set-WinUserLanguageList $list -Force
    foreach ($tip in $tips) { [void][Meowpuut.Input]::InstallLayoutOrTip($tip, 1) }  # ILOT_UNINSTALL
    Invoke-Elevated
    Remove-Item -LiteralPath 'HKCU:\Software\Meowpuut' -Recurse -ErrorAction SilentlyContinue
    Write-Host '喵撲輸入法已移除。'
    exit 0
}

# The DLL must match Windows: an x64 DLL does not load in Arm64 programs, nor the other way round.
$bytes = [IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'MeowpuutTextService.dll'))
$machine = [BitConverter]::ToUInt16($bytes, [BitConverter]::ToInt32($bytes, 0x3c) + 4)
$packageArch = @{ 0x8664 = 'AMD64'; 0xAA64 = 'ARM64' }[[int]$machine]
$windowsArch = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment').PROCESSOR_ARCHITECTURE
if ($packageArch -ne $windowsArch) {
    $wanted = if ($windowsArch -eq 'ARM64') { 'windows-arm64' } else { 'windows-x64' }
    throw "這個套件是給 $packageArch 的 Windows，這台電腦是 $windowsArch。請下載檔名有 $wanted 的套件。"
}

# An install from before the 中日混打 switch existed (the service is registered, or its folder is there)
# keeps today's behaviour: mixed Japanese stays on and the script stays Traditional unless one was chosen.
# The 0.1.x versions wrote nothing for a person who never opened the menu, so the files tell it apart.
# Written once, before the new DLL can run, so a later change is never overwritten.
$hadEarlier = [bool](Get-RegisteredDll) -or (Test-Path -LiteralPath $root)

Invoke-Elevated

if ($hadEarlier) {
    $settings = 'HKCU:\Software\Meowpuut'
    if (-not (Test-Path -LiteralPath $settings)) { New-Item -Path $settings -Force | Out-Null }
    $have = (Get-Item -LiteralPath $settings).Property
    if ($have -notcontains 'MixedJapanese') { New-ItemProperty -Path $settings -Name 'MixedJapanese' -Value 1 -PropertyType DWord | Out-Null }
    if ($have -notcontains 'Simplified') { New-ItemProperty -Path $settings -Name 'Simplified' -Value 0 -PropertyType DWord | Out-Null }
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
