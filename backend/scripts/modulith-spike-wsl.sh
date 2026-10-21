#!/usr/bin/env bash
# 捨てブランチのWSL試験入口。外側のWindows turnstileからのみ呼ぶ。
set -euo pipefail
cd "$(dirname "$0")/.."
exec bash ./gradlew --project-cache-dir /tmp/mannschaft-modulith-4022-gradle "$@" --console=plain