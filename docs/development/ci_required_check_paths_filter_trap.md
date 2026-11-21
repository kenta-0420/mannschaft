# CI の罠: required status check × `on.pull_request.paths` は永久 pending を生む

**対象**: `.github/workflows/*.yml` を書く / 直す全員
**初出**: 2026-07-28（PR #2484 が BLOCKED になった事故の根治にあたり整理）

---

## 結論（先に守るべきルール）

> **main の Ruleset に required status check として登録されている workflow に、
> `on.pull_request.paths` フィルタを付けてはならない。**
>
> 実行対象の絞り込みは **「全 PR で起動 → ステップの `if:` で重い処理を skip」** で行う。

---

## なぜ罠になるのか

GitHub の required status check は **「その名前の結果が報告されるまで待つ」** 仕様である。
一方 `on.pull_request.paths` は **「workflow をそもそも起動しない」** フィルタで、
起動しなかった workflow は success も failure も報告しない。

この 2 つが噛み合うと:

```
paths に当たらない PR
  → workflow が起動しない
  → 「OpenAPI Drift Check」という名前の check-run が 1 件も生成されない
  → required check が永久 pending
  → 他の全チェックが緑でも mergeStateStatus = BLOCKED
```

となり、**マージ不能になる**。しかも PR 画面上は「pending のチェックがある」としか出ず、
`Failed 0 / 全緑` に見えるため原因の特定が難しい。

### 実例: PR #2484

- 12 チェック全緑・Failed 0 なのに `mergeStateStatus=BLOCKED`
- `gh api repos/<owner>/<repo>/commits/<sha>/check-runs` に
  **`OpenAPI Drift Check` の check-run が一件も存在しなかった**
- 変更ファイルは `common/GlobalExceptionHandler.java` / `auth/service/*.java` /
  `frontend/**` / `docs/**` で、`paths` の 6 パターンいずれにも当たらなかった
- やむなく `--admin` で越える運用事故になった

影響範囲は広い。**Service / Repository / Entity / common だけ触る BE の PR、
FE だけの PR、ドキュメントだけの PR がすべて足止め**される。

---

## 正しい形（本リポジトリの定石）

`.github/workflows/backend-ci.yml` が 2026-05-25 に同じ理由で全 PR トリガー化しており、
`.github/workflows/openapi-drift-check.yml` も 2026-07-28 にこの形へ揃えた。

1. **`on.pull_request` から `paths:` を削除**し、必ず起動させる
2. **ジョブ名 / チェック名は絶対に変えない**
   （Ruleset の required 登録名と一致しなくなると、今度は全 PR が恒久ブロックされる）
3. **ジョブに `if:` は付けない**
   ジョブごと skip すると「skipped な required check を Ruleset がどう扱うか」という
   曖昧な挙動に依存してしまう。**常に走って常に success を報告する**形にする
4. 代わりに **判定ステップを先頭に置き、各ステップに `if:` を付ける**
5. 無関係な PR では「関連変更が無いため skip した」旨を echo するステップだけ走って緑で終わる

### 判定ステップの書き方（`backend-ci.yml` / `openapi-drift-check.yml` 共通の流儀）

```yaml
- name: Checkout source
  uses: actions/checkout@v4
  with:
    fetch-depth: 0          # 差分を取るため必須

- name: 関連変更パス検出
  id: changes
  working-directory: ${{ github.workspace }}   # defaults.run が別ディレクトリの場合は必須
  run: |
    set -e
    if [ "${{ github.event_name }}" != "pull_request" ]; then
      # workflow_dispatch 等は PR コンテキストが無いので安全側（実行する）へ倒す
      echo "xxx_changed=true" >> "$GITHUB_OUTPUT"
      exit 0
    fi
    BASE_SHA="${{ github.event.pull_request.base.sha }}"
    HEAD_SHA="${{ github.event.pull_request.head.sha }}"
    # 3 ドット（merge-base...head）= PR が実際に加えた純変更のみ。
    # 2 ドットだと base が分岐後に進めた変更まで拾い、無関係 PR でも重い処理が走る。
    CHANGED=$(git diff --name-only "${BASE_SHA}...${HEAD_SHA}")
    if echo "${CHANGED}" | grep -E '<パターン>' > /dev/null; then
      echo "xxx_changed=true"  >> "$GITHUB_OUTPUT"
    else
      echo "xxx_changed=false" >> "$GITHUB_OUTPUT"
    fi
```

