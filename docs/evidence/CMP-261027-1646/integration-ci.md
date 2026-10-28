# 統合HEAD f281 のCI採取結果

対象: [Backend CI40648568860 / attempt1](https://github.com/kenta-0420/mannschaft/actions/runs/40648568860/attempts/1)、HEAD `f2817eb94326b413122c69f4b0c409288c8982a6`。run・全shard・集約はsuccess。採取時の全latest head check/statusは成功または条件skip/neutralで、未完了・失敗は無い。元のa8b CI40481639328とは別の実測である。

この後、文書のみのmain `83877c756647c06f1f829ab4257b7171525e5d03` を通常mergeし、現在の実装HEADは `e67a77172f67ddbacfe4c8d8f9504eae8f54b004`。docs以外の全root tree30件がf281と同一であることを[source bridge](build/integration-e67-source-bridge.json)へ記録した。新HEADの[Backend CI40662266445](https://github.com/kenta-0420/mannschaft/actions/runs/40662266445)と実装mergeは本原稿時点で待機中。f281成功だけで新HEADを完了としない。

## 実測

| shard | main Test | Test失敗/skip | wrapper / test step | job全体 | Test JVM / heap MiB | fork / 並列 |
|---|---|---|---|---|---|---|
| 0 | fresh / 5111件 | 0 / 1 | 63:28 / 63:28 | 67:56 | 10 / 4096 | 180 / 2 |
| 1 | fresh / 5308件 | 0 / 3 | 96:02 / 96:02 | 102:24 | 10 / 4096 | 180 / 2 |
| 2 | fresh / 5556件 | 0 / 2 | 76:25 / 76:25 | 81:38 | 10 / 4096 | 180 / 2 |
| 3 | fresh / 5343件 | 0 / 6 | 58:49 / 58:49 | 62:54 | 10 / 4096 | 180 / 2 |
| 4 | fresh / 5497件 | 0 / 0 | 90:29 / 90:29 | 93:58 | 10 / 4096 | 180 / 2 |
| 5 | fresh / 5488件 | 0 / 5 | 103:37 / 103:37 | 109:43 | 10 / 4096 | 180 / 2 |

全6本でcommandExitCode/shellExitCode/testStepExitCode=0、completed=true、testStepOutcome=success。main Test計32,303件（成功32,286・失敗0・skip17）。context cache cap5、job120分/test step105分を実invocation logで確認した。timeout/OOM error-pattern検出0、収集anomalies0。壁時計はwrapper start/finishおよびGHA step/job時刻差であり、JUnit時間和ではない。

| shard | archUnit JVM / heap / fork / 並列 / tests | freeze JVM / heap / fork / 並列 / tests |
|---|---|---|
| 0 | 9 / 4096 / 180 / 1 / 6 | 1 / 128 / 0 / 1 / 2 |
| 1 | 9 / 4096 / 180 / 1 / 1 | 1 / 128 / 0 / 1 / 2 |
| 2 | 9 / 4096 / 180 / 1 / 5 | 1 / 128 / 0 / 1 / 2 |
| 3 | 9 / 4096 / 180 / 1 / 1 | 1 / 128 / 0 / 1 / 2 |
| 4 | 9 / 4096 / 180 / 1 / 13 | 1 / 128 / 0 / 1 / 2 |
| 5 | 10 / 4096 / 180 / 1 / 11 | 1 / 128 / 0 / 1 / 2 |

## 採取・原本対応

[safe summary](build/integration-ci-40648568860-summary.json)にrun/jobs/全check結論、fresh/cache・exit・JVM/heap/fork/context・観測異常とZIP/member/job logのbytes/SHAを保存した。run/head/attempt/workflow/repository、全6shard job/artifact、API digest、ZIP必須memberを照合。旧run原本には書き込んでいない。

support原本summaryのSHA256は `d4ff6833e4d21dac6d3483d143cda42fc09a7583cc846a04509d956daf5d84ad`。Git採用時のCRLF→LFは[manifest](build/manifest.json)のsourceSha256/packagedSha256で区別する。raw API本文・log・ZIPはGit未収録で、索引の絶対pathは採取時位置である。durable記録確定後に自隊cleanupし、撤去証明を別記する。本原稿時点で今回rawは未削除。

## 限界

- f281で実機を再実施したとは主張しない。実機/探索は元a8b、元移設11fileのblob対応は先のsource bridge、f281以後のdocs-only追従は全非docs tree対応として別記する。
- 旧a8bのfresh計測・初回post-Test105分timeout・cache再試行成功を本runへ混ぜない。本run6fresh成功も性能改善や今後のtimeout回避を保証しない。
- 全checkは採取時API snapshotで、branch protection設定やmerge時点のcheck選択を保証しない。最終実装HEADのCI成功・merge実体を別途照合する。
- 全6公開診断が取得できたことは欠損時gate拒否の証拠ではない。公開診断gate強化は別件1554/Issue4200の未解決として維持する。
