<#
.SYNOPSIS
    JianDa Phase 9.8_4 resident REPORTED/HIDDEN post interaction-blocking real regression.
.DESCRIPTION
    Two real resident accounts + platform admin, full E2E:
    A creates post (VISIBLE) -> B like/comment (ok) -> B report -> post REPORTED ->
    feed invisible -> B like/comment again (blocked) -> admin restore VISIBLE ->
    B can interact again -> admin hide HIDDEN -> B cannot interact.
    All real API calls, no Mock.
#>
[CmdletBinding()]
param(
    [string]$BackendUrl = "http://localhost:8080",
    [string]$UserA = "demo_chen",
    [string]$UserB = "demo_li",
    [string]$ResidentPwd = "Resident@123",
    [string]$AdminUser = $env:REAL_PLATFORM_ADMIN_USERNAME,
    [string]$AdminPwd = $env:REAL_PLATFORM_ADMIN_PASSWORD
)
if (-not $AdminUser) { $AdminUser = "platform_admin" }
if (-not $AdminPwd) { $AdminPwd = "Jianda@123" }

$ErrorActionPreference = "Stop"
$results = [ordered]@{}
$artifacts = "E:\Code\JIANDA\artifacts\phase9-8-4-final"
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
$utf8 = [System.Text.Encoding]::UTF8

function Post-Json($url, $body, $headers) {
    $bytes = $utf8.GetBytes($body)
    try {
        return Invoke-RestMethod -Uri $url -Method Post -Body $bytes -ContentType "application/json; charset=utf-8" -Headers $headers
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $detail = $sr.ReadToEnd()
            Write-Host "  [debug] POST $url -> $([int]$resp.StatusCode): $detail" -ForegroundColor Yellow
        }
        throw
    }
}
function Login-Resident($user, $pwd) {
    $body = @{ username = $user; password = $pwd } | ConvertTo-Json
    $r = Post-Json "$BackendUrl/api/public/resident/login" $body @{}
    return $r.data.token
}
function Login-Admin($user, $pwd) {
    $body = @{ username = $user; password = $pwd } | ConvertTo-Json
    $r = Post-Json "$BackendUrl/api/auth/login" $body @{}
    return $r.data.token
}
function Expect-Block($label, $scriptBlock) {
    try {
        & $scriptBlock | Out-Null
        $results[$label] = "FAIL_STILL_ACCESSIBLE"
        Write-Host "  [FAIL] $label : reported/hidden post still accessible" -ForegroundColor Red
        return $false
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        if ($code -eq 404 -or $code -eq 400) {
            $results[$label] = "PASS_BLOCKED($code)"
            Write-Host "  [PASS] $label : blocked (HTTP $code)" -ForegroundColor Green
            return $true
        } else {
            $results[$label] = "UNEXPECTED($code)"
            Write-Host "  [??] $label : unexpected status $code" -ForegroundColor Yellow
            return $false
        }
    }
}
function Expect-Ok($label, $scriptBlock) {
    try {
        & $scriptBlock | Out-Null
        $results[$label] = "PASS"
        Write-Host "  [PASS] $label" -ForegroundColor Green
        return $true
    } catch {
        $results[$label] = "FAIL: $($_.Exception.Message)"
        Write-Host "  [FAIL] $label : $($_.Exception.Message)" -ForegroundColor Red
        return $false
    }
}

Write-Host "=== Resident REPORTED/HIDDEN interaction-blocking real regression ===" -ForegroundColor Cyan

# 1. Login A/B/admin
Write-Host "[1] Login resident A/B and platform admin"
$tokA = Login-Resident $UserA $ResidentPwd
$tokB = Login-Resident $UserB $ResidentPwd
$tokAdmin = Login-Admin $AdminUser $AdminPwd
if (-not $tokA -or -not $tokB -or -not $tokAdmin) { throw "login failed" }
Write-Host "  A=$UserA B=$UserB admin=$AdminUser login ok" -ForegroundColor Green

