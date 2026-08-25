[CmdletBinding()]
param(
    [string]$ExamplePath,
    [string]$TargetPath
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($ExamplePath)) {
    $ExamplePath = Join-Path $projectRoot ".env.example"
}
if ([string]::IsNullOrWhiteSpace($TargetPath)) {
    $TargetPath = Join-Path $projectRoot ".env"
}

$exampleFile = [System.IO.Path]::GetFullPath($ExamplePath)
$targetFile = [System.IO.Path]::GetFullPath($TargetPath)
if (-not (Test-Path -LiteralPath $exampleFile -PathType Leaf)) {
    throw "Environment template not found: $exampleFile"
}

$entryPattern = "^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$"
$sensitivePattern = "(?i)(API_KEY|SECRET|PASSWORD|TOKEN)$"
$knownKeys = @{}

if (Test-Path -LiteralPath $targetFile -PathType Leaf) {
    foreach ($line in Get-Content -LiteralPath $targetFile -Encoding UTF8) {
        if ($line -match $entryPattern) {
            $knownKeys[$matches[1]] = $true
        }
    }
}
else {
    New-Item -ItemType File -Path $targetFile -Force | Out-Null
}

$missingLines = New-Object System.Collections.Generic.List[string]
$missingKeys = New-Object System.Collections.Generic.List[string]
foreach ($line in Get-Content -LiteralPath $exampleFile -Encoding UTF8) {
    if ($line -notmatch $entryPattern) {
        continue
    }
    $key = $matches[1]
    $value = $matches[2]
    if ($knownKeys.ContainsKey($key)) {
        continue
    }
    if ($key -match $sensitivePattern) {
        $value = ""
    }
    $missingLines.Add("$key=$value")
    $missingKeys.Add($key)
}

if ($missingLines.Count -eq 0) {
    Write-Host "Environment is already synchronized. No values were displayed."
    exit 0
}

Add-Content -LiteralPath $targetFile -Value "" -Encoding UTF8
Add-Content -LiteralPath $targetFile -Value "# Added by scripts/sync-env.ps1" -Encoding UTF8
Add-Content -LiteralPath $targetFile -Value $missingLines -Encoding UTF8

Write-Host "Added missing environment keys (values hidden):"
foreach ($key in $missingKeys) {
    Write-Host "  $key"
}
