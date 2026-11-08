# CMP-261022-0338 Timeline 長語折返し実機

## 最新実機

専有runtimeで2026-10-26に正式specを1回実行し、4件passed / 0 failed、所要2分。Frontendは e202bb96c03fc1b9197bfad8d27217dc7b4e8687、Backendは d969c21a5ef184112bdd50957e20d6e7f73f4b1a、jar SHA-256 906d0e6a8a6bf758d5544972b60bb005c8f0e2b03e85e698cef36243dfae498d。専有DBのreport #11はbeforeAllのsetup APIで作成し、その後、自然UI導線から詳細を開いてコメント4種類を画面で送信・再取得した。証跡: [実行結果](e202/real-execution.json)、[runtime](e202/runtime.json)、[test stdout](e202/real.stdout.log)、[stderr](e202/real.stderr.log)。起動前warmupは実API成功、pageerror/requestfailedなしを [warmup readiness](e202/warmup-readiness.json) で確認。

ASCII 2000文字、URL 2000文字、日本語混在2000文字、改行と空行を360/390pxで検証。全画面でRange外側描画0、scrollWidth=clientWidth。360pxでは206/206、390pxでは236/236。各コメント末尾へスクロールでき、長文カード同士の重なりなし。権限のない一般会員・組織管理者・別組織会員の詳細/timeline GETとコメントPOSTは403、直URLはdashboardへ拒否された。

代表画像: [ASCII 360px](e202/real/ASCII2000-360.png)、[URL 360px](e202/real/URL2000-360.png)、[改行・空行 360px](e202/real/改行空行-360.png)。read-only末尾計測の最新成功は [tail-metrics.json](e202/real/tail-metrics.json) と対応する [追加計測stdout](e202/tail-ready.stdout.log) に記録。390pxはheader下端64px、scroller y=253..843.797、末尾 y=811..831、client/scroll 236/236。360pxでも末尾 y=811..831、client/scroll 206/206。画像はT担当の最終原本から代表分だけ複製した。

## 追従と試験境界

PR #4125 のhead e202bb96c03fc1b9197bfad8d27217dc7b4e8687 は2026-10-26にmerge済み。追従前の実機4件および3住民探索はsource 03be10c6d87ceeec29d4b09f0bac4db0d391c514 として別資料に残し、今回の最新head実機4件と混同しない。main追従で検査した16の対象/ガードblob不変は [main-follow.json](e202/main-follow.json)。

初回client warmupはVite optimizer初回処理後のdocument reload/reconnectで失敗し、次のready warmupと正式実機を分けて記録した。末尾座標の最初の補足試験も描画前にbodyをscrollする順序で失敗したため、本文/API描画後に合わせた補足を別途実行して成功した。これらの失敗は正式specの4/4結果へ混ぜていない。原本全体の統合要約は [summary.json](e202/summary.json)。

旧source 03beの初回実機と住民探索は [旧実機記録](real-03be.md) と [住民記録](residents/overview.md) に分けて保持する。