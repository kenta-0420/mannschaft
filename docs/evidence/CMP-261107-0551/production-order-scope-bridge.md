# Production順序と入力scopeの限定採択

状態は最終正式化提案。実装PR #4353 は通常merge済み、head H=`9e8e9ba15cc7040d88d88067646be2d6a51ec862`、merge commit M=`e2b4744f438d0d376fb3c243e28aaa5ad2ff545d`。HのPR workflow12成功・全29checkのpending/fail0は別の `ci-current-head.json`。production gateはproduction-M-proof.jsonのDocker成功とproduction-M-workflow-final.jsonの両job成功で採択済み。待機snapshotはproduction-pre-success-history.jsonに履歴保持。

根拠原本は `outputs/ranch-approved64-production-order-review-20261108.md`（SHA256 `d584816026eef7483456902fa1e3a284fbbdcec5ac69d2ff2b037ca0e44fb4ab`）。その限定追記を含む最終判断を選別した。既定workflowはproduction Docker検証をmain pushに置き、context `./frontend` / file `./frontend/Dockerfile`、node22.23.2-alpine・heap8192で通常build→scannerを実行し、CI内runtimeの自己完結性・HTTP・healthを確認する。push:false/load:trueであり、このjob自体の本番deploymentはない。既定pathを利用するために基準・型・コード・timeout/RAM閾値を緩めていない。

全repo tree同一は主張しない。actual MをGit/API由来bridgeとlocal Gitで照合し、H↔M差は `docs/task-list.md` のみ、frontend tree=`ccdb9b22051266c89b9bfbe1c5eb8f6a712e2936`、backend tree=`4bd7a289c1683dff8992916ddeb04ef190975644`、workflow blob=`f7b79f1564e0aac5ee74ea6d23a4f5d05af2021e` が両commitで完全一致した。Task-ListはDocker context外でCOPYされない。この限定条件でtracked production入力scopeの完全一致を採択する。任意のdocs変更へ一般化しない。詳細は `postmerge-production-input-bridge.json`。

実装mergeと戦役完了を分ける。Mのmain-push run42406375715/job128504862033のbuild/scanner/self-contained/containerHTTP/health/Stop containerは成功。Installも最終completed SUCCESS。別run42406375664のBuild & Deploy成功はECR push/ECS deployがSKIPPEDであり、本番配備成功とはしない。Windows H buildは所有確認後に意図的中断・非PASSを保持し生成物撤収済み、追加nativeの資源guard NO_STARTは作成0・製品failureではない歴史として保持する。このworkerは既存Windowsbuildやruntimeへ操作していない。

後続formal JSONは `implementation.sha=H`、ci[]はHに完全一致する成功PR runだけを用いる。Mのpush runはproduction evidenceへ分ける。Task-List pull_request_target gateもci[]へ機械追加しない。M runをH実行へ書き換えず、Docker成功を未完Windows試行のPASSや原因解決へ読み替えない。公開512gate closed・正式64/81未割当・本番公開裁可なしを維持する。
