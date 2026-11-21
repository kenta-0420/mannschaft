# 完了証拠ゲート

`docs/task-list.md` の状態を新たに「完了」へ変えるPRは、同じPRで `docs/evidence/<CMP-ID>.json` を追加し、証拠列へ `[完了証拠](evidence/<CMP-ID>.json)` の形でリンクする。リンク先は台帳ファイルからの相対pathである。台帳は従来どおり7列で、セル内の `|` は `\|` にする。

validatorは `pull_request_target` で動くため、workflowのbase SHAからcheckoutしたtrusted版だけを実行する。PR headから取得するのは台帳とJSONなどのデータだけで、PR由来のソースをcheckout・import・実行しない。証拠JSONだけのdocs PRはコード登録対象から除外する一方、完了遷移と証拠変更は検査する。validatorがbaseにまだ無い初回導入PRはbootstrapとして登録検査だけ行い、このPRがmainへ入った後に完了証拠検査が有効になる。mainからvalidatorを削除・改名するPRはTask-List PR Gateが`pulls.listFiles`の`removed` / `previous_filename`で拒否し、bootstrap経由で検査を無効化できないようにする。

### merge queue（`merge_group`）経路

`pull_request_target` は `merge_group` で発火しないため、merge queue 用に別 workflow `.github/workflows/task-list-pr-gate-merge-group.yml`（`on: merge_group` の `checks_requested` のみ）を置き、同名ジョブ `Task-List PR Gate` を報告させる。PR 側の `task-list-pr-gate.yml` には `merge_group` を入れない。同一 workflow 内でジョブ単位の `if` で分けると skipped の同名チェックが PR 側にも報告され、必須チェックの判定に混ざるためである。

- PR 側（`pull_request_target`）: 従来どおり（CMP-ID 登録・完了証拠の検査。checkout・権限・判定ロジックは不変）。
- merge_group 側: PR 本文検査・完了証拠検査は各 PR の `pull_request_target` で済んでいるため行わない。合成コミット（`merge_group.head_sha`）の `docs/task-list.md` の構造だけを `scripts/task-list-structure-check.mjs` で検査する。CMP-ID（新旧両形式）の重複、列数≠7、未完行の状態セル先頭の優先度 `P1`〜`P5` 欠落、ファイル不在・空・CMP 行0件・緩い走査と厳密抽出の件数不一致はいずれも理由を出力して fail にする。判定規則は Java 番人 `TaskListCmpIdDuplicateGuardTest` の移植で、行終端（LF・CRLF・CR・NEL・U+2028・U+2029）や空白・trim の扱いも Java に合わせている。規則を変えるときは両方と `scripts/task-list-structure-check.test.mjs` の共通 fixture を同時に直す。
- 検査器は信頼できる `merge_group.base_sha` からワークスペース直下へ checkout したものだけを実行し、`head_sha` からは台帳1ファイルだけを sparse checkout で別ディレクトリ（`.merge-group-head/`）へ取得する。キュー内の PR が改変した検査器で自己承認することはできない。base に検査器が無い場合は合格にせず fail する（本 workflow を入れた PR が main に入るまでは merge queue を有効化しないこと）。権限は `contents: read` のみ、`persist-credentials: false`。

## JSON形式

```json
{
  "schemaVersion": 1,
  "cmpId": "CMP-261010-1234",
  "scope": "app",
  "implementation": { "pr": 1234, "sha": "40桁の実装PR head SHA" },
  "acceptanceCriteria": [
    {
      "id": "AC-1",
      "description": "受け入れ条件",
      "tests": [{ "name": "対象テスト名", "evidence": "backend/src/test/.../FeatureTest.java" }]
    }
  ],
  "ci": [{ "runId": 123456789 }],
  "stages": {
    "review": { "status": "passed", "evidence": "https://github.com/OWNER/REPO/pull/1234" },
    "real": { "status": "passed", "evidence": "docs/evidence/実機ログ.md" },
    "exploratory": { "status": "passed", "evidence": "docs/evidence/住民観測.md" }
  },
  "regressions": []
}
```

