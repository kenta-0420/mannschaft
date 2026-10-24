# CMP-261024-0528 検証・完了記録

PR #4083、最終実装head `814d1d89a271b824add2fe5ed25b968e7602916e`。製品検証head ff92de745f9f75e48e532e03451e789b500452ec以後は監査script/docs更新とmainの台帳追加追従。最終headのCI成功後、2026-10-24T10:00:38ZにmergeSHA `840a6fed82ef690f7ab7038ffb31766a3119c2c6` でマージ済み。本書は既存の実検証とGitHub照合結果を記録したもの。

## 検分

殿本人の検分で未対応コードfinding 0。日付クリック、保存再取得、種別導線、取得失敗、認可、日時復元、期間交差、呼出元/API/実DBまで照合済み。EX-1〜4もroot検分finding0、Node25/25と実監査gate0を独立確認。根拠は既存戦役台帳とローカルcompletion.json。review passedはこの既存結果を示す。

## 実機

カレンダー最終5件green/skip0。個人/team/org、権限なしMEMBER、他テナント、月跨ぎを実API・実画面で検証した。旧BEでは月跨ぎ両API200/data空、日別一覧assert red。修正BEでは両API200/count1/matching1と翌月一覧を確認した。

移行後はカレンダー5＋代表フォーム3＝8 passed/skip0/flaky0、89.2秒。[実レポート抽出](real-results-summary.json)。初回7pass/1failは既存BE仕様に対するspec期待の誤りで製品修正なし。count3 assertion最終追加後はテンプレート単独1件green。最終assert追加後8件一括再実行とは記載しない。登録invalid/POST0、template default/reset、住戸trim/create、日付/TZ/reminders保持PATCH、所有データcleanup204を確認した。

## 探索

カレンダー3住民は別account/contextで実施。mobileは360/390px操作、cancelは入力取消しと別日状態・desktop寸法を確認。timeは翌月の発見に失敗し、月跨ぎの製品不備を発見。修正後DAY004実機とBE6回帰で解消を確認したが、当時の探索を全目的成功へ改変しない。[mobile](exploration-calendar-mobile.md)、[cancel](exploration-calendar-cancel.md)、[time](exploration-calendar-time.md)。rootはmobile/desktop実画像を確認済み。

移行後も3住民探索を完了。mobile目的達成、localeは既存日本語直書きのため非日本語条件理解が未達、interruptはテンプレート導線未発見で中断再開が未検証。公開2名は独立匿名context、管理1名は専用admin。新規確定製品不具合0。[観測](exploration-zod-summary.json)。exploratory passedは実行・記録済みを示し、全目的達成や承認済coverageを意味しない。元overlayはobserved-unverified/approvedCoverage=false。interruptはHTTP method未収集のため探索POST0を断定しない。

気づき箱validatorは上流snapshot参照集合とtask-list不整合でoverlay自己検証前に停止した。構文・宣言・証跡存在は確認したがvalidator成功扱いしない。

## 自動検証

- カレンダー最終11spec127test/skip0。[当初](calendar-summary.md)、[編集TZ追加/source境界](timezone-summary.md)。保存失敗時入力維持も検証。
- BE127/skip0、別にArch37＋freeze2 green。[期間交差6経路の実DB回帰と権限負例](period-summary.md)。bootJar成功。BE全量実行という意味ではない。
- Zod/Happy全量初回690file中685pass/5fail、5991pass/2fail/既存SSR5skip。nativeコピーの実リポジトリ横断fixture不足。実fixture補完後、失敗5fileを再実行し5file836pass/skip0。再試行間を単純加算しない。[初回抜粋](transcripts/full-unit-initial-excerpt.txt)、[再試行](transcripts/fixture-retry.txt)。SSR5skipを合格へ数えない。
- [通常npm ci](transcripts/npm-ci.txt)、[npm ls](transcripts/npm-ls.txt)、[lint](transcripts/lint.txt)成功（既存warning19）、[型検査](transcripts/typecheck.txt)成功（8GB、既定4GB OOM履歴あり）。platform lock140entry保持。[関連unit](transcripts/zod-related.txt)。
- Windows Nuxt初期化timeout、BE discovery中断は未実行でred/greenへ数えない。native WSLで実assertを検証した。
- reset/localeのmutation red→green、forge期限境界Node25件red→greenの実ログを同梱した。回帰JSONは各実ログを参照する。

## 期限付き例外

Zod4.6.7/HappyDOM21.0.4へ更新しZod3 adapterを撤去。raw high9→6、critical0。残る6entryはnode-forge由来で脆弱性は未解消。ユーザーはGHSA-86w9-cpqp-85rvの短期個別例外を再承認。forgeのみ2026-10-30 UTC末まで、2026-10-31T00:00:00Z以降fail。braces期限2026-10-23 UTC末は変更なし。自動延長なし。

[静的到達性調査](forge-reachability.md)では当該RSA署名検証呼出経路は見つからず、本番.outputからの除外は未実測。同書の「延長していない」は再承認前の調査時点の記述。承認後の実npm auditはexit1、照合gateはexit0。[実監査](transcripts/forge-audit-gate.txt)。unknown/critical/不正report/通信失敗、range/locked version/既知node/edgeのfail closedを維持。

## AC対応

AC-1/2/4/5/7はcalendar-summary.mdとtimezone-summary.mdのwidget/dialog/form/composableテスト、AC-3/6は追加でperiod-summary.mdの境界・認可回帰、AC-8は日時specと探索寸法/実画像、AC-9は実機8件と両探索記録へ対応する。DEP-1はnpm ci/npm lsと実監査、DEP-2/3は関連unitとreset回帰、DEP-4はlocale回帰と関連unit、DEP-5は実機と探索、DEP-6はlint/型検査/関連テストに対応。EX-1〜3はforgeの期限・fail closed回帰、EX-4は到達性調査と正本文書、EX-5はNode25件と実監査に対応。

## 最終CIとマージ

最終head 814d1d89a271b824add2fe5ed25b968e7602916eのFrontend40082849170、Backend40082849135、FE E2E40082849118、real API40082849085、lock40082849112、Lighthouse40082849104がsuccess。PR4083は2026-10-24T10:00:38ZにmergeSHA840a6fed82ef690f7ab7038ffb31766a3119c2c6でマージ済み。GitHub APIで実PR・変更64file・マージ時点check/status・6CIrunを取得し、正本summarizeImplementationChecksでsuccess28/skipped2を確認。validateEvidenceとvalidateCompletionChangesはいずれも成功。全runは同一repo・同一head・success。[照合結果](remote-validation.json)。台帳差分は自CMPの654行目のみ、7列を維持。

Frontend全量は690/690file、6822tests passed、既存SSR5skip、810.70秒。別ranch-clipsは2file27passed、1.65秒。[CIログ抜粋](transcripts/ci-unit-summary.txt)。Backend6shard・ArchUnit・Non-JST、SSR/cookie、API、lockも成功。ローカル初回fixture不足による失敗履歴と再試行は前節へ保持した。DEP-1/6・EX-5とAC-9の最終CI条件を満たした。