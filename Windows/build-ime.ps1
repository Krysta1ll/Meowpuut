param([Parameter(Mandatory)][string]$Compiler)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$output = Join-Path $root '.build/windows/ime'
New-Item -ItemType Directory -Force $output | Out-Null
& python (Join-Path $PSScriptRoot 'compile-lexicon.py') $output
if ($LASTEXITCODE -ne 0) { throw 'Meowpuut lexicon build failed.' }
Copy-Item -LiteralPath (Join-Path $output 'native-japanese.tsv') -Destination (Join-Path $output 'japanese.tsv') -Force
& $Compiler -std=c++17 -O2 -shared -static -Wno-dll-attribute-on-redeclaration -o (Join-Path $output 'MeowpuutTip.dll') (Join-Path $PSScriptRoot 'TextService.cpp') (Join-Path $root 'Core/MeowpuutCore.cpp') -lole32 -loleaut32 -luuid -ladvapi32 -lgdi32 -luser32
if ($LASTEXITCODE -ne 0) { throw 'TSF DLL build failed.' }
$deliveryTest = Join-Path $output 'PendingDeliveryTests.exe'
& $Compiler -std=c++17 -O2 -static -o $deliveryTest (Join-Path $PSScriptRoot 'tests/PendingDeliveryTests.cpp')
if ($LASTEXITCODE -ne 0) { throw 'Pending delivery test build failed.' }
& $deliveryTest
if ($LASTEXITCODE -ne 0) { throw 'Pending delivery test failed.' }
