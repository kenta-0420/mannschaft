# CMP-261022-0338 Timeline 長語折返し回帰

## 実装headと根拠

実装PR [#4125](https://github.com/kenta-0420/mannschaft/pull/4125) は e202bb96c03fc1b9197bfad8d27217dc7b4e8687 でmain追従し、同headでmerge済み。製品はFE e202bb96c03fc1b9197bfad8d27217dc7b4e8687、実機BE d969c21a5ef184112bdd50957e20d6e7f73f4b1a、起動jar SHA-256 906d0e6a8a6bf758d5544972b60bb005c8f0e2b03e85e698cef36243dfae498d。main追従前後で対象componentと関連15 blob、計16 blobが不変であることを [main-follow.json](e202/main-follow.json) で確認した。旧03beのCI・実機を新headの合格へ流用していない。住民探索は新head実行と偽らず別sourceとして記録し、target/guard16 blob不変根拠とあわせて再利用している。

## 実Vue限定描画のred/green

[verify-error-report-timeline-wrap.mjs](../../../frontend/scripts/verify-error-report-timeline-wrap.mjs) は実Vue SFC、DynamicScroller、Tailwind CSSを組み合わせた限定描画ハーネス。認証/APIを検査するものではなく、実機Playwright specとは別の回帰確認である。

制御redでは本文幅320pxに対し scrollWidth=16760、Rangeの外側描画1件でexit 1。保存した原本は [red stdout](e202/limited/red.stdout.log) と [red stderr](e202/limited/red.stderr.log)。greenでは長さ2000のASCII・URL・日本語混在を390/360px viewport（本文幅320/290px）で検証し、9組の測定すべて scrollWidth=width、outside=0。末尾文字到達、履歴行の非重なりも確認し、空/null、空行、空状態、loading中/追加取得、仮想高さとresizeを通してPASSでexit 0。原本は [green stdout](e202/limited/green.stdout.log) / [stderr](e202/limited/green.stderr.log)。

red制御が使ったcomponent原本はSHA-256 e00ebc7d77ec3bc8b7d742c105b97f329a347b60e41d3fb3e98b8cc9edfa67fb（5,343 bytes）。finally後・green後とも同じSHAでbyte一致し、git statusも前後clean。復元証明は [restoration.json](e202/limited/restoration.json)。赤用一時変更は残していない。

## ACとgreen試験

- AC-TL-1: 2000文字の連続ASCII、URL、日本語混在を360/390pxで横はみ出しなく折り返し、末尾まで読める。限定ハーネス9測定と実機specの本文Range計測。
- AC-TL-2: 改行・空行、null/空文字、空状態を維持する。限定描画の本文条件テストで確認。
- AC-TL-3: 仮想化・resize・追加取得/loadingの表示と末尾到達を維持する。限定描画および実機specで確認。
- AC-TL-4: 一般会員、組織管理者、別組織会員が詳細・timeline・コメントAPIへアクセスできず、URL直打ちでもdashboardへ拒否される。実機specの3権限テストはすべて403と非表示を検証。

green試験sourceは [実機spec](../../../frontend/tests/e2e/real/admin/error-report-timeline-wrap.real.spec.ts) と [限定描画script](../../../frontend/scripts/verify-error-report-timeline-wrap.mjs)。GitHub ActionsのFrontend CI 40265342034 と E2E 40265342068 は実装head e202...でsuccess。最新head実機の内容・境界は [実機記録](real.md) に分けて記録する。