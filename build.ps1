param(
    [string]$Task = "assembleDebug"
)
# PocketSDR build helper (ASCII-only, no BOM).
#
# Why the paths live in toolchain.json instead of inline:
#   PowerShell 5.1 reads a BOM-less file as the system codepage (GBK here), so
#   any Chinese path written literally in this script gets mangled into
#   mojibake ("vibe coding宸ョ▼") and the path stops resolving. The original
#   build.ps1 was UTF-8 WITH a BOM to dodge that, but then this file itself
#   could not stay ASCII-only.
#   Cleanest fix: keep this script pure ASCII and read the paths from a UTF-8
#   JSON file, decoded explicitly with [IO.File]::ReadAllText(..., UTF8).
#
# Why the paths changed in the first place:
#   - JDK 17 under F:\vibe coding工程\jdk17 is gone; a full JDK 21 lives at
#     sfx\jdkfull\jdk-21.0.12.1+1 (Gradle 8.11 + AGP 8.5.2 accept JDK 21).
#   - The standalone gradle-8.11 distribution under F:\vibe coding工程 is gone;
#     the wrapper's cached copy still works, but gradlew tries to RE-DOWNLOAD it
#     from services.gradle.org, which this machine cannot reach. So we call the
#     cached distribution directly and never let a build hit the network for
#     gradle itself.
$ErrorActionPreference = "Continue"

$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$CfgPath = Join-Path $Here "toolchain.json"
$cfg = [IO.File]::ReadAllText($CfgPath, [Text.Encoding]::UTF8) | ConvertFrom-Json

$Repo        = $cfg.repo
$JavaHome    = $cfg.javaHome
$AndroidHome = $cfg.androidHome
$Gradle      = [Environment]::ExpandEnvironmentVariables($cfg.gradleDist)

try {
    if (-not (Test-Path $JavaHome)) { throw "JDK not found: $JavaHome" }
    if (-not (Test-Path $Gradle))    { throw "Gradle not found: $Gradle" }
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidHome
    $env:Path = "$JavaHome\bin;$AndroidHome\platform-tools;$env:Path"
    Push-Location $Repo
    & $Gradle $Task --console=plain
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