# 2. A creates post (VISIBLE) - category omitted so backend defaults to "最新"
Write-Host "[2] Resident A creates text post (VISIBLE)"
$stamp = Get-Date -Format "HHmmss"
$postBody = @{ content = "Dachang help test post - report interaction regression $stamp" } | ConvertTo-Json
$hdrA = @{ "X-Resident-Token" = $tokA }
$pr = Post-Json "$BackendUrl/api/public/community/posts" $postBody $hdrA
$postId = $pr.data.id
Write-Host "  postId=$postId" -ForegroundColor Green

# 3. Verify feed visible
Write-Host "[3] Verify post visible in public feed"
$feed = Invoke-RestMethod -Uri "$BackendUrl/api/public/community/posts?regionCode=310113102" -Method Get
$inFeed = [bool]($feed.data | Where-Object { [string]$_.id -eq [string]$postId })
$results["feed_visible_after_create"] = if ($inFeed) { "PASS" } else { "FAIL" }
Write-Host "  [debug] feed count=$($feed.data.Count) lookingFor=$postId ids=$($feed.data.id -join ',')" -ForegroundColor DarkGray
$msg3 = if ($inFeed) { "[PASS] feed visible" } else { "[FAIL] feed not visible" }
$col3 = if ($inFeed) { "Green" } else { "Red" }
Write-Host "  $msg3" -ForegroundColor $col3

# 4. B like (ok)
Write-Host "[4] Resident B likes post (VISIBLE, should succeed)"
$hdrB = @{ "X-Resident-Token" = $tokB }
Expect-Ok "like_before_report" { Post-Json "$BackendUrl/api/public/community/posts/$postId/like" "{}" $hdrB } | Out-Null

# 5. B comment (ok)
Write-Host "[5] Resident B comments (VISIBLE, should succeed)"
$cmtBody = @{ content = "test comment - before report" } | ConvertTo-Json
Expect-Ok "comment_before_report" { Post-Json "$BackendUrl/api/public/community/posts/$postId/comments" $cmtBody $hdrB } | Out-Null

# 6. B report -> post REPORTED
Write-Host "[6] Resident B reports post -> REPORTED"
$rptBody = @{ reason = "This post content is not suitable for public display, needs admin review." } | ConvertTo-Json
Expect-Ok "report_post" { Post-Json "$BackendUrl/api/public/community/posts/$postId/report" $rptBody $hdrB } | Out-Null

# 7. Verify feed invisible
$feed2 = Invoke-RestMethod -Uri "$BackendUrl/api/public/community/posts?regionCode=310113102" -Method Get
$stillVisible = [bool]($feed2.data | Where-Object { [string]$_.id -eq [string]$postId })
$results["feed_hidden_after_report"] = if (-not $stillVisible) { "PASS" } else { "FAIL" }
$msg7 = if (-not $stillVisible) { "[PASS] feed invisible after REPORTED" } else { "[FAIL] feed still visible after REPORTED" }
$col7 = if (-not $stillVisible) { "Green" } else { "Red" }
Write-Host "  $msg7" -ForegroundColor $col7

# 8. B like again (blocked)
Write-Host "[8] Resident B likes REPORTED post (should be blocked 404)"
Expect-Block "like_after_report_blocked" { Post-Json "$BackendUrl/api/public/community/posts/$postId/like" "{}" $hdrB } | Out-Null

# 9. B comment again (blocked)
Write-Host "[9] Resident B comments on REPORTED post (should be blocked 404)"
$cmtBody2 = @{ content = "test comment - after report" } | ConvertTo-Json
Expect-Block "comment_after_report_blocked" { Post-Json "$BackendUrl/api/public/community/posts/$postId/comments" $cmtBody2 $hdrB } | Out-Null

