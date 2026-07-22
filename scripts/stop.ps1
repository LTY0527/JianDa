$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
. (Join-Path $PSScriptRoot "process-utils.ps1")

$foundAny = $false
foreach ($port in $script:JiandaDevelopmentPorts) {
    $listeners = @(Get-JiandaPortListeners -Port $port)
    if ($listeners.Count -eq 0) {
        Write-Host ("Port {0}: free" -f $port)
        continue
    }

    $foundAny = $true
    foreach ($listener in $listeners) {
        Write-Host ("Port {0}: PID {1}, process {2}" -f $listener.Port, $listener.ProcessId, $listener.Name)
        Write-Host ("Command line: {0}" -f $listener.CommandLine)
        if (Test-JiandaProjectProcess -ProcessInfo $listener -Root $root) {
            Stop-JiandaPortProcess -Port $port -Root $root -ExpectedProcessId $listener.ProcessId
        }
        else {
            Write-Host ("Not stopped: PID {0} is not confirmed as this JianDa project." -f $listener.ProcessId) -ForegroundColor Yellow
        }
    }
}

if (-not $foundAny) {
    Write-Host "No JianDa development listeners were found."
}

if (Wait-JiandaPortsReleased -Ports $script:JiandaDevelopmentPorts -TimeoutSeconds 20) {
    Write-Host "Ports 5173, 5174, 8080, and 8001 are free." -ForegroundColor Green
    exit 0
}

Write-Host "One or more development ports are still in use:" -ForegroundColor Red
foreach ($port in $script:JiandaDevelopmentPorts) {
    foreach ($listener in @(Get-JiandaPortListeners -Port $port)) {
        Write-Host ("  port={0} pid={1} process={2}" -f $port, $listener.ProcessId, $listener.Name)
    }
}
exit 1
