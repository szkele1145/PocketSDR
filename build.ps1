param(
    [string]$Task = "assembleDebug",
    [switch]$KeepZ
)
# PocketSDR build script (UTF-8 BOM so PowerShell decodes Chinese paths correctly)
$ErrorActionPreference = "Continue"

try {
    $env:JAVA_HOME = "F:\vibe coding工程\jdk17"
    $env:ANDROID_HOME = "C:\asdk"
    $env:Path = "F:\vibe coding工程\jdk17\bin;C:\asdk\platform-tools;$env:Path"
    Push-Location "F:\vibe coding工程\PocketSDR"
    & "F:\vibe coding工程\gradle-8.11\bin\gradle.bat" $Task --no-daemon
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code