# CMP-261114-0618 所有fixture後始末の完了証拠

[実装PR #4503](https://github.com/kenta-0420/mannschaft/pull/4503) の head は `beb62201ba183d64e5ff469ea3d2e18c1ac9ee0c`。2026-11-14 09:21:44 UTC に通常 squash merge 済みで、採用commitは `e0e2589edd87c55e116dbd8d4895c184958aa9e5`、treeは `b9e9decf1b79c9efa0b75918ead371e45e287461`。Rootの最終検分は指摘0件で合格。新Ready世代の [Task-List PR Gate run 43579750621](https://github.com/kenta-0420/mannschaft/actions/runs/43579750621) 成功後にマージした。

変更は `MatchRequestControllerKeywordSearchIntegrationTest.java` だけ。保存から取得済みの所有IDを追跡して回収し、未知の子行があれば連鎖削除へ進まず、従前のSecurityContextをfinallyで復元する。元の検索14テストメソッドは維持した。製品・共通基底・build/CI・DDL・query・Clock/locks/generation/history/weights/deadlineの変更はない。

## 過去のcontrolled red / green（AC1〜6）

修正前source `da6e0c665b497b5d65a18c72557822a1a13b38e6` の専用driverで、同一JVM・共有MySQLの検索14件を成功させた後、所有membership残留0→1・対応user_rolesなしを観測した。続く `MembershipUserRoleConsistencyRepositoryIntegrationTest` は6件中5件失敗・1件成功となった。これはfixture残留による原因のcontrolled再現であり、発端のPR #4308 CI run 43352034582で実際に加算されたrow IDは未特定である。

修正後source `651366631ae9ea22388dec5910097ed9170193ee` のv3 controlled検証では、元の検索14件と整合性6件が全成功した。`fault-setup-after-membership`・`fault-partial-save`・`fault-test-body` の3経路は意図した元失敗を保持した。全62保存について内部TX接続10と異なる固定observer接続9からcommit済み可視性を確認した（observerが9接続あるという意味ではない）。各経路後の所有行残留0・baseline復元、他fixtureの保護5行不変、正常・故障時の従前SecurityContext即時復元17件を確認した。observerとcontextはclose、専用containerは停止、専用検証コピーは撤去済みである。未知子行の連鎖削除防止と取得済みIDだけの回収はRootのsource検分でも確認した。全PIDの網羅を証明するものではない。

v3 historical sourceと最終headは所有テストblob `46e9ea405e3a6ac9a8fa27222ca7e6375bc14ac6` が同一。最終headと固定main `2b40343e8cfb6d452a9f2aaad7238cc3f4356888` の差分は当該テスト1ファイルのみで、他のtracked blobは全てmainと一致した。この同一性はcontrolled証拠をつなぐ根拠であり、以下の新head全CIとは別に扱う。

## 最終headの新CI

[Backend CI run 43571979399](https://github.com/kenta-0420/mannschaft/actions/runs/43571979399) は実装head `beb62201ba183d64e5ff469ea3d2e18c1ac9ee0c` に対してsuccess。実checkout `dcb92eff14d9cf1c29b2495ba610d7e2bb4982b1` はparentsが固定mainと実装head、treeは実装headと同一であることを公式commit応答でも確認した。

通常6shardと専用Archは全てfresh。通常33,310件（成功33,293・skip17）、検索14件・整合性6件は全成功。Role coreはConcurrency46＋RowWait12＋Diagnostics3＝61件成功で、Native2件成功は別群。archUnitTest37件、freeze integrity12件、専用archTest465件も成功した。担当が原runtime6件・Arch1件・log1件・通常XML6件を各1GETで取得し、Rootが独立parserで確認して、XML headerと全case indexの件数、全classのsource mappingが一致した。cached取得0、coverage/full results取得0。旧headのrun 43551890889の件数はこの結果へ移していない。

## 証拠の識別と適用範囲

採用した私有原本のSHA256を以下に示す。rawログ・個別行・私有絶対パスは公開文書へ含めない。

| 原本 | SHA256 |
| --- | --- |
| root-red-verification.json | `1f1d740a6224b717e542e32752dc012237c682530847d91244a3b77355131556` |
| root-green-v3-verification.json | `eca15eb65eb63e044561aeaaa18eaaf998a7d0a088c6e95007a749837a2e77ea` |
| root-source-review-6513666.json | `131f0dbe19008c4dbbadebefb8f8bad3068f4b4b7ab658826d3aed29657b64da` |
| source-bridge.json | `2b7d6b137d9514480ffc5856ec442811dc432ae928dc884ffb2206211ea93dfa` |
| root-final-adoption.json | `35628d8327c64315051354b6df99fa013e1e5ab938cc5f8eb8e252d217af88ea` |
| root-merge-completed.json | `acb8f956133718571164976d2f49c04cb8b3aa56f477e8a34ec9bbe5ba9cdc26` |
| root-verified-new-evidence.json | `44fcbd0554d0f8bf6b0b54f59a76f251558ed0b022fbe0150ff55748943edb41` |

red driver SHA256は `65732aefee6e408fc4400b37aef6036e79da51196f17db905b66fddda3b34d8b`、red実行receiptは `b89aa8f28f7f5c0528478a10f448d0ab8ecfb80013700452902300b4ca440e53`。green v3 driverは `ae17e20dded04ce9958eb0d023c0f791de90f1d4eaab6a0f6f90c03199195faf`、manifestは `7e111b23248cea60c506b1919646cdee2d63051d77e9486489886e8840161167`。

scopeはtest-only。製品の画面・API挙動・導線を変えないため、このfixture修正の実機・探索はnot-applicableとする。アプリ戦役の実機や新住民探索へ流用しない。本完了文書作成時のDB・container・runtime・build操作や原artifact再取得は行っていない。ローカル完了証拠CLIはGit構造と参照を検査し、GitHub API上のPR/CI/check世代の照合は新しいTask-List PR Gate CIへ委ねる。