実装PRの `head.sha` を記録する。完了状態を記録する台帳更新PR自身のSHAは使わない。GitHub APIで同一リポジトリの実装PRがmerge済みで、PR head SHAがJSONと一致すること、マージ時点のcheck/statusに失敗・未完了がなく少なくとも成功checkを含むことを検査する。GitHubが明示する `skipped` / `neutral` は許容する。`ci[].runId` は各runが同一リポジトリ・実装head SHA・`conclusion=success` であることを個別に照合する。404、権限不足、APIエラーは成功扱いにしない。通常のコードPRでは過去の全証拠JSONを取得せず、新規完了と今回変更された完了証拠だけを取得する。

### checkの世代規則

check runとcommit statusはhead SHA単位でしか取得できず、`check_run.pull_requests` はopenのPRしか返さないため、PR番号では絞れない。マージ後に同じhead SHAから別PRが開かれると、その別PRのcheck（例: 重複PRのTask-List PR Gateのfailure）が同じSHAに積まれる。そこでworkflowは `checks.listForRef` を `filter: 'all'` と `github.paginate` で全件取得し、実装PRの `merged_at` とともに `summarizeImplementationChecks` へ渡す。規則は次のとおり。

- `mergedAt` が欠落・不正なら拒否する。
- check runは `completed_at <= mergedAt` のものだけを候補にし、名前ごとに最新（`completed_at` 降順、同時刻なら `id` 降順）を採る。
- `started_at <= mergedAt` なのに未完了、またはマージ後に完了したcheckは「マージ時点で実行中」として拒否する。
- マージ後に開始したcheckは無視する（マージ前にfailure、マージ後の再実行でsuccessなら、failureが採られて拒否される）。
- commit statusは `created_at <= mergedAt` のものだけを使い、contextごとに最新を採る。
- 候補が0件なら拒否する。

あわせて `pulls.get` の `changed_files` と `pulls.listFiles` で取得した件数が一致しなければ拒否する（listFilesの上限で一覧が欠けたままscopeを推定しない）。

### scope

scopeは `app` / `tooling` / `test-only` の3値。実装PRの変更ファイルを1件ずつ次の順（先勝ち）で分類する。改名ファイルは `previous_filename` も同じ規則で分類する。

1. 不正path（文字列でない、filename欠落、`..` セグメント、`/` か `\` で始まる、`\` を含む、`path.posix.normalize` で変わる）→ app
2. `scripts/`、`.github/`、`.claude/`、`docs/` または既知の開発規約ファイル（`backend/.claudecode.md` を含む） → tooling
3. 次のいずれか（大文字小文字を区別）→ test
   - `backend/src/test/` 配下
   - `frontend/tests/` 配下
   - `frontend/{app,server,scripts,eslint-rules}/` 配下の `*.spec.ts` / `*.test.ts` / `*.spec.mjs` / `*.test.mjs`（同居spec）
4. その他 → app

appが1件でもあれば `app`、無くてtestが1件でもあれば `test-only`、それ以外は `tooling`。テスト設定（`vitest.config.ts`、`playwright*.config.ts`、`package.json`、`build.gradle`）はテストの実行範囲や合否を変えうるため app のまま扱う。ArchUnitのfreezeストアなどテスト側の基準を緩める変更が test-only に入りうる点は、自動判定では区別できないためreviewで確認する。

申告scopeは推定scopeと一致するか、より厳しい `app` であれば認める。`app` は実機検証と探索がどちらも `passed` でなければ完了できない。reviewは常に`passed`必須。`not-applicable`には理由が必要で、reviewには使えない。real/exploratoryの`not-applicable`は、scopeが `tooling` か `test-only` で、かつstageの `scope` が証拠の `scope` と一致する場合だけ使える。成功stageにはGitHub URLまたは存在するリポジトリ内ファイルの参照が必要。

test-onlyの例:

```json
{
  "schemaVersion": 1,
  "cmpId": "CMP-261013-0000",
  "scope": "test-only",
  "implementation": { "pr": 3841, "sha": "40桁の実装PR head SHA" },
  "acceptanceCriteria": [
    {
      "id": "AC-1",
      "description": "受け入れ条件",
      "tests": [{ "name": "対象テスト名", "evidence": "backend/src/test/.../FeatureScopeContractIT.java" }]
    }
  ],
  "ci": [{ "runId": 123456789 }],
  "stages": {
    "review": { "status": "passed", "evidence": "https://github.com/OWNER/REPO/pull/3841" },
    "real": { "status": "not-applicable", "scope": "test-only", "reason": "テストコードだけの変更で画面・APIの挙動を変えない" },
    "exploratory": { "status": "not-applicable", "scope": "test-only", "reason": "テストコードだけの変更で導線を変えない" }
  },
  "regressions": []
}
```

証拠参照は `https://github.com/<同一owner>/<同一repo>/...` または相対リポジトリファイルとする。絶対パス、別リポジトリURL、`..`を含むパスは拒否する。回帰を修正した場合は `regressions` に説明、テスト名、red/green両方の証拠を記録する。該当する回帰が無い場合は空配列でよい。

