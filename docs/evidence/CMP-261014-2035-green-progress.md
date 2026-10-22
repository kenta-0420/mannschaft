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
## main追従・canonical入力・部分ブラウザの保全

固定main `1ae1ee1ca526c0103b66117571447967a79a6d8a` を通常mergeしたhead `a7fb905568e0bc95440a91b6e77005fd0a010e73` をpush。merge前後のfrontend/workflow/social/advertising tree完全一致を確認した。main由来にはshift本番コード等も含むため、BE全体不変とは扱わない。対象headのCI run39609707365は成功し、実ログで671files/6602tests、対象13spec/141成功・失敗/skip0、旧3spec不収集を確認した。

Windows physical SHAとGit canonical LFとの差を別観測へ分けた。Windows git archiveもautocrlfを反映するため、永続gitconfigは変えずarchiveコマンド限定core.autocrlf=falseで生成したrawソースを展開し、Git blob→WSL全4109blobとcanonical52 SHA256の一致、移設旧3path不存在を確認した。node_modules/.nuxt/env/旧画像を保持。Nuxt3002は所有PIDと実listenerを一致確認、正URLのcold待機後200を確認した。

同headの全196件runはhost空きが約6.9GiBから2392MiBへ急低下し、指示の停止目安近傍となったため所有run/Nuxtのみ停止した。永続stdoutは25完了=24成功/先頭1失敗/残171未完。先頭folders ja360はloadingのままh1未出現15秒でgeometry測定前に失敗、後続24件は成功。JSON reporter完了前の中断につき正式結果JSONは未生成、stdout/失敗png/error-context/部分観測を保全した。収集専用--listが生成したexpected0/skipped196のJSONはcollection-resultsへ別保全し、以後--listはreporter=listに限定する。

24固定成功＋171未完＋cold限定1=196 uniqueをfile/titleで分割し、CLI選択--list=171/2filesと1/1fileがmanifest集合へ一致することを確認した。これは準備であり未実行。旧headの22成功も別の環境中断として保持する。

## 実BE query不具合の再現とC1契約を保つ修正

BE8091 ready共有後、既存fixtureの通常認証とGETだけで比較した。org f3orggvkwbk/numeric632、x(user990623)はorg/me/permissionsでADMIN/scopeAdmin=true。既存広告accountは404 AD_005、campaign一覧は403 COMMON_002で所有campaignを取得できなかったため候補9000000000000を共通使用した。同一x認証・同一campaign/from/toで、organizationId=632は403 COMMON_002、orgSlugは404 COMMON_005、数値再検査も403 COMMON_002。数値側はADMIN確認後の広告account不在経路、slug側はLong bindingのScopeSlugIdConverterがURIに無いquery slugを既定TEAMで解決する経路で区別できた。成功campaign画面の検証ではない。資格/トークン/認証responseは保存せず、Stripe/DB fixture書込み/追加serverは実行していない。

正本C1行48はページがexportCampaignCsv('ORGANIZATION', slug, …)を呼ぶ契約であるため、ページでnumericへ置き換える最初の案は採用しない。既存useOrganizationNumericId（getOrganization(slug).data.numericIdを正整数確認して文字列化、失敗をreject）を再利用し、useAdvertiserApiのperformance/creative comparison/breakdown/exportの4関数だけ送信queryを数値化した。ページ/戻り先/公開引数、TEAMパス・日付・CSV blob、既存billingParamsと他billing関数は保持。既存resolver本体へfastpath/cache/汎用化を追加していない。解決失敗は広告API未呼出しのまま既存画面catch/retryまたはCSV error handlerへ伝播する。

既存tests/unit/composables/useOrganizationNumericId.spec.tsの6件へ、4関数×ORG query/TEAM resolver不要/解決失敗の12件を先行追加。固定196ソースを触らないNode overlayでred実測は18件=10成功/8実assert失敗/skip0、greenは18成功/失敗/skip0。CI向けmock初期化をhoistedへ揃えた最終入力も同18件全成功。red/greenのJSON/logは専有verify/org-query-nodeへ別保存。Nuxt環境ではmodule mock、Node限定では同じ依存のauto-import stand-inを用い、製品関数と既存resolverを実際に実行する。ページ既存4試験のCSV slug期待は維持し、初期3APIのslug引数assertも追加した。ページ4件の動的確認は次CIで行い、ローカルNuxt/Gradleは起動しない。

影響case: 196中のorg広告詳細36case（6幅×6locale）は新query経路の対象で、旧成功24に含まれるorg5件も再検査対象となる。他19成功は対象ファイル/表示文字列不変で保持可能だが、新headで実行したとは記さない。再開案は19固定成功＋未実施171とorg再検査5の176件＋cold1=196 unique。selectorは旧head版を保全し、新head/CI確定後に対応付ける。P8のteam広告はTEAM query不変を回帰で確認、P9のDOM/日本語/属性は不変。台帳/最終evidence/実機画面/最低3住民探索/マージは未完了。
## 数値query修正後のstrict fixture追従（ブラウザ未再開時の観測）

