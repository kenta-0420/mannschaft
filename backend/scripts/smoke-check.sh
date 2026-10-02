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
echo "=== 認可スモークチェック（Wave4: 非メンバー403/未認証401の実機裏取り） ==="
# --------------------------------------------------------------------------
# 位置づけ（重要）:
#   認可回帰の一次防御は必須CI「Compile & Test」内で全実行される *ScopeContractIT
#   （非メンバー403/越境404/正当成功を実MySQL Testcontainersで検証する契約テスト群）
#   ＋ ArchUnit 認可番人である。ここでの認可スモークはあくまで非required workflow
#   （e2e-real-smoke）上での「実際に起動したAPIサーバーへの実機裏取り」であり、
#   flaky要因になり得るためrequired化はしない（詳細: docs/security/README.md）。
#   アサートは単純明快: 保護EPに非メンバーでアクセスして 200 が返ったら認可漏れとしてfailする。
# --------------------------------------------------------------------------

# --- 認可-1: 未認証アクセス → 401 ---
echo "→ (未認証) GET /api/v1/teams/{teamId}/schedules"
if [ -n "$TEAM_PUBLIC_ID" ]; then
  UNAUTH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    "$BASE_URL/api/v1/teams/$TEAM_PUBLIC_ID/schedules?from=2026-01-01T00:00:00&to=2026-12-31T23:59:59" \
    2>/dev/null || echo "000")
  if [ "$UNAUTH_STATUS" != "401" ]; then
    echo "❌ FAIL: 未認証アクセスが 401 以外（HTTP $UNAUTH_STATUS）"
    exit 1
  fi
  echo "✅ 未認証アクセス → 401"
else
  echo "⚠️  TEAM_PUBLIC_ID が未取得のため未認証チェックをスキップします"
fi

# --- 認可-2: 非メンバー（outsider）でログイン ---
echo "→ POST /api/v1/auth/login (e2e-outsider)"
OUTSIDER_LOGIN_HTTP_CODE=$(curl -s -o /tmp/outsider_login_body.txt -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"e2e-outsider@test.mannschaft.local","password":"TestPass2026!"}' \
  2>/tmp/outsider_login_err.txt) || true
OUTSIDER_LOGIN_BODY=$(cat /tmp/outsider_login_body.txt 2>/dev/null || echo "")
if [ "$OUTSIDER_LOGIN_HTTP_CODE" != "200" ]; then
  echo "❌ FAIL: outsider ログインリクエスト失敗（HTTP $OUTSIDER_LOGIN_HTTP_CODE）"
  echo "レスポンスボディ: $OUTSIDER_LOGIN_BODY"
  cat /tmp/outsider_login_err.txt 2>/dev/null || true
  exit 1
fi
OUTSIDER_TOKEN=$(extract_json "$OUTSIDER_LOGIN_BODY" ".data.accessToken")
if [ -z "$OUTSIDER_TOKEN" ]; then
  echo "❌ FAIL: outsider ログイン失敗またはアクセストークンが取得できません"
  echo "レスポンス: $OUTSIDER_LOGIN_BODY"
  exit 1
fi
echo "✅ outsider ログイン成功（非メンバーとしてトークン取得済み）"

# 認可スモークで使う数値ID（team/schedule/organization）を MySQL クライアント経由で取得する。
# member-cards/payment-summary は数値ID（public_id ではない）を要求する。
TEAM_NUMERIC_ID=""
SCHEDULE_ID=""
ORG_NUMERIC_ID=""
if command -v mysql &> /dev/null; then
  TEAM_NUMERIC_ID=$(mysql -h 127.0.0.1 -u mannschaft -pmannschaft mannschaft \
    -N -e "SELECT id FROM teams WHERE name LIKE 'FC東京U-18%' LIMIT 1" 2>/dev/null || echo "")
  TEAM_NUMERIC_ID=$(echo "$TEAM_NUMERIC_ID" | tr -d '[:space:]')

  if [ -n "$TEAM_NUMERIC_ID" ]; then
    SCHEDULE_ID=$(mysql -h 127.0.0.1 -u mannschaft -pmannschaft mannschaft \
      -N -e "SELECT id FROM schedules WHERE team_id = $TEAM_NUMERIC_ID ORDER BY id LIMIT 1" 2>/dev/null || echo "")
    SCHEDULE_ID=$(echo "$SCHEDULE_ID" | tr -d '[:space:]')
  fi

  ORG_NUMERIC_ID=$(mysql -h 127.0.0.1 -u mannschaft -pmannschaft mannschaft \
    -N -e "SELECT id FROM organizations WHERE name LIKE '東京都サッカー協会%' LIMIT 1" 2>/dev/null || echo "")
  ORG_NUMERIC_ID=$(echo "$ORG_NUMERIC_ID" | tr -d '[:space:]')
fi

# 保護EPに非メンバーでアクセスし、200 が返ったら認可漏れとしてfailする。
# 401/403/404（未認証・非メンバー・越境）はいずれも「認可で正しく弾かれた」として許容する。
assert_not_owned_by_outsider() {
  local label="$1"
  local url="$2"
  echo "→ (outsider) GET $url"
  local status
  status=$(curl -s -o /tmp/outsider_ep_body.txt -w "%{http_code}" \
    "$url" -H "Authorization: Bearer $OUTSIDER_TOKEN" 2>/dev/null || echo "000")
  if [ "$status" = "200" ]; then
    echo "❌ FAIL: 認可漏れ検出 — 非メンバーが $label に 200 でアクセスできました"
    cat /tmp/outsider_ep_body.txt 2>/dev/null | head -c 2000 || true
    echo ""
    exit 1
  fi
  case "$status" in
    401|403|404)
      echo "✅ $label → HTTP $status（非メンバーとして正しく拒否）"
      ;;
    *)
      echo "❌ FAIL: $label が予期しないステータス（HTTP $status）"
      cat /tmp/outsider_ep_body.txt 2>/dev/null | head -c 2000 || true
      echo ""
      exit 1
      ;;
  esac
}

if [ -n "$TEAM_PUBLIC_ID" ] && [ -n "$SCHEDULE_ID" ]; then
  assert_not_owned_by_outsider "GET /teams/{teamId}/schedules/{scheduleId}（他チームschedule詳細）" \
    "$BASE_URL/api/v1/teams/$TEAM_PUBLIC_ID/schedules/$SCHEDULE_ID"
else
  echo "⚠️  TEAM_PUBLIC_ID または SCHEDULE_ID が未取得のため schedules 詳細チェックをスキップします"
fi

if [ -n "$ORG_NUMERIC_ID" ]; then
  assert_not_owned_by_outsider "GET /organizations/{orgId}/payment-summary（Wave3-B1bで是正済）" \
    "$BASE_URL/api/v1/organizations/$ORG_NUMERIC_ID/payment-summary"
else
  echo "⚠️  ORG_NUMERIC_ID が未取得のため payment-summary チェックをスキップします"
fi

if [ -n "$TEAM_NUMERIC_ID" ]; then
  assert_not_owned_by_outsider "GET /teams/{teamId}/member-cards（membership・ADMIN限定）" \
    "$BASE_URL/api/v1/teams/$TEAM_NUMERIC_ID/member-cards"
else
  echo "⚠️  TEAM_NUMERIC_ID が未取得のため member-cards チェックをスキップします"
fi

echo ""
echo "✅ All smoke checks passed"
