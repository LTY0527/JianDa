$script:JiandaDevelopmentPorts = @(5173, 5174, 8080, 8001)

function ConvertTo-JiandaNormalizedPath {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return ""
    }
    return ([regex]::Replace($Value.Trim(), '[\\/]+', '/')).TrimEnd("/").ToLowerInvariant()
}

function Get-JiandaPortListeners {
    param([int]$Port)

    $connections = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    $processIds = @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($processIdValue in $processIds) {
        $processInfo = Get-CimInstance Win32_Process -Filter ("ProcessId = " + $processIdValue) -ErrorAction SilentlyContinue
        if ($processInfo) {
            [PSCustomObject]@{
                Port = $Port
                ProcessId = [int]$processIdValue
                Name = [string]$processInfo.Name
                CommandLine = [string]$processInfo.CommandLine
            }
        }
    }
}

function Test-JiandaBackendArgFile {
    param(
        [string]$CommandLine,
        [string]$Root
    )

    $backendPath = ConvertTo-JiandaNormalizedPath (Join-Path $Root "services\backend")
    $matches = [regex]::Matches($CommandLine, '@(?:"([^"]+\.argfile)"|([^\s"]+\.argfile))', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    foreach ($match in $matches) {
        $argFilePath = if ($match.Groups[1].Success) { $match.Groups[1].Value } else { $match.Groups[2].Value }
        if (Test-Path -LiteralPath $argFilePath -PathType Leaf) {
            $argFileContent = ConvertTo-JiandaNormalizedPath ([System.IO.File]::ReadAllText($argFilePath))
            if ($argFileContent.Contains($backendPath)) {
                return $true
            }
        }
    }
    return $false
}

function Test-JiandaProjectProcess {
    param(
        [PSCustomObject]$ProcessInfo,
        [string]$Root
    )

    if (-not $ProcessInfo) {
        return $false
    }

    $commandLine = ConvertTo-JiandaNormalizedPath $ProcessInfo.CommandLine
    $rootPath = ConvertTo-JiandaNormalizedPath $Root
    $name = $ProcessInfo.Name.ToLowerInvariant()
    $portToken = "--port " + $ProcessInfo.Port

    switch ([int]$ProcessInfo.Port) {
        { $_ -eq 5173 -or $_ -eq 5174 } {
            return ($name -eq "node.exe" -and $commandLine.Contains($rootPath) -and $commandLine.Contains("vite") -and $commandLine.Contains($portToken))
        }
        8001 {
            $aiPath = $rootPath + "/services/ai-service/.venv/scripts/python.exe"
            return ($name -eq "python.exe" -and $commandLine.Contains($aiPath) -and $commandLine.Contains("uvicorn") -and $commandLine.Contains($portToken))
        }
        8080 {
            $backendPath = $rootPath + "/services/backend"
            $hasProjectPath = $commandLine.Contains($backendPath) -or (Test-JiandaBackendArgFile -CommandLine $ProcessInfo.CommandLine -Root $Root)
            return ($name -eq "java.exe" -and $commandLine.Contains("cn.jianda.jiandaapplication") -and $hasProjectPath)
        }
        default {
            return $false
        }
    }
}

function Stop-JiandaProcessTree {
    param([int]$ProcessIdValue)

    $children = @(Get-CimInstance Win32_Process -Filter ("ParentProcessId = " + $ProcessIdValue) -ErrorAction SilentlyContinue)
    foreach ($child in $children) {
        Stop-JiandaProcessTree -ProcessIdValue ([int]$child.ProcessId)
    }

    if (Get-Process -Id $ProcessIdValue -ErrorAction SilentlyContinue) {
        Stop-Process -Id $ProcessIdValue -Force -ErrorAction SilentlyContinue
    }
}

function Stop-JiandaPortProcess {
    param(
        [int]$Port,
        [string]$Root,
        [int]$ExpectedProcessId = 0
    )

    $listeners = @(Get-JiandaPortListeners -Port $Port)
    if ($ExpectedProcessId -gt 0) {
        $listeners = @($listeners | Where-Object { $_.ProcessId -eq $ExpectedProcessId })
    }

    foreach ($listener in $listeners) {
        Write-Host ("Port {0}: PID {1}, process {2}" -f $listener.Port, $listener.ProcessId, $listener.Name)
        Write-Host ("Command line: {0}" -f $listener.CommandLine)
        if (-not (Test-JiandaProjectProcess -ProcessInfo $listener -Root $Root)) {
            Write-Host ("Skipped PID {0}: command line is not confirmed as this JianDa project." -f $listener.ProcessId) -ForegroundColor Yellow
            continue
        }

        Write-Host ("Stopping JianDa process tree at PID {0}..." -f $listener.ProcessId)
        Stop-JiandaProcessTree -ProcessIdValue $listener.ProcessId
    }
}

function Wait-JiandaPortsReleased {
    param(
        [int[]]$Ports,
        [int]$TimeoutSeconds = 20
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $busyPorts = @($Ports | Where-Object { @(Get-JiandaPortListeners -Port $_).Count -gt 0 })
        if ($busyPorts.Count -eq 0) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)

    return $false
}