過去の完了行は移動や注記の編集ができるが、行そのもの、既存の証拠JSON、証拠リンクを削除・差替えできない。証拠JSONを編集するPRでは、その完了行の証拠全体を再検査する。

### mainの前進とPR自身の変更の区別

workflowは固定したbase SHA・head SHAの比較APIから共通祖先SHAを取得し、3時点の台帳をデータとして読む。mainでのみ追加された完了行・完了状態・証拠リンクは、headの行内容が共通祖先と同一（両方に行が無い場合を含む）で、参照する証拠JSONにもPR自身の変更が無い場合に限り、PRによる削除と判定しない。行の位置だけの違いは内容の変更に含めない。

head側でも行を変更した場合はこの免除を適用せず、mainの過去完了行・リンクの保護を維持する。行が共通祖先から無変更でも、PRによる証拠JSONの変更・削除・改名があれば免除しない。改名は旧pathも検査する。PR自身の新規完了化・証拠リンク追加は共通祖先から検出し、mainも独立に完了化済みの場合でも検査する。比較APIの失敗、共通祖先SHAの不正、台帳の取得失敗は拒否する。現在PRの変更ファイル一覧も `changed_files` の非負整数と取得件数の一致を要求し、欠測・不正・一覧不足からJSON操作を見逃さない。

この判定は `ready_for_review`・`edited`・`synchronize` で共通であり、実行するvalidatorは引き続きtrusted baseのものだけである。ローカルCLIの明示的な `--base` / `--head` 比較は従来どおり指定した2時点を比較する。CLIの `git show` 読取は台帳・証拠JSON・参照ファイルそれぞれに有限の16 MiB上限を設ける。

## 検証限界

GitHub API照合はPRのmerge状態、head SHA、GitHubが返すマージ時点のcheck/statusとCI runの状態を確認する。これはbranch protectionに設定された必須check名そのものを読み取って完全一致を証明するものではない。JSONに書かれたACや証拠参照が真実であること、テストが説明どおりの契約を検査すること、実機操作や探索が実際に行われたことまでは証明しない。申告された内容の妥当性は検分と実機証跡を確認する担当者が判断する。ローカルCLI (`node scripts/completion-evidence.mjs --base <ref> --head <ref>`) はgit上の台帳・JSON構造と参照ファイルを確認するが、GitHub API上のPR/CI状態は検証しないと明示する。

## 実行

```sh
node --test scripts/completion-evidence.test.mjs
node scripts/completion-evidence.mjs --base origin/main --head HEAD
```
