#!/usr/bin/env bash
#
# F08.9 P5 (継続課金) §11-3 Stripe PoC
# 検証命題: Subscription (destination charge / transfer_data.destination + on_behalf_of) の
#           更新サイクル invoice に対し、invoice.created の draft 窓で
#           application_fee_amount を「固定値」に上書きできるか。
#
# このスクリプトは TEST MODE 専用。実行には sk_test_ で始まる Stripe テストキーが必要。
#   export STRIPE_SECRET_KEY='sk_test_'<あなたのテストキー>   # sk_test_ で始まる test mode キー
#   bash scripts/poc/f089_p5_invoice_fee_override_poc.sh
#
# 鉄則:
#   - キーは環境変数からのみ受け取り、echo / ログ / ファイルへ一切書き出さない。
#   - Windows Git Bash で動くこと(bash + curl + jq のみ依存)。
#   - 後始末は test clock 削除で連鎖(顧客/サブスク/invoice)削除される。
#
# 詳細・机上精査の出典は scripts/poc/README_f089_p5_poc.md を参照。
#
set -euo pipefail

# ---------------------------------------------------------------------------
# 0. 前提チェック
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${SCRIPT_DIR}/out"
mkdir -p "${OUT_DIR}"

die() { echo "ERROR: $*" >&2; exit 1; }

[ -n "${STRIPE_SECRET_KEY:-}" ] || die "環境変数 STRIPE_SECRET_KEY が未設定です。export STRIPE_SECRET_KEY='sk_test_'<テストキー> を実行してください。"
case "${STRIPE_SECRET_KEY}" in
  sk_test_*|rk_test_*) : ;;  # テストキーのみ許可
  *) die "STRIPE_SECRET_KEY が test キー(sk_test_/rk_test_)ではありません。本番キーでの実行は禁止です。" ;;
esac

command -v curl >/dev/null 2>&1 || die "curl が見つかりません。"
command -v jq   >/dev/null 2>&1 || die "jq が見つかりません。Git Bash には別途インストールが必要です(https://jqlang.github.io/jq/)。"

API="https://api.stripe.com/v1"
STAMP="$(date +%Y%m%d_%H%M%S)"
RUN_DIR="${OUT_DIR}/run_${STAMP}"
mkdir -p "${RUN_DIR}"

# 検証用の「素数的」固定手数料。face=1000 の 5%=50 とは敢えてズラし、
# fee_policy 算出値が「そのまま」通ることを判別可能にする。
FIXED_FEE_AMOUNT=53
PRICE_UNIT_AMOUNT=1000          # 月額 ¥1,000 (JPY は最小単位=1円なので 1000 = ¥1,000)
CURRENCY=jpy
ACCOUNT_COUNTRY=JP             # connected account / price を JPY に揃える

# ---------------------------------------------------------------------------
# Stripe API ラッパ
#   - -u "<key>:" で Basic 認証(キーはプロセス引数に渡るが stdout/ファイルには出さない)
#   - レスポンス本文を ${RUN_DIR}/<label>.json に保存
#   - HTTP ステータスを別途取得し 4xx/5xx は呼び出し側で判定可能にする
# ---------------------------------------------------------------------------
# 使い方: api <label> <http_method> <path> [curl -d 引数...]
api() {
  local label="$1"; shift
  local method="$1"; shift
  local path="$1"; shift
  local outfile="${RUN_DIR}/${label}.json"
  local httpfile="${RUN_DIR}/${label}.http"

  # --write-out で HTTP コードを別ファイルへ。本文は outfile へ。
  # キーは -u 経由のみ。set -x は使わない(キー漏洩防止)。
  # Stripe-Version は本番 SDK (stripe-java 28.2.0) の固定版 2025-02-24.acacia に合わせる。
  # アカウント既定の最新版(basil 以降)では invoice.application_fee_amount /
  # transfer_data / charge が存在せず 200 黙殺になる(2026-06-05 実走で発覚)。
  curl -sS -o "${outfile}" -w '%{http_code}' \
    -X "${method}" \
    -u "${STRIPE_SECRET_KEY}:" \
    -H "Stripe-Version: ${STRIPE_API_VERSION:-2025-02-24.acacia}" \
    "${API}${path}" "$@" > "${httpfile}" || true

  local code; code="$(cat "${httpfile}")"
  echo "[api] ${label}: ${method} ${path} -> HTTP ${code}" >&2
  if [ "${code}" -ge 400 ]; then
    echo "      レスポンス: $(jq -c '.error // .' "${outfile}" 2>/dev/null || cat "${outfile}")" >&2
  fi
  # 呼び出し側が jq できるよう outfile のパスを返す
  echo "${outfile}"
}