### 落とし穴

| # | 罠 | 対処 |
|---|---|---|
| 1 | `defaults.run.working-directory` が `./backend` 等になっていると、判定ステップの `git diff` がそのディレクトリ基準になり誤判定する | 判定ステップに `working-directory: ${{ github.workspace }}` を明示 |
| 2 | `fetch-depth` 既定（=1）だと base...head 差分が取れない | `fetch-depth: 0` |
| 3 | 2 ドット差分は base 側の進行分まで拾う | 必ず 3 ドット（`BASE...HEAD`） |
| 4 | 重量ステップに `if:` を付け忘れる | 30 分級のステップ（`generateOpenApiDocs` 等）は必ず条件付きに。ここがコスト設計の要 |
| 5 | glob の `**` を正規表現に落とすとき `.*/` にすると 0 セグメントを取りこぼす | `(.*/)?` を使う（例: `backend/src/main/java/(.*/)?controller/`） |

---

## 現状の required status checks（main Ruleset）

| チェック名 | 定義元 | 形 |
|---|---|---|
| `Compile & Test` | `.github/workflows/backend-ci.yml`（集約ゲートジョブ `gate`） | 全 PR・全 merge group 起動 + detect ジョブ + shard skip |
| `OpenAPI Drift Check` | `.github/workflows/openapi-drift-check.yml`（ジョブ `drift-check`） | 全 PR・全 merge group 起動 + ステップ `if` skip |
| `ER Diagram Drift Check` | `.github/workflows/er-diagram-drift-check.yml`（ジョブ `drift-check`） | 全 PR・全 merge group・main push 起動 + ステップ `if` skip |
| `Task-List PR Gate` | `.github/workflows/task-list-pr-gate.yml` | 台帳ゲート（merge_group 対応は別作業で扱う） |

**この 4 つのジョブ名 / workflow 名は Ruleset 登録名と結び付いている。改名は全 PR の恒久ブロックを意味する。**
どうしても改名する場合は、先に Ruleset 側の required 登録を新名で追加してから workflow を変えること。
（2026-11-21 時点で `gh api repos/{owner}/{repo}/rules/branches/main` の required_status_checks を実測して更新）

---

## merge queue（merge_group）対応の注意（CMP-261121-1114）

GitHub merge queue を使うと、queue に入った PR ごとに「対象ブランチの先端 + queue の先行エントリ + 自エントリ」を
積んだ一時 commit（ref は `gh-readonly-queue/main/pr-N-<sha>`）が作られ、その commit に対して必須チェックが
要求される。**`on: merge_group` を持たない workflow の必須チェックは queue で永久 pending になり、queue が止まる**
（paths フィルタの罠と同じ構造）。必須チェック3本（`Compile & Test` / `OpenAPI Drift Check` / `ER Diagram Drift Check`）は
次の形で対応済み。

| # | 注意点 | 対処 |
|---|---|---|
| 1 | `on:` に `merge_group` が無いとチェックが報告されない | `merge_group: { types: [checks_requested] }` を追加。ジョブ名・required ジョブの `if: always()`（または job-level `if` 無し）は PR と同じに保つ |
| 2 | `concurrency.group` を `github.event.pull_request.number` だけで作ると、merge_group / workflow_dispatch では空文字になり全 run が同じ group に入って互いをキャンセルする | `${{ github.event.pull_request.number \|\| github.ref }}` でフォールバックする（merge_group の ref はエントリごとに一意） |
| 3 | merge_group には `pull_request.base.sha` / `head.sha` が無い。旧来の「pull_request 以外はフル実行」分岐のままだと queue で毎回フル実行になる | `github.event.merge_group.base_sha...head_sha` で差分を取る。`base_sha` は queue で先行するエントリの合成コミット（先頭なら対象ブランチ先端）を指すので、この差分は **このエントリ自身の差分** になる（先行エントリの変更は含まない）。先行エントリは各自の merge group で検証済みで、先行が落ちればキューが後続を再構築して検証し直すため、自身の差分だけで判定してよい |
| 4 | 差分取得の失敗（SHA 空・`git diff` 失敗）を「変更なし」に倒すと検査が黙って skip される | 共通スクリプト `.github/scripts/detect-changed-files.sh` が非0で終了し、呼び出し側は全実行（`migrations_changed=true` を含む）に倒す |
| 5 | 改名・削除で旧パスを見落とす | `git diff --no-renames` で旧パス・新パスの両方を拾う |
| 6 | 判定スクリプト・パターンファイル自体の変更で検査が走らない | `detect-changed-files.sh`・`detect-migration-changes.sh`・`migration-sensitive-paths.regex` を各検査の実行条件に入れている（backend-ci では `backend_changed=true` かつ `migrations_changed=true`） |
| 7 | `pull_request_target` は merge_group では発火しない | merge queue の必須チェックにしたい workflow は `pull_request_target` だけに頼らず `merge_group` を明示する |

