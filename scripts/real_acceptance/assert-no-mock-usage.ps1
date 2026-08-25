[CmdletBinding()]
param(
    [string]$SuitePath = (Join-Path $PSScriptRoot "..\..\tests\e2e\real")
)

$ErrorActionPreference = "Stop"
$resolved = (Resolve-Path -LiteralPath $SuitePath).Path
$forbidden = @(
    "page.route(",
    "context.route(",
    "route.fulfill(",
    "MockProvider",
    "FixtureCollector",
    "local mock HTTP server"
)
$violations = @()

Get-ChildItem -LiteralPath $resolved -Recurse -File -Include *.ts,*.js,*.ps1 | ForEach-Object {
    $path = $_.FullName
    $content = [System.IO.File]::ReadAllText($path)
    foreach ($needle in $forbidden) {
        if ($content.Contains($needle)) {
            $violations += "${path}: forbidden marker '${needle}'"
        }
    }
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Error $_ }
    throw "REAL ACCEPTANCE suite contains mock or interception markers."
}

Write-Host "REAL ACCEPTANCE guard passed: no mock or request interception markers found."