jqr() { jq -r "$2" "$1" 2>/dev/null; }   # jqr <file> <filter>

# PASS/FAIL 集計
declare -a RESULT_NAMES=()
declare -a RESULT_STATUS=()
declare -a RESULT_DETAIL=()
record() {  # record <name> <PASS|FAIL|INFO> <detail>
  RESULT_NAMES+=("$1"); RESULT_STATUS+=("$2"); RESULT_DETAIL+=("$3")
  echo ">>> [$2] $1 — $3" >&2
}

echo "=== F08.9 P5 §11-3 invoice 固定手数料 × destination charge PoC ==="
echo "    出力先: ${RUN_DIR}"
echo "    固定手数料(判別用): ${FIXED_FEE_AMOUNT} / 月額: ${PRICE_UNIT_AMOUNT} ${CURRENCY}"
echo

# ===========================================================================
# 1. test clock 作成 → clock 付き customer → テスト PM attach
# ===========================================================================
NOW="$(date +%s)"
f="$(api clock_create POST "/test_helpers/test_clocks" \
      -d "frozen_time=${NOW}" \
      -d "name=F089_P5_invoice_fee_override")"
CLOCK_ID="$(jqr "$f" '.id')"
[ -n "${CLOCK_ID}" ] && [ "${CLOCK_ID}" != "null" ] || die "test clock 作成に失敗。"
echo "test_clock: ${CLOCK_ID}"

# clock 付き customer
f="$(api customer_create POST "/customers" \
      --data-urlencode "email=p5-poc+${STAMP}@example.com" \
      -d "test_clock=${CLOCK_ID}")"
CUSTOMER_ID="$(jqr "$f" '.id')"
[ -n "${CUSTOMER_ID}" ] && [ "${CUSTOMER_ID}" != "null" ] || die "customer 作成に失敗。"
echo "customer:   ${CUSTOMER_ID}"

# テスト PaymentMethod を作成して attach + default 設定
# 注: pm_card_visa は確認用トークン。test mode でそのまま attach 可能。
f="$(api pm_create POST "/payment_methods" \
      -d "type=card" \
      -d "card[token]=tok_visa")"
PM_ID="$(jqr "$f" '.id')"
[ -n "${PM_ID}" ] && [ "${PM_ID}" != "null" ] || die "payment_method 作成に失敗。"

api pm_attach POST "/payment_methods/${PM_ID}/attach" \
  -d "customer=${CUSTOMER_ID}" >/dev/null

api customer_set_default POST "/customers/${CUSTOMER_ID}" \
  -d "invoice_settings[default_payment_method]=${PM_ID}" >/dev/null
echo "payment_method: ${PM_ID} (attached + default)"
echo

# ===========================================================================
# 2. Connect カスタムテスト口座作成 (charges_enabled / transfers になるテストデータ)
# ===========================================================================
# JP の test mode は「最小 create → currently_due どおり update」の二段が確実。
# 注意: Windows Git Bash の curl は --data-urlencode でも UTF-8/`+` が化けて
#       Stripe が一般 400 を返す（実証済み 2026-06-05）。日本語/記号を含むボディは
#       必ず python の urllib.parse.urlencode で生成して --data @file で送る。
# EXISTING_CONNECT_ACCOUNT 環境変数があれば作成をスキップして再利用する。
if [ -n "${EXISTING_CONNECT_ACCOUNT:-}" ]; then
  CONNECT_ID="${EXISTING_CONNECT_ACCOUNT}"
  echo "connected account: ${CONNECT_ID} (EXISTING_CONNECT_ACCOUNT で再利用)"