製品head833633a2fba4cad161371df89b6bddeb11b112ffは本陣独立検分で新たな確定finding 0。196件共通fixtureのGET organizationはnumericId=12を返していたが、広告analyticsはURL末尾だけを判定し、実際のcreatives URLは汎用応答へ流れていた。組織広告4経路だけを先に厳密分岐し、OrganizationResponseに適合する同じ組織のnumericId文字列、GET/bodyなし、from/toの日付形式とquery3件、campaign7と正しいresourceを検査する。誤slug・未知URL・追加query等はunexpectedへ記録し500にする。TEAMとfriend領域の既存分岐は変更しない。レスポンス3種は製品型へsatisfiesで照合し、CSVはtext/csvを返す。

Nuxt/Chromeなしの既存Node依存による診断probeは正常5経路（組織取得と広告4経路）成功、不正9経路すべて500/unexpected9、対象fixtureのstdin lint成功。診断probeは製品試験件数へ算入しない。833の18回帰red/green証拠と旧196中断結果は保持する。当時はfixture更新後の196動的実行前で、再開集合は19既存成功と176選択対象とcold1を予定した。共通fixture差分が組織広告分岐だけであることと各対象の依存ソース同一性を固定head manifestへ記録する。実機と最低3住民探索、最終証跡、台帳完了、マージは未完。

## 196 uniqueブラウザ観測の完走（実機・住民探索は未完）

固定c6be2255540e9b73afa878c75d5dc79b8002a65dの正式WSL入力は4109 blob/52 SHA一致、移設旧3path不存在。collection176と1を実行結果と分離し、選択176は174成功/先頭ORG ja/en360のloading-only2失敗/skip0で44.9分完走。正本の条件を変えず、本陣許可のexact2再検査は各1回で2成功、旧folders ja360 cold1も同head1回で成功。初回失敗JSON/log/context/PNGと旧22件中断runは保全した。

依存経路の同一性証明付き旧19成功と、新176の174成功、ORG再検査2、folders1をfile/titleで対応付け、196 uniqueの最新outcome全成功/skip0を確認。本陣も観測JSONの196 unique/新head177と旧19を独立照合。新実行回数は179であり、初回失敗を無かったことにはしない。P7全60/C3全6/strict10成功、正常ORGで厳密numeric queryが通過。これはmockブラウザ観測で、実BE成功画面/住民探索/戦役完了ではない。

c6のFE CI39619489911は671files/6614tests成功、対象14spec159成功/失敗0/skip0（resolver18とorg page4含む）。自分Chrome残存0、Nuxt停止/port3002解放を確認。他ownerのサーバーやプロセスは操作していない。

実BE CsvExportService精読により、fixtureのORG export応答body1文字列をUTF-8 BOMと8列へ追従した。196の幾何試験はCSVをクリックせず、C3は検索、strict10はfriend/folder/feed/forwardであり当branchを実行しない。差分は当literalだけ、残る全ソースbyte同一を証明し、c6で実行した事実と新head未再実行を区別して対応付ける。literal複製試験は追加しない。新headCIと実BE/最低3住民、最終証跡、台帳完了、マージは引き続き未完。

直近2観測節はPowerShellからPythonへの標準入力で日本語が疑問符へ変換されたため、UTF-8直接書込で復元した。833までの既存本文はGit blobをそのまま保持した。

## 2026-10-22 再開: 先行フォロー実装とのfixture統合

旧ローカルHEAD `9f35c70b4f0eb4003c359419693aa1d9137524f2` を保持し、最新main `b9624f125435f40f6fd26b53b0c6c3755d23dab2` を競合なく通常mergeした（`a752b2fc639138912e65013cd75788247f9ccd1f`）。製品への追加修正はない。

標準Playwright設定のC3 ja 1件で実redを確認した。placeholder幅・ヒント表示の断言を通過した後、先行フォロー実装が追加した `GET /api/v1/teams/1/friends/pending?page=0&size=20` がstrict fixtureの想定外通信として検出された。修正commit `acf6e1a9d69c6c58dd45fad5bbc171aca287da19` は生成型 `PendingTeamFollowListResponse` のimportと、GET・page/size・bodyなしを検査する空pending応答だけである。未知通信を拒否する条件は維持した。

`frontend/playwright.config.ts` のchromium、workers=1、retries=0で、C3の6言語と既存フレンド契約10件を実行し、16 passed・skipped/unexpected/flaky/global errors各0（173.860秒）を確認した。redは1件の実assert失敗・skip0（13.779秒）。ESLintと今回差分の `git diff --check` は成功。殿も標準reporter原本を再集計して確認した。

原本と要約は専有worktreeの `.claude/cmp2035-verify/resume-red-corrected.json`、`resume-green.json`、対応する `*-summary.json`、`resume-worker-evidence.json` に保持する。reporter原本の先頭にはdotenvの説明1行があり、JSONとして読む際はその1行だけを分離する。

検証用native FEの入力は9f35と同じfixture修正である。mainから取り込んだ牧場のopt-in/devAssets、Vitest分離、package設定等は当runtimeを再起動して検証していない。最新main統合後HEADで16件すべてを実走したという主張や、今回196件・P8/P9・実機・住民探索を全再実行したという主張はしない。先行証拠は各旧HEADに対応する履歴として保持し、最新HEADのCIは別に確認する。

他テナントの指定2URL負試験は自動承認レビュー拒否後も未実行で、具体的なユーザー許可を確認中。広告fixture account32・campaign28はその試験用に保持し、既存card1は後始末対象外。最終完了証拠・台帳完了・マージは未実施である。
