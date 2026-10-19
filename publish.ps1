param(
    [Parameter(Mandatory = $true)][string]$Version,      # 例：2.35.0
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [Parameter(Mandatory = $true)][string]$Message,      # 提交信息
    [string[]]$Files,                                      # 要提交的文件（相对仓库根）
    [Parameter(Mandatory = $true)][string]$ReleaseName,
    [Parameter(Mandatory = $true)][string]$ReleaseBody,
    [string]$Tag = ""                                      # 留空则用 v<Version>
)
# PocketSDR 发布脚本：一次完成「提交 → 建树 → 提交 → 更新 main → 建 release → 传 APK → 校验」
#
# 为什么用脚本而不是手工敲 curl：
#   1. 建树必须带 base_tree，否则新提交只含本次改动的几个文件（v2.34.0 踩过这个坑）。
#      脚本对 base_tree 做非空断言，取不到就报错退出，绝不推半截树。
#   2. 更新 ref、传 asset 都要重试——GitHub 偶尔返回空响应。
#   3. 推完必须校验远端树与本地一致，别让坏提交留在 main 上。
$ErrorActionPreference = "Stop"

$Repo      = "F:\vibe coding工程\PocketSDR"
$ApiBase   = "https://api.github.com/repos/szkele1145/PocketSDR"
$TokenFile = "C:\Users\一只屑\.dsh\gh.token"
$Aapt      = "C:\asdk\build-tools\35.0.0\aapt.exe"
$Tmp       = "C:\Temp"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

if (-not $Tag) { $Tag = "v$Version" }
$Token = (Get-Content $TokenFile -Raw).Trim()
$H = "Authorization: token $Token"

function Invoke-Gh {
    param([string]$Url, [string]$Method = "GET", [string]$BodyFile = "", [string]$OutFile = "")
    $args = @("-s", "-k", "-X", $Method, $Url, "-H", $H, "-H", "Accept: application/vnd.github+json")
    if ($BodyFile) { $args += @("-H", "Content-Type: application/json", "--data-binary", "@$BodyFile") }
    if ($OutFile)  { $args += @("-o", $OutFile) }
    & curl.exe @args
}

function Get-Json {
    param([string]$Url)
    $out = Join-Path $Tmp "gh_out.json"
    & curl.exe -s -k --retry 3 --retry-delay 3 -H $H -H "Accept: application/vnd.github+json" $Url -o $out
    if (-not (Test-Path $out)) { throw "GitHub 请求无响应：$Url" }
    $raw = [IO.File]::ReadAllText($out, [Text.Encoding]::UTF8)
    if ($raw.Length -lt 10) { throw "GitHub 返回空响应：$Url" }
    return ($raw | ConvertFrom-Json)
}

Write-Host "==> 1/8 确认远端 main 与本地基线"
$remote = Get-Json "$ApiBase/branches/main"
$baseCommit = $remote.commit.sha
$baseTree = $remote.commit.commit.tree.sha
if (-not $baseCommit -or -not $baseTree) { throw "取不到远端基线（commit=$baseCommit tree=$baseTree），中止" }
Write-Host "    基线提交 $baseCommit  基线树 $baseTree"

Write-Host "==> 2/8 上传 blob"
$blobMap = @{}
foreach ($f in $Files) {
    $full = Join-Path $Repo ($f -replace '/', '\')
    if (-not (Test-Path $full)) { throw "文件不存在：$f" }
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($full))
    [IO.File]::WriteAllText("$Tmp\blob.json", ('{"content":"' + $b64 + '","encoding":"base64"}'), $Utf8NoBom)
    $resp = Invoke-Gh "$ApiBase/git/blobs" -Method POST -BodyFile "$Tmp\blob.json"
    $sha = [regex]::Match($resp, '"sha":\s*"([0-9a-f]{40})"').Groups[1].Value
    if (-not $sha) { throw "blob 上传失败：$f`n$resp" }
    $blobMap[$f] = $sha
    Write-Host "    $f -> $sha"
}

