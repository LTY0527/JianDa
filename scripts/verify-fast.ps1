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

Invoke-Step "AI assistant tests" {
    & ".\services\ai-service\.venv\Scripts\python.exe" -m pytest `
        "services/ai-service/tests/test_external_provider.py" -q
}
Invoke-Step "Backend Phase 9.4 tests" {
    mvn -f "services/backend/pom.xml" `
        "-Dtest=AssistantIntegrationTest,AssistantExternalIntegrationTest,OperationMetricsIntegrationTest" test
}
Invoke-Step "Institution typecheck" { npm --prefix "apps/institution-web" run typecheck }
Invoke-Step "H5 typecheck" { npm --prefix "apps/user-h5" run typecheck }
Invoke-Step "Git whitespace check" { git diff --check }

Write-Host ""
Write-Host "Fast verification passed." -ForegroundColor Green
