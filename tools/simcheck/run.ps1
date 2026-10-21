# Runs the JVM-side simulation gain-model check (ASCII-only, no BOM).
#
# Why this exists:
#   The app's unit-test task cannot see the Kotlin test class on this
#   machine (ClassNotFoundException: the compiled test class never makes it
#   onto the unit-test classpath). Rather than fight the AGP/Kotlin
#   plumbing, this compiles the three PURE-Kotlin sources the model needs
#   (SimulatedSource, Fft, RdsEncoder - none of them touch android.*) plus
#   a small driver, and runs it on the desktop JVM.
#
#   That gives a real executable check of the simulation gain model:
#   floor / peak / SNR per gain step, exactly as SdrEngine computes them.
#
# Usage: powershell -File tools\simcheck\run.ps1
param()
$ErrorActionPreference = "Stop"

$Repo = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$CfgPath = Join-Path $Repo "toolchain.json"
$cfg = [IO.File]::ReadAllText($CfgPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
$env:JAVA_HOME = $cfg.javaHome

$cache = "C:\gradle-home\caches\modules-2\files-2.1\org.jetbrains.kotlin"
$compiler = (Get-ChildItem -Path $cache -Recurse -Filter "kotlin-compiler-embeddable-2.0.20.jar" |
    Select-Object -First 1).FullName
$stdlib = (Get-ChildItem -Path $cache -Recurse -Filter "kotlin-stdlib-2.0.20.jar" |
    Select-Object -First 1).FullName
if (-not $compiler) { throw "kotlin-compiler-embeddable not found in $cache" }
if (-not $stdlib)   { throw "kotlin-stdlib not found in $cache" }

# kotlin-compiler-embeddable alone is not runnable: the CLI also needs these
# (coroutines for the compiler's own threading, trove/annotations for the
# embedded IntelliJ core, reflect + script-runtime for the CLI plumbing).
# Gradle's cache already has every one of them, so just point at them.
$modRoot = "C:\gradle-home\caches\modules-2\files-2.1"
$extra = @(
    "org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.7.3",
    "org.jetbrains.intellij.deps\trove4j\1.0.20200330",
    "org.jetbrains\annotations\13.0",
    "org.jetbrains.kotlin\kotlin-daemon-embeddable\2.0.20",
    "org.jetbrains.kotlin\kotlin-reflect\2.0.20",
    "org.jetbrains.kotlin\kotlin-script-runtime\2.0.20"
) | ForEach-Object {
    $p = Join-Path $modRoot $_
    (Get-ChildItem -Path $p -Recurse -Filter "*.jar" | Select-Object -First 1).FullName
}
if ($extra -contains $null) { throw "a compiler dependency jar is missing under $modRoot" }

$out = Join-Path $Repo "build\simcheck"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$srcs = @(
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\source\SimulatedSource.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\source\RdsEncoder.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\source\IQSource.kt"),
    (Join-Path $Repo "app\src\main\java\com\pocketsdr\app\dsp\Fft.kt"),
    (Join-Path $Repo "tools\simcheck\Main.kt")
)

$cp = (@($compiler, $stdlib) + $extra) -join ";"
$args = @("-cp", $cp, "-d", $out, "-nowarn", "-jvm-target", "17", "-no-stdlib") + $srcs

Write-Host "compiling..."
& "$($cfg.javaHome)\bin\java.exe" -cp $cp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @args
if ($LASTEXITCODE -ne 0) { throw "kotlinc failed ($LASTEXITCODE)" }

Write-Host "running..."
& "$($cfg.javaHome)\bin\java.exe" -cp "$out;$stdlib" MainKt
exit $LASTEXITCODE
