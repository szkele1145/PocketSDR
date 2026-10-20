param(
    [Parameter(Mandatory = $true)][string]$Version,      # e.g. 2.37.0
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [Parameter(Mandatory = $true)][string]$Message,     # commit message
    [string[]]$Files,                                     # paths relative to repo root
    [Parameter(Mandatory = $true)][string]$ReleaseName,
    [Parameter(Mandatory = $true)][string]$ReleaseBody,
    [string]$Tag = ""                                     # defaults to v<Version>
)
# PocketSDR publish script: commit -> tree -> commit -> update main -> release -> upload APK -> verify.
#
# NOTE: this file is intentionally ASCII-only. PowerShell 5.1 reads BOM-less files as
# the system codepage (GBK here), which garbles Chinese and breaks parsing. Keeping
# the script ASCII removes the whole class of encoding failures (v2.36.0 hit it twice).
#
# Why a script instead of hand-rolled curl:
#   1. Tree creation MUST carry base_tree, otherwise the new commit only contains the
#      changed files (v2.34.0 shipped a 6-file commit and lost the other 36 source files).
#      The script asserts base_tree is non-empty and aborts otherwise.
#   2. ref PATCH and asset upload need retries - GitHub sometimes returns empty bodies.
#   3. After pushing, verify the remote tree matches local file-for-file.
$ErrorActionPreference = "Stop"

$Repo      = Split-Path -Parent $MyInvocation.MyCommand.Path
$ApiBase   = "https://api.github.com/repos/szkele1145/PocketSDR"
$TokenFile = Join-Path $env:USERPROFILE ".dsh\gh.token"
# Keep temp files on the repo drive: C: is nearly full on this machine and a
# 15 MB APK copy per release adds up fast (it filled C: once already).
$Tmp       = Join-Path $Repo "build\publish_tmp"
if (-not (Test-Path $Tmp)) { New-Item -ItemType Directory -Path $Tmp -Force | Out-Null }
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# Full transcript to a log file.
#
# The v2.49.0 publish died inside step 8 with NO visible error: the console
# output was cut off after step 4 and the script left the stash unpopped, so
# there was no way to tell which sub-step failed. A log file makes every
# failure diagnosable after the fact - the log path is printed at the end.
$LogFile = Join-Path $Tmp "publish_$Version.log"
try { Start-Transcript -Path $LogFile -Force | Out-Null } catch { $LogFile = "" }

if (-not $Tag) { $Tag = "v$Version" }
$Token = (Get-Content $TokenFile -Raw).Trim()
$H = "Authorization: token $Token"

function Invoke-Gh {
    param([string]$Url, [string]$Method = "GET", [string]$BodyFile = "", [string]$OutFile = "", [int]$EmptyRetries = 0)
    $a = @("-s", "-k", "-X", $Method, $Url, "-H", $H, "-H", "Accept: application/vnd.github+json")
    if ($BodyFile) { $a += @("-H", "Content-Type: application/json", "--data-binary", "@$BodyFile") }
    if ($OutFile)  { $a += @("-o", $OutFile) }
    # Pipe through Out-String: curl output can come back as a string ARRAY in PowerShell,
    # and regex-matching an array fails silently (that is exactly how v2.36.0's upload
    # check reported failure for a successful upload).
    $resp = ""
    $tries = [Math]::Max(1, $EmptyRetries + 1)
    for ($i = 1; $i -le $tries; $i++) {
        $resp = (& curl.exe @a) | Out-String
        if ($resp.Length -ge 10) { break }
        if ($i -lt $tries) {
            Write-Host "    empty GitHub response (try $i), retrying"
            Start-Sleep -Seconds 3
        }
    }
    return $resp
}

