param([Parameter(Mandatory)][string]$Compiler)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$build = Join-Path $root '.build/windows'
$output = Join-Path $build 'dist'
New-Item -ItemType Directory -Force $output | Out-Null
$output = (Resolve-Path -LiteralPath $output).Path
$stage = Join-Path $output ([guid]::NewGuid().ToString('N'))
$payload = Join-Path $stage 'payload'
$licenses = Join-Path $stage 'Licenses'
New-Item -ItemType Directory -Force $payload,$licenses | Out-Null

try {
    foreach ($name in 'MeowpuutTip.dll','native-pinyin.tsv','japanese.tsv','language-evidence.sqlite') {
        Copy-Item -LiteralPath (Join-Path $build "ime/$name") -Destination $payload
    }
    Copy-Item -LiteralPath (Join-Path $build 'MeowpuutGlassPreview.exe') -Destination $payload
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Install.cmd') -Destination $stage
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install-ime.ps1') -Destination (Join-Path $stage 'Install.ps1')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md'),(Join-Path $root 'LICENSE'),(Join-Path $root 'THIRD_PARTY.md') -Destination $stage
    Copy-Item -LiteralPath (Join-Path $root 'DataSources/McBopomofo/LICENSE.txt') -Destination (Join-Path $licenses 'McBopomofo-MIT.txt')
    Copy-Item -LiteralPath (Join-Path $root 'DataSources/PinyinPro/LICENSE') -Destination (Join-Path $licenses 'PinyinPro-MIT.txt')
    Copy-Item -LiteralPath (Join-Path $root 'DataSources/UniDic/LICENSE.txt') -Destination (Join-Path $licenses 'UniDic-derived-BSD.txt')
    Copy-Item -LiteralPath (Join-Path $root 'DataSources/UniDic/UNIDIC-BSD.txt') -Destination (Join-Path $licenses 'UniDic-BSD.txt')
    Copy-Item -LiteralPath (Join-Path $root 'DataSources/UniDic/UNIDIC-COPYING.txt') -Destination (Join-Path $licenses 'UniDic-COPYING.txt')
    $toolchain = Split-Path (Split-Path (Resolve-Path -LiteralPath $Compiler).Path)
    Copy-Item -LiteralPath (Join-Path $toolchain 'LICENSE.TXT') -Destination (Join-Path $licenses 'LLVM-MinGW.txt')
    $mingw = Join-Path $toolchain 'x86_64-w64-mingw32/share/mingw32'
    foreach ($name in 'COPYING.MinGW-w64.txt','COPYING.MinGW-w64-runtime.txt','COPYING.winpthreads.txt') {
        Copy-Item -LiteralPath (Join-Path $mingw $name) -Destination $licenses
    }
    & (Join-Path $stage 'Install.ps1') -Prebuilt -VerifyOnly | Out-Null
    $zip = Join-Path $output 'Meowpuut-Windows-x64-0.1.0-alpha.zip'
    Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -Force
    $sha = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS.txt') -Encoding ascii -Value "$sha  $(Split-Path $zip -Leaf)"
    Write-Output $zip
} finally {
    $resolvedStage = (Resolve-Path -LiteralPath $stage).Path
    if ([IO.Path]::GetDirectoryName($resolvedStage) -eq $output) {
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
}
