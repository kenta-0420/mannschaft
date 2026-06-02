#!/bin/bash
# Spring Boot auto-restart loop (WSL native filesystem)
# Installed to /home/kenta/backend-loop.sh by /陣立て
# Stop: place .stop-backend file in C:\Claude\mannschaft\, or use /撤収

export HOME=/home/kenta
STOP_FLAG="/mnt/c/Claude/mannschaft/.stop-backend"
LOG="/mnt/c/Claude/mannschaft/logs/backend-loop.log"
BACKEND="/home/kenta/mannschaft/backend"

ts() { date '+%Y/%m/%d %H:%M:%S'; }

mkdir -p /mnt/c/Claude/mannschaft/logs

while true; do
    if [ -f "$STOP_FLAG" ]; then
        rm -f "$STOP_FLAG"
        echo "[$(ts)] stop flag detected. halting." >> "$LOG"
        break
    fi

    echo "[$(ts)] starting Spring Boot (WSL)..." >> "$LOG"
    cd "$BACKEND" && git pull --ff-only --quiet 2>/dev/null || true
    cd "$BACKEND" && ./gradlew bootRun --no-daemon >> /home/kenta/bootRun.log 2>&1
    echo "[$(ts)] Spring Boot exited (code: $?)." >> "$LOG"

    if [ -f "$STOP_FLAG" ]; then
        rm -f "$STOP_FLAG"
        echo "[$(ts)] stop flag detected. halting." >> "$LOG"
        break
    fi

    echo "[$(ts)] restarting in 5 seconds..." >> "$LOG"
    sleep 5
done