else
  f="$(api connect_create POST "/accounts" \
        -d "type=custom" \
        -d "country=${ACCOUNT_COUNTRY}" \
        --data-urlencode "email=p5-connect+${STAMP}@example.com" \
        -d "business_type=individual" \
        -d "capabilities[card_payments][requested]=true" \
        -d "capabilities[transfers][requested]=true" \
        -d "tos_acceptance[date]=${NOW}" \
        -d "tos_acceptance[ip]=127.0.0.1")"
  CONNECT_ID="$(jqr "$f" '.id')"
  [ -n "${CONNECT_ID}" ] && [ "${CONNECT_ID}" != "null" ] || die "connected account 作成に失敗。"
  echo "connected account: ${CONNECT_ID} (最小 create 完了 → KYC update)"

  # currently_due の JP 項目を python で正しく urlencode して一括 update
  KYC_BODY="${RUN_DIR}/connect_kyc_body.txt"
  python - "$STAMP" > "${KYC_BODY}" <<'PYEOF'
import sys, urllib.parse
stamp = sys.argv[1]
fields = {
    'business_profile[mcc]': '7941',  # 会費徴収のスポーツクラブ相当
    'business_profile[product_description]': 'PoC sports club membership',
    'business_profile[url]': 'https://accessible.stripe.com',
    'individual[email]': f'p5-connect+{stamp}@example.com',
    'individual[first_name_kana]': 'タロウ',
    'individual[last_name_kana]': 'ヤマダ',
    'individual[first_name_kanji]': '太郎',
    'individual[last_name_kanji]': '山田',
    'individual[dob][day]': '1',
    'individual[dob][month]': '1',
    'individual[dob][year]': '1901',
    'individual[phone]': '+819012345678',
    'individual[address_kana][postal_code]': '1500001',
    'individual[address_kana][state]': 'トウキヨウト',
    'individual[address_kana][city]': 'シブヤク',
    'individual[address_kana][town]': 'ジングウマエ 3-',
    'individual[address_kana][line1]': '23-4',
    'individual[address_kanji][postal_code]': '1500001',
    'individual[address_kanji][state]': '東京都',
    'individual[address_kanji][city]': '渋谷区',
    'individual[address_kanji][town]': '神宮前　３丁目',
    'individual[address_kanji][line1]': '２３－４',
    'external_account[object]': 'bank_account',
    'external_account[country]': 'JP',
    'external_account[currency]': 'jpy',
    'external_account[routing_number]': '1100000',
    'external_account[account_number]': '0001234',
    'external_account[account_holder_name]': 'ヤマダ タロウ',
}
print(urllib.parse.urlencode(fields))
PYEOF
  f="$(api connect_kyc POST "/accounts/${CONNECT_ID}" --data "@${KYC_BODY}")"
  [ "$(jqr "$f" '.id')" = "${CONNECT_ID}" ] || die "connected account KYC update に失敗。"
fi

# capability の確認 (transfers が active になっているか)
f="$(api connect_get GET "/accounts/${CONNECT_ID}")"
CHARGES_ENABLED="$(jqr "$f" '.charges_enabled')"
TRANSFERS_CAP="$(jqr "$f" '.capabilities.transfers')"
echo "  charges_enabled=${CHARGES_ENABLED} / capabilities.transfers=${TRANSFERS_CAP}"
if [ "${CHARGES_ENABLED}" = "true" ]; then
  record "Connectテスト口座 charges_enabled" "PASS" "charges_enabled=true / transfers=${TRANSFERS_CAP}"
else
  record "Connectテスト口座 charges_enabled" "INFO" "charges_enabled=${CHARGES_ENABLED}。test data 要見直し(JP は magic 値が US と異なる場合あり)。transfers=${TRANSFERS_CAP}"
fi
echo

# ===========================================================================
# 3. product / price (月額) 作成
# ===========================================================================
f="$(api product_create POST "/products" \
      -d "name=F089 P5 PoC Monthly Membership")"
