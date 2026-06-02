# Spring Boot auto-restart loop (WSL native filesystem)
# Stop: place .stop-backend file in root, or use /撤収

$root     = 'C:\Claude\mannschaft'
$logFile  = "$root\logs\backend-loop.log"
$stopFlag = "$root\.stop-backend"

if (-not (Test-Path "$root\logs")) { New-Item -ItemType Directory -Path "$root\logs" | Out-Null }

while ($true) {
    if (Test-Path $stopFlag) {
        Remove-Item $stopFlag -Force
        Add-Content $logFile "[$(Get-Date -Format 'yyyy/MM/dd HH:mm:ss')] stop flag detected. halting."
        break
    }

    Add-Content $logFile "[$(Get-Date -Format 'yyyy/MM/dd HH:mm:ss')] starting Spring Boot (WSL)..."

    # git pull して最新コードを反映してから起動
    wsl -d Ubuntu-24.04 -- bash -c "cd /home/kenta/mannschaft && git pull --ff-only --quiet 2>/dev/null || true"

    $proc = Start-Process -FilePath "wsl.exe" `
        -ArgumentList "-d","Ubuntu-24.04","--","bash","-c",`
            "cd /home/kenta/mannschaft/backend && ./gradlew bootRun --no-daemon > /home/kenta/bootRun.log 2>&1" `
        -WindowStyle Hidden `
        -PassThru
    $proc.WaitForExit()

    Add-Content $logFile "[$(Get-Date -Format 'yyyy/MM/dd HH:mm:ss')] Spring Boot exited (code: $($proc.ExitCode))."

    if (Test-Path $stopFlag) {
        Remove-Item $stopFlag -Force
        Add-Content $logFile "[$(Get-Date -Format 'yyyy/MM/dd HH:mm:ss')] stop flag detected. halting."
        break
    }

    Add-Content $logFile "[$(Get-Date -Format 'yyyy/MM/dd HH:mm:ss')] restarting in 5 seconds..."
    Start-Sleep -Seconds 5
}
