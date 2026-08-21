#!/usr/bin/env bash
# =============================================================================
# CI テストシャード重み表（backend/src/test/resources/shard-weights.properties）
# の再生成スクリプト
# -----------------------------------------------------------------------------
# 【前提】
#   - gh CLI が必須（`gh auth login` 済みであること）。
#   - python3 が必須（標準ライブラリのみ使用。追加依存なし）。
#   - backend-nightly-full.yml の run が直近で成功していること
#     （nightly-test-results-xml-shard-* artifact は retention-days: 7 のため、
#      7日以内に成功した run から取得すること）。
#
# 【なぜ backend-ci.yml ではなく backend-nightly-full.yml の run を使うか（重要）】
#   通常の backend-ci.yml の PR run は、db/migration/** 無変更の PR では
#   -PexcludeMigrationTests=true で実行される（大半の PR がこれに該当する）。
#   このとき Flyway マイグレーション再生テスト（migration パッケージ配下 かつ
#   クラス名に Flyway を含む、約70クラス）が丸ごと除外され、その実行時間が
#   JUnit XML に一切現れない。ところがこの70クラスは全テスト時間の約8割
#   （実測: 8166/10054秒）を占める最重量級のテスト群であり、まさに「均等化の
#   効果が最も大きいはずのクラス群」である。backend-ci.yml の run を素朴に
#   使うと、この最重要クラス群が重み表から恒常的に欠落し、安定ハッシュ任せに
#   戻ってしまう（＝重み付けシャードを導入した意味が失われる）。
#
#   backend-nightly-full.yml は毎晩 -PexcludeMigrationTests を渡さずフル実行
#   するため、常に全クラス（migration 系込み）の実測値が揃う。よって本スクリプト
#   は backend-nightly-full.yml の run を一次ソースとする。
#
#   もし backend-nightly-full.yml の成功 run が retention 期間内に存在しない
#   場合は、db/migration/** を変更した PR（-PexcludeMigrationTests=false で
#   走った backend-ci.yml run）を代替ソースとして手動で run ID 指定すること
#   （その場合も全クラスが揃っているかは生成後の [INFO] 集計クラス数 と
#    Flyway 系クラス数の出力で必ず確認すること）。
#
# 【やること】
#   1. backend-nightly-full.yml の直近の成功 run を 1 つ選ぶ（引数で run ID を
#      指定可能。未指定なら最新の成功 run を自動選択する）。
#   2. その run の 6 shard 分すべての nightly-test-results-xml-shard-*
#      （JUnit XML）artifact をダウンロードする。
#   3. 各 XML の testsuite の time 属性を、ファイル名から復元した完全修飾クラス名
#      （ネストクラスは "$" より前のトップレベル名に集約）単位で合算する。
#   4. backend/src/test/resources/shard-weights.properties を上書き生成する
#      （ヘッダーに migration テストを含むクラス数も明記する）。
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
#   ./scripts/regen-shard-weights.sh                       # nightly-full の最新成功 run を自動選択
#   ./scripts/regen-shard-weights.sh <RUN_ID>               # backend-nightly-full.yml の run ID を明示指定
#   ./scripts/regen-shard-weights.sh <RUN_ID> backend-ci    # backend-ci.yml の run から生成（代替経路。
#                                                            #   migration 系クラスが欠落する可能性がある点に注意）
#
#   生成後は差分を確認し、Flyway 系クラス数が十分含まれているか（0 に近い場合は
#   代替経路を使ってしまっている可能性が高い）を確認したうえでコミットすること。
# =============================================================================
set -euo pipefail

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUT_FILE="${BACKEND_DIR}/src/test/resources/shard-weights.properties"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

RUN_ID="${1:-}"
SOURCE_KIND="${2:-nightly-full}"  # "nightly-full"（既定）または "backend-ci"

if [ "${SOURCE_KIND}" = "nightly-full" ]; then
  WORKFLOW="backend-nightly-full.yml"
  ARTIFACT_PREFIX="nightly-test-results-xml-shard-"
elif [ "${SOURCE_KIND}" = "backend-ci" ]; then
  WORKFLOW="backend-ci.yml"
  ARTIFACT_PREFIX="test-results-xml-"
  echo "[WARN] backend-ci.yml を明示指定。-PexcludeMigrationTests=true で走った run の場合" >&2
  echo "[WARN] Flyway マイグレーション再生テスト（約70クラス・全体の約8割）が欠落する。" >&2
  echo "[WARN] db/migration/** を変更した PR の run（migrations_changed=true）を選ぶこと。" >&2
