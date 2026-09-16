#!/usr/bin/env bash
# =============================================================================
# Flyway マイグレーション再生テスト（65クラス・実測で全テスト正味の81.6%）の
# 実行要否を、2点の SHA 間の差分から判定する共通スクリプト。
#
# backend-deploy.yml（main への push。本番デプロイを伴う）から呼ばれる。
# 対象パスの正規表現は .github/scripts/migration-sensitive-paths.regex に
# 一本化してある（backend-ci.yml の判定ステップもこのファイルを参照する。
# 同じ正規表現を2箇所に書くと必ずドリフトするため）。
#
# 引数:
#   $1 = BASE_SHA（比較の起点。呼び出し側が決める。fetch 済みであること）
#   $2 = HEAD_SHA（比較の終点。通常は $GITHUB_SHA）
#
# 標準出力: "true" または "false" のみ（呼び出し側で $GITHUB_OUTPUT に書く）。
# 判定ログは標準エラーへ出す（標準出力を汚さないため）。
#
# 【重要】このスクリプト自体は「与えられた2点の差分」を機械的に判定するだけで、
# 「判定不能なら true に倒す」というフェイルオープン方針は呼び出し側
# （backend-deploy.yml の detect ジョブ）の責務とする。BASE_SHA の取得失敗・
# 祖先関係の検証・fetch 失敗など「差分を安全に取れるか」の判断は呼び出し側で
# 行い、ここには「取得済みの2点」だけを渡すこと。
# =============================================================================
set -uo pipefail

BASE_SHA="${1:?BASE_SHA が指定されていません}"
HEAD_SHA="${2:?HEAD_SHA が指定されていません}"
PATTERN_FILE="$(dirname "$0")/migration-sensitive-paths.regex"

CHANGED=$(git diff --name-only --no-renames "${BASE_SHA}" "${HEAD_SHA}" 2>&1)
DIFF_STATUS=$?

{
  echo "----- git diff --name-only --no-renames ${BASE_SHA} ${HEAD_SHA} -----"
  echo "${CHANGED}"
  echo "-----------------------------------------------------------------"
} >&2

if [ "${DIFF_STATUS}" -ne 0 ]; then
  echo "[ERROR] git diff が失敗した（exit=${DIFF_STATUS}）→ 判定不能" >&2
  exit 2
fi

# パターンファイルが無い・空の場合は「一致なし」ではなく「判定不能」として扱う。
# grep -E -f は対象ファイルが読めないと exit 2 以上を返すが、後段を
# if grep ...; then true; else false; fi の二分岐のままにすると
# exit 2（読めない）が exit 1（一致なし）に潰れ、migrations_changed=false
# （Flyway 再生テストの黙った除外）に誤って倒れてしまう。
# そのためファイルの存在・非空をここで明示的に検証し、無ければ即座に
# 判定不能として異常終了する。
if [ ! -s "${PATTERN_FILE}" ]; then
  echo "[ERROR] パターンファイルが存在しないか空（${PATTERN_FILE}）→ 判定不能" >&2
  exit 3
fi

echo "${CHANGED}" | grep -E -f "${PATTERN_FILE}" > /dev/null
GREP_STATUS=$?

# grep の終了コードは 0（一致あり）/ 1（一致なし）/ 2以上（ファイル読み込み等の
# 実行時エラー）の3種。1 だけを「一致なし」として扱い、2以上は「判定不能」
# として明確に区別する（0/1 の二分岐に潰さない）。
case "${GREP_STATUS}" in
  0)
    echo "[INFO] migration-sensitive な変更を検出" >&2
    echo "true"
    ;;
  1)
    echo "[INFO] migration-sensitive な変更なし" >&2
    echo "false"
    ;;
  *)
    echo "[ERROR] grep 照合が失敗した（exit=${GREP_STATUS}）→ 判定不能" >&2
    exit 4
    ;;
esac