共通スクリプトの終了コード: `0`=一覧出力 / `2`=SHA・イベント名が空（判定不能）/ `3`=`git diff` 失敗（判定不能）/ `10`=差分判定の対象外イベント（workflow_dispatch・push 等。フル実行）。
回帰は `scripts/detect-changed-files.test.mjs`（一時 git リポジトリで各分岐を検証）と
`scripts/ci-merge-group-workflows.test.mjs`（3本の YAML の静的検査）・
`scripts/ci-change-detection-steps.test.mjs`（3本の判定ステップの `run:` を YAML から抜き出し、一時 git リポジトリで実行して `GITHUB_OUTPUT` の値を検証）が `pr-light-checks.yml` の `[Harness]` で検出する。

---

## 軽量 PR 検査は1ジョブに統合している（CMP-261120-1301）

CI の遅さの主因は、実行数秒のジョブが GitHub ランナーの順番待ちで 30〜100 分止まることである。
ジョブ本数そのものが待ち行列を長くするため、軽い PR 時検査は `.github/workflows/pr-light-checks.yml` の
**1ジョブ（`light-checks`）** にまとめた。統合前の対応は次のとおり。

| 旧 workflow / ジョブ | 現在 | 起動範囲 |
|---|---|---|
| `harness-ci.yml`（Harness CI） | `[Harness]` step 群（Node 24） | main 向け PR |
| `worktree-cleanup.yml`（Worktree cleanup guard） | `[Worktree guard]` step 群（Node 22） | main 向け PR |
| `frontend-lockfile-verify.yml`（変更検出 / 内容検査 / glibc / musl） | `[lockfile]` step 群（musl は `docker run node:22.23.2-alpine`） | main 向け PR かつ `frontend/package-lock.json`・`package.json` に変更あり |
| `secret-scan.yml` の PR 差分スキャン | `[gitleaks]` step 群 | 全 PR |

- 旧3 workflow は PR 以外の trigger が無かったため削除した。`secret-scan.yml` は main push 差分と週次全履歴のために残している（PR 起動のみ外した）。
- 起動範囲は step の `if`（`github.base_ref == 'main'`・lockfile 変更判定 step の出力）で従来と同じに保つ。対象外は step を skip しジョブは成功する。
- 各 step は `!cancelled()` 付きで、1つ失敗しても残りを走らせ、最後にジョブが赤になる。lockfile の変更判定 step が失敗した場合も、判定 step 自身が赤のままジョブを失敗させる。
- concurrency は `cancel-in-progress: false` のまま。秘密情報スキャンは、後続の push（force push を含む）で範囲から外れうるコミットも走査し切るため、従来から走りかけを捨てない方針で、統合ジョブはこれに合わせた。旧 Harness / Worktree guard / lockfile 検証は `cancel-in-progress: true` だったので、連続 push 時は古い run も最後まで走る点が変わる（1 run は数分）。
- 旧4 workflow のチェック名（`Harness CI` 等）は main の必須チェックではなかった。新ジョブ名は `PR 軽量検査`。
- 新しい軽量検査は、ここへ step として足す（別 workflow・別ジョブを増やさない）。重い検査（backend-ci・frontend-ci 等）は従来どおり別 workflow。
- `scripts/test-secret-scan.py` は、PR 差分は `pr-light-checks.yml`、main push 差分と全履歴は `secret-scan.yml` を読んで3経路を検証する。

---

## 参考

- `.github/workflows/backend-ci.yml` 冒頭コメント（2026-05-25 の項）
- `.github/workflows/openapi-drift-check.yml` 冒頭コメント（2026-07-28 の項）