PRODUCT_ID="$(jqr "$f" '.id')"

f="$(api price_create POST "/prices" \
      -d "product=${PRODUCT_ID}" \
      -d "unit_amount=${PRICE_UNIT_AMOUNT}" \
      -d "currency=${CURRENCY}" \
      -d "recurring[interval]=month")"
PRICE_ID="$(jqr "$f" '.id')"
[ -n "${PRICE_ID}" ] && [ "${PRICE_ID}" != "null" ] || die "price 作成に失敗。"
echo "product: ${PRODUCT_ID} / price: ${PRICE_ID}"
echo

# ===========================================================================
# 4. subscription 作成
#    transfer_data.destination + on_behalf_of + default_payment_method
#    + collection_method=charge_automatically
#    安全側既定として application_fee_percent も併設(初回 invoice 対策の案 a)。
# ===========================================================================
f="$(api sub_create POST "/subscriptions" \
      -d "customer=${CUSTOMER_ID}" \
      -d "items[0][price]=${PRICE_ID}" \
      -d "collection_method=charge_automatically" \
      -d "default_payment_method=${PM_ID}" \
      -d "on_behalf_of=${CONNECT_ID}" \
      -d "transfer_data[destination]=${CONNECT_ID}" \
      -d "application_fee_percent=5" \
      -d "expand[]=latest_invoice" \
      -d "expand[]=latest_invoice.charge")"
SUB_ID="$(jqr "$f" '.id')"
SUB_STATUS="$(jqr "$f" '.status')"
[ -n "${SUB_ID}" ] && [ "${SUB_ID}" != "null" ] || die "subscription 作成に失敗。"
echo "subscription: ${SUB_ID} (status=${SUB_STATUS})"
echo

# ===========================================================================
# 5. 初回 invoice の観察 (上書き窓が無い = 失敗を期待値として記録)
# ===========================================================================
FIRST_INVOICE_ID="$(jqr "$f" '.latest_invoice.id')"
if [ -z "${FIRST_INVOICE_ID}" ] || [ "${FIRST_INVOICE_ID}" = "null" ]; then
  f="$(api sub_get GET "/subscriptions/${SUB_ID}")"
  FIRST_INVOICE_ID="$(jqr "$f" '.latest_invoice')"
fi
echo "初回 invoice: ${FIRST_INVOICE_ID}"

if [ -n "${FIRST_INVOICE_ID}" ] && [ "${FIRST_INVOICE_ID}" != "null" ]; then
  f="$(api first_invoice_get GET "/invoices/${FIRST_INVOICE_ID}")"
  FI_STATUS="$(jqr "$f" '.status')"
  FI_REASON="$(jqr "$f" '.billing_reason')"
  FI_FEE="$(jqr "$f" '.application_fee_amount')"
  echo "  status=${FI_STATUS} / billing_reason=${FI_REASON} / application_fee_amount=${FI_FEE}"

  # 初回 invoice への上書き試行。命題どおりなら draft でなく finalize 済みで失敗するはず。
  f="$(api first_invoice_update_try POST "/invoices/${FIRST_INVOICE_ID}" \
        -d "application_fee_amount=${FIXED_FEE_AMOUNT}")"
  UPD_HTTP="$(cat "${RUN_DIR}/first_invoice_update_try.http")"
  if [ "${UPD_HTTP}" -ge 400 ]; then
    record "初回invoice上書き(期待:不可)" "PASS" "billing_reason=${FI_REASON} status=${FI_STATUS} で update が HTTP ${UPD_HTTP} 拒否。命題どおり初回は窓無し → 回避策(案a/b)が必要。"
  else
    UPD_FEE="$(jqr "${RUN_DIR}/first_invoice_update_try.json" '.application_fee_amount')"
    record "初回invoice上書き(期待:不可)" "INFO" "status=${FI_STATUS} で update が HTTP ${UPD_HTTP} 成功(fee=${UPD_FEE})。初回が draft のまま=想定外、または trial 等で窓があった可能性。要精査。"
  fi
