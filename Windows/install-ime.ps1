param([switch]$Register, [switch]$Signed, [switch]$Prebuilt, [switch]$VerifyOnly)

$ErrorActionPreference = 'Stop'
$build = Join-Path (Split-Path $PSScriptRoot) '.build/windows/ime'
$sourceDll = Join-Path $build 'MeowpuutTip.dll'
$sourceLexicon = Join-Path $build 'native-pinyin.tsv'
$sourceJapanese = Join-Path $build 'japanese.tsv'
$sourceEvidence = Join-Path $build 'language-evidence.sqlite'
$renderer = Join-Path (Split-Path $build) 'MeowpuutGlassPreview.exe'
if ($Prebuilt) {
    $build = Join-Path $PSScriptRoot 'payload'
    $sourceDll = Join-Path $build 'MeowpuutTip.dll'
    $sourceLexicon = Join-Path $build 'native-pinyin.tsv'
    $sourceJapanese = Join-Path $build 'japanese.tsv'
    $sourceEvidence = Join-Path $build 'language-evidence.sqlite'
    $renderer = Join-Path $build 'MeowpuutGlassPreview.exe'
}

if ($Register) {
    $packageFiles = $sourceDll, $sourceLexicon, $sourceJapanese, $sourceEvidence, $renderer
    $version = ($packageFiles | ForEach-Object { (Get-FileHash -LiteralPath $_).Hash.Substring(0, 12) }) -join '-'
    $install = Join-Path $env:ProgramFiles "Meowpuut/IME/$version"
    $dll = Join-Path $install 'MeowpuutTip.dll'
    New-Item -ItemType Directory -Force $install | Out-Null
    foreach ($source in $packageFiles) {
        $destination = Join-Path $install (Split-Path $source -Leaf)
        if (!(Test-Path -LiteralPath $destination) -or
            (Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $destination).Hash) {
            Copy-Item -LiteralPath $source -Destination $destination -Force
        }
    }
    $registration = Start-Process -FilePath "$env:WINDIR/System32/regsvr32.exe" -ArgumentList @('/s', ('"' + $dll + '"')) -PassThru -Wait -WindowStyle Hidden
    exit $registration.ExitCode
}

if (!(Test-Path -LiteralPath $sourceDll) -or !(Test-Path -LiteralPath $sourceLexicon) -or !(Test-Path -LiteralPath $sourceJapanese) -or !(Test-Path -LiteralPath $sourceEvidence)) {
    throw 'Build the Windows IME before installing it.'
}
if ($Signed) {
    foreach ($binary in $sourceDll, $renderer) {
        if (!(Test-Path -LiteralPath $binary) -or
            (Get-AuthenticodeSignature -LiteralPath $binary).Status -ne 'Valid') {
            throw "A valid Authenticode signature is required for $binary."
        }
    }
} elseif (!$Prebuilt) {
    & (Join-Path $PSScriptRoot 'build-preview.ps1') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Glass renderer build failed.' }
}
if (!(Test-Path -LiteralPath $renderer -PathType Leaf)) {
    throw 'The glass renderer is missing from the installation files.'
}
if ($VerifyOnly) {
    Write-Output 'All Meowpuut installation files are present.'
    return
}
$arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '" -Register'
if ($Prebuilt) { $arguments += ' -Prebuilt' }
$setup = Start-Process -FilePath "$env:WINDIR/System32/WindowsPowerShell/v1.0/powershell.exe" -ArgumentList $arguments -Verb RunAs -PassThru -Wait -WindowStyle Hidden
if ($setup.ExitCode -ne 0) {
    throw "TSF registration failed with exit code $($setup.ExitCode)."
}

# Remove the old per-user server override only after machine registration succeeds.
$legacy = 'HKCU:\Software\Classes\CLSID\{961C0ADA-4DC6-488F-9563-4FC144EFAE27}\InprocServer32'
if (Test-Path -LiteralPath $legacy) {
    Remove-Item -LiteralPath $legacy
}
$tip = '0404:{961C0ADA-4DC6-488F-9563-4FC144EFAE27}{8FDE097D-3513-486A-9AB5-2D1B2C67C359}'
$list = Get-WinUserLanguageList
$language = $list | Where-Object LanguageTag -eq 'zh-Hant-TW'
if (!$language) {
    $language = (New-WinUserLanguageList 'zh-Hant-TW')[0]
    $list.Add($language)
}
if ($language.InputMethodTips -notcontains $tip) {
    $language.InputMethodTips.Add($tip)
    Set-WinUserLanguageList $list -Force
}


# Publish the enabled profile to the active sign-in session.
if (-not ('Meowpuut.InputProfile' -as [type])) {
    Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
namespace Meowpuut
{
    public static class InputProfile
    {
        [DllImport("input.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
        [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool InstallLayoutOrTip(string profile, uint flags);

        [DllImport("msctf.dll", ExactSpelling = true)]
        [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
        public static extern int TF_InvalidAssemblyListCacheIfExist();
    }
}
"@
}
if ([Meowpuut.InputProfile]::TF_InvalidAssemblyListCacheIfExist() -lt 0) {
    Write-Warning 'Windows could not refresh the TSF cache. Sign out and back in if apps still load the previous version.'
}
if (-not [Meowpuut.InputProfile]::InstallLayoutOrTip("0x$tip", 0)) {
    throw 'Windows could not apply the Meowpuut profile to the current session.'
}

# Run the glass renderer outside packaged text editors, and start it again at sign-in.
$serverKey = 'HKLM:\Software\Classes\CLSID\{961C0ADA-4DC6-488F-9563-4FC144EFAE27}\InprocServer32'
$registeredDll = (Get-Item -LiteralPath $serverKey).GetValue('')
$glass = Join-Path (Split-Path $registeredDll) 'MeowpuutGlassPreview.exe'
if (-not (Get-Process MeowpuutGlassPreview -ErrorAction SilentlyContinue | Where-Object Path -eq $glass)) {
    try {
        $rendererProcess = Start-Process -FilePath $glass -ArgumentList '--ime','desktop' -WindowStyle Hidden -PassThru
        if ($rendererProcess.WaitForExit(500)) {
            throw "The renderer exited during startup with code $($rendererProcess.ExitCode)."
        }
    } catch {
        Write-Warning "The glass renderer could not start. The installed IME will use its native candidate window. $_"
        return
    }
}
$startup = Join-Path ([Environment]::GetFolderPath('Startup')) 'Meowpuut Candidate Renderer.lnk'
$previous = (New-Object -ComObject WScript.Shell).CreateShortcut($startup)
if ($previous.TargetPath -ne $glass) {
    $oldPath = $previous.TargetPath
    if ($oldPath) {
        Get-Process MeowpuutGlassPreview -ErrorAction SilentlyContinue |
            Where-Object Path -eq $oldPath |
            Stop-Process
    }
    $previous.TargetPath = $glass
    $previous.Arguments = '--ime desktop'
    $previous.WorkingDirectory = Split-Path $glass
    $previous.Save()
}
