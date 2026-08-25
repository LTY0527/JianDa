$ErrorActionPreference = "Stop"
$api = "http://127.0.0.1:8080/api"

function Send-Json($uri, $method, $obj, $headers) {
  $json = $obj | ConvertTo-Json -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  Invoke-RestMethod -Uri $uri -Method $method -Body $bytes -ContentType "application/json; charset=utf-8" -Headers $headers
}

# login as platform admin
$login = Send-Json "$api/auth/login" "Post" @{ username = "platform_admin"; password = "Jianda@123" } @{}
$token = $login.data.token
$hdr = @{ Authorization = "Bearer $token" }

# get doc 73 and 75 details
foreach ($id in 73, 75) {
  $det = Invoke-RestMethod -Uri "$api/documents/$id" -Method Get -Headers $hdr
  $det | ConvertTo-Json -Depth 10 | Out-File -FilePath "artifacts\phase9-9-3-final\_doc_${id}_detail.json" -Encoding utf8
  Write-Host "saved doc $id"
}

# also get a published doc as reference for category values
$pub = Invoke-RestMethod -Uri "$api/documents/88" -Method Get -Headers $hdr
$pub | ConvertTo-Json -Depth 10 | Out-File -FilePath "artifacts\phase9-9-3-final\_doc_88_detail.json" -Encoding utf8
Write-Host "saved doc 88 (reference)"
