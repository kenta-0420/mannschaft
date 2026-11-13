# CMP-261108-2000 L-SHIME早期締め

前回締め以後の全7storeを照合し、main実測へ4上限を下げ、最新適格成功nightlyの重み表を更新する。親CMP-261021-0017とL5-Iの完了ではない。

## 現在の採用内容

`e2be47c9bfb0229a8f7582e6a8894c432054d844` の重み表は、正規Nightly `43093835182` attempt2由来の全3218行で、canonical LFのSHA-256は `6d5bea1188f56bbe293628278923c4172785a6adb1d2d96ead178a5c14d5acf0`。入力資格と適用範囲は[重み更新の根拠](../CMP-261111-0508-shard-weights-20261112.md)に記録した。以下の3182行・`74be`・旧CI失敗は原実装時点の履歴として保持する。

同headの通常Backend run `43133320581` は全12ジョブ成功。全6の実checkoutは `83a2979dabf8beec66aef3990bb8f394f994fe31` で、対象headと同じtree `703360ab36f10ca9801f33311e15f0cb9807216d` を確認した。通常テストは全6で実行され、ログ集計は33,302件、成功33,285・失敗0・skip17。実行全体76分08秒、各テストstepは56.10〜69.95分だった。これは当該sourceでの1回の観測として保持する。全JUnit XML件数の独立集計は未実施である。[重み更新headの通常CI](https://github.com/kenta-0420/mannschaft/actions/runs/43133320581)

compiled9793/top-level3581の全4profile coverage、独立Integrity taskの2case、別JVMのSelfTest 2caseとIntegritySelfTest 17caseの成功を原ログと対象XMLで照合した。今回のmain `1e21bb181497ac6e1d064bede6d21e2041aec0f1` 追従では新しい本番・テストを取り込むため、更新headのCIと最終検分が残る。PR4410のDraftとWS→closure→canonicalの採用順を維持する。[通常CIのcoverage・独立ArchUnit検証](https://github.com/kenta-0420/mannschaft/actions/runs/43133320581)

## 所有と先行登録

ownerは `e8ded143-7eea-4189-a530-7708882e1200`。正規closure claim `027449f2f8260338bf7054a54df38d410f8089a0`、planning `8df21e657e65af07080c935ddbdfddd36ea29b1a`、working `e36378244b8b7c76581c74f9866d9d33adfbf6fc` の順に再開した。workingの固定manifestが `freeze-constants` と `weights` を予約する。他領地のbuild/measurementを取得・解放しない。

子CMPの[登録PR #4407](https://github.com/kenta-0420/mannschaft/pull/4407)はTask-List末尾7列1行だけ。main `a91bc4ccf063a615a54b7e4cf178d03dafc7ce61` のP4実装中行を確認後、[対象宣言](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6720882910)を行い、共有予約後に別実装worktreeで編集した。登録head `dfe73b8e4d75822284511a20588fa80c5cc9ad46` は全18checkが成功または予定skip。gitleaks run `42474890497` attempt1はバイナリ取得後のgzip不正で実scan未到達、同headの失敗jobだけを再実行したattempt2は実scanを含めsuccess。未scanを成功とは扱わない。

前回11月5日から週次期限前の早期締めであり、計画§7.4の「週1回以上」の監査と、定数・減少行照合・推移報告・weightsの全出口を維持する。

## 全7storeと減少行の照合

前回はmain `2557ef2a314076683df993376a8f4d0e4ac6da1f`、今回の固定storeは登録採用main `a91bc4ccf063a615a54b7e4cf178d03dafc7ce61`。最新側の既存 `collect_archunit_debt.py` を固定Git blobの全7storeへ入力し、両方exit0。重複0、自然順、`stored.rules`の閉集合を確認した。[全domain集計・blob SHA・削除58行の原文](store-audit.json)を保持する。

| 番人 | 前回上限/実測 | 今回実測/新上限 | 差分 |
|---|---:|---:|---:|
| AUTHZ_WAVE4 | 0 | 0 | 0 |
| D-1 | 2153 | 2146 | -7 |
| D-2b（親返済対象外） | 569 | 569 | 0 |
| D-3 | 1335 | 1320 | -15 |
| D-3T | 7352 | 7331 | -21 |
| D-5 | 1881 | 1866 | -15 |
| ServiceAPI | 600 | 600 | 0 |
| 合計 | 13890 | 13832 | -58 |

前回以後のstore変更は[PR #4394](https://github.com/kenta-0420/mannschaft/pull/4394)、commit `bce74a785016f24da1a2097b0be49b24ce90759a` の削除58行のみ。追加・置換は0。[原因PR #4371](https://github.com/kenta-0420/mannschaft/pull/4371)、commit `c65748ecfcbcaa07a6c8dfaa522400f09e042a5f` で、`InviteService`の `joinByInvite`（2署名）・`declineInvite`・`assignToFolder`・`checkNotBlocked`・`recordMembershipInviteAudit`・`resolveTargetName` と他ドメインRepository注入が撤去された。

D-1はそのEntity依存7行、D-3/D-5はconstructor・field・call記録各15行、D-3Tは旧入口からの到達21行で、削除原文・旧ソースと対応する。移設先の `InviteAcceptanceFacade` と `InviteFolderPlacementService` はTXを持たず、Writerはrole内Repositoryを扱い、team/orgの照会を各ドメインServiceへ委譲する。`MembershipGrantService`のrole/membership越境や、`InviteService.createInviteToken` / `previewInvite`由来の既存記録は残す。58行の消失をrole全域の負債0やclass-pair全解消とは扱わず、既知後続CMP-261107-1832/1833も本締めで解消しない。store本体をこのPRで変更しない。

[L0-Cドメイン別推移](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6720913549)の全非0差分はroleのみ: D-1 59→52、D-3 54→39、D-3T 177→156、D-5 67→52。他の番人・domain差分0、合計-58と全7store差分が一致する。対象先domainを別加算せず、物理行をdedupしない。

## weightsの原本と適用判断

APIで確認した最新成功nightlyは[run 42416993329](https://github.com/kenta-0420/mannschaft/actions/runs/42416993329)、head `bf427ea138eaa9e2ff31b85d469f19137fbea51a`、attempt1。固定workflowの除外なしfull profile、同一run/head/attemptの全6 fresh runtime、cache0、exit0、正常task終端、class開始/終了対応、重複0、artifact digestとsafe pathを既存generatorで検証した原本を使用する。[原本検証proof](nightly-artifact-proof.json)には全artifact digestとmember SHAを保存した。失敗/partial PR CIの実測を生成入力へ混ぜない。

今回、最新mainのgenerator SHA `b4f8815711b6e459d4df754e412a8fb0eaac0b95c591a3bd1452479620b30602` が原本と一致し、保存された全memberがproofのbyte数/SHAと一致することを再確認した。無改変の `build_weights` / `render` を再実行し、実測3181件と正規render SHA `8707e251c88532e6b7fe436516210cd0a5496a86af59f66b964f15cfc49d5a87` を再現した。[今回の再検証](runtime-revalidation.json)は、元ZIP取得時のdigest/safe_zip検証と区別する。原本ZIPを再取得・再圧縮した検証ではない。

実測3181件から、現sourceにない旧 `chat.ChatChannelAccessGuardTest` 1件を除外し、現存する未計測2件の旧歴史値を維持した3182件を適用する。歴史値は `reservation.EmergencyClosureIdempotencyIT=87.709` と `reservation.migration.FlywayExistingDataEmergencyClosureIdempotencyMigrationTest=117.981`。この2値を本nightly実測とは記載しない。移動後 `chat.service.ChatChannelAccessGuardTest` へ旧値を転記せず、未計測の新FQCNは既存hash fallbackに任せる。

私有authoring-copyはCRLF 3193・235274 bytes・SHA256 `cc907fedc56c93ee133684fb363064f3660c9070c61939866c2c0d9ebe24d462`。Gitの通常EOL正規化後、exact実装commitのcanonical LF blobは232081 bytes・SHA256 `74be9c96f7883cec63ab7f6d8a9a0fd293512296802881104a8074590b024676`（Git blob `5966c9195a8c5dff161cb8094317c770bfe76e0e`）。両者はCRLF→LF後に全bytes一致し、3182 key/秒数は全件同一である。先報の「Git内容SHA cc907」は誤りとして[訂正原本](eol-normalization.json)へ分離する。元の生成・訂正receiptはauthoring-copyの証拠として保持する。現表3161件に対し追加22・更新3128・同値32・削除1。全3182keyの秒数・実測/歴史値の由来・現sourceパス/SHA/直接annotation分類を[全key TSV](weights-provenance.tsv)に置き、[source/filter manifest](weights-source-manifest.json)へ未計測・arch/perf/abstract/条件付き/専用Integrity/補助sourceの一覧と適用判断を記録する。3506はsourceファイルの棚卸数であり、実行クラス数ではない。継承tag、補助class、package-info等の最終的な実class集合は既存compiled coverageで判定する。

headerのmigrationSecondsはmigration小計で、全classのtotalSecondsとは別である。[訂正receipt](header-correction-receipt.json)によりheader修正前後でweights本文SHA `fd702f1e7a845bf865c8c46b5d67c24e950d7aa6c7708f688640c5cf7c2ad836` が不変と確認する。generator/ShardAssignment/CI/timeout/6shard/2fork/番人を変更しない。この更新だけで85分内完走を保証しない。

## ACと検証範囲

| AC | 検証・証拠 | 状態 |
|---|---|---|
| 正規所有・新周期先行登録・対象宣言・共有予約 | 上記claim/登録PR/main/親Issue/working | 確認済み |
| 全7store実測一致・未知追加/減少なし | store-audit、4定数差分、減少行と#4371/#4394の対応 | 読取照合済み |
| L0-Cの全domain推移と履歴 | 親報告6720913549、archunit_freeze_history.md | 記録済み |
| 適格成功nightly・全key由来/source分類 | 原本proof、無改変再検証、TSV/manifest、receipt | 軽量検証済み |
| generatorの既存回帰 | `python -m unittest scripts/test_regen_shard_weights.py`、6件、failure/error0（0.280秒） | 成功 |
| compiled全4profile coverage・Integrity・SelfTest・ArchUnit・通常6shard/集約・全必須CI | 本実装exact headの既存CI | 未完了 |
| 独立検分 | rootが本差分・AC・原本・同head CIを照合 | 未完了 |

[ローカル検証記録](local-validation.json)。ローカルGradle/heavy実行は0。新しい検出分岐やgeneratorを実装しておらず、既存の意味ある回帰と全CIを使用する。実機/画面探索は製品UI/API/DB・認可・画面導線を変更しないtest-onlyのため理由付き対象外で、passedへ読み替えない。全CIまたは独立検分が未完了の間は出荷/レーンdoneとしない。


### 原実装commitのCI終端結果（訂正資料作成時点）

原実装 `57f72bf0e089c81f453bcaee417f87435571c3cb` のBackend run [42477757763](https://github.com/kenta-0420/mannschaft/actions/runs/42477757763) は失敗で終端した。actual checkoutは `7ef41b707df173923eaf2e0b157bd51fdb5196f2`、全treeは原実装と一致する。6 shardのXMLヘッダ合計は33,110件、failure 1、error 0、skip 17（各shardで反復するIntegrity検査等を含む）。5 shardは成功、shard 3は `InviteAcceptanceConcurrencyIT.blockCommittedAfterBlockCheck` のORGANIZATIONで10秒以内の実ロック待機を観測できず失敗した。原因は未特定であり、この文書訂正がC7を修正したとは扱わない。

未計測16クラス中14クラスは実XMLとcase classname/件数を照合した（Concurrencyの46件中1件は上記失敗）。`ErDiagramFlywayIT` は専用ER図タスク、`FlywayInviteTokenClaimsMigrationTest` は正常PRのmigration除外により未実行であり、成功扱いにしない。compiled 4 profile guardの成功は対象割当の証拠として区別する。17 skipは12外側クラスの既存Disabled注釈に対応し、skipを成功実行に数えない。[公開CI原本参照と限定](ci-terminal-57f.json)にartifact digestと集計資格を保存した。旧失敗原本を保持し、本訂正資料は新commitのCI合格を証明しない。
