$ErrorActionPreference = 'Stop'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework/v4.0.30319/csc.exe'
$output = Join-Path $PSScriptRoot '../.build/windows'
New-Item -ItemType Directory -Force $output | Out-Null
$exe = Join-Path $output 'MeowpuutGlassPreview.exe'
$wpf = Join-Path (Split-Path $compiler) 'WPF'
$references = 'WindowsBase', 'PresentationCore', 'PresentationFramework', 'UIAutomationTypes' |
    ForEach-Object { '/reference:' + (Join-Path $wpf "$_.dll") }
$layout = Join-Path $PSScriptRoot 'GlassPreview.xaml'
& $compiler /nologo /target:winexe /out:$exe $references /reference:System.Xaml.dll /reference:System.Drawing.dll "/resource:$layout,GlassPreview.xaml" (Join-Path $PSScriptRoot 'GlassPreview.cs') (Join-Path $PSScriptRoot 'GlassBackdrop.cs')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output (Resolve-Path $exe).Path
