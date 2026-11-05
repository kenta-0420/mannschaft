# CMP-261105-0755 締めの集計と重み原本

開始親 `bb9e81532ee8b6b24445dd50332a8e909110ef23`。[限定裁可・対象宣言](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6646486176)は先行8記録と#4294の追加2記録を別々に今回限り受け入れる。内訳・返済と置換の区別は [履歴](../../architecture/archunit_freeze_history.md) に記載。

固定親の8 blobを一時領域に抽出し既存 `collect_archunit_debt.py` はexit 0、7登録を照合した。全7の重複とUTF-16 Java自然順の崩れは各0。実測はAUTHZ 0、D-1 2153、D-2b 569、D-3 1335、D-3T 7352、D-5 1881、ServiceAPI 600。以下は親のGit blob IDであり、store自体は本変更で触れない。

| 対象 | Git blob |
|---|---|
| stored.rules | `97c34f263d0067def6161b6dd506321abf4cc95c` |
| AUTHZ | `e69de29bb2d1d6434b8b29ae775ad8c2e48c5391` |
| D-1 | `8045c4f3a9a4eafd661c9c749103c75615823c49` |
| D-2b | `efdea9a22ceee9f3efa2ec0c3d92fe7a398e8222` |
| D-3 | `3a79b24aa14b5efb4b9ecf8f2771d3c0b4f73afd` |
| D-3T | `5a256dcd2035c036c56ceb680c9771bc30c76dff` |
| D-5 | `f7fb59b8af37160b58169ce85077a6d6731ab97d` |
| ServiceAPI | `94f9ec63847eecd00c0088b20338d0c2784dc704` |

## 重み原本

着手時に成功nightlyを1回選択し、[run 41736609204](https://github.com/kenta-0420/mannschaft/actions/runs/41736609204)、head `8d05e6ad970af5c5f86ed88a2554a15dfa74a31d`、attempt 1、schedule/main、2026-11-03 23:07:33 UTCを固定。Windows上で既存 `regen-shard-weights.sh` のPython本体を変更せず正規targetに実行しexit 0。6つの同一run/head/attemptの非期限切れartifactについてdigest・安全path・fresh/completed・exit・通常test終端・class開始終了対応・shard間重複・migration/Flyway存在を既存判定が検証した。3,151 class、migration/Flyway 80、同集計 11,602.206秒。full profileは固定nightly workflowの `./gradlew test --rerun` による。重みはSQL性能や壁時計短縮の保証ではない。

生成ファイルのGit正規blob `84c56b446131c32db0de150e882f4f8dd5dfa02f`、生成時Windows raw SHA-256 `18f7d4ef0bcf8a289145705a554096323203c4bb9f4dd7beab4f6e0d7bdf4f26`（今回rawはLF）。旧blob `dd8cf26bdb058c2eb6ca1995457050c7294e3fed`。rawとGit blobは異なる形式の識別子であり、checkout時の改行変換後rawは変わり得る。ZIP原本や全manifestは保存・コミットしない。

## sourceの静的分類

親refの全 `.java`（package-info/module-info除外）をGit blobで比較。既存の固定 `e1223bfc7e81e604c68a7038fff71ea60983a4d4` 分類3,437 sourceのうち削除0・変更10・追加8。重みの3,151キーは既存weighted source 3,151と完全一致し削除/filename非対応0。変更10件は全てweightedで、対象top-levelの注釈と分類を再確認。追加8件は通常未計測テスト7とhelper 1。現refの静的分類はweighted 3151（class-disabled 2含む）、通常未計測15、arch 62、perf 9、freeze専用 1、ER専用 1、abstract 28、annotation helper 1、helper/fixture 177、計3445。未計測15はいずれもnightly後追加。

未計測一覧（接頭辞 `com.mannschaft.app.`）:

- `billing.api.BillingEntitledFeaturesN1IT`、`billing.api.BillingEntitledFeaturesQueryCountTest`、`common.erdiagram.ErDiagramRendererTest`、`payment.service.PaymentRequirementN1IT`
- `timetable.personal.service.PersonalTimetableDashboardN1IT`、`timetable.personal.service.PersonalTimetableDashboardUnlinkedSlotTest`、`todo.service.TodoProjectListN1IT`、`village.service.VillageFeedN1IT`
- `admin.service.AdminBusinessAlertN1IT`、`chat.service.ChatMessageEnrichN1IT`、`dashboard.controller.DashboardControllerN1IT`、`dashboard.service.ChatHubFolderN1IT`、`dashboard.service.ScopeWidgetSummaryN1IT`、`organization.service.OrganizationMembersN1IT`、`team.service.TeamSearchN1IT`

分類はfilenameとtop-level宣言・注釈に基づく静的照合であり、二次top-level・継承/meta注釈・条件付き実行や実candidate.classの完全証明ではない。既存CIの実class 4 profile coverage・XML/runtimeで別途照合する。未計測を0秒とせず既存hash fallbackを維持する。

## 検証状態

既存Python再生成unit 6件は変更前にrootが成功確認。同一コードの重複実行は省略。ローカル重いGradle、実class 4 profile、Integrity SelfTest 17、専用Integrity 12、および実装headの全必須CIは本記録時点で未実施・未確認。CI結果と独立検分は実装merge後の別完了記録で示す。画面/API/業務認可の変更はなく、実機・探索はtest-only scopeにおいて対象外。

## 最新main追従時のsource再確認

通常mergeしたmain `f51ee7c58660297b9ce2eb7bd252da2b6eed2dd6` では開始親 `bb9e81532ee8b6b24445dd50332a8e909110ef23` からtest source 2ファイルのみが変更された。`ErDiagramRenderer` はclass注釈・テスト注釈なしのhelperのまま、`ErDiagramRendererTest` はclassの `@DisplayName` のままで通常 `@Test` が23→26となり、nightly未計測の通常テスト分類を維持する。追加・削除なし、静的source 3445と上記分類数は不変。実class coverage・XMLは追従後の実装headで改めて確認する。開始親とnightly採用時の記録は上記のとおり維持する。
