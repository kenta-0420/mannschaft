#!/usr/bin/env bash
# =============================================================================
# CI テストシャード重み表（backend/src/test/resources/shard-weights.properties）
# の再生成スクリプト
# -----------------------------------------------------------------------------
# 【前提】
#   - gh CLI が必須（`gh auth login` 済みであること）。
#   - python3 が必須（標準ライブラリのみ使用。追加依存なし）。
#   - backend-ci.yml の shard ジョブが直近で成功していること
#     （test-results-xml-* artifact は retention-days: 7 のため、
#      7日以内に成功した run から取得すること）。
#
# 【やること】
#   1. backend-ci.yml の直近の成功 run を 1 つ選ぶ（引数で run ID を指定可能。
#      未指定なら最新の成功 run を自動選択する）。
#   2. その run の 6 shard 分すべての test-results-xml-*（JUnit XML）artifact を
#      ダウンロードする。
#   3. 各 XML の testsuite の time 属性を、ファイル名から復元した完全修飾クラス名
#      （ネストクラスは "$" より前のトップレベル名に集約）単位で合算する。
#   4. backend/src/test/resources/shard-weights.properties を上書き生成する。
#
# 【再生成タイミングの目安】
#   - テストクラスが大幅に増減した（数十クラス規模の追加/削除があった）とき。
#   - 特定 shard が恒常的に他より大きく偏るようになったとき
#     （= 重み表が古くなり、新規/削除されたテストの実行時間を反映できていない）。
#   - 通常の数クラス程度の増減では、build.gradle.kts のフォールバック
#     （安定ハッシュ）で自動的に吸収されるため、都度の再生成は不要。
#
# 【使い方】
#   cd backend
#   ./scripts/regen-shard-weights.sh                # 最新の成功 run を自動選択
#   ./scripts/regen-shard-weights.sh <RUN_ID>        # run ID を明示指定
#
#   生成後は差分を確認し、コミットすること（ヘッダーコメントの生成元 run 情報も
#   このスクリプトが自動更新する）。
# =============================================================================
set -euo pipefail

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUT_FILE="${BACKEND_DIR}/src/test/resources/shard-weights.properties"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

RUN_ID="${1:-}"
if [ -z "${RUN_ID}" ]; then
  echo "[INFO] run ID 未指定 → backend-ci.yml の直近成功 run を自動選択する"
  RUN_ID="$(gh run list -R "${REPO}" --workflow=backend-ci.yml --status=success --limit 1 --json databaseId -q '.[0].databaseId')"
  if [ -z "${RUN_ID}" ]; then
    echo "[ERROR] 直近の成功 run が見つからない。run ID を明示指定してください。" >&2
    exit 1
  fi
fi
echo "[INFO] 対象 run: ${RUN_ID}"

RUN_META="$(gh run view "${RUN_ID}" -R "${REPO}" --json headBranch,createdAt,conclusion,displayTitle,number)"
HEAD_BRANCH="$(echo "${RUN_META}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["headBranch"])')"
CREATED_AT="$(echo "${RUN_META}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["createdAt"])')"
CONCLUSION="$(echo "${RUN_META}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["conclusion"])')"
PR_NUMBER="$(echo "${RUN_META}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("number",""))')"

if [ "${CONCLUSION}" != "success" ]; then
  echo "[WARN] 選択した run の conclusion は '${CONCLUSION}' であり success ではない。" >&2
  echo "[WARN] 失敗 shard のテスト時間が欠落する可能性がある。続行するが確認すること。" >&2
fi

for n in 0 1 2 3 4 5; do
  ARTIFACT_NAME="$(gh api "repos/${REPO}/actions/runs/${RUN_ID}/artifacts" --jq ".artifacts[] | select(.name | startswith(\"test-results-xml-\") and endswith(\"-shard-${n}\")) | .name" | head -1)"
  if [ -z "${ARTIFACT_NAME}" ]; then
    echo "[ERROR] shard ${n} 用の test-results-xml artifact が見つからない（retention 7日切れの可能性）。" >&2
    exit 1
  fi
  echo "[INFO] shard ${n}: ${ARTIFACT_NAME} をダウンロード"
  mkdir -p "${WORK_DIR}/s${n}"
  gh run download "${RUN_ID}" -R "${REPO}" -n "${ARTIFACT_NAME}" -D "${WORK_DIR}/s${n}"
done

echo "[INFO] JUnit XML を集計して重み表を生成する"
python3 - "${WORK_DIR}" "${OUT_FILE}" "${RUN_ID}" "${PR_NUMBER}" "${HEAD_BRANCH}" "${CONCLUSION}" "${CREATED_AT}" <<'PYEOF'
import sys, os, glob, xml.etree.ElementTree as ET, collections

work_dir, out_file, run_id, pr_number, head_branch, conclusion, created_at = sys.argv[1:8]

weights = collections.defaultdict(float)
count = 0
for f in glob.glob(os.path.join(work_dir, "s*", "TEST-*.xml")):
    base_name = os.path.basename(f)
    fqcn = base_name[len("TEST-"):-len(".xml")]
    top = fqcn.split("$")[0]
    try:
        tree = ET.parse(f)
    except Exception as e:
        print(f"[WARN] parse失敗: {f}: {e}")
        continue
    root = tree.getroot()
    time = float(root.attrib.get("time", "0") or "0")
    weights[top] += time
    count += 1

print(f"[INFO] 集計クラス数: {len(weights)}（parsed files: {count}）")

header = f"""# =============================================================================
# CI テストシャード 重み付け振り分け用データ（実行時間ベース）
# =============================================================================
# 生成元: GitHub Actions run #{run_id}（backend-ci.yml, PR #{pr_number}
#   「{head_branch}」, conclusion={conclusion}, {created_at}）
#   の 6 shard すべての test-results-xml-*（JUnit XML, retention 7日）artifact を
#   ダウンロードし、testsuite の time 属性をトップレベルクラス単位で合算した。
#   ネストクラス（"Foo$Bar"）は "$" より前のトップレベル名に集約する
#   （backend/build.gradle.kts の既存シャードフィルタと同じ単位）。
#
# 形式: 1行 "完全修飾クラス名=秒数（小数）"。
#
# 用途: backend/build.gradle.kts のシャードフィルタが、この重み表を読み込んで
#   貪欲法（重い順に、その時点で合計が最小の shard へ割り当て）でクラスを
#   6 分割に振り分ける。表に無いクラス（新規テスト等）は従来の安定ハッシュへ
#   フォールバックする。表そのものが存在しない場合も全体がハッシュ方式へ
#   フォールバックし、正常に動作する（詳細: build.gradle.kts のコメント参照）。
#
# 再生成手順: backend/scripts/regen-shard-weights.sh を参照
#   （目安: テストクラス数が大幅に増減した時。gh CLI 必須）。
# =============================================================================
"""

with open(out_file, "w", encoding="utf-8", newline="\n") as out:
    out.write(header)
    for k in sorted(weights):
        out.write(f"{k}={weights[k]:.3f}\n")

print(f"[OK] 書き出し完了: {out_file}")
PYEOF

echo "[DONE] ${OUT_FILE} を再生成した。git diff を確認しコミットすること。"