else
  record "初回invoice観察" "INFO" "latest_invoice が取得できず。trial 即時化等の可能性。"
fi
echo

# ===========================================================================
# 6. test clock を「period_end + 5分」へ advance → 更新サイクル invoice (draft) を捕まえる
# ===========================================================================
# 重要（2026-06-05 実走の教訓）: +32日など draft 窓(約1時間)を一気に飛び越える advance を
# すると、更新 invoice は clock 時間内で finalize/paid まで進んでしまい draft を観測できない。
# current_period_end の直後（+5分）へ狙い撃ちで進め、finalize 前の draft を捕まえる。
f="$(api sub_get_period GET "/subscriptions/${SUB_ID}")"
PERIOD_END="$(jqr "$f" '.current_period_end')"
if [ -z "${PERIOD_END}" ] || [ "${PERIOD_END}" = "null" ]; then
  # API バージョンにより current_period_end が subscription item 側にある場合のフォールバック
  PERIOD_END="$(jqr "$f" '.items.data[0].current_period_end')"
fi
[ -n "${PERIOD_END}" ] && [ "${PERIOD_END}" != "null" ] || die "current_period_end が取得できない。"
ADVANCE_TO=$(( PERIOD_END + 300 ))
echo "advance 目標: period_end(${PERIOD_END}) + 300s = ${ADVANCE_TO}"
api clock_advance POST "/test_helpers/test_clocks/${CLOCK_ID}/advance" \
  -d "frozen_time=${ADVANCE_TO}" >/dev/null

# advance は非同期 (status: advancing -> ready)。ready までポーリング。
echo "test clock advance 中... (status ready を待機)"
CLOCK_STATUS=""
for i in $(seq 1 30); do
  f="$(api "clock_poll_${i}" GET "/test_helpers/test_clocks/${CLOCK_ID}")"
  CLOCK_STATUS="$(jqr "$f" '.status')"
  echo "  [${i}] clock status=${CLOCK_STATUS}"
  [ "${CLOCK_STATUS}" = "ready" ] && break
  sleep 3
done
[ "${CLOCK_STATUS}" = "ready" ] || record "clock advance" "INFO" "30回ポーリングしても ready にならず(status=${CLOCK_STATUS})。ネットワーク/レート要確認。"

# 更新サイクル invoice (billing_reason=subscription_cycle, status=draft) を探す。
# advance 直後は invoice が draft で生成され ~1h は auto_advance で draft 維持される想定。
CYCLE_INVOICE_ID=""
for i in $(seq 1 20); do
  f="$(api "cycle_inv_list_${i}" GET "/invoices?subscription=${SUB_ID}&limit=10")"
  # subscription_create でない最新を拾う
  CYCLE_INVOICE_ID="$(jq -r \
    '.data | map(select(.billing_reason=="subscription_cycle")) | sort_by(.created) | last | .id // empty' \
    "$f" 2>/dev/null)"
  if [ -n "${CYCLE_INVOICE_ID}" ] && [ "${CYCLE_INVOICE_ID}" != "null" ]; then
    break
  fi
  sleep 3
done
echo "更新サイクル invoice: ${CYCLE_INVOICE_ID:-<未検出>}"
echo

