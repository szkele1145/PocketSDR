# PocketSDR pre-release verification.
#
# ASCII-only on purpose (same reason as publish.ps1: PowerShell 5.1 reads
# BOM-less files as the system codepage, which garbles Chinese).
#
# Two gates:
#   1. DSP self-check + simulation gain model (pure JVM, tools/simcheck)
#   2. APK version matches the requested version
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify.ps1 -Version 2.76.0
param(
    [Parameter(Mandatory = $true)][string]$Version
)

$ErrorActionPreference = "Stop"
$Repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

Write-Host "==> 1/2 DSP self-check + gain model"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Repo "tools\simcheck\run.ps1")
if ($LASTEXITCODE -ne 0) {
    Write-Host "VERIFY FAILED: DSP checks did not pass (exit $LASTEXITCODE)"
    exit 1
}

Write-Host ""
Write-Host "==> 2/2 APK version check"
$apk = Join-Path $Repo "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) {
    Write-Host "VERIFY FAILED: APK not found at $apk (build first)"
    exit 1
}
# The SDK path contains non-ASCII characters, so it must come from
# toolchain.json (read as UTF-8) instead of being typed into this
# ASCII-only script - a literal here would be mangled by the
# GBK code page this script is read with.
$cfg = [IO.File]::ReadAllText((Join-Path $Repo "toolchain.json"), [Text.Encoding]::UTF8) | ConvertFrom-Json
$aapt = Join-Path $cfg.androidHome "build-tools\35.0.0\aapt.exe"
if (-not (Test-Path $aapt)) {
    Write-Host "VERIFY FAILED: aapt not found at $aapt"
    exit 1
}
# aapt.exe is a native tool: it reads the APK path as ANSI, so a path with
# non-ASCII characters (the repo lives under one) makes it fail with
# "no AndroidManifest.xml found". Copy to an ASCII path first.
$tmpDir = "F:\ptmp"
if (-not (Test-Path $tmpDir)) { New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null }
$tmpApk = Join-Path $tmpDir ("verify_" + $Version + ".apk")
Copy-Item $apk $tmpApk -Force
$badging = (& $aapt dump badging $tmpApk) | Out-String
if ($badging -notmatch [regex]::Escape("versionName='$Version'")) {
    Write-Host "VERIFY FAILED: APK versionName is not $Version"
    Write-Host $badging
    exit 1
}
Write-Host "OK: APK versionName=$Version"

Write-Host ""
Write-Host "VERIFY PASSED"
exit 0
