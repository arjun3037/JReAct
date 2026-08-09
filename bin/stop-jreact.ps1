<#
.SYNOPSIS
    Stops the JReact Spring Boot server.

.DESCRIPTION
    Finds whatever process is listening on the app's port (default 8080)
    and stops it. Targets that one process specifically rather than
    killing every java.exe on the machine, so it won't touch an IDE's
    language server, another Java app, etc.

.PARAMETER Port
    Port the app is listening on. Defaults to 8080 (see application.yml).

.EXAMPLE
    .\bin\stop-jreact.ps1
    .\bin\stop-jreact.ps1 -Port 8081
#>
param(
    [int]$Port = 8080
)

$connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1

if (-not $connection) {
    Write-Host "Nothing listening on port $Port - JReact doesn't appear to be running."
    exit 0
}

$processId = $connection.OwningProcess
$process = Get-Process -Id $processId -ErrorAction SilentlyContinue

if (-not $process) {
    Write-Host "Found a listener on port $Port but couldn't resolve its process (PID $processId)."
    exit 1
}

Write-Host "Stopping $($process.ProcessName) (PID $processId) listening on port $Port..."
Stop-Process -Id $processId -Force
Write-Host "Stopped."
