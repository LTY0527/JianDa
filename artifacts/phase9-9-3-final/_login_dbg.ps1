function Send-Json($uri, $method, $obj, $headers) {
  $json = $obj | ConvertTo-Json -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  Invoke-RestMethod -Uri $uri -Method $method -Body $bytes -ContentType "application/json; charset=utf-8" -Headers $headers
}
$api = "http://127.0.0.1:8080/api"
try {
  $login = Send-Json "$api/auth/login" "Post" @{ username = "platform_admin"; password = "Jianda@123" } @{}
  Write-Host "=== login response (depth 6) ==="
  $login | ConvertTo-Json -Depth 6
} catch {
  Write-Host "=== login threw ==="
  Write-Host "StatusCode: $($_.Exception.Response.StatusCode.value__)"
  $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
  $body = $reader.ReadToEnd()
  Write-Host "Body: $body"
}
