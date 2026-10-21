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

## main追従headと画像再検査（2026-10-21）

mainを通常mergeしたhead `5c94540024b9ab1ad34ee767d42faee3db6577c0` のCIは全active check成功。Frontendは671 files/6602 tests成功、対象13specの141件・skip 0を実ログから確認した。組織広告同居specは再試行1件追加後4件となっている。

同head・52入力cmp一致のモック画像でP9は9/9数値条件成功。4画面のタイトルxy・ボタン幅/高さ/右端/旧新アンカーと許容縦移動を確認した。ScopeGuideは旧同一行DOMを再現し、font-family/30px/weight700/line-height36pxが一致、左上xyと文字列不変、overflowなし。本陣裁可の位置不変に従い末尾1字折返し解消を記録し、全bbox寸法一致という追加要件は課していない。

P8は既存7画面の厳密RGB差0に加え、teams広告詳細を同contextで2枚再撮影。初回は原本との差26pixel（CSV丸角のみ）、300ms後の2枚目は原本との差0でPNGのSHA256まで一致した。初回は前回差26pixel画像とファイルhashが一致。h1/Tag/actions/CSV bboxは不変。原本・失敗観測・両再撮影・全差分座標/RGB・hashを専有証拠に保全し、閾値緩和/基準上書き/製品CSS変更はしない。具体的描画機構は未確定だが、同context初回fullPage後300msの安定描画条件を実測した。本陣裁可により既存7zero＋当画面再実測zeroを対応付けた。

Nuxt起動のcold構築中は保護route curlが30秒/60秒000だったが、Nitro構築と初回page compile後は200/1.533秒へ回復。その後196件をworkers1で開始し22件連続成功、assert失敗なし。host空きが約10.4GiB→8.1GiB→6.9GiB→4.8GiBと低下したため、所有するrunとNuxtのみCtrlC停止した。JSON reporter最終出力前の中断につき結果JSONは未生成、196件の完了とは扱わない。成功22件はP6のja/en/zh/ko各5画面とesのfolders/exports。残174件は未完了。所有server/browser残存なしを確認し、他担当プロセスは変更していない。

## 正式検分によるLINE/SNS範囲訂正

本陣の正式検分で、陣立てv3行95の「line・sns の日本語直書きはそのまま subtitle へ、i18n 化は別起票」がv4/v5/末尾でも変更されていないことを確認した。従前の翻訳許容判断を本陣が訂正し、CMP1330範囲のi18nを今回から除外する。

LINE/SNSのタイトル・副題・削除/保存/アカウント追加とSNSダイアログ保存を元の日本語へ最小復元した。PageHeader/subtitle/actions移行、md位置補正、LINEの元flex wrapper、v-if/disabled/loading/イベントとhandlerは保持。6言語common.jsonへ今回追加したroot `lineSettings`/`snsSettings` だけを使用箇所確認後に除去し、既存sidebar等の翻訳は保持した。関連6件specは翻訳stubを除去し、元タイトル/副題/操作文字列とSNSfooterのキャンセル/保存を確認する。

196件runの入力は実行中に変更していない。上記141件CI・P8/P9・22件は訂正前headの証拠として保全し、訂正後headのCIは別途確認する。新しいNuxt/Chromeは起動せず、軽量lintと6言語JSON構文・差分検査を行う。独立検分・モックなし実機・住民探索・台帳/最終evidence/マージは未完了のまま。
訂正差分9入力はWSLへ同期後cmp一致。対象LINE/SNS/specのeslintはexit 0、6言語JSONは全て構文解析成功、git diff --check成功。新設root翻訳キー参照は0件。de commonの友達チーム用語統一2箇所は2035正本要件として保持した。関連6件の動的検証は次head CIで確認し、環境停止中のローカルNuxt再起動はしない。

## B3精読によるドイツ語の最小訂正

本陣が16キーの語順・格変化を精読し、de `friends.guide.related.item_feed` と `friend_feed.subtitle` の「Beiträge Freundschaftsteams」に必要な前置詞が無い実findingを確認した。この2箇所だけ「Beiträge von Freundschaftsteams」へ修正し、item_feedの末尾・他言語・単数属格「des Freundschaftsteams」の2キー・相互成立の引用一致は保持した。既存番人に当該2文の完全一致期待値はなく、翻訳を複製する新規試験は本陣裁可に従い追加しない。変更入力2ファイルのcmp一致後、既存friendI18nContract.spec.tsの18件をNode限定で再実行し、18成功/0失敗/skip0・exit0を確認した。

P8/P9実測と訂正後差分の依存関係: LINE/SNSのja翻訳値と復元後の文字列は一致し、PageHeaderのclass・action wrapper・ボタン属性/handlerは不変。他7移行画面とPageHeader本体にも変更が無い。除去common rootキーは他の使用箇所0件でP8の8画面には未使用。B3はde2文字列だけの変更で、jaのP8/P9には入力文字列差が無い。このため既存ja測定値を維持できるが、新headで撮影を再実行したという意味ではない。de幅測定を含む196件は最終headで未完分を検証する必要がある。