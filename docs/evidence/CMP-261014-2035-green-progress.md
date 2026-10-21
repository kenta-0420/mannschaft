# CMP-261014-2035 green実装の検証記録

設計正本v5と2026-10-21御裁可に基づく。戦役完了・実機完了の記録ではない。

## 入力と試験配置

Windows専有worktreeで編集し、専用WSL `/home/kenta/verify-friend2035` へファイル単位で同期後、`cmp` で全入力一致を確認して試験する。基点・red commit・旧画像は変更しない。同期前入力が混在した初回runは正式greenの証拠から除外した。

FE規約§11およびTEST§4.2に従い、redで新設した製品specだけを移設した。既存specの拡張とhelper自身のspecは元位置を維持する。検証コピーに残った旧3パスは今回の対応関係を確認して明示的に除外した。

| redパス（3452b212db） | greenパス | 実件数 |
|---|---|---:|
| tests/unit/pages/admin/settings-header-actions.spec.ts | app/pages/admin/settings-header-actions.spec.ts | 6 |
| tests/unit/pages/teams/friend-folders-header.spec.ts | app/pages/teams/[slug]/friend-folders-header.spec.ts | 5 |
| tests/unit/pages/organizations/advertiser-campaign-header.spec.ts | app/pages/organizations/[slug]/advertiser/campaigns/advertiser-campaign-header.spec.ts | 3 |

## 確認済み結果

Node 22.23.2/npm 10.9.8、Nuxt hook/test timeout 600000、maxWorkers=1。

| 対象 | 実件数 | 成功 | 失敗 | skip |
|---|---:|---:|---:|---:|
| red由来node契約4 spec | 59 | 59 | 0 | 0 |
| red由来Nuxt契約8 spec（移設後） | 71 | 71 | 0 | 0 |
| P14追加後node契約（上の59件を含む） | 64 | 64 | 0 | 0 |
| R5既存FriendForwardModal回帰 | 3 | 3 | 0 | 0 |

重複を除く確認済み合計138件。上表は照合済み入力での成功履歴であり、その後の4画面の文字色保持slot移行・追加境界試験・再試行キー修正は再検証待ち。P14の5件は画面側の翻訳式・操作なし・data-testid不変をASTで固定し、段落の実描画はPageHeader.specの13件で検証する。AST実app違反は8→0。変更対象と追加E2Eの対象lintはexit 0。検索2/3文字の境界追加2件、組織広告の再試行追加1件は未実行。

## 未完了の検証

幅別モックブラウザ196件（P6=120、P7=60、C3=6、実在UIのAPI契約=10）、P8/P9画像比較、全体typecheck/CI、独立検分、モックなし実機と探索は未完了。

専用3002でP8 billingの移行後撮影を完了後、blogのpage.gotoが60000msでタイムアウトした。続くWSL呼出が `Wsl/Service/CreateInstance/0x80072746` となり、Windowsの `wsl --list --verbose` でUbuntu-24.04のStoppedとVmmemWSLなしを確認した。同時刻のWindows側python.exe起動にも `System.OutOfMemoryException` があり、そのcallは比較script未作成のまま失敗していた。shutdown/terminate/他担当process killは実行していない。中断runをブラウザ成功証拠へ算入しない。

旧PNG・manifest・補完フォルダ副題は `.claude/cmp2035-verify/pre-final/` と `folders-subtitle-supplement/` に保持。移行後の撮影は別領域へ保存する。台帳や最終evidence JSONは完了化していない。