else
  echo "[ERROR] 第2引数は 'nightly-full' または 'backend-ci' のみ対応（指定値: ${SOURCE_KIND}）" >&2
  exit 1
fi

if [ -z "${RUN_ID}" ]; then
  echo "[INFO] run ID 未指定 → ${WORKFLOW} の直近成功 run を自動選択する"
  RUN_ID="$(gh run list -R "${REPO}" --workflow="${WORKFLOW}" --status=success --limit 1 --json databaseId -q '.[0].databaseId')"
  if [ -z "${RUN_ID}" ]; then
    echo "[ERROR] ${WORKFLOW} の直近の成功 run が見つからない（retention 7日切れの可能性）。run ID を明示指定してください。" >&2
    exit 1
  fi
fi
echo "[INFO] 対象 run: ${RUN_ID}（${WORKFLOW}）"

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
  ARTIFACT_NAME="$(gh api "repos/${REPO}/actions/runs/${RUN_ID}/artifacts" --jq ".artifacts[] | select(.name | startswith(\"${ARTIFACT_PREFIX}\") and endswith(\"-shard-${n}\")) | .name" | head -1)"
  if [ -z "${ARTIFACT_NAME}" ]; then
    echo "[ERROR] shard ${n} 用の ${ARTIFACT_PREFIX}* artifact が見つからない（retention 7日切れの可能性）。" >&2
    exit 1
  fi
  echo "[INFO] shard ${n}: ${ARTIFACT_NAME} をダウンロード"
  mkdir -p "${WORK_DIR}/s${n}"
  gh run download "${RUN_ID}" -R "${REPO}" -n "${ARTIFACT_NAME}" -D "${WORK_DIR}/s${n}"
done

echo "[INFO] JUnit XML を集計して重み表を生成する"
python3 - "${WORK_DIR}" "${OUT_FILE}" "${RUN_ID}" "${PR_NUMBER}" "${HEAD_BRANCH}" "${CONCLUSION}" "${CREATED_AT}" "${WORKFLOW}" <<'PYEOF'
import sys, os, glob, xml.etree.ElementTree as ET, collections

work_dir, out_file, run_id, pr_number, head_branch, conclusion, created_at, workflow = sys.argv[1:9]

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

migration_flyway = {k: v for k, v in weights.items() if "migration." in k and "Flyway" in k}
migration_total_sec = sum(migration_flyway.values())
all_total_sec = sum(weights.values())
migration_pct = (migration_total_sec / all_total_sec * 100.0) if all_total_sec > 0 else 0.0

print(f"[INFO] 集計クラス数: {len(weights)}（parsed files: {count}）")
print(f"[INFO] migration+Flyway クラス数: {len(migration_flyway)} / 実行時間: {migration_total_sec:.1f}秒（全体の{migration_pct:.1f}%）")
if len(migration_flyway) == 0:
    print("[WARN] migration+Flyway クラスが 0 件。excludeMigrationTests=true の run から生成した可能性が高い。")
    print("[WARN] backend-nightly-full.yml の run を使っているか確認すること。")

header = f"""# =============================================================================
# CI テストシャード 重み付け振り分け用データ（実行時間ベース）
# =============================================================================
# 生成元: GitHub Actions run #{run_id}（{workflow}, {head_branch}
#   PR #{pr_number}, conclusion={conclusion}, {created_at}）
#   の 6 shard すべての JUnit XML artifact をダウンロードし、testsuite の time
#   属性をトップレベルクラス単位で合算した。ネストクラス（"Foo$Bar"）は
#   "$" より前のトップレベル名に集約する（backend/build.gradle.kts の既存
#   シャードフィルタと同じ単位）。
#
# 【本表は migration テストを{'含む' if len(migration_flyway) > 0 else '含まない（要注意）'}】
#   migration+Flyway クラス数: {len(migration_flyway)} / 実行時間: {migration_total_sec:.1f}秒
#   （全体の{migration_pct:.1f}%）。
#   backend-ci.yml の通常 PR run は -PexcludeMigrationTests=true で走ることが
#   多く、その場合この約70クラス（全体の約8割を占める最重量級テスト群）が
#   欠落する。そのため本表は原則 backend-nightly-full.yml（-PexcludeMigrationTests
#   を渡さずフル実行）の run から生成する。詳細は regen-shard-weights.sh 冒頭
#   コメント参照。
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
