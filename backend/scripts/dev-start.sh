#!/bin/bash
# 開発環境起動スクリプト
# Usage: bash scripts/dev-start.sh

set -e

echo "=== WSL2 起動 ==="
wsl -d Ubuntu-24.04 -- bash -c "nohup sleep infinity </dev/null &>/dev/null & disown; echo 'WSL2 keepalive started'"

echo ""
echo "=== Docker コンテナ起動 ==="
wsl -d Ubuntu-24.04 -- bash -c "cd /mnt/c/cloudeProject && docker compose up -d"

echo ""
echo "=== MySQL 起動待機（最大60秒）==="
for i in $(seq 1 12); do
  if wsl -d Ubuntu-24.04 -- bash -c "docker exec mannschaft-mysql mysql -u root -proot -e 'SELECT 1'" &>/dev/null; then
    echo "MySQL ready!"
    break
  fi
  if [ "$i" -eq 12 ]; then
    echo "WARNING: MySQL did not become ready within 60 seconds"
    exit 1
  fi
  echo "  waiting... (${i}/12)"
  sleep 5
done

echo ""
echo "=== 接続確認 ==="
powershell.exe -Command "
  \$mysql = (Test-NetConnection -ComputerName localhost -Port 3306 -WarningAction SilentlyContinue).TcpTestSucceeded
  \$valkey = (Test-NetConnection -ComputerName localhost -Port 6379 -WarningAction SilentlyContinue).TcpTestSucceeded
  Write-Host \"MySQL  (3306): \$mysql\"
  Write-Host \"Valkey (6379): \$valkey\"
  if (\$mysql -and \$valkey) { Write-Host '=== All OK ===' }
  else { Write-Host '=== FAILED ===' ; exit 1 }
"

echo ""
echo "開発環境の起動が完了しました。"
