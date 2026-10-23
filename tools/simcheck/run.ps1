# PocketSDR desktop self-check (simcheck) - ASCII only, no BOM.
# Compiles the main-project Kotlin DSP sources on a plain JVM (no Gradle, no Android)
# and runs the same SelfCheck suite the app shows in the "DSP self-check" dialog,
# plus the simulation gain model, the RDS spec cross-check, the RDS robustness
# scan and the RDS end-to-end check.
$ErrorActionPreference = "Stop"
$Repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$cfg = Get-Content (Join-Path $Repo "toolchain.json") -Raw -Encoding UTF8 | ConvertFrom-Json

$out = Join-Path $env:TEMP ("pocketsdr-simcheck-" + [Guid]::NewGuid().ToString("N").Substring(0,8))
New-Item -ItemType Directory -Path $out -Force | Out-Null

$m2 = "C:\gradle-home\caches\modules-2\files-2.1"
function FindJar([string]$root, [string]$name) {
    $hit = Get-ChildItem -Path $root -Recurse -Filter $name -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit -eq $null) { throw "jar not found: $name" }
    return $hit.FullName
}

$kotlin = FindJar $m2 "kotlin-compiler-embeddable-2.0.20.jar"
$stdlibJar = FindJar $m2 "kotlin-stdlib-2.0.20.jar"
$cp = @(
    $kotlin,
    (FindJar $m2 "kotlinx-coroutines-core-jvm-1.7.3.jar"),
    (FindJar $m2 "trove4j-1.0.20200330.jar"),
    (FindJar $m2 "annotations-13.0.jar"),
    (FindJar $m2 "kotlin-daemon-embeddable-2.0.20.jar"),
    (FindJar $m2 "kotlin-reflect-2.0.20.jar"),
    (FindJar $m2 "kotlin-script-runtime-2.0.20.jar"),
    $stdlibJar
) -join ";"

$srcs = @(
    (Join-Path $Repo "tools\simcheck\Main.kt"),
    (Join-Path $Repo "tools\simcheck\RdsCheck.kt"),
    (Join-Path $Repo "tools\simcheck\RdsRobust.kt"),
    (Join-Path $Repo "tools\simcheck\RdsSpecCheck.kt"),
    (Join-Path $Repo "tools\simcheck\LpBandwidth.kt"),
    (Join-Path $Repo "tools\simcheck\RdsSwitch.kt"),
    (Join-Path $Repo "tools\simcheck\Fc0013PllCheck.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\source\rtl\Fc0013Pll.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\dsp\SelfCheck.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\dsp\Biquad.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\dsp\Fft.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\dsp\HilbertFir.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\dsp\ComplexDecimator.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\dsp\FirFilter.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\dsp\TrigLut.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\dsp\Demodulators.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\dsp\AudioAgc.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\dsp\RdsDecoder.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\source\IQSource.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\source\RdsEncoder.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\source\SimulatedSource.kt")
)

Write-Host "=== compiling ==="
& "$($cfg.javaHome)\bin\java.exe" -cp $cp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -cp $stdlibJar -d $out -nowarn -jvm-target 17 @srcs
if ($LASTEXITCODE -ne 0) { Write-Host "COMPILE FAILED"; exit 1 }

$stdlib = $stdlibJar
$fail = 0

Write-Host "=== DSP self-check + simulation gain model ==="
& "$($cfg.javaHome)\bin\java.exe" -cp "$out;$stdlib" MainKt
if ($LASTEXITCODE -ne 0) { $fail = 1 }

Write-Host "=== FC0013 PLL register cross-check ==="
& "$($cfg.javaHome)\bin\java.exe" -cp "$out;$stdlib" Fc0013PllCheckKt
if ($LASTEXITCODE -ne 0) { $fail = 1 }

Write-Host "=== RDS spec cross-check ==="
& "$($cfg.javaHome)\bin\java.exe" -cp "$out;$stdlib" RdsSpecCheckKt
if ($LASTEXITCODE -ne 0) { $fail = 1 }

Write-Host "=== RDS robustness scan ==="
& "$($cfg.javaHome)\bin\java.exe" -cp "$out;$stdlib" RdsRobustKt
if ($LASTEXITCODE -ne 0) { $fail = 1 }

Write-Host "=== RDS station-switch contamination check ==="
& "$($cfg.javaHome)\bin\java.exe" -cp "$out;$stdlib" RdsSwitchKt
if ($LASTEXITCODE -ne 0) { $fail = 1 }

Write-Host "=== RDS data-lowpass bandwidth experiment (info only) ==="
& "$($cfg.javaHome)\bin\java.exe" -cp "$out;$stdlib" LpBandwidthKt

Write-Host "=== RDS end-to-end check ==="
& "$($cfg.javaHome)\bin\java.exe" -cp "$out;$stdlib" RdsCheckKt
if ($LASTEXITCODE -ne 0) { $fail = 1 }

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
if ($fail -ne 0) { Write-Host "== SIMCHECK FAILED =="; exit 1 }
Write-Host "== ALL PASS =="
exit 0