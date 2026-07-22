param([switch]$ExitAfterReady)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
. (Join-Path $PSScriptRoot "process-utils.ps1")

$pythonExe = Join-Path $root "services\ai-service\.venv\Scripts\python.exe"
$requiredDirectories = @(
    "apps\institution-web",
    "apps\user-h5",
    "services\backend",
    "services\ai-service"
)
$services = @()
$logRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("jianda-dev-" + $PID)

function Show-VenvRebuildHelp {
    Write-Host "Rebuild the AI virtual environment with Python 3.11 or newer:"
    Write-Host "  cd services\ai-service"
    Write-Host "  deactivate"
    Write-Host "  Remove-Item -LiteralPath '.\.venv' -Recurse -Force"
    Write-Host "  py -3.13 -m venv '.\.venv'"
    Write-Host "  & '.\.venv\Scripts\python.exe' -m pip install -r requirements.txt"
}

function Test-HealthEndpoint {
    param([string]$Url)

    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
        return ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400)
    }
    catch {
        return $false
    }
}

function Start-ManagedService {
    param(
        [string]$Name,
        [int]$Port,
        [string]$HealthUrl,
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$WorkingDirectory
    )

    $existingListeners = @(Get-JiandaPortListeners -Port $Port)
    if ($existingListeners.Count -gt 0) {
        if ($existingListeners.Count -ne 1) {
            throw "Port $Port has multiple listener processes; refusing to claim ownership."
        }

        $existing = $existingListeners[0]
        if (-not (Test-JiandaProjectProcess -ProcessInfo $existing -Root $root)) {
            throw "Port $Port is occupied by a process that is not confirmed as this JianDa project."
        }
        if (-not (Test-HealthEndpoint -Url $HealthUrl)) {
            throw "Existing JianDa service on port $Port failed its health check."
        }

        return [PSCustomObject]@{
            Name = $Name
            Port = $Port
            HealthUrl = $HealthUrl
            LauncherProcess = $null
            ListenerProcessId = $existing.ProcessId
            StdoutLog = $null
            StderrLog = $null
            Ready = $true
            Reused = $true
        }
    }

    $safeName = $Name.ToLowerInvariant().Replace(" ", "-")
    $stdoutLog = Join-Path $logRoot ($safeName + ".out.log")
    $stderrLog = Join-Path $logRoot ($safeName + ".err.log")
    $launcher = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkingDirectory -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog

    return [PSCustomObject]@{
        Name = $Name
        Port = $Port
        HealthUrl = $HealthUrl
        LauncherProcess = $launcher
        ListenerProcessId = $null
        StdoutLog = $stdoutLog
        StderrLog = $stderrLog
        Ready = $false
        Reused = $false
    }
}

function Wait-ServiceReady {
    param(
        [PSCustomObject]$Service,
        [int]$TimeoutSeconds = 120
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $launcherExitDeadline = $null
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-HealthEndpoint -Url $Service.HealthUrl) {
            $listeners = @(Get-JiandaPortListeners -Port $Service.Port)
            $projectListeners = @($listeners | Where-Object { Test-JiandaProjectProcess -ProcessInfo $_ -Root $root })
            if ($projectListeners.Count -eq 1) {
                $Service.ListenerProcessId = $projectListeners[0].ProcessId
                return $true
            }
        }

        if ($Service.LauncherProcess) {
            $Service.LauncherProcess.Refresh()
            if ($Service.LauncherProcess.HasExited) {
                if ($launcherExitDeadline -eq $null) {
                    $launcherExitDeadline = [DateTime]::UtcNow.AddSeconds(15)
                }
                elseif ([DateTime]::UtcNow -ge $launcherExitDeadline) {
                    return $false
                }
            }
        }

        Start-Sleep -Seconds 2
    }

    return $false
}

foreach ($relativePath in $requiredDirectories) {
    $fullPath = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Container)) {
        throw "Required directory not found: $fullPath"
    }
}

if (-not (Test-Path -LiteralPath $pythonExe -PathType Leaf)) {
    Write-Host "AI Python interpreter not found: $pythonExe" -ForegroundColor Red
    Show-VenvRebuildHelp
    exit 1
}

$pythonVersionOutput = (& $pythonExe --version 2>&1 | Out-String).Trim()
Write-Host "AI interpreter: $pythonVersionOutput"
$versionMatch = [regex]::Match($pythonVersionOutput, "Python\s+(\d+)\.(\d+)")
if (-not $versionMatch.Success) {
    Write-Host "Unable to parse the AI Python version." -ForegroundColor Red
    Show-VenvRebuildHelp
    exit 1
}

