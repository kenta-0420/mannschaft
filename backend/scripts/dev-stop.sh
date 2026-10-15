#!/bin/bash
# 開発環境終了スクリプト
# Usage: bash scripts/dev-stop.sh

set -e

echo "=== Docker コンテナ停止 ==="
wsl -d Ubuntu-24.04 -- bash -c "cd /mnt/c/cloudeProject && docker compose stop"

echo ""
echo "=== WSL2 シャットダウン ==="
wsl --shutdown

echo ""
echo "開発環境を終了しました。"
