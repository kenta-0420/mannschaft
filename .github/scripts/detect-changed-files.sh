#!/usr/bin/env bash
# =============================================================================
# 必須チェック（Compile & Test / OpenAPI Drift Check / ER Diagram Drift Check）の
# 「変更ファイル一覧」を、イベント種別と2点の SHA から取り出す共通スクリプト
# （CMP-261121-1114。GitHub merge queue = merge_group 対応で切り出した）。
#
# 使い方:
#   bash .github/scripts/detect-changed-files.sh <EVENT_NAME> <BASE_SHA> <HEAD_SHA>
#   ※ リポジトリルートで実行すること（git diff の相対パスがずれるため）。
#
# イベントごとの差分の取り方:
#   - pull_request: pull_request.base.sha...pull_request.head.sha（3ドット =
#     merge-base...head）。base が分岐後に進めた変更は含めない（従来どおり）。
#   - merge_group : merge_group.base_sha...merge_group.head_sha。
#     base_sha は queue で先行するエントリの合成コミット（先頭エントリなら対象ブランチの
#     先端）を指すため、この差分は **このエントリ自身の差分** になる（先行エントリの変更は
#     含まない）。先行エントリは各自の merge group で検証済みであり、先行が落ちれば
#     キューが後続のエントリを再構築して検証し直すので、自身の差分だけで判定してよい。
#     base_sha は head の祖先なので 3ドットと2ドットは同じ結果になる。
#   - それ以外（workflow_dispatch / push / schedule 等）: 差分判定の対象外。
#     呼び出し側はフル実行（安全側）にすること。
#
# 改名・削除: --no-renames で改名を「旧パス削除 + 新パス追加」に分解し、旧パスも一覧に出す
# （backend/ から外へ移すだけの改名で backend の検査が skip されないように）。
#
# 標準出力: 変更ファイルのパスを1行1件（終了コード 0 のときだけ）。
# 標準エラー: 判定ログ。
# 終了コード:
#    0 = 一覧を出力した（空のこともある）
#    2 = 判定不能: イベント名・BASE_SHA・HEAD_SHA のいずれかが空
#    3 = 判定不能: git diff が失敗した（SHA が存在しない・履歴不足 等）
#   10 = 差分判定の対象外イベント（フル実行せよ）
# 呼び出し側は 0 以外をすべて「フル実行（migrations_changed=true を含む）」に倒すこと。
# 黙って「変更なし」に倒してはならない。
# =============================================================================
set -uo pipefail

EVENT_NAME="${1:-}"
BASE_SHA="${2:-}"
HEAD_SHA="${3:-}"

if [ -z "${EVENT_NAME}" ]; then
  echo "[ERROR] イベント名が空 → 判定不能（フル実行せよ）" >&2
  exit 2
fi

case "${EVENT_NAME}" in
  pull_request | merge_group) ;;
  *)
    echo "[INFO] ${EVENT_NAME} は差分判定の対象外イベント → フル実行せよ" >&2
    exit 10
    ;;
esac

if [ -z "${BASE_SHA}" ] || [ -z "${HEAD_SHA}" ]; then
  echo "[ERROR] ${EVENT_NAME}: BASE_SHA='${BASE_SHA}' HEAD_SHA='${HEAD_SHA}' のいずれかが空 → 判定不能（フル実行せよ）" >&2
  exit 2
fi

echo "[INFO] ${EVENT_NAME}: git diff --name-only --no-renames ${BASE_SHA}...${HEAD_SHA}" >&2
if ! CHANGED=$(git diff --name-only --no-renames "${BASE_SHA}...${HEAD_SHA}"); then
  echo "[ERROR] git diff が失敗した → 判定不能（フル実行せよ）" >&2
  exit 3
fi

{
  echo "----- changed files -----"
  echo "${CHANGED}"
  echo "-------------------------"
} >&2

if [ -n "${CHANGED}" ]; then
  printf '%s\n' "${CHANGED}"
fi
exit 0
