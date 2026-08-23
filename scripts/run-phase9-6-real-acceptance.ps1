[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipRegression
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$phase73 = Join-Path $projectRoot "artifacts\phase7-3"
$realSpec = "tests/e2e/real"

function Invoke-Step {
    param([string]$Name, [scriptblock]$Action)
    Write-Host "`n== $Name ==" -ForegroundColor Cyan
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

function Get-EvidenceHash {
    param([string]$Directory)
    $lines = Get-ChildItem -LiteralPath $Directory -File -Recurse |
        Sort-Object FullName |
        ForEach-Object { "$(Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName | Select-Object -ExpandProperty Hash) $($_.FullName.Substring($Directory.Length))" }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally { $sha.Dispose() }
}

function Assert-Http200 {
    param([string]$Name, [string]$Url)
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 20
        if ($response.StatusCode -ne 200) { throw "HTTP $($response.StatusCode)" }
        Write-Host "Ready: $Name ($Url)"
    } catch {
        throw "$Name is not healthy at $Url : $($_.Exception.Message)"
    }
}

Set-Location -LiteralPath $projectRoot
$beforeHash = Get-EvidenceHash $phase73

if (-not $SkipBuild) {
    Invoke-Step "Docker build" { docker compose build }
}
Invoke-Step "Docker start" { docker compose up -d }
Invoke-Step "Docker status" { docker compose ps }

Assert-Http200 "AI service" "http://127.0.0.1:8001/health"
Assert-Http200 "Backend" "http://127.0.0.1:8080/actuator/health"
Assert-Http200 "Institution web" "http://127.0.0.1:8090/health"
Assert-Http200 "User H5" "http://127.0.0.1/health"

Invoke-Step "No-Mock guard" { & "$projectRoot\scripts\real_acceptance\assert-no-mock-usage.ps1" }

$env:JIANDA_DOCKER_COMPOSE_UP = "1"
if (-not $env:JIANDA_REAL_PLATFORM_PASSWORD) {
    Write-Warning "JIANDA_REAL_PLATFORM_PASSWORD is absent. Public real tests run; authenticated review test is reported as skipped."
}
Invoke-Step "REAL Playwright" { npx playwright test $realSpec }

if (-not $SkipRegression) {
    Invoke-Step "AI pytest" {
        Push-Location -LiteralPath "$projectRoot\services\ai-service"
        try {
            & ".\.venv\Scripts\python.exe" -m pytest tests -q
        } finally {
            Pop-Location
        }
    }
    Invoke-Step "Backend Maven tests" { mvn -f "$projectRoot\services\backend\pom.xml" test }
    Invoke-Step "Institution typecheck" { npm --prefix "$projectRoot\apps\institution-web" run typecheck }
    Invoke-Step "Institution build" { npm --prefix "$projectRoot\apps\institution-web" run build }
    Invoke-Step "H5 typecheck" { npm --prefix "$projectRoot\apps\user-h5" run typecheck }
    Invoke-Step "H5 build" { npm --prefix "$projectRoot\apps\user-h5" run build }
}

$afterHash = Get-EvidenceHash $phase73
if ($beforeHash -ne $afterHash) {
    throw "Historical Phase 7.3 evidence changed during acceptance."
}
Write-Host "`nPhase 9.6 real acceptance completed. Phase 7.3 evidence unchanged: $afterHash" -ForegroundColor Green
