# CMP-261105-0755 検証・完了記録

実装 [PR #4300](https://github.com/kenta-0420/mannschaft/pull/4300) は2026-11-05 04:17:43 UTCにmerge。実装head `daf25f889559d14cd32782ed5a7fa1f8af2a4be4`、merge commit `2557ef2a314076683df993376a8f4d0e4ac6da1f`。両treeは `ef7452d5e51c7cde708c2107d036dbe5e5f8f88f` で一致。子CMPの完了記録であり、親戦役・L5-Iは未完了。

## 凍結ストア・履歴と重み

[実装時summary](summary.md) に固定blob・全7件数・限定裁可先8件と後2件・nightly原本と重みhash・静的source分類を記録する。開始親から実装headまでstore全8 blobは不変、登録一致・重複0・Java自然順崩れ0。上限はD1 2157→2153、D3 1346→1335、D3T 7353→7352、D5 1897→1881で合計32減。[親Issueへのドメイン別報告](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6646795431) は全7 store物理行の純減31で、上限引下げ32とは別の集計。call occurrence減少や署名置換をclass-pair全解消と扱わない。

[nightly run 41736609204](https://github.com/kenta-0420/mannschaft/actions/runs/41736609204) attempt 1、head `8d05e6ad970af5c5f86ed88a2554a15dfa74a31d` の6 fresh runtimeを既存再生成器で検証し3151 class、migration/Flyway 80。出力Git blob `84c56b446131c32db0de150e882f4f8dd5dfa02f`。同head CIで既存Python unit 6件も成功した。重みは過去実測であり、SQL性能や壁時計の均等化を保証しない。

## 実装headのCI

[Backend CI run 41956802436 attempt 1](https://github.com/kenta-0420/mannschaft/actions/runs/41956802436/attempts/1) は実装headに一致しsuccess。マージ時点の24 checksは23 success・週次1 skipped、集約 `Compile & Test` job `127041183268` もsuccess。6 shard・lint・coverage・non-JST・archは成功。重いローカルGradleはCIと重複して実行していない。

[Shard coverage guard job 127013693064](https://github.com/kenta-0420/mannschaft/actions/runs/41956802436/job/127013693064) は実class file 9594、top-level 3499を検査。静的source 3445とは異なる数である。4 profile全てで欠落・重複・migration漏れ・nested分断0。

| 重み | migration | shard別class数 0–5 |
|---|---|---|
| なし | 含む | 568, 540, 590, 616, 589, 596 |
| なし | 除外 | 548, 527, 579, 602, 576, 587 |
| あり | 含む | 574, 575, 576, 585, 596, 593 |
| あり | 除外 | 563, 562, 562, 572, 582, 578 |

XML artifactはGitHub metadataの名前・ID・run/head・digestを照合し、ZIPをメモリ内で検証した。SHA-256はZIP原本のdigest。全量ZIP・ログはコミットしない。

| artifact | ID | SHA-256 |
|---|---:|---|
| archtest-results-xml-pr-4300 | 13383177680 | `b6dd16067ead2b376a43a36fc6e8e8ccff6995b329d1ae255946c5ca03a0c247` |
| test-results-xml-pr-4300-shard-0 | 13386064711 | `876c4c25b29f9065978d239d77dad275f6a8b185076c62d148cb6fdba2739f82` |
| test-results-xml-pr-4300-shard-1 | 13386554733 | `0a56b628c15685bab52a406e42771ca944a43308a7b0f1e1f46359eff5118370` |
| test-results-xml-pr-4300-shard-2 | 13386482537 | `fd37c904f675db20b2e33bd8379da49e4d983b0d7755e1500c4b494c02799b91` |
| test-results-xml-pr-4300-shard-3 | 13385599036 | `2d3d751157f9db3d0f8a68c4025d64116e5bf4ea9a981b1f93fe43793e7ebf83` |
| test-results-xml-pr-4300-shard-4 | 13386952510 | `5ff883fdb4bf7f20a4eefce580f88f63af8d6e061bac820bb0858f8355f9250e` |
| test-results-xml-pr-4300-shard-5 | 13387381993 | `b28b3c79c25c0db850360e520196e7f5e142168b397d00ee91d2c12e674e354c` |

`ArchUnitFreezeStoreIntegritySelfTest` は17 cases、専用Integrityは6 shard各2で計12 cases、いずれもfailure/error/skip各0。直接 `@AnalyzeClasses` を持つ18 sourceと18 engine XML suite各1回を照合し計37 cases、failure/error/skip各0、欠落・suite重複0。

静的未計測15 classは各1 shardで出現し、計77 cases、failure/error/skip各0、classのshard間重複0。`DashboardControllerN1IT` は同一shardのnested 2 XMLで計5 cases。testcase名重複は各XML内0、異なるnested XML間の同名比較は対象外。nested分断0は上記coverage guardで確認した。

| 未計測class（接頭辞 `com.mannschaft.app.`） | shard | cases |
|---|---:|---:|
| `billing.api.BillingEntitledFeaturesN1IT` | 5 | 3 |
| `billing.api.BillingEntitledFeaturesQueryCountTest` | 2 | 2 |
| `common.erdiagram.ErDiagramRendererTest` | 5 | 26 |
| `payment.service.PaymentRequirementN1IT` | 5 | 5 |
| `timetable.personal.service.PersonalTimetableDashboardN1IT` | 0 | 6 |
| `timetable.personal.service.PersonalTimetableDashboardUnlinkedSlotTest` | 4 | 1 |
| `todo.service.TodoProjectListN1IT` | 3 | 5 |
| `village.service.VillageFeedN1IT` | 2 | 5 |
| `admin.service.AdminBusinessAlertN1IT` | 0 | 3 |
| `chat.service.ChatMessageEnrichN1IT` | 2 | 2 |
| `dashboard.controller.DashboardControllerN1IT` | 5 | 5 |
| `dashboard.service.ChatHubFolderN1IT` | 1 | 3 |
| `dashboard.service.ScopeWidgetSummaryN1IT` | 0 | 2 |
| `organization.service.OrganizationMembersN1IT` | 3 | 6 |
| `team.service.TeamSearchN1IT` | 2 | 3 |

## 検分・適用範囲

Claude CLIを1回起動しexit 0、前後snapshot一致・コード指摘0だったが、Bash参照が拒否され一部未検証のためClaude検分は未完了。Codexが固定Git objectのstore・履歴再現、既存5 PRのcaller監査、新head XMLを補完し、最終コード指摘0・出荷可と判定した。Claude単独の完全合格とはしない。差分はテスト側定数・重み・文書でありscopeはtest-only。画面・API・DB・業務認可・導線を変更しないため実機と探索は対象外。
