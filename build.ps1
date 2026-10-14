param(
    [string]$Task = "assembleDebug",
    [switch]$KeepZ
)
# PocketSDR build script (ASCII-safe paths via Z:)
$ErrorActionPreference = "Continue"

cmd /c "subst Z: /d" | Out-Null
cmd /c "subst Z: `"F:\vibe coding工程`"" | Out-Null
if (-not (Test-Path "Z:\PocketSDR")) { Write-Host "[build] subst FAILED"; exit 1 }

try {
    $env:JAVA_HOME = "Z:\jdk17"
    $env:ANDROID_HOME = "C:\asdk"
    $env:Path = "Z:\jdk17\bin;C:\asdk\platform-tools;$env:Path"
    Push-Location "Z:\PocketSDR"
    & "Z:\gradle-8.11\bin\gradle.bat" $Task --no-daemon
    $code = $LASTEXITCODE
} finally {
    Pop-Location
    if (-not $KeepZ) { cmd /c "subst Z: /d" | Out-Null }
}
exit $code