# ===========================================================================
# 7. draft invoice に application_fee_amount=固定値(53) を update
# ===========================================================================
if [ -n "${CYCLE_INVOICE_ID}" ] && [ "${CYCLE_INVOICE_ID}" != "null" ]; then
  f="$(api cycle_invoice_get GET "/invoices/${CYCLE_INVOICE_ID}")"
  CI_STATUS="$(jqr "$f" '.status')"
  CI_REASON="$(jqr "$f" '.billing_reason')"
  CI_FEE_BEFORE="$(jqr "$f" '.application_fee_amount')"
  echo "  before: status=${CI_STATUS} / reason=${CI_REASON} / application_fee_amount=${CI_FEE_BEFORE}"

  f="$(api cycle_invoice_update POST "/invoices/${CYCLE_INVOICE_ID}" \
        -d "application_fee_amount=${FIXED_FEE_AMOUNT}")"
  UPD_HTTP="$(cat "${RUN_DIR}/cycle_invoice_update.http")"
  CI_FEE_AFTER="$(jqr "$f" '.application_fee_amount')"

  if [ "${UPD_HTTP}" -lt 400 ] && [ "${CI_FEE_AFTER}" = "${FIXED_FEE_AMOUNT}" ]; then
    record "更新invoice draft窓で固定手数料上書き" "PASS" "status=${CI_STATUS} で application_fee_amount=${CI_FEE_AFTER} に上書き成功(HTTP ${UPD_HTTP})。★命題の核心が成立。"
  else
    record "更新invoice draft窓で固定手数料上書き" "FAIL" "update HTTP ${UPD_HTTP} / after fee=${CI_FEE_AFTER}(期待 ${FIXED_FEE_AMOUNT})。status=${CI_STATUS} が draft でない可能性。"
  fi

  # =========================================================================
  # 8. invoice finalize → pay → 最終 charge の application_fee_amount / transfer を assert
  # =========================================================================
  api cycle_invoice_finalize POST "/invoices/${CYCLE_INVOICE_ID}/finalize" >/dev/null || true
  f="$(api cycle_invoice_pay POST "/invoices/${CYCLE_INVOICE_ID}/pay" \
        -d "expand[]=charge" -d "expand[]=charge.balance_transaction")"
  PAY_HTTP="$(cat "${RUN_DIR}/cycle_invoice_pay.http")"
  # expand[]=charge 指定時 .charge はオブジェクトで返る。ID は .charge.id を先に見る
  # （オブジェクトを URL に連結すると charge_get が壊れる・2026-06-05 実走の教訓）。
  CHARGE_ID="$(jqr "$f" '.charge.id')"
  if [ -z "${CHARGE_ID}" ] || [ "${CHARGE_ID}" = "null" ]; then
    CHARGE_ID="$(jqr "$f" '.charge')"
  fi
  echo "  pay HTTP=${PAY_HTTP} / charge=${CHARGE_ID}"

  if [ -n "${CHARGE_ID}" ] && [ "${CHARGE_ID}" != "null" ] && [[ "${CHARGE_ID}" == ch_* ]]; then
    f="$(api charge_get GET "/charges/${CHARGE_ID}?expand[]=transfer&expand[]=application_fee")"
    CH_FEE="$(jqr "$f" '.application_fee_amount')"
    CH_AMOUNT="$(jqr "$f" '.amount')"
    TRANSFER_AMOUNT="$(jqr "$f" '.transfer.amount')"
    DEST="$(jqr "$f" '.transfer.destination')"
    echo "  charge.amount=${CH_AMOUNT} / application_fee_amount=${CH_FEE} / transfer.amount=${TRANSFER_AMOUNT} -> ${DEST}"

    if [ "${CH_FEE}" = "${FIXED_FEE_AMOUNT}" ]; then
      record "最終charge application_fee_amount一致" "PASS" "charge.application_fee_amount=${CH_FEE}=固定値。transfer.amount=${TRANSFER_AMOUNT}(=amount-fee 期待)。destination=${DEST}。"
    else
      record "最終charge application_fee_amount一致" "FAIL" "charge.application_fee_amount=${CH_FEE}(期待 ${FIXED_FEE_AMOUNT})。上書きが charge へ伝播せず。"
    fi
    # transfer.amount = amount - fee の整合(destination charge)
    if [ -n "${CH_AMOUNT}" ] && [ -n "${CH_FEE}" ] && [ -n "${TRANSFER_AMOUNT}" ] \
       && [ "${CH_FEE}" != "null" ] && [ "${TRANSFER_AMOUNT}" != "null" ]; then
      EXPECT_TRANSFER=$(( CH_AMOUNT - CH_FEE ))
      if [ "${TRANSFER_AMOUNT}" = "${EXPECT_TRANSFER}" ]; then
        record "transfer.amount = amount - fee" "PASS" "${TRANSFER_AMOUNT} = ${CH_AMOUNT} - ${CH_FEE}"
      else
        record "transfer.amount = amount - fee" "INFO" "transfer=${TRANSFER_AMOUNT} != ${EXPECT_TRANSFER}。destination charge の手数料控除方式を要確認。"
      fi
    fi
  else
    record "最終charge取得" "INFO" "pay HTTP=${PAY_HTTP} で charge を取得できず。auto_advance 待ちの可能性。"
  fi
