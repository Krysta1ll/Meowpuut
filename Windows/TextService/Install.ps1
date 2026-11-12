# 安裝或移除喵撲輸入法。由 Install.cmd、Uninstall.cmd 呼叫。
#
#   Install.ps1             安裝：複製到 Program Files 並註冊（會要求系統管理員權限），
#                           再把喵撲拼音、喵撲注音加入目前使用者的繁體中文（台灣）鍵盤。
#   Install.ps1 -Uninstall  從使用者的鍵盤清單拿掉，取消註冊，刪除 Program Files 裡的檔案。
param([switch]$Uninstall, [switch]$Elevated)

$ErrorActionPreference = 'Stop'
$clsid = '{961C0ADA-4DC6-488F-9563-4FC144EFAE27}'
$tips = @(
    "0404:$clsid{8FDE097D-3513-486A-9AB5-2D1B2C67C359}",  # 喵撲拼音
    "0404:$clsid{1F5D9D62-F4AF-4AAF-82A6-A482D7EC3AE2}"   # 喵撲注音
)
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

Invoke-Elevated

# 0.1.x started a separate glass renderer at sign-in; it is gone now.
$startup = Join-Path ([Environment]::GetFolderPath('Startup')) 'Meowpuut Candidate Renderer.lnk'
if (Test-Path -LiteralPath $startup) { Remove-Item -LiteralPath $startup }
Get-Process MeowpuutGlassPreview -ErrorAction SilentlyContinue | Stop-Process -ErrorAction SilentlyContinue

$list = Get-WinUserLanguageList
$language = $list | Where-Object LanguageTag -eq 'zh-Hant-TW' | Select-Object -First 1
if (-not $language) {
    $language = (New-WinUserLanguageList 'zh-Hant-TW')[0]
    $list.Add($language)
}
foreach ($tip in $tips) {
    if ($language.InputMethodTips -notcontains $tip) { $language.InputMethodTips.Add($tip) }
}
Set-WinUserLanguageList $list -Force
foreach ($tip in $tips) {
    if (-not [Meowpuut.Input]::InstallLayoutOrTip($tip, 0)) { Write-Warning "Windows 沒有啟用 $tip，登出再登入後再試一次。" }
}
Write-Host '喵撲拼音和喵撲注音已加入「中文（台灣）」。按 Windows 鍵 + 空白鍵切換。已經開著的程式要重新開啟才會載入新版。'
