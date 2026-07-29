[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root

function Invoke-Step {
    param([string]$Name, [scriptblock]$Command)
    Write-Host ""
    Write-Host "== $Name ==" -ForegroundColor Cyan
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Assert-Http200 {
    param([string]$Name, [string]$Url)
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 15
    if ($response.StatusCode -ne 200) {
        throw "$Name health check returned HTTP $($response.StatusCode)"
    }
    Write-Host "$Name Ready ($Url)"
}

Invoke-Step "AI tests" {
    Push-Location "services/ai-service"
    try { & ".\.venv\Scripts\python.exe" -m pytest tests -q }
    finally { Pop-Location }
}
Invoke-Step "Backend tests" { mvn -f "services/backend/pom.xml" test }
Invoke-Step "Institution build" { npm --prefix "apps/institution-web" run build }
Invoke-Step "H5 build" { npm --prefix "apps/user-h5" run build }
Invoke-Step "Docker build" { docker compose build }
Invoke-Step "Docker up" { docker compose up -d }
Invoke-Step "Docker status" { docker compose ps }

Assert-Http200 "AI" "http://127.0.0.1:8001/health"
Assert-Http200 "Backend" "http://127.0.0.1:8080/actuator/health"
Assert-Http200 "Institution" "http://127.0.0.1:8090/health"
Assert-Http200 "H5" "http://127.0.0.1/health"
Invoke-Step "Playwright" {
    $previous = @{
        JIANDA_H5_TEST_URL = $env:JIANDA_H5_TEST_URL
        JIANDA_H5_URL = $env:JIANDA_H5_URL
        JIANDA_INSTITUTION_TEST_URL = $env:JIANDA_INSTITUTION_TEST_URL
        JIANDA_INSTITUTION_URL = $env:JIANDA_INSTITUTION_URL
    }
    try {
        $env:JIANDA_H5_TEST_URL = "http://127.0.0.1"
        $env:JIANDA_H5_URL = "http://127.0.0.1"
        $env:JIANDA_INSTITUTION_TEST_URL = "http://127.0.0.1:8090"
        $env:JIANDA_INSTITUTION_URL = "http://127.0.0.1:8090"
        npx playwright test
    } finally {
        foreach ($name in $previous.Keys) {
            if ($null -eq $previous[$name]) {
                Remove-Item "Env:$name" -ErrorAction SilentlyContinue
            } else {
                Set-Item "Env:$name" $previous[$name]
            }
        }
    }
}
Invoke-Step "Git whitespace check" { git diff --check }

Write-Host ""
Write-Host "Full verification passed." -ForegroundColor Green
