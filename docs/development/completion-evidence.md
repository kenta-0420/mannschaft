# 完了証拠ゲート

`docs/task-list.md` の状態を新たに「完了」へ変えるPRは、同じPRで `docs/evidence/<CMP-ID>.json` を追加し、証拠列へ `[完了証拠](evidence/<CMP-ID>.json)` の形でリンクする。リンク先は台帳ファイルからの相対pathである。台帳は従来どおり7列で、セル内の `|` は `\|` にする。

validatorは `pull_request_target` で動くため、workflowのbase SHAからcheckoutしたtrusted版だけを実行する。PR headから取得するのは台帳とJSONなどのデータだけで、PR由来のソースをcheckout・import・実行しない。証拠JSONだけのdocs PRはコード登録対象から除外する一方、完了遷移と証拠変更は検査する。validatorがbaseにまだ無い初回導入PRはbootstrapとして登録検査だけ行い、このPRがmainへ入った後に完了証拠検査が有効になる。mainからvalidatorを削除・改名するPRはTask-List PR Gateが`pulls.listFiles`の`removed` / `previous_filename`で拒否し、bootstrap経由で検査を無効化できないようにする。

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
2. `scripts/`、`.github/`、`.claude/`、`docs/` または既知の開発規約ファイル → tooling
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

## 検証限界

GitHub API照合はPRのmerge状態、head SHA、GitHubが返すマージ時点のcheck/statusとCI runの状態を確認する。これはbranch protectionに設定された必須check名そのものを読み取って完全一致を証明するものではない。JSONに書かれたACや証拠参照が真実であること、テストが説明どおりの契約を検査すること、実機操作や探索が実際に行われたことまでは証明しない。申告された内容の妥当性は検分と実機証跡を確認する担当者が判断する。ローカルCLI (`node scripts/completion-evidence.mjs --base <ref> --head <ref>`) はgit上の台帳・JSON構造と参照ファイルを確認するが、GitHub API上のPR/CI状態は検証しないと明示する。

## 実行

```sh
node --test scripts/completion-evidence.test.mjs
node scripts/completion-evidence.mjs --base origin/main --head HEAD
```
