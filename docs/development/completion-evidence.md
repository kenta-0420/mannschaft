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

実装PRの `head.sha` を記録する。完了状態を記録する台帳更新PR自身のSHAは使わない。GitHub APIで同一リポジトリの実装PRがmerge済みで、PR head SHAがJSONと一致すること、check/statusの最新結果に失敗・未完了がなく少なくとも成功checkを含むことを検査する。再実行された同名checkとstatus contextは最新結果を採用し、GitHubが明示する `skipped` / `neutral` は許容する。`ci[].runId` は各runが同一リポジトリ・実装head SHA・`conclusion=success` であることを個別に照合する。404、権限不足、APIエラーは成功扱いにしない。通常のコードPRでは過去の全証拠JSONを取得せず、新規完了と今回変更された完了証拠だけを取得する。

変更ファイルの全てが `scripts/`、`.github/`、`.claude/`、`docs/` または既知の開発規約ファイルに限られる実装PRだけを `scope: tooling` と認める。未知のpathやアプリコードを含むPRは `scope: app` が必要で、実機検証と探索がどちらも `passed` でなければ完了できない。reviewは常に`passed`必須。`not-applicable`には理由が必要で、reviewには使えず、real/exploratoryではtooling scopeだけに使える。成功stageにはGitHub URLまたは存在するリポジトリ内ファイルの参照が必要。

証拠参照は `https://github.com/<同一owner>/<同一repo>/...` または相対リポジトリファイルとする。絶対パス、別リポジトリURL、`..`を含むパスは拒否する。回帰を修正した場合は `regressions` に説明、テスト名、red/green両方の証拠を記録する。該当する回帰が無い場合は空配列でよい。

過去の完了行は移動や注記の編集ができるが、行そのもの、既存の証拠JSON、証拠リンクを削除・差替えできない。証拠JSONを編集するPRでは、その完了行の証拠全体を再検査する。

## 検証限界

GitHub API照合はPRのmerge状態、head SHA、GitHubが返す最新check/runの状態を確認する。これはbranch protectionに設定された必須check名そのものを読み取って完全一致を証明するものではない。JSONに書かれたACや証拠参照が真実であること、テストが説明どおりの契約を検査すること、実機操作や探索が実際に行われたことまでは証明しない。申告された内容の妥当性は検分と実機証跡を確認する担当者が判断する。ローカルCLI (`node scripts/completion-evidence.mjs --base <ref> --head <ref>`) はgit上の台帳・JSON構造と参照ファイルを確認するが、GitHub API上のPR/CI状態は検証しないと明示する。

## 実行

```sh
node --test scripts/completion-evidence.test.mjs
node scripts/completion-evidence.mjs --base origin/main --head HEAD
```
