# CMP-261022-0338 Timeline 長語折返し実機（旧source 03be）

この記録は実装PRの旧head 03be10c6d87ceeec29d4b09f0bac4db0d391c514 で行った初回実機であり、最新head e202の成功結果ではない。system-admin不具合報告 #10のTimelineで、長い日本語/英数字混在、改行・空行、ASCII 2000文字、長URLを360/390pxで確認した。旧sourceの実機specは4件passed / 0 failed。

代表画像: [ASCII 2000文字・360px](real/ASCII2000-360.png)、[URL 2000文字・360px](real/URL2000-360.png)、[ASCII末尾計測・360px](real/ASCII-tail-360.png)。旧計測の [tail-metrics.json](real/tail-metrics.json) では360pxの本文領域clientWidth=scrollWidth=206、390pxは236=236。両幅とも末尾上端811px、下端831pxでviewport高さ844px内に収まった。header下端64px、本文scroller y=253..843.80。画像と寸法は旧専有runtime run由来。raw error-context.mdは転載していない。

旧実機sourceは frontend/test-results/timeline-wrap-real/artifacts-final-ready-warm、末尾計測・実見sourceは frontend/test-results/timeline-wrap-real/tail-metrics/tail-metrics-成功report10の末尾座標を読み取り補足。住民探索も別source 03be であり [住民探索overview](residents/overview.md) に記録する。最新追従head e202の正式実機4件は [最新実機記録](real.md) を参照する。