# L1-B 完了証拠の原稿入口

子 CMP-261027-1646、実装[PR #4169](https://github.com/kenta-0420/mannschaft/pull/4169)。移設検証HEADは `a8b7d8b999973d2c138ffecce2fbe2233f6bb09a`。main統合HEAD `f2817eb94326b413122c69f4b0c409288c8982a6` の[CI40648568860](https://github.com/kenta-0420/mannschaft/actions/runs/40648568860)は6fresh・失敗0で成功。その後docsのみのmainを通常追従し、現在の実装HEADは `e67a77172f67ddbacfe4c8d8f9504eae8f54b004`。[新HEAD CI40662266445](https://github.com/kenta-0420/mannschaft/actions/runs/40662266445)と実装mergeは本原稿時点で待機中。実装merge・同HEAD成功CI・最終検分が確定するまでは、完了JSONとtask-listの完了遷移を作成しない。

| 証拠 | 内容と対象 |
|---|---|
| [実装・検証報告](../CMP-261027-1646-l1b-implementation.md) | B-1〜B-8の移設設定・前後比較・重み再生成・負試験・OpenAPI。元の対象SHAを明示 |
| [最小build証拠](build/README.md) | 旧/新重み各37profile、候補/設定/helper76比較ずつ、raw9本ずつの一致。安全な負XML・原本hash・重み分類・gate入力27件 |
| [B7初回](../CMP-261027-1646-l1b-b7-attempt1.md) / [再試行](../CMP-261027-1646-l1b-b7-attempt2.md) | a8bの6fresh Test本体失敗0、初回shard5 post-Test105分timeout、設定不変のfailed-only再試行cache hit成功を分離 |
| [f281統合CI実測](integration-ci.md) | CI40648568860の6fresh成功・Test32,303件失敗0。新HEAD e67のCIは別途待機中 |
| [ローカルTest終端契約](terminal-contract/README.md) | f281固定git blobの実wrapperを未変更で実行したsynthetic producer7ケース。実Gradle/公開artifact欠損試験と区別 |
| [実機・探索報告](real-and-exploration.md) / [安全な実機証拠](real/README.md) | a8bの実機4case・独立3住民探索・代表PNG3枚。個別予定詳細復帰未検証、集計snapshot差を保持 |
| [検分記録](review.md) | rootによるa8bの検分と裁可確認。統合HEAD最終判定は別途確定 |
| [source bridge](build/integration-261028-source-bridge.json) | original L1-B変更の非Markdown11fileがa8b→f281でblob一致。upstream製品全体の同一性を主張しない |
| [docs-only追従bridge](build/integration-e67-source-bridge.json) | f281→e67でdocs以外の全root tree30件が同一。製品・テスト・build・CI bytes不変 |

## B5の裁可と未解決の別件

[裁可 REQ-GRADLE-DIAG-02](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6462685947)を[条件同期PR #4201](https://github.com/kenta-0420/mannschaft/pull/4201)で正本・準備計画・子台帳へ反映済み。L1-Bは既存挙動を維持し、公開診断artifactのmissing/empty/partial/upload failureでgateを非成功にする強化は[別件 CMP-261028-1554 / Issue #4200](https://github.com/kenta-0420/mannschaft/issues/4200)として未着手で残す。ローカル終端検証や再生成拒否を公開artifact検証と混同しない。他のB5条件、B1〜B8の残条件、共有予約・後続入口を維持する。

## 対象と限界

旧実機/探索をf281で再実施したとは記録しない。統合後にはupstreamの製品・テスト・凍結store・画面/API変更があり、元の比較結果を全アプリ同一性へ拡張しない。元の移設コードはsource bridge、統合後の成立は同HEAD CI、実機/探索の実施内容は元a8bの証拠として別々に評価する。

元ログ・大きなTSV・archive・OpenAPI本体は収録しない。絶対ローカルpathは採取時位置を示す原本hash索引にだけ用い、恒久保持は保証せず、stage参照には本directoryなどrepo内pathを使う。小さな証拠JSON・画像はGitへ保存し、全量rawはdurable記録確定後の自隊cleanup対象とする。本原稿時点では未削除で、最終撤去は別のcleanup証明へ記録する。`build/manifest.json`、`real/provenance.json`に原本と安全なコピー/要約を対応付けた。認証値・Cookie・fixtureデータは収録しない。rootは同梱の代表PNG3枚を確認済み。

member HTTP200の当時報告183件と保存responsesの再集計188件は同一snapshotと扱わず、差5件の因果未確定を保持する。全画面/全viewportの監査、個別予定詳細復帰、性能改善は実証していない。L0-M基準計測は未完了のまま。別件1554、親戦役、L1-M等の後続入口を本原稿だけで完了・充足としない。
