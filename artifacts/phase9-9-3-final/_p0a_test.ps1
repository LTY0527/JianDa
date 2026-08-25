$ErrorActionPreference = "Stop"
$api = "http://127.0.0.1:8080/api"
$TARGET = 76  # doc id; HPV vaccine article; semantically HEALTH

function Send-Json($uri, $method, $obj, $headers) {
  $json = $obj | ConvertTo-Json -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  Invoke-RestMethod -Uri $uri -Method $method -Body $bytes -ContentType "application/json; charset=utf-8" -Headers $headers
}
function Channel-Counts {
  $items = Invoke-RestMethod -Uri "$api/public/items" -Method Get
  $groups = @{}
  foreach ($it in $items.data) { $ch = $it.publish_channel; if (-not $groups.ContainsKey($ch)) { $groups[$ch] = 0 }; $groups[$ch]++ }
  return $groups
}

# 1. login
$login = Send-Json "$api/auth/login" "Post" @{ username = "platform_admin"; password = "Jianda@123" } @{}
$token = $login.data.token
if (-not $token) { throw "login failed" }
$hdr = @{ Authorization = "Bearer $token" }
Write-Host "[1] login OK"

# 2. before: admin detail (d.*) + channel counts
$det0 = Invoke-RestMethod -Uri "$api/documents/$TARGET" -Method Get -Headers $hdr
$b = @{ channel=$det0.data.publish_channel; originalUrl=$det0.data.original_url; canonicalUrl=$det0.data.canonical_url; status=$det0.data.processing_status }
$cnt0 = Channel-Counts
Write-Host "[2-before] channel=$($b.channel) original_url=$($b.originalUrl) canonical_url=$($b.canonicalUrl) status=$($b.status)"
Write-Host "[2-counts]  HEALTH=$($cnt0['HEALTH']) ACTIVITY=$($cnt0['ACTIVITY'])"

# 3. adjust -> ACTIVITY
Send-Json "$api/documents/$TARGET/publication-channel" "Put" @{ publishChannel = "ACTIVITY" } $hdr | Out-Null
Write-Host "[3] PUT -> ACTIVITY"

# 4. after move: verify channel=ACTIVITY, invariants unchanged
$det1 = Invoke-RestMethod -Uri "$api/documents/$TARGET" -Method Get -Headers $hdr
$a1 = @{ channel=$det1.data.publish_channel; originalUrl=$det1.data.original_url; canonicalUrl=$det1.data.canonical_url; status=$det1.data.processing_status }
$cnt1 = Channel-Counts
Write-Host "[4-after1] channel=$($a1.channel) original_url=$($a1.originalUrl) canonical_url=$($a1.canonicalUrl) status=$($a1.status)"
Write-Host "[4-counts]  HEALTH=$($cnt1['HEALTH']) ACTIVITY=$($cnt1['ACTIVITY'])"

# 5. restore -> HEALTH
Send-Json "$api/documents/$TARGET/publication-channel" "Put" @{ publishChannel = "HEALTH" } $hdr | Out-Null
$det2 = Invoke-RestMethod -Uri "$api/documents/$TARGET" -Method Get -Headers $hdr
$a2 = @{ channel=$det2.data.publish_channel; originalUrl=$det2.data.original_url; canonicalUrl=$det2.data.canonical_url; status=$det2.data.processing_status }
$cnt2 = Channel-Counts
Write-Host "[5-restored] channel=$($a2.channel) original_url=$($a2.originalUrl) canonical_url=$($a2.canonicalUrl) status=$($a2.status)"
Write-Host "[5-counts]  HEALTH=$($cnt2['HEALTH']) ACTIVITY=$($cnt2['ACTIVITY'])"

# 6. assertions
$inv1 = ($b.originalUrl -eq $a1.originalUrl) -and ($b.canonicalUrl -eq $a1.canonicalUrl) -and ($b.status -eq $a1.status)
$inv2 = ($b.originalUrl -eq $a2.originalUrl) -and ($b.canonicalUrl -eq $a2.canonicalUrl) -and ($b.status -eq $a2.status)
$movedToActivity = ($a1.channel -eq "ACTIVITY") -and ($cnt1['ACTIVITY'] -eq ($cnt0['ACTIVITY'] + 1)) -and ($cnt1['HEALTH'] -eq ($cnt0['HEALTH'] - 1))
$restoredToHealth = ($a2.channel -eq "HEALTH") -and ($cnt2['HEALTH'] -eq $cnt0['HEALTH']) -and ($cnt2['ACTIVITY'] -eq $cnt0['ACTIVITY'])
$allPass = $inv1 -and $inv2 -and $movedToActivity -and $restoredToHealth

$result = [ordered]@{
  document_id = $TARGET
  invariant_preserved_after_move = $inv1
  invariant_preserved_after_restore = $inv2
  moved_to_activity_in_feed = $movedToActivity
  restored_to_health_in_feed = $restoredToHealth
  channel_counts_before = $cnt0
  channel_counts_after_move = $cnt1
  channel_counts_after_restore = $cnt2
  original_url_unchanged = ($b.originalUrl -eq $a2.originalUrl)
  processing_status_unchanged = ($b.status -eq $a2.status)
  PUBLISHED_CHANNEL_ADJUSTMENT_ACCEPTANCE = $(if ($allPass) { "PASS" } else { "FAIL" })
}
$result | ConvertTo-Json -Depth 5 | Out-File -FilePath artifacts\phase9-9-3-final\p0a-channel-adjustment-real.json -Encoding utf8
Write-Host "==== SUMMARY ===="
Write-Host "invariants=$($inv1 -and $inv2) moved_to_activity=$movedToActivity restored=$restoredToHealth"
Write-Host "GATE PUBLISHED_CHANNEL_ADJUSTMENT_ACCEPTANCE = $(if ($allPass) { 'PASS' } else { 'FAIL' })"
