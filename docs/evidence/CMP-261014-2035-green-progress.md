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

重複を除く確認済み合計138件。その後、文字色保持slotを含むP14/ASTの限定18件と、検索・組織広告の限定18件も全成功・skip 0を確認した。追加の検索境界2件と再試行1件を含め、重複なし合計141件。P14の5件は画面側の翻訳式・操作なし・data-testid不変をASTで固定し、段落の実描画はPageHeader.specの13件で検証する。AST実app違反は8→0。変更対象と追加E2Eの対象lintはexit 0。検索2/3文字の境界追加2件、組織広告の再試行追加1件も限定再実行で成功した。

## 未完了の検証

幅別モックブラウザ196件（P6=120、P7=60、C3=6、実在UIのAPI契約=10）、P8/P9画像比較、全体typecheck/CI、独立検分、モックなし実機と探索は未完了。

専用3002でP8 billingの移行後撮影を完了後、blogのpage.gotoが60000msでタイムアウトした。続くWSL呼出が `Wsl/Service/CreateInstance/0x80072746` となり、Windowsの `wsl --list --verbose` でUbuntu-24.04のStoppedとVmmemWSLなしを確認した。同時刻のWindows側python.exe起動にも `System.OutOfMemoryException` があり、そのcallは比較script未作成のまま失敗していた。shutdown/terminate/他担当process killは実行していない。中断runをブラウザ成功証拠へ算入しない。

旧PNG・manifest・補完フォルダ副題は `.claude/cmp2035-verify/pre-final/` と `folders-subtitle-supplement/` に保持。移行後の撮影は別領域へ保存する。台帳や最終evidence JSONは完了化していない。

## CI初回指摘の修正

移設した新設specの `get(...).exists()` を存在検査用の `find(...).exists()` へ修正し、URLのstatic overrideを明示した。型エラーを抑制せず、次CIで検証する。移行前raw観測2 JSONは、完了証拠validatorが検査する直下の `CMP-*.json` と分類を分け、`observations/CMP-261014-2035/` へ移設した。内容のSHA256一致を確認し、red文書のリンクだけを追従した。validatorと成功条件は変更していない。

限定再実行後のWindows空き物理メモリは11,990MiB、VmmemWSLのworking setは9,594MiBだった。次段階は専用Nuxt1台とブラウザ1 workerで実行する。


## P9初期実測と最小補正（ブラウザ再測定は未完了）

専有証拠 `post-anchor-initial/manifest.json` と旧 `pre-final/manifest.json` を比較し、friend-folders/LINE/SNSのh1が6px、member-permissionsが8px下がったことを実測した。左右接触画像を実装担当・本陣が実見。原文のタイトル位置不変へ戻すため、当該4画面の既存attrsを使いmd以上のタイトル行のみ負marginを付与した。LINEは元 `flex gap-2` wrapperもactionsへ保持し、削除ボタンの元42px高を維持する。PageHeader全体の新optionや広いCSS補正は導入していない。

ScopeGuideのh1左上xyと文字列は同一。副題を別行へ移した結果、旧h1幅289.5625/高さ72から自然幅291.5/高さ36になり、末尾1字の折返しが消えた。正本P9の禁止はタイトル位置変化であり全bbox寸法一致ではないため、本陣裁可により書体不変・overflowなしの確認後に許容する。旧新bbox生値は保全し、旧同一行DOMと書体/利用可能幅の再現観測を準備した。まだ再現観測の実行前である。

補正後5画面の撮影は最初のfoldersでpage.goto 60秒timeoutとなったため、環境未実行として分離。LINE単画面は途中で撮れたがsuite合格には数えない。撮影sessionを中断しChrome残存なしを確認、自分のNuxt3002も停止してローカル実行窓を返却した。Windows空きは停止後約4.68GiB。別担当のranch Gradleや共有daemonを変更していない。P8全8画像差分ゼロ、P9全9画面、P6/P7/strict全196件は引き続き未完了。

checkpoint b0d6fa2e6fの全active CIは成功（Smoke/Frontend Install/harness含む）。本節の位置補正はその後の差分であり、対応CIとブラウザ再検証は別途追跡する。台帳完了化・最終evidence JSON作成・マージは行わない。
