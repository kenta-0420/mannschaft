# Spring Boot auto-restart loop (WSL native filesystem)
# Stop: place .stop-backend file in root, or use /撤収

$root     = 'C:\Claude\mannschaft'
$logFile  = "$root\logs\backend-loop.log"
$stopFlag = "$root\.stop-backend"

if (-not (Test-Path "$root\logs")) { New-Item -ItemType Directory -Path "$root\logs" | Out-Null }

# 多重起動防止: 名前付きミューテックスで1プロセスのみ許可
$mutex = New-Object System.Threading.Mutex($false, "MannschaftBackendLoop")
if (-not $mutex.WaitOne(0)) {
    Add-Content $logFile "[$(Get-Date -Format 'yyyy/MM/dd HH:mm:ss')] already running. duplicate start blocked."
    exit 1
}

# 再起動ループは WSL 内の /home/kenta/backend-loop.sh に委譲
# bash -c 経由で呼ぶことで PowerShell→wsl.exe のパス変換問題を回避
$proc = Start-Process -FilePath "wsl.exe" `
    -ArgumentList "-d","Ubuntu-24.04","--","bash","-c","/home/kenta/backend-loop.sh" `
    -WindowStyle Hidden `
    -PassThru
$proc.WaitForExit()
