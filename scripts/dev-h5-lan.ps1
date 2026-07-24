param(
    [string]$ProxyTarget = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$h5Path = Join-Path $projectRoot "apps\user-h5"

if (-not (Test-Path -LiteralPath $h5Path -PathType Container)) {
    throw "Missing user H5 directory: $h5Path"
}
if (-not (Get-Command "npm.cmd" -ErrorAction SilentlyContinue)) {
    throw "npm.cmd was not found in PATH."
}

Remove-Item Env:VITE_API_BASE_URL -ErrorAction SilentlyContinue
$env:VITE_PROXY_TARGET = $ProxyTarget

function Find-LanIPv4 {
    $ipconfigText = (ipconfig | Out-String)
    $blocks = $ipconfigText -split "(\r?\n){2,}"
    foreach ($block in $blocks) {
        $ipMatch = [regex]::Match($block, "IPv4[^:]*:\s*(\d{1,3}(?:\.\d{1,3}){3})")
        $gatewayMatch = [regex]::Match($block, "Default Gateway[^:]*:\s*(\d{1,3}(?:\.\d{1,3}){3})")
        if ($ipMatch.Success -and $gatewayMatch.Success) {
            return $ipMatch.Groups[1].Value
        }
    }
    return $null
}

$lanIp = Find-LanIPv4
Write-Host "VITE_API_BASE_URL cleared. H5 will use same-origin /api."
Write-Host "VITE_PROXY_TARGET=$ProxyTarget"
Write-Host "Local: http://127.0.0.1:5174"
if ($lanIp) {
    Write-Host "Network: http://${lanIp}:5174"
    Write-Host "Proxy check: http://${lanIp}:5174/api/public/items"
} else {
    Write-Host "Network IP was not detected automatically. Run ipconfig and open http://<LAN-IP>:5174"
    Write-Host "Proxy check: http://<LAN-IP>:5174/api/public/items"
}
Write-Host "Press Ctrl+C to stop the H5 dev server."

Push-Location $projectRoot
try {
    & npm.cmd run dev:h5:lan
    if ($LASTEXITCODE -ne 0) {
        throw "H5 dev server exited with code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
