#!/usr/bin/env bash
# =============================================================================
# API スモークチェック — seed データが Hibernate 経由で正常に読み書きできるかを確認する。
# seed-e2e-data.js で投入したデータを実際の API 経由で取得し、HTTP 200 を確認する。
# =============================================================================
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
echo "=== API Smoke Check: $BASE_URL ==="

# jq が使えない場合は Python3 でフォールバック
extract_json() {
  local json="$1"
  local key="$2"
  if command -v jq &> /dev/null; then
    echo "$json" | jq -r "$key // empty"
  else
    python3 -c "
import json, sys
try:
    data = json.loads(sys.argv[1])
    keys = sys.argv[2].lstrip('.').split('.')
    for k in keys:
        data = data[k]
    print(data if data is not None else '')
except Exception:
    print('')
" "$json" "$key"
  fi
}

# --- ステップ 1: ログイン ---
echo "→ POST /api/v1/auth/login"
LOGIN_HTTP_CODE=""
LOGIN_RESPONSE=$(curl -s -o /tmp/login_body.txt -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"e2e-user@test.mannschaft.local","password":"TestPass2026!"}' \
  2>/tmp/login_err.txt) || true
LOGIN_HTTP_CODE="$LOGIN_RESPONSE"
LOGIN_BODY=$(cat /tmp/login_body.txt 2>/dev/null || echo "")
if [ "$LOGIN_HTTP_CODE" != "200" ]; then
  echo "❌ FAIL: ログインリクエスト失敗（HTTP $LOGIN_HTTP_CODE）"
  echo "レスポンスボディ: $LOGIN_BODY"
  cat /tmp/login_err.txt 2>/dev/null || true
  exit 1
fi
LOGIN_RESPONSE="$LOGIN_BODY"

# レスポンスは ApiResponse<LoginResponse> = { "data": { "accessToken": "...", ... } }
ACCESS_TOKEN=$(extract_json "$LOGIN_RESPONSE" ".data.accessToken")
if [ -z "$ACCESS_TOKEN" ]; then
  echo "❌ FAIL: ログイン失敗またはアクセストークンが取得できません"
  echo "レスポンス: $LOGIN_RESPONSE"
  exit 1
fi
echo "✅ ログイン成功（トークン取得済み）"

# --- ステップ 2: チーム public_id を取得（MySQL クライアント経由）---
# PR #1358 以降、TeamScheduleController は {teamPublicId}（UUID）を受け取るため
# BIGINT id ではなく BIN_TO_UUID(public_id) を使用する（MySQL 8.0+）
TEAM_PUBLIC_ID=""
if command -v mysql &> /dev/null; then
  TEAM_PUBLIC_ID=$(mysql -h 127.0.0.1 -u mannschaft -pmannschaft mannschaft \
    -N -e "SELECT BIN_TO_UUID(public_id) FROM teams WHERE name LIKE 'FC東京U-18%' AND public_id IS NOT NULL LIMIT 1" 2>/dev/null || echo "")
  TEAM_PUBLIC_ID=$(echo "$TEAM_PUBLIC_ID" | tr -d '[:space:]')
fi

if [ -z "$TEAM_PUBLIC_ID" ]; then
  echo "⚠️  MySQL クライアントが使えないか、チームの public_id が見つかりませんでした。schedules チェックをスキップします"
else
  echo "→ GET /api/v1/teams/$TEAM_PUBLIC_ID/schedules"
  STATUS=$(curl -sf -o /dev/null -w "%{http_code}" \
    "$BASE_URL/api/v1/teams/$TEAM_PUBLIC_ID/schedules?from=2026-01-01T00:00:00&to=2026-12-31T23:59:59" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    2>/dev/null || echo "000")
  if [ "$STATUS" != "200" ]; then
    echo "❌ FAIL: GET /api/v1/teams/$TEAM_PUBLIC_ID/schedules → HTTP $STATUS"
    curl -s "$BASE_URL/api/v1/teams/$TEAM_PUBLIC_ID/schedules?from=2026-01-01T00:00:00&to=2026-12-31T23:59:59" \
      -H "Authorization: Bearer $ACCESS_TOKEN" | head -c 2000 || true
    echo ""
    exit 1
  fi
  echo "✅ GET /api/v1/teams/$TEAM_PUBLIC_ID/schedules → 200"
fi

# --- ステップ 3: 通知 API（Hibernate enum 変換の検証）---
# notifications テーブルには notification_type として SCHEDULE_CREATED 等の enum が入っており、
# Hibernate の @Enumerated(EnumType.STRING) 経由で変換される。不正な enum 値があれば HTTP 500 になる。
echo "→ GET /api/v1/notifications"
NOTIF_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/v1/notifications" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  2>/dev/null || echo "000")
if [ "$NOTIF_STATUS" != "200" ]; then
  echo "❌ FAIL: GET /api/v1/notifications → HTTP $NOTIF_STATUS"
  curl -s "$BASE_URL/api/v1/notifications" \
    -H "Authorization: Bearer $ACCESS_TOKEN" | head -c 2000 || true
  echo ""
  exit 1
fi
echo "✅ GET /api/v1/notifications → 200"

# --- ステップ 4: チャットチャンネル一覧（channel_type enum 検証）---
# chat_channels の channel_type カラムに TEAM_PUBLIC / ORG_PUBLIC が入っている。
# 過去に TEAM / ORGANIZATION という不正値を投入してしまい 500 になった経緯があるため、
# このチェックが最も重要な smoke テストとなっている。
# エンドポイント: GET /api/v1/chat/channels（ログインユーザーが属するチャンネル一覧）
echo "→ GET /api/v1/chat/channels"
CHANNEL_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/v1/chat/channels" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  2>/dev/null || echo "000")
if [ "$CHANNEL_STATUS" != "200" ]; then
  echo "❌ FAIL: GET /api/v1/chat/channels → HTTP $CHANNEL_STATUS"
  curl -s "$BASE_URL/api/v1/chat/channels" \
    -H "Authorization: Bearer $ACCESS_TOKEN" | head -c 2000 || true
  echo ""
  exit 1
fi
echo "✅ GET /api/v1/chat/channels → 200"

echo ""
echo "✅ All smoke checks passed"
