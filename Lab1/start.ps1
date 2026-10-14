# Starts the system in Docker for a demo (Windows):
#   - broker + publisher web UI (http://localhost:3000), opened in the browser
#   - a window with the broker's log
# then turns this window into a prompt where you create receivers while everything runs:
#   add <name> <topics>   e.g.  add carol weather   or   add dave news,sports,weather
#   stop <name>, list, help, quit
# Each receiver opens in its own window.
#
# Usage (from anywhere; or double-click start.cmd):
#   powershell -ExecutionPolicy Bypass -File Lab1\start.ps1                   build, then start
#   powershell -ExecutionPolicy Bypass -File Lab1\start.ps1 -SkipBuild        start without rebuilding
#   powershell -ExecutionPolicy Bypass -File Lab1\start.ps1 -DemoReceivers    also start the demo receivers below
#   powershell -ExecutionPolicy Bypass -File Lab1\start.ps1 -Stop             stop everything

param(
    [switch]$SkipBuild,
    [switch]$DemoReceivers,
    [switch]$Stop
)

$root = $PSScriptRoot
$uiUrl = 'http://localhost:3000'
# Started only with -DemoReceivers. Name is the receiver's id (-c), Topics its comma-separated topics (-t).
$demoReceivers = @(
    @{ Name = 'alice'; Topics = 'news,sports' },
    @{ Name = 'bob';   Topics = 'news' },
    @{ Name = 'carol'; Topics = 'weather' },
    @{ Name = 'dave';  Topics = 'news,sports,weather' }
)

Set-Location $root

# Every receiver container is named pad-receiver-<name>, so they can be found without keeping a list.
function Get-ReceiverContainers {
    docker ps -a --filter 'name=^pad-receiver-' --format '{{.Names}}' 2>$null
}

function Remove-AllReceivers {
    foreach ($container in @(Get-ReceiverContainers)) { if ($container) { docker rm -f $container 2>$null | Out-Null } }
}

function Stop-Everything {
    Write-Host 'Stopping receivers, broker and publisher UI...' -ForegroundColor Cyan
    Remove-AllReceivers
    docker compose --profile clients down --remove-orphans
    Write-Host 'Stopped. You can close the receiver and log windows.' -ForegroundColor Green
}

function Test-Docker {
    docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
}

function Start-DockerDesktop {
    $candidates = @(
        "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe",
        "$env:LOCALAPPDATA\Programs\DockerDesktop\Docker Desktop.exe"
    )
    $exe = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $exe) { return $false }
    Write-Host 'Starting Docker Desktop (this can take a minute)...' -ForegroundColor Cyan
    Start-Process $exe
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 3
        if (Test-Docker) { return $true }
    }
    return $false
}

# Opens a new PowerShell window in this folder that runs $command and stays open afterwards.
# Ctrl+C ends a running PowerShell script, so $command is wrapped in a Start-Receiver function
# that restarts it when typed in that window.
function Open-Window($title, $hint, $command) {
    $script = @"
`$Host.UI.RawUI.WindowTitle = '$title'
Set-Location '$root'
function Start-Receiver { $command }
Write-Host '$hint' -ForegroundColor DarkGray
Start-Receiver
"@
    # -EncodedCommand (base64 UTF-16LE) avoids quoting problems in the arguments.
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))
    Start-Process powershell -ArgumentList '-NoExit', '-NoProfile', '-EncodedCommand', $encoded | Out-Null
}

function Add-Receiver($name, $topics) {
    if (-not $name -or -not $topics) {
        Write-Host 'Usage: add <name> <topics>    e.g.  add carol weather   or   add dave news,sports,weather' -ForegroundColor Yellow
        return
    }
    if ($name -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$') {
        Write-Host "Invalid name '$name': use letters, digits, '.', '_' or '-'." -ForegroundColor Yellow
        return
    }
    if ($topics -notmatch '^[A-Za-z0-9_.-]+(,[A-Za-z0-9_.-]+)*$') {
        Write-Host "Invalid topics '$topics': separate topics with commas and no spaces, e.g. news,sports." -ForegroundColor Yellow
        return
    }
    if (@(Get-ReceiverContainers) -contains "pad-receiver-$name") {
        Write-Host "Receiver '$name' is already running. Use 'stop $name' first." -ForegroundColor Yellow
        return
    }
    $run = "docker compose run --rm --name pad-receiver-$name receiver -t '$topics' -c $name -w 1"
    Open-Window "Receiver $name" "Receiver $name ($topics). Ctrl+C stops it. To start it again (missed messages are replayed), type Start-Receiver and press Enter." $run
    Write-Host "Started receiver '$name' on [$($topics -replace ',', ', ')] in a new window." -ForegroundColor Green
}

function Stop-Receiver($name) {
    if (-not $name) { Write-Host 'Usage: stop <name>' -ForegroundColor Yellow; return }
    if (@(Get-ReceiverContainers) -notcontains "pad-receiver-$name") {
        Write-Host "No running receiver named '$name'. Type 'list' to see them." -ForegroundColor Yellow
        return
    }
    # docker stop sends SIGTERM, so the receiver finishes and ACKs what it already received.
    docker stop "pad-receiver-$name" 2>$null | Out-Null
    Write-Host "Stopped receiver '$name'. The broker keeps its messages until it comes back with the same name." -ForegroundColor Green
}

function Show-Receivers {
    $rows = @(docker ps --filter 'name=^pad-receiver-' --no-trunc --format '{{.Names}}|{{.Command}}' 2>$null | Where-Object { $_ })
    if ($rows.Count -eq 0) { Write-Host 'No receivers running. Create one with: add <name> <topics>'; return }
    foreach ($row in $rows) {
        $name, $command = $row -split '\|', 2
        $topics = if ($command -match "-t\s+'?([^\s'`"]+)") { $Matches[1] -replace ',', ', ' } else { '?' }
        Write-Host ("  {0,-12} {1}" -f ($name -replace '^pad-receiver-', ''), $topics)
    }
}

function Show-Help {
    Write-Host 'Commands:'
    Write-Host '  add <name> <topics>   create a receiver, e.g.  add carol weather   or   add dave news,sports,weather'
    Write-Host '                        (reusing a name gets the messages that receiver missed while it was stopped)'
    Write-Host '  stop <name>           stop a receiver'
    Write-Host '  list                  show running receivers'
    Write-Host '  quit                  stop everything and exit'
}

if ($Stop) {
    Stop-Everything
    exit 0
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host 'Docker is not installed. Install Docker Desktop: https://www.docker.com/products/docker-desktop/' -ForegroundColor Red
    exit 1
}
if (-not (Test-Docker) -and -not (Start-DockerDesktop)) {
    Write-Host 'Docker is not running. Start Docker Desktop, wait for "Engine running", and try again.' -ForegroundColor Red
    exit 1
}

if (-not $SkipBuild) {
    Write-Host 'Building images (the first build takes a few minutes)...' -ForegroundColor Cyan
    docker compose --profile clients build
    if ($LASTEXITCODE -ne 0) { Write-Host 'Build failed.' -ForegroundColor Red; exit 1 }
}

# Leftovers from a previous run would block the container names.
Remove-AllReceivers

Write-Host 'Starting broker and publisher UI...' -ForegroundColor Cyan
docker compose up -d broker publisher-ui
if ($LASTEXITCODE -ne 0) { Write-Host 'Could not start the containers.' -ForegroundColor Red; exit 1 }

$ready = $false
for ($i = 0; $i -lt 30 -and -not $ready; $i++) {
    try {
        Invoke-WebRequest $uiUrl -UseBasicParsing -TimeoutSec 2 | Out-Null
        $ready = $true
    } catch {
        Start-Sleep -Seconds 1
    }
}
if (-not $ready) { Write-Host "The UI did not come up at $uiUrl; check 'docker compose logs publisher-ui'." -ForegroundColor Yellow }

Open-Window 'Broker log' 'Broker log. Closing this window does not stop the broker.' 'docker compose logs -f broker'
Start-Process $uiUrl

Write-Host ''
Write-Host 'Everything is running:' -ForegroundColor Green
Write-Host "  Publisher UI   $uiUrl  (opened in your browser)"
Write-Host '  Broker         localhost:5000  (log in the "Broker log" window)'
Write-Host ''
if ($DemoReceivers) {
    foreach ($receiver in $demoReceivers) { Add-Receiver $receiver.Name $receiver.Topics }
    Write-Host ''
}
Show-Help
Write-Host ''

while ($true) {
    Write-Host '> ' -NoNewline -ForegroundColor Cyan
    $line = [Console]::ReadLine()
    if ($null -eq $line) { break }
    $parts = @($line.Trim() -split '\s+' | Where-Object { $_ })
    if ($parts.Count -eq 0) { continue }
    $verb = $parts[0].ToLowerInvariant()
    if ($verb -in 'quit', 'exit') { break }
    switch ($verb) {
        # Joining the rest also accepts topics typed with spaces: add dave news, sports
        'add'  { Add-Receiver $parts[1] ((@($parts) | Select-Object -Skip 2) -join '') }
        'stop' { Stop-Receiver $parts[1] }
        'list' { Show-Receivers }
        'help' { Show-Help }
        default { Write-Host "Unknown command '$verb'." -ForegroundColor Yellow; Show-Help }
    }
}
Stop-Everything