$pythonMajor = [int]$versionMatch.Groups[1].Value
$pythonMinor = [int]$versionMatch.Groups[2].Value
if ($pythonMajor -lt 3 -or ($pythonMajor -eq 3 -and $pythonMinor -lt 11)) {
    Write-Host "Unsupported AI Python version: $pythonVersionOutput" -ForegroundColor Red
    Write-Host "Python 3.11 or newer is required. Python 3.9 is not supported." -ForegroundColor Red
    Show-VenvRebuildHelp
    exit 1
}

$npmCommand = Get-Command "npm.cmd" -ErrorAction Stop
$mavenCommand = Get-Command "mvn.cmd" -ErrorAction Stop
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
Write-Host "Logs: $logRoot"

try {
    $services += Start-ManagedService -Name "AI service" -Port 8001 `
        -HealthUrl "http://127.0.0.1:8001/health" -FilePath $pythonExe `
        -ArgumentList @("-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8001") `
        -WorkingDirectory (Join-Path $root "services\ai-service")
    $services += Start-ManagedService -Name "Backend" -Port 8080 `
        -HealthUrl "http://127.0.0.1:8080/v3/api-docs" -FilePath $mavenCommand.Source `
        -ArgumentList @("spring-boot:run") -WorkingDirectory (Join-Path $root "services\backend")
    $services += Start-ManagedService -Name "Institution web" -Port 5173 `
        -HealthUrl "http://127.0.0.1:5173" -FilePath $npmCommand.Source `
        -ArgumentList @("run", "dev:institution") -WorkingDirectory $root
    $services += Start-ManagedService -Name "User H5" -Port 5174 `
        -HealthUrl "http://127.0.0.1:5174" -FilePath $npmCommand.Source `
        -ArgumentList @("run", "dev:h5") -WorkingDirectory $root

    $allReady = $true
    foreach ($service in $services) {
        if ($service.Reused) {
            Write-Host ("Ready (existing): {0}, port {1}, listener PID {2}" -f $service.Name, $service.Port, $service.ListenerProcessId) -ForegroundColor Green
            continue
        }

        Write-Host ("Checking {0} on port {1}..." -f $service.Name, $service.Port)
        $service.Ready = Wait-ServiceReady -Service $service
        if ($service.Ready) {
            Write-Host ("Ready: {0}, port {1}, listener PID {2}" -f $service.Name, $service.Port, $service.ListenerProcessId) -ForegroundColor Green
        }
        else {
            $allReady = $false
            Write-Host ("Failed: {0} on port {1}" -f $service.Name, $service.Port) -ForegroundColor Red
            Write-Host ("  stdout: {0}" -f $service.StdoutLog)
            Write-Host ("  stderr: {0}" -f $service.StderrLog)
        }
    }

    if (-not $allReady) {
        throw "One or more services failed their health checks."
    }

    Write-Host "All four services are Ready. Press Ctrl+C to stop services started by this run."
    if ($ExitAfterReady) {
        Write-Host "ExitAfterReady requested; running the same cleanup path used by Ctrl+C."
        return
    }
    while ($true) {
        Start-Sleep -Seconds 2
    }
}
finally {
    $ownedServices = @($services | Where-Object { -not $_.Reused })
    $ownedPorts = @($ownedServices | Select-Object -ExpandProperty Port -Unique)

    foreach ($service in $ownedServices) {
        if ($service.ListenerProcessId -ne $null) {
            Stop-JiandaPortProcess -Port $service.Port -Root $root -ExpectedProcessId $service.ListenerProcessId
        }
    }

    foreach ($service in $ownedServices) {
        if ($service.LauncherProcess -and -not $service.LauncherProcess.HasExited) {
            Stop-JiandaProcessTree -ProcessIdValue $service.LauncherProcess.Id
        }
    }

    if ($ownedPorts.Count -gt 0) {
        Write-Host "Waiting for ports started by this run to be released..."
        if (Wait-JiandaPortsReleased -Ports $ownedPorts -TimeoutSeconds 20) {
            Write-Host "Owned service ports released." -ForegroundColor Green
        }
        else {
            Write-Host "Some owned service ports are still in use. Run scripts\stop.ps1 for a verified cleanup." -ForegroundColor Red
        }
    }
}