else
  record "更新invoice draft窓で固定手数料上書き" "FAIL" "subscription_cycle の draft invoice を検出できず。advance 後の生成タイミング/test clock 制約を要確認。"
fi
echo

# ===========================================================================
# 9. (オプション) pause_collection(behavior=void) → さらに +1ヶ月 advance
#    → void 月は invoice が voided/webhook 無し (§4.5 スキップ設計の整合確認)
# ===========================================================================
api sub_pause_void POST "/subscriptions/${SUB_ID}" \
  -d "pause_collection[behavior]=void" >/dev/null || true

ADVANCE_TO2=$(( ADVANCE_TO + 60*60*24*32 ))
api clock_advance2 POST "/test_helpers/test_clocks/${CLOCK_ID}/advance" \
  -d "frozen_time=${ADVANCE_TO2}" >/dev/null || true

CLOCK_STATUS=""
for i in $(seq 1 30); do
  f="$(api "clock_poll2_${i}" GET "/test_helpers/test_clocks/${CLOCK_ID}")"
  CLOCK_STATUS="$(jqr "$f" '.status')"
  [ "${CLOCK_STATUS}" = "ready" ] && break
  sleep 3
done

f="$(api void_inv_list GET "/invoices?subscription=${SUB_ID}&limit=10")"
VOID_COUNT="$(jq -r '[.data[] | select(.status=="void")] | length' "$f" 2>/dev/null || echo 0)"
NEW_OPEN_COUNT="$(jq -r '[.data[] | select(.status=="open" or .status=="paid")] | length' "$f" 2>/dev/null || echo 0)"
echo "  pause後 void invoice 数=${VOID_COUNT} / open+paid 数=${NEW_OPEN_COUNT}"
if [ "${VOID_COUNT}" -ge 1 ] || [ "${NEW_OPEN_COUNT}" -le 1 ]; then
  record "pause_collection(void)で課金スキップ" "PASS" "void月の invoice は voided もしくは未課金(void=${VOID_COUNT})。§4.5 スキップ設計と整合。"
else
  record "pause_collection(void)で課金スキップ" "INFO" "void invoice を明示検出できず(void=${VOID_COUNT})。advance 幅/タイミングを要確認。"
fi
echo

# ===========================================================================
# 10. 後始末 + PASS/FAIL サマリ
# ===========================================================================
# test clock 削除で customer/subscription/invoice が連鎖削除される。
# connected account は clock 配下ではないので個別削除。
echo "後始末: test clock + connected account を削除..."
api clock_delete DELETE "/test_helpers/test_clocks/${CLOCK_ID}" >/dev/null || true
api connect_delete DELETE "/accounts/${CONNECT_ID}" >/dev/null || true
echo

echo "================================ PoC サマリ ================================"
printf "%-48s | %-6s | %s\n" "検証項目" "結果" "詳細"
printf -- "-------------------------------------------------+--------+--------------------------\n"
overall="PASS"
for idx in "${!RESULT_NAMES[@]}"; do
  printf "%-48s | %-6s | %s\n" "${RESULT_NAMES[$idx]}" "${RESULT_STATUS[$idx]}" "${RESULT_DETAIL[$idx]}"
  if [ "${RESULT_STATUS[$idx]}" = "FAIL" ]; then overall="FAIL"; fi
done
printf -- "-------------------------------------------------+--------+--------------------------\n"
echo "命題総合判定(更新サイクル invoice の固定手数料上書き): ${overall}"
echo "詳細 JSON: ${RUN_DIR}"
echo "==========================================================================="

# 命題の核心(更新invoice上書き + charge伝播)が FAIL なら非ゼロ終了
[ "${overall}" = "PASS" ]
