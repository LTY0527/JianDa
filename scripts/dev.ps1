$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$processes = @()
try {
  $processes += Start-Process -FilePath "$root\services\ai-service\.venv\Scripts\python.exe" -ArgumentList "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001" -WorkingDirectory "$root\services\ai-service" -PassThru -WindowStyle Hidden
  $processes += Start-Process -FilePath "mvn.cmd" -ArgumentList "spring-boot:run" -WorkingDirectory "$root\services\backend" -PassThru -WindowStyle Hidden
  $processes += Start-Process -FilePath "npm.cmd" -ArgumentList "run", "dev:institution" -WorkingDirectory $root -PassThru -WindowStyle Hidden
  $processes += Start-Process -FilePath "npm.cmd" -ArgumentList "run", "dev:h5" -WorkingDirectory $root -PassThru -WindowStyle Hidden
  Write-Host "简达已启动：机构端 http://localhost:5173，用户端 http://localhost:5174，后端 http://localhost:8080，AI http://localhost:8001"
  Write-Host "按 Ctrl+C 停止全部服务。"
  while ($true) { Start-Sleep -Seconds 2 }
} finally {
  foreach ($process in $processes) { if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force } }
}