Write-Host "==> 3/8 建树（base_tree 必须非空）"
$treeEntries = @()
foreach ($f in $Files) {
    $treeEntries += @{ path = $f; mode = "100644"; type = "blob"; sha = $blobMap[$f] }
}
[IO.File]::WriteAllText("$Tmp\tree.json",
    (ConvertTo-Json -InputObject @{ base_tree = $baseTree; tree = $treeEntries } -Depth 6), $Utf8NoBom)
$resp = Invoke-Gh "$ApiBase/git/trees" -Method POST -BodyFile "$Tmp\tree.json"
$treeSha = [regex]::Match($resp, '"sha":\s*"([0-9a-f]{40})"').Groups[1].Value
if (-not $treeSha) { throw "建树失败：$resp" }

# 断言：新树必须包含基线的全部文件（递归比对路径数 + 抽查 blob sha）
$newTree = Get-Json "$ApiBase/git/trees/$treeSha`?recursive=1"
$baseTreeFull = Get-Json "$ApiBase/git/trees/$baseTree`?recursive=1"
$newPaths = @($newTree.tree | Where-Object { $_.type -eq "blob" } | ForEach-Object { $_.path })
$basePaths = @($baseTreeFull.tree | Where-Object { $_.type -eq "blob" } | ForEach-Object { $_.path })
if ($newPaths.Count -lt $basePaths.Count) {
    throw "新树文件数($($newPaths.Count))少于基线($($basePaths.Count))——base_tree 没生效，中止"
}
$missing = $basePaths | Where-Object { $newPaths -notcontains $_ }
if ($missing.Count -gt 0) { throw "新树丢失文件：$($missing -join ', ')，中止" }
Write-Host "    新树 $treeSha  文件数 $($newPaths.Count)（基线 $($basePaths.Count)，无缺失）"

Write-Host "==> 4/8 提交"
[IO.File]::WriteAllText("$Tmp\commit.json",
    (ConvertTo-Json -InputObject @{ message = $Message; tree = $treeSha; parents = @($baseCommit) } -Depth 4), $Utf8NoBom)
$resp = Invoke-Gh "$ApiBase/git/commits" -Method POST -BodyFile "$Tmp\commit.json"
$commitSha = [regex]::Match($resp, '"sha":\s*"([0-9a-f]{40})"').Groups[1].Value
if (-not $commitSha) { throw "提交失败：$resp" }
Write-Host "    提交 $commitSha"

Write-Host "==> 5/8 更新 main"
[IO.File]::WriteAllText("$Tmp\ref.json",
    (ConvertTo-Json -InputObject @{ sha = $commitSha; force = $false } -Depth 3), $Utf8NoBom)
$ok = $false
for ($i = 1; $i -le 3; $i++) {
    $resp = Invoke-Gh "$ApiBase/git/refs/heads/main" -Method PATCH -BodyFile "$Tmp\ref.json"
    if ([regex]::Match($resp, '"sha":\s*"([0-9a-f]{40})"').Groups[1].Value -eq $commitSha) {
        $ok = $true; Write-Host "    更新成功（第 $i 次）"; break
    }
    Write-Host "    第 $i 次失败，重试"; Start-Sleep -Seconds 4
}
if (-not $ok) { throw "更新 main 失败" }