# Run a git command without letting its stderr become a terminating exception.
#
# With $ErrorActionPreference = "Stop", PowerShell turns ANY native stderr
# output into a terminating error - even when the command succeeds. Git
# prints progress/warnings on stderr routinely, so `git fetch -q ... 2>&1`
# could abort the publish before it even started (this is what killed the
# v2.49.0 publish in step 8, leaving the stash unpopped and no visible error).
#
# Usage: Invoke-Git @("fetch","-q","origin","main")
#        Invoke-Git @("rev-parse","HEAD")   -> returns stdout as string
#
# NOTE: the parameter must NOT be named $Args - that is a PowerShell automatic
# variable and shadowing it makes git receive an empty argument list (git then
# prints its usage text and exits 1).
function Invoke-Git {
    param([string[]]$GitArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = (& git @GitArgs 2>&1) | Out-String
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    if ($code -ne 0 -and $GitArgs[0] -eq "fetch") {
        Write-Host "    git $($GitArgs -join ' ') failed (exit $code)"
    }
    return $out
}

function Get-Json {
    param([string]$Url)
    $out = Join-Path $Tmp "gh_out.json"
    # curl --retry only retries transport errors, not a 200 with an empty body.
    # GitHub occasionally answers 200 with an empty payload while its edge is
    # unhappy - that aborted the v2.48.0 publish. Retry the whole request.
    $raw = ""
    for ($try = 1; $try -le 4; $try++) {
        if (Test-Path $out) { Remove-Item $out -Force }
        & curl.exe -s -k --retry 2 --retry-delay 2 --max-time 60 `
            -H $H -H "Accept: application/vnd.github+json" $Url -o $out
        if (Test-Path $out) {
            $raw = [IO.File]::ReadAllText($out, [Text.Encoding]::UTF8)
        }
        if ($raw.Length -ge 10) { break }
        Write-Host "    empty GitHub response (try $try), retrying"
        Start-Sleep -Seconds 3
    }
    if ($raw.Length -lt 10) { throw "GitHub request returned nothing usable: $Url" }
    return ($raw | ConvertFrom-Json)
}

# Restore the auto-stash created in step 8.
#
# The stash is made against the OLD head, so popping it after a reset onto the
# NEW published commit can conflict (3-way merge with a stale base). The
# stashed content is already part of the new commit, so "ours" (the new HEAD)
# is the right resolution: taking "theirs" would resurrect the pre-publish
# version of the file and silently undo the release in the worktree.
function Restore-Stash {
    $list = Invoke-Git @("stash", "list")
    if (-not $list.Trim()) { return }
    Invoke-Git @("stash", "pop") | Out-Null
    $unmerged = @((Invoke-Git @("diff", "--name-only", "--diff-filter=U")) -split "`r?`n" | Where-Object { $_.Trim() })
    if ($unmerged.Count -gt 0) {
        Write-Host "    $($unmerged.Count) conflict(s) after stash pop; keeping published version"
        foreach ($u in $unmerged) {
            Invoke-Git @("checkout", "--ours", "--", $u) | Out-Null
            Invoke-Git @("add", "--", $u) | Out-Null
        }
    }
    Write-Host "    stashed changes restored"
}

Write-Host "==> 1/8 Fetch remote main baseline"
$remote = Get-Json "$ApiBase/branches/main"
$baseCommit = $remote.commit.sha
$baseTree = $remote.commit.commit.tree.sha
if (-not $baseCommit -or -not $baseTree) { throw "Failed to fetch baseline (commit=$baseCommit tree=$baseTree), aborting" }
Write-Host "    base commit $baseCommit  base tree $baseTree"

Write-Host "==> 2/8 Upload blobs"
$blobMap = @{}
foreach ($f in $Files) {
    $full = Join-Path $Repo ($f -replace '/', '\')
    if (-not (Test-Path $full)) { throw "File not found: $f" }
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($full))
    [IO.File]::WriteAllText("$Tmp\blob.json", ('{"content":"' + $b64 + '","encoding":"base64"}'), $Utf8NoBom)
    $resp = Invoke-Gh "$ApiBase/git/blobs" -Method POST -BodyFile "$Tmp\blob.json" -EmptyRetries 3
    $sha = [regex]::Match($resp, '"sha":\s*"([0-9a-f]{40})"').Groups[1].Value
    if (-not $sha) { throw "Blob upload failed for $f`n$resp" }
    $blobMap[$f] = $sha
    Write-Host "    $f -> $sha"
}

Write-Host "==> 3/8 Create tree (base_tree must be non-empty)"
$treeEntries = @()
foreach ($f in $Files) {
    $treeEntries += @{ path = $f; mode = "100644"; type = "blob"; sha = $blobMap[$f] }
}
[IO.File]::WriteAllText("$Tmp\tree.json",
    (ConvertTo-Json -InputObject @{ base_tree = $baseTree; tree = $treeEntries } -Depth 6), $Utf8NoBom)
$resp = Invoke-Gh "$ApiBase/git/trees" -Method POST -BodyFile "$Tmp\tree.json"
$treeSha = [regex]::Match($resp, '"sha":\s*"([0-9a-f]{40})"').Groups[1].Value
if (-not $treeSha) { throw "Tree creation failed: $resp" }

# Assert: the new tree must contain every path from the baseline.
$newTree = Get-Json "$ApiBase/git/trees/$treeSha`?recursive=1"
$baseTreeFull = Get-Json "$ApiBase/git/trees/$baseTree`?recursive=1"
$newPaths = @($newTree.tree | Where-Object { $_.type -eq "blob" } | ForEach-Object { $_.path })
$basePaths = @($baseTreeFull.tree | Where-Object { $_.type -eq "blob" } | ForEach-Object { $_.path })
if ($newPaths.Count -lt $basePaths.Count) {
    throw "New tree has $($newPaths.Count) files, fewer than baseline $($basePaths.Count) - base_tree did not apply, aborting"
}
$missing = $basePaths | Where-Object { $newPaths -notcontains $_ }
if (@($missing).Count -gt 0) { throw "New tree lost files: $($missing -join ', '), aborting" }
Write-Host "    new tree $treeSha  $($newPaths.Count) files (baseline $($basePaths.Count), none missing)"

Write-Host "==> 4/8 Create commit"
[IO.File]::WriteAllText("$Tmp\commit.json",
    (ConvertTo-Json -InputObject @{ message = $Message; tree = $treeSha; parents = @($baseCommit) } -Depth 4), $Utf8NoBom)
$resp = Invoke-Gh "$ApiBase/git/commits" -Method POST -BodyFile "$Tmp\commit.json"
$commitSha = [regex]::Match($resp, '"sha":\s*"([0-9a-f]{40})"').Groups[1].Value
if (-not $commitSha) { throw "Commit failed: $resp" }
Write-Host "    commit $commitSha"

Write-Host "==> 5/8 Update main"
[IO.File]::WriteAllText("$Tmp\ref.json",
    (ConvertTo-Json -InputObject @{ sha = $commitSha; force = $false } -Depth 3), $Utf8NoBom)
$ok = $false
for ($i = 1; $i -le 3; $i++) {
    $resp = Invoke-Gh "$ApiBase/git/refs/heads/main" -Method PATCH -BodyFile "$Tmp\ref.json"
    if ([regex]::Match($resp, '"sha":\s*"([0-9a-f]{40})"').Groups[1].Value -eq $commitSha) {
        $ok = $true; Write-Host "    updated (try $i)"; break
    }
    Write-Host "    try $i failed, retrying"; Start-Sleep -Seconds 4
}
if (-not $ok) { throw "Failed to update main" }

Write-Host "==> 6/8 Verify remote tree matches local"
$finalCommit = Get-Json "$ApiBase/commits/main"
$finalTreeSha = $finalCommit.commit.tree.sha
if ($finalTreeSha -ne $treeSha) { throw "main tree $finalTreeSha != pushed $treeSha" }
$finalFull = Get-Json "$ApiBase/git/trees/$finalTreeSha`?recursive=1"
$remoteMap = @{}
foreach ($e in $finalFull.tree) { if ($e.type -eq "blob") { $remoteMap[$e.path] = $e.sha } }
Push-Location $Repo
$bad = @()
try {
    $tracked = @((Invoke-Git @("ls-files")) -split "`r?`n" | Where-Object { $_.Trim() })
    foreach ($f in $tracked) {
        if (-not $remoteMap.ContainsKey($f)) { $bad += "missing on remote: $f"; continue }
        $p = Join-Path $Repo ($f -replace '/', '\')
        $localHash = (Invoke-Git @("hash-object", "--no-filters", $p)).Trim()
        $remoteHash = $remoteMap[$f]
        if ($localHash -ne $remoteHash) {
            # CRLF vs LF does not count as a difference: compare normalized content.
            $blob = Get-Json "$ApiBase/git/blobs/$remoteHash"
            $remoteText = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(($blob.content -replace '\s', '')))
            $localText = [IO.File]::ReadAllText($p)
            if (($remoteText -replace "`r`n", "`n") -ne ($localText -replace "`r`n", "`n")) {
                $bad += "content mismatch: $f"
            }
        }
    }
} finally { Pop-Location }
if (@($bad).Count -gt 0) { throw "Remote does not match local:`n$($bad -join "`n")" }
Write-Host "    verified: $($tracked.Count) files match"

Write-Host "==> 7/8 Create release and upload APK"
[IO.File]::WriteAllText("$Tmp\relbody.json", (ConvertTo-Json -InputObject @{
    tag_name = $Tag; target_commitish = "main"; name = $ReleaseName
    body = $ReleaseBody; draft = $false; prerelease = $false
} -Depth 4), $Utf8NoBom)
$resp = Invoke-Gh "$ApiBase/releases" -Method POST -BodyFile "$Tmp\relbody.json"
$releaseId = [regex]::Match($resp, '"id":\s*(\d+)').Groups[1].Value
if (-not $releaseId) { throw "Release creation failed: $resp" }
Write-Host "    release $releaseId"

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
    # already_exists means the previous attempt actually succeeded.
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

Write-Host "==> 8/8 Final verification"
$rel = Get-Json "$ApiBase/releases/tags/$Tag"
if ($rel.tag_name -ne $Tag) { throw "release tag mismatch: $($rel.tag_name)" }
$asset = @($rel.assets) | Where-Object { $_.name -eq $assetName } | Select-Object -First 1
if (-not $asset) { throw "release asset $assetName not found" }
Write-Host "    $Tag -> $($asset.browser_download_url)"
Write-Host "    commit $commitSha"

Push-Location $Repo
try {
    # Snapshot uncommitted changes BEFORE the hard reset: reset --hard would discard
    # them (that is how v2.36.0 lost a script fix). Stash first, restore after.
    $dirty = @((Invoke-Git @("status", "--porcelain")) -split "`r?`n" | Where-Object { $_.Trim() })
    if ($dirty.Count -gt 0) {
        Write-Host "    $($dirty.Count) uncommitted change(s), stashing"
        Invoke-Git @("stash", "push", "-u", "-m", "publish.ps1 auto-stash") | Out-Null
    }
    $fetched = $false
    for ($i = 1; $i -le 3; $i++) {
        $fr = Invoke-Git @("fetch", "-q", "origin", "main")
        if ($LASTEXITCODE -eq 0) { $fetched = $true; break }
        Write-Host "    fetch try $i failed: $($fr.Trim().Substring(0, [Math]::Min(120, $fr.Trim().Length)))"
        Start-Sleep -Seconds 5
    }
    if ($fetched) {
        Invoke-Git @("reset", "--hard", "origin/main") | Out-Null
        # Pop after the reset: the stash base is the OLD head, so git does a
        # 3-way merge against a stale base and may report conflicts. The stashed
        # content is already IN the new commit, so "ours" (the new HEAD) is the
        # correct resolution - taking "theirs" would resurrect the old version.
        Restore-Stash
    } else {
        # git transport is down (connection reset). Resetting to the stale
        # origin/main would move the worktree BACKWARD, undoing what we just
        # published - that is exactly what happened after v2.45.0.
        # Rebuild the published commit locally instead, using the GitHub API:
        # restore the stashed changes first (base still matches, no conflict),
        # then write-tree must reproduce the remote tree sha. If it does not,
        # the local content drifted and we must NOT reset (it would lose work).
        Write-Host "    git fetch failed; rebuilding commit locally via GitHub API"
        Restore-Stash
        $rc = Get-Json "$ApiBase/git/commits/$commitSha"
        $remoteTree = $rc.tree.sha
        $remoteParent = @($rc.parents)[0].sha
        Write-Host "    remote tree   $remoteTree"
        Write-Host "    remote parent $remoteParent"
        Invoke-Git @("add", "-A") | Out-Null
        $localTree = (Invoke-Git @("write-tree")).Trim()
        Write-Host "    local tree    $localTree"
        if ($localTree -ne $remoteTree) {
            throw "Local tree $localTree != remote tree $remoteTree - refusing to reset, local work would be lost"
        }
        Write-Host "    tree matches remote ($remoteTree)"
        $env:GIT_AUTHOR_NAME = $rc.author.name
        $env:GIT_AUTHOR_EMAIL = $rc.author.email
        $env:GIT_AUTHOR_DATE = $rc.author.date
        $env:GIT_COMMITTER_NAME = $rc.committer.name
        $env:GIT_COMMITTER_EMAIL = $rc.committer.email
        $env:GIT_COMMITTER_DATE = $rc.committer.date
        # Use the remote message so the rebuilt commit is byte-identical to the
        # published one (same tree + parent + author + committer + message).
        $localCommit = (Invoke-Git @("commit-tree", $remoteTree, "-p", $remoteParent, "-m", $rc.message)).Trim()
        Write-Host "    local commit $localCommit (remote $commitSha)"
        Invoke-Git @("reset", "--hard", $localCommit) | Out-Null
    }
} finally { Pop-Location }
$headShort = (Invoke-Git @("-C", $Repo, "rev-parse", "--short", "HEAD")).Trim()
Write-Host "local synced to origin/main: $headShort"

Write-Host ""
Write-Host "PUBLISHED: $Tag  commit $commitSha"
if ($LogFile) {
    Write-Host "log: $LogFile"
    try { Stop-Transcript | Out-Null } catch {}
}
