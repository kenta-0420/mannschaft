# 統合HEAD f281 のCI採取結果

対象: [Backend CI40648568860 / attempt1](https://github.com/kenta-0420/mannschaft/actions/runs/40648568860/attempts/1)、HEAD `f2817eb94326b413122c69f4b0c409288c8982a6`。run・全shard・集約はsuccess。採取時の全latest head check/statusは成功または条件skip/neutralで、未完了・失敗は無い。元のa8b CI40481639328とは別の実測である。

この後、文書のみのmain `83877c756647c06f1f829ab4257b7171525e5d03` を通常mergeし、現在の実装HEADは `e67a77172f67ddbacfe4c8d8f9504eae8f54b004`。docs以外の全root tree30件がf281と同一であることを[source bridge](build/integration-e67-source-bridge.json)へ記録した。新HEADの[Backend CI40662266445](https://github.com/kenta-0420/mannschaft/actions/runs/40662266445)は同HEADで成功し、実装PRも通常mergeされた（下記）。f281成功だけで新HEADを完了とは判定していない。

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

support原本summaryのSHA256は `d4ff6833e4d21dac6d3483d143cda42fc09a7583cc846a04509d956daf5d84ad`。Git採用時のCRLF→LFは[manifest](build/manifest.json)のsourceSha256/packagedSha256で区別する。raw API本文・log・ZIPはGit未収録で、索引の絶対pathは採取時位置である。durable記録確定後に自隊cleanupし、撤去証明を別記する。今回runを含む残rawは[保持記録](cleanup.md)の14rootに含まれ、承認待ちで未撤去。

## 限界

- f281で実機を再実施したとは主張しない。実機/探索は元a8b、元移設11fileのblob対応は先のsource bridge、f281以後のdocs-only追従は全非docs tree対応として別記する。
- 旧a8bのfresh計測・初回post-Test105分timeout・cache再試行成功を本runへ混ぜない。本run6fresh成功も性能改善や今後のtimeout回避を保証しない。
- 全checkは採取時API snapshotで、branch protection設定やmerge時点のcheck選択を保証しない。最終実装HEADのCI成功・merge実体を別途照合する。
- 全6公開診断が取得できたことは欠損時gate拒否の証拠ではない。公開診断gate強化は別件1554/Issue4200の未解決として維持する。

## 最終実装HEAD e67 の確認

最終[Backend CI40662266445 / attempt1](https://github.com/kenta-0420/mannschaft/actions/runs/40662266445/attempts/1)は同HEADで成功。全6main Testの実task-stateはskipMessage=FROM-CACHE・upToDate=true、cacheHit=trueで、fresh0/cache6。commandExitCode/shellExitCode=0、testStepOutcome=success。completed=false/testStepExitCode=nullはfresh計測を無効とする既存仕様でありcommand失敗とは別である。通常TestのJVM/heap/件数を旧freshから補完しない。archUnit/freezeの実実行値は以下。

| shard | main Test | wrapper / test step | arch JVM / heap / tests | freeze JVM / heap / tests |
|---|---|---|---|---|
| 0 | cache hit | 0:57 / 0:57 | 9 / 4096 / 6 | 1 / 128 / 2 |
| 1 | cache hit | 1:30 / 1:30 | 9 / 4096 / 1 | 1 / 128 / 2 |
| 2 | cache hit | 1:14 / 1:14 | 9 / 4096 / 5 | 1 / 128 / 2 |
| 3 | cache hit | 1:11 / 1:11 | 9 / 4096 / 1 | 1 / 128 / 2 |
| 4 | cache hit | 2:05 / 2:05 | 9 / 4096 / 13 | 1 / 128 / 2 |
| 5 | cache hit | 1:33 / 1:33 | 10 / 4096 / 11 | 1 / 128 / 2 |

全shardでcontext cap5をinvocationで確認し、実archはfork180/parallel1・4096MiB、freezeはfork0/parallel1・128MiB。timeout/OOM error-pattern検出0、収集anomalies0。採取時の全latest check/statusは成功または条件skip/neutralで未完了なし。root確認では23checkの22pass/1skip。

[e67 safe summary](build/integration-ci-40662266445-summary.json)のsupport原本SHA256は `4a0dce570ef90d3ca84c3731a98565da7ab9d91633ef80e0fd24fcc4b21d0478`。文字列改行だけをLFへ揃えたGit採用SHAはmanifestに記録する。rawは本run専用directoryへ分離し、f281/旧run原本は変更していない。

[PR #4169](https://github.com/kenta-0420/mannschaft/pull/4169)は2026-10-28T09:22:13Zに通常squash merge、main実体 `bc2f2c89e7a40c0775d751aad2bd0e80287fdf0a`。[実装採用記録](implementation-adoption.json)でAPIのmerged/head/merge時刻・16変更fileと、e67とmain mergeの全repository tree OID一致を確認した。f281の6fresh成功と最終e67の6cache成功を別々の実証として保持する。
