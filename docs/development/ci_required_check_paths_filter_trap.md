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
| `Compile & Test` | `.github/workflows/backend-ci.yml`（集約ゲートジョブ `gate`） | 全 PR 起動 + detect ジョブ + shard skip |
| `OpenAPI Drift Check` | `.github/workflows/openapi-drift-check.yml`（ジョブ `drift-check`） | 全 PR 起動 + ステップ `if` skip |

**この 2 つのジョブ名 / workflow 名は Ruleset 登録名と結び付いている。改名は全 PR の恒久ブロックを意味する。**
どうしても改名する場合は、先に Ruleset 側の required 登録を新名で追加してから workflow を変えること。

---

## 参考

- `.github/workflows/backend-ci.yml` 冒頭コメント（2026-05-25 の項）
- `.github/workflows/openapi-drift-check.yml` 冒頭コメント（2026-07-28 の項）
