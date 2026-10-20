# Publish tail: create release + upload APK for a commit that is ALREADY pushed.
#
# publish.ps1 does all eight steps in one run. When it dies between step 5
# (update main) and step 7 (create release) - e.g. a transient "Access is
# denied" while spawning git.exe - the commit is on the remote but the tag and
# the APK asset do not exist yet. Re-running publish.ps1 from the top would
# create a SECOND commit on top of the first one (same tree, different
# parent), so history would carry a pointless duplicate.
#
# This script finishes the job for an existing commit instead:
#   1. create the release (which creates the tag at the current main)
#   2. upload the APK as a release asset
#   3. verify the tag, the asset and the remote main
#
# ASCII-only, no BOM. The release body is read from a UTF-8 file.
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$VersionCode,
    [Parameter(Mandatory = $true)][string]$BodyFile,
    [Parameter(Mandatory = $true)][string]$ReleaseName,
    [string]$Repo = "",
    [string]$TokenFile = "$env:USERPROFILE\.dsh\gh.token"
)

$ErrorActionPreference = "Stop"
$Tmp = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "build\publish_tmp"
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$Tag = "v$Version"
$ApiBase = "https://api.github.com/repos/szkele1145/PocketSDR"

if (-not $Repo) { $Repo = Split-Path -Parent $MyInvocation.MyCommand.Path }
$Token = ([IO.File]::ReadAllText($TokenFile)).Trim()
$H = @("-H", "Authorization: token $Token", "-H", "Accept: application/vnd.github+json")

function Get-Json {
    param([string]$Url)
    $out = Join-Path $Tmp "gh_out.json"
    $raw = ""
    for ($try = 1; $try -le 4; $try++) {
        if (Test-Path $out) { Remove-Item $out -Force }
        & curl.exe -s -k --retry 2 --retry-delay 2 --max-time 60 `
            -H "Authorization: token $Token" -H "Accept: application/vnd.github+json" $Url -o $out
        if (Test-Path $out) { $raw = [IO.File]::ReadAllText($out, [Text.Encoding]::UTF8) }
        if ($raw.Length -ge 10) { break }
        Write-Host "    empty GitHub response (try $try), retrying"
        Start-Sleep -Seconds 3
    }
    if ($raw.Length -lt 10) { throw "GitHub request returned nothing usable: $Url" }
    return ($raw | ConvertFrom-Json)
}

# The release may already exist (partial previous run). Reuse it instead of
# failing on 422 "already_exists".
$existing = Get-Json "$ApiBase/releases/tags/$Tag"
$releaseId = 0
if ($existing.tag_name -eq $Tag) {
    $releaseId = [int]$existing.id
    Write-Host "==> Release $Tag already exists (id $releaseId), reusing it"
} else {
    Write-Host "==> Create release $Tag"
    $body = [IO.File]::ReadAllText($BodyFile, [Text.Encoding]::UTF8)
    [IO.File]::WriteAllText("$Tmp\relbody.json", (ConvertTo-Json -InputObject @{
        tag_name = $Tag; target_commitish = "main"; name = $ReleaseName
        body = $body; draft = $false; prerelease = $false
    } -Depth 4), $Utf8NoBom)
    & curl.exe -s -k --retry 3 --retry-delay 3 --max-time 60 `
        -X POST "$ApiBase/releases" `
        -H "Authorization: token $Token" -H "Accept: application/vnd.github+json" `
        -H "Content-Type: application/json" `
        --data-binary "@$Tmp\relbody.json" -o "$Tmp\relresp.json"
    $resp = [IO.File]::ReadAllText("$Tmp\relresp.json", [Text.Encoding]::UTF8)
    $releaseId = [int]([regex]::Match($resp, '"id":\s*(\d+)').Groups[1].Value)
    if (-not $releaseId) { throw "Release creation failed: $resp" }
    Write-Host "    release $releaseId"
}

Write-Host "==> Upload APK"
$apkSrc = Join-Path $Repo "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkSrc)) { throw "APK not found: $apkSrc (run assembleDebug first)" }
$apkTmp = Join-Path $Tmp "publish_$VersionCode.apk"
Copy-Item $apkSrc $apkTmp -Force
$assetName = "PocketSDR-$Tag-debug.apk"
$uploaded = $false
for ($i = 1; $i -le 3; $i++) {
    $resp = & curl.exe -s -k --retry 3 --retry-delay 4 -X POST `
        "https://uploads.github.com/repos/szkele1145/PocketSDR/releases/$releaseId/assets?name=$assetName" `
        -H "Authorization: token $Token" -H "Content-Type: application/octet-stream" --data-binary "@$apkTmp"
    $respText = ($resp | Out-String)
    if ($respText -match '"code":"already_exists"') {
        $uploaded = $true
        Write-Host "    APK already present (try $i, GitHub said already_exists)"
        break
    }
    if ($respText -match '"state":"uploaded"' -or $respText -match '"browser_download_url"') {
        $uploaded = $true
        $sz = [regex]::Match($respText, '"size":(\d+)').Groups[1].Value
        Write-Host "    APK uploaded ($sz bytes, try $i)"
        break
    }
    Write-Host "    upload try $i failed: $($respText.Substring(0, [Math]::Min(160, $respText.Length)))"
    Start-Sleep -Seconds 4
}
if (-not $uploaded) { throw "APK upload failed" }

Write-Host "==> Final verification"
$rel = Get-Json "$ApiBase/releases/tags/$Tag"
if ($rel.tag_name -ne $Tag) { throw "release tag mismatch: $($rel.tag_name)" }
$asset = @($rel.assets) | Where-Object { $_.name -eq $assetName } | Select-Object -First 1
if (-not $asset) { throw "release asset $assetName not found" }
if ($asset.state -ne "uploaded") { throw "asset state is $($asset.state), expected uploaded" }
$branch = Get-Json "$ApiBase/branches/main"
Write-Host "    $Tag -> $($asset.browser_download_url)"
Write-Host "    asset $($asset.name) $($asset.size) bytes state=$($asset.state)"
Write-Host "    remote main $($branch.commit.sha)"
Write-Host "PUBLISHED: $Tag"