# 10. Admin moderation queue accessible
Write-Host "[10] Platform admin moderation queue sees REPORTED post"
$ahdr = @{ Authorization = "Bearer $tokAdmin" }
$queue = Invoke-RestMethod -Uri "$BackendUrl/api/community-admin/posts" -Method Get -Headers $ahdr
$inQueue = [bool]($queue.data | Where-Object { [string]$_.id -eq [string]$postId -and $_.status -eq "REPORTED" })
$results["admin_queue_has_reported"] = if ($inQueue) { "PASS" } else { "FAIL" }
Write-Host "  [debug] queue count=$($queue.data.Count) lookingFor=$postId statuses=$($queue.data | ForEach-Object { $_.id })" -ForegroundColor DarkGray
$msg10 = if ($inQueue) { "[PASS] moderation queue contains this REPORTED post" } else { "[FAIL] moderation queue missing post" }
$col10 = if ($inQueue) { "Green" } else { "Red" }
Write-Host "  $msg10" -ForegroundColor $col10

# 11. Admin restore VISIBLE
Write-Host "[11] Admin restores post to VISIBLE"
$modBody = @{ status = "VISIBLE" } | ConvertTo-Json
Expect-Ok "admin_restore_visible" { Post-Json "$BackendUrl/api/community-admin/posts/$postId/status" $modBody $ahdr } | Out-Null

# 12. B can interact again after restore
Write-Host "[12] After restore, B can like again (should succeed)"
Expect-Ok "like_after_restore" { Post-Json "$BackendUrl/api/public/community/posts/$postId/like" "{}" $hdrB } | Out-Null

# 13. Admin hide HIDDEN
Write-Host "[13] Admin hides post to HIDDEN"
$hideBody = @{ status = "HIDDEN" } | ConvertTo-Json
Expect-Ok "admin_hide" { Post-Json "$BackendUrl/api/community-admin/posts/$postId/status" $hideBody $ahdr } | Out-Null

# 14. B like HIDDEN (blocked)
Write-Host "[14] Resident B likes HIDDEN post (should be blocked 404)"
Expect-Block "like_after_hidden_blocked" { Post-Json "$BackendUrl/api/public/community/posts/$postId/like" "{}" $hdrB } | Out-Null

# 15. B comment HIDDEN (blocked)
Write-Host "[15] Resident B comments on HIDDEN post (should be blocked 404)"
$cmtBody3 = @{ content = "test comment - after hidden" } | ConvertTo-Json
Expect-Block "comment_after_hidden_blocked" { Post-Json "$BackendUrl/api/public/community/posts/$postId/comments" $cmtBody3 $hdrB } | Out-Null

# Summary
$allPass = $true
foreach ($k in $results.Keys) { if ($results[$k] -notlike "PASS*") { $allPass = $false } }
Write-Host ""
Write-Host "=== Summary ===" -ForegroundColor Cyan
foreach ($k in $results.Keys) { Write-Host ("  {0,-38} {1}" -f $k, $results[$k]) }
Write-Host ""
$gate = if ($allPass) { "PASS" } else { "FAIL" }
Write-Host "RESIDENT_GOVERNANCE_REAL_GATE: $gate" -ForegroundColor $(if ($allPass) {"Green"} else {"Red"})

$out = [ordered]@{
    started_at = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssK")
    gate = $gate
    accounts = @{ residentA = $UserA; residentB = $UserB; admin = $AdminUser }
    postId = $postId
    steps = $results
    finished_at = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssK")
}
$out | ConvertTo-Json -Depth 5 | Out-File -FilePath (Join-Path $artifacts "resident-governance-real.json") -Encoding utf8
Write-Host "Report saved: $(Join-Path $artifacts 'resident-governance-real.json')" -ForegroundColor Green

# Cleanup: keep post HIDDEN to keep moderation queue clean
try {
    $cleanupBody = @{ status = "HIDDEN" } | ConvertTo-Json
    Post-Json "$BackendUrl/api/community-admin/posts/$postId/status" $cleanupBody $ahdr | Out-Null
} catch {}
exit $(if ($allPass) { 0 } else { 1 })