Write-Host "==> 6/8 校验远端树与本地一致"
$finalTree = Get-Json "$ApiBase/commits/main"
$finalTreeSha = $finalTree.commit.tree.sha
$finalFull = Get-Json "$ApiBase/git/trees/$finalTreeSha`?recursive=1"
$remoteMap = @{}
foreach ($e in $finalFull.tree) { if ($e.type -eq "blob") { $remoteMap[$e.path] = $e.sha } }
Push-Location $Repo
try {
    $tracked = git ls-files
    $bad = @()
    foreach ($f in $tracked) {
        if (-not $remoteMap.ContainsKey($f)) { $bad += "远端缺失 $f"; continue }
        $p = Join-Path $Repo ($f -replace '/', '\')
        $localHash = (git hash-object --no-filters $p).Trim()
        $remoteHash = $remoteMap[$f]
        if ($localHash -ne $remoteHash) {
            # 行尾差异（autocrlf）不算错：比对归一化后的内容
            $blob = Get-Json "$ApiBase/git/blobs/$remoteHash"
            $remoteText = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(($blob.content -replace '\s', '')))
            $localText = [IO.File]::ReadAllText($p)
            if (($remoteText -replace "`r`n", "`n") -ne ($localText -replace "`r`n", "`n")) {
                $bad += "内容不一致 $f"
            }
        }
    }
} finally { Pop-Location }
if ($bad.Count -gt 0) { throw "远端与本地不一致：`n$($bad -join "`n")" }
Write-Host "    校验通过：$(($tracked | Measure-Object).Count) 个文件内容一致"

Write-Host "==> 7/8 建 release 并传 APK"
[IO.File]::WriteAllText("$Tmp\relbody.json", (ConvertTo-Json -InputObject @{
    tag_name = $Tag; target_commitish = "main"; name = $ReleaseName
    body = $ReleaseBody; draft = $false; prerelease = $false
} -Depth 4), $Utf8NoBom)
$resp = Invoke-Gh "$ApiBase/releases" -Method POST -BodyFile "$Tmp\relbody.json"
$releaseId = [regex]::Match($resp, '"id":\s*(\d+)').Groups[1].Value
if (-not $releaseId) { throw "建 release 失败：$resp" }
Write-Host "    release $releaseId"

$apkSrc = Join-Path $Repo "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkSrc)) { throw "找不到 APK：$apkSrc（先跑 assembleDebug）" }
$apkTmp = Join-Path $Tmp "publish_$VersionCode.apk"
Copy-Item $apkSrc $apkTmp -Force
$assetName = "PocketSDR-$Tag-debug.apk"
$uploaded = $false
for ($i = 1; $i -le 3; $i++) {
    $resp = & curl.exe -s -k --retry 3 --retry-delay 4 -X POST `
        "https://uploads.github.com/repos/szkele1145/PocketSDR/releases/$releaseId/assets?name=$assetName" `
        -H "Authorization: token $Token" -H "Content-Type: application/octet-stream" --data-binary "@$apkTmp"
    if ($resp -and [regex]::Match($resp, '"state":\s*"uploaded"').Groups[1].Value -eq "uploaded") {
        $uploaded = $true
        Write-Host "    APK 已上传（$([regex]::Match($resp, '"size":\s*(\d+)').Groups[1].Value) 字节，第 $i 次）"
        break
    }
    Write-Host "    第 $i 次上传失败，重试"; Start-Sleep -Seconds 4
}
if (-not $uploaded) { throw "APK 上传失败" }

Write-Host "==> 8/8 最终校验"
$rel = Get-Json "$ApiBase/releases/tags/$Tag"
if ($rel.tag_name -ne $Tag) { throw "release tag 不对：$($rel.tag_name)" }
if (-not $rel.assets -or $rel.assets[0].name -ne $assetName) { throw "release 资产不对" }
Write-Host "    $Tag -> $($rel.assets[0].browser_download_url)"
Write-Host "    提交 $commitSha"

Push-Location $Repo
try {
    for ($i = 1; $i -le 3; $i++) {
        git fetch origin main 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 5
    }
    git reset --hard origin/main 2>&1 | Out-Null
} finally { Pop-Location }
Write-Host "本地已同步到 origin/main：$(git -C $Repo rev-parse --short HEAD)"

Write-Host ""
Write-Host "发布完成：$Tag  提交 $commitSha"
