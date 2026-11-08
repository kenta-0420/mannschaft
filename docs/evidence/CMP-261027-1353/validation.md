# CMP-261027-1353 検証記録

実装PRは[#4166](https://github.com/kenta-0420/mannschaft/pull/4166)、最終実装sourceは `f157e033bb650205dc4ba94d0772f9b1a0be7d15`、公式成果物・実機・探索・性能の実行sourceは `3db393c2e4e5608e0e168d59baba85e68342ae78`。実装PR・CI・検分・実機・3目的探索・本陣反映を確認し、台帳の完了証拠を本書とschema1 JSONへ対応付ける。実装PRは通常squash merge済み。本陣は7610007へ安全FFし、実compileJava・64class照合・既存loopによるAPI再起動・health200まで完了。本陣FEも部分smokeと独立した正準feed補完で採用確認済み。完了資料PRのmergeと所有資産の片付けは別の後段作業である。

## 問題と修正

ログイン後の待ちには、所属数に比例するSQL、不要な非表示画面の初期通信、各静的requestでの巨大runtimeConfig走査が重なっていた。TEAM/ORGを多数兼務する固定主体では417scopeが影響を増幅した。

- 個人カレンダーをTEAM/ORGそれぞれ1000 ID以下のIN照会へまとめ、元の所属順とscope内順を復元した。旧日時WHERE、予定の多重度、可視性、対象者、色、enricherは保持する。
- 個人要対応は既存pendingの保守的supersetをdomain service越しにまとめて求め、候補scopeだけ従来facadeの認可/count/preview3を通す。Inboxは履歴が何らか存在するscopeだけ従来の正準取得/window500を通す。domain・後続chunkの失敗時は元全scopeへ戻し、失敗を空成功で隠さない。
- 同じPrimeVueテーマをimportThemeへ分離した。閉じたdialogの初期importと非表示Carousel bodyの初回通信を遅らせ、訪問後のDOM・入力状態を保持する。
- feedは数字slugと内部IDを混同せず、取得済み内部IDとpermissions用slugを分ける。退会済みscopeのfeed403/404は親へ伝え、初回成功前と拒否後のcomposerを隠し、拒否を明示する。追加権限APIやrole推測、BE認可の緩和は行わない。

## SQL・先行redとgreen

| 条件 | 旧実装の正式red | 修正後 |
|---|---|---|
| calendar空fixture、所属2/42/417 | 2→42相当で11→51 | 11/11/11 |
| 個人要対応・非空2scope、所属2/42/417 | 22→302、同値assert後の性能assertで失敗。417未到達 | 28/28/28 |
| Inbox空feed、所属1→21 | 従来scopeごとfeed取得 | 候補1→1、正準feed0→0、role一定 |
| Inbox可視feed1scope＋残空所属 | 従来scopeごとfeed取得 | 候補1＋正準1、DTO保持、role一定 |

calendarはSQL IT2・Order8・Color15・QueryService12の計37件。green1で旧list系Mockito stub6件が失敗し、製品と他3targetのblobを維持したtest-only補正後にQueryService12件を正式再実行した。最初のfixture/環境不備は製品redとしない。

個人要対応の正式red `177ecfcbcfaa8f1db5f086a692f24f1ea820534f` は1test/1AssertionError/error0/skip0、表示項目集合・型・順序同値を確認した後のSQL22→302だけが失敗した。正式green `9da8f094ce04029961bf32baa9b56d4f15b6f08a` は6class39pass/skip0。空/1/1000/1001、型付き同ID衝突、SQL引数上限と全件一度ずつ、後続chunk失敗fallback、旧pendingの本人・論理削除・境界・順序・countを検証した。Inbox性能期待のtest-only補正 `25d9ea729647039d65f7457db1f71090981e33c1` は関連37pass。

feed識別87pass、Carouselは11件中9件の期待red後に11pass。退会UIの正式red2は製品70ff同値で新12assertすべて期待失敗・環境error0、正式green `fc53a3139b2232169e5d79f40719e2f9bdfa33cc` は関連6spec49pass/skip0、7file lint error/warn0。各公式receiptはsource固定・exit/error/cleanupをsource-bridgeへ対応付ける。途中失敗や部分成功を全体成功に読み替えない。

## 最終実装f157と実行source3db・成果物・CI

3dbはfc53の製品3blobをそのまま統合し、main `ae3ab142f2a11473f287bdf4f02d5ce5f7ecc26f` を通常mergeした。AEのORG target_audience/定数role照会・broadcast slug型等も含むため、過去70ff/47288との厳密な製品入力同値は主張しない。その後のmain f6ae38c31bは別CMP台帳1語のみで、殿が全文・製品/build/lock/security差分0を確認した。

同3dbの公式API fixed4、FE build3はどちらもexit0/error=null/cleanup=removed。API Jar SHA256は `e171aad842ae4f6fe564b67862dcf366b315c9d700f8fa4e312bdcd801b59ab9`。preview入替後の所有UID/PIDticks/cwd/source/実entry hash/loopback待受を確認した。本陣反映は別gateであり、preview成功だけで本陣採用済みとはしない。

実行source3dbのCIはBE run40523313140、FE run40523312994。6shardと集約Compile & Test、OpenAPI Drift Check、Task-List PR Gateのrequired3件すべてSUCCESS、active0/FAILURE0。Docker main-push-onlyと週次full-history scanの条件SKIP2を分け、全結論SUCCESSとは書かない。最終ready世代TaskGate SUCCESSと、直前edited世代superseded CANCELLED1を分離する。FEは725files7191pass/既存SSR専用5skip＋ranch27pass＝7218pass/5skip。appearance.specは既存blobと同一でclient3pass、SSR条件skip5をproduction SSR/light-dark実機で補完する。全体skip0とはしない。

同CIの選択XMLはLeave11/ActiveGuard12/PersonalSQL1/Timeline35/CandidateQuery4/InboxRole19すべてfail/error/skip0。Personal SQLの既知数値2行だけを抽出し、2/42/417scope＝28/28/28を確認した。生system-out本文は恒久資料へ含めない。

## 条件別の性能参考

以下は各source時点の改善根拠であり、最終3dbの同一試料とはしない。歴史的development環境の約7秒/14秒も分母へ混ぜない。Windows production HTTP1・FE/API別origin・v2 helper・各SW条件fresh context n=2の初回中央値。再訪は独立時計で別sample。mainはcontainer表示で全content readyではなく、personal readyはcalendar DOMと4 API成功を要する。

| SW | 指標 | baseline e355 | 修正47288 |
|---|---|---:|---:|
| block | route / main container（秒） | 9.132 / 10.239 | 2.224 / 3.057 |
| block | calendar DOM / personal ready（秒） | 20.449 / 20.709 | 11.711 / 11.936 |
| allow | route / main container（秒） | 10.634 / 11.983 | 1.913 / 3.041 |
| allow | calendar DOM / personal ready（秒） | 26.051 / 26.359 | 12.618 / 12.619 |

別Linux最小helperはc63 main1.956/calendar7.204→47288 main0.554/calendar2.556秒。70ff同runtimeの起動直後1回はmain2.675/calendar7.042秒、他actor利用後warm1回は0.521/2.533秒だった。Node lag中央値0.355/0.304ms、最大22.063/8.347ms、100ms超0。cache/JIT/host寄与は個別制御しておらず、毎login SLAやcold問題の完全解消は主張しない。最終3dbのLinux SWblock warm1回はroute0.590/main0.767/calendar DOM2.809秒。Node lag中央値0.128ms/最大1.633ms/100ms超0、logout200/closedを殿が全文確認した。前段Node起動・読込待ちはsubmit起点の区間外で詳細phase未特定。n=1のwarm診断であり、cold/毎login SLAを証明しない。

native keepalive逐次各3GETではc63→47288の要対応中央値2739→126ms、Inbox1915→159ms。全200/件数・bytes不変/logout・agent破棄を確認。本文を保存しないmemory内canonical DTO digest比較も同値だった。native API時間を画面秒数へ読み替えない。

## 実機と3目的探索

退会UI修正後の3db正式実機は7case＋補完5観測（独立した12caseではない）を殿がsafeJSON/画像で直接確認済み。合法scope、退会後の403拒否とcomposer0、他アカウント一覧不在・直URL403、再訪・拒否表示・空状態を確認した。完全tenant祖先分離は主張しない。旧sourceのCarousel3section/初回body/訪問後DOM・検索値保持、touchcancel、390/360幅calendar、初回予定Form日付/取消再open、設定dialog初期化の実機は別sourceの根拠として保持する。未踏の既存予定編集・保存・dayDialogまで成功扱いしない。

| 住民・目的 | 結果 | 限界・観測 |
|---|---|---|
| ⚔️足軽1：スマホ片手操作/表示崩れ、本人の予定・所属連絡を確認して帰還 | Completed既存予定詳細と所属先の連絡を確認、ホーム帰還。logout200/closed/pageError0 | 未完了予定・フィルタ種別は未照合。touch contextでclickを使用し、物理片手操作の証明ではない。旧Back仮定timeout/遷移中画像はinstrumentationとして補完 |
| ⚔️足軽2：高齢・低リテラシー/素直、運営情報と要対応の有無を把握して帰還 | 運営の警告・未対応通報0・バッチ失敗を把握、ホーム帰還。logout200/context/browser閉鎖/pageError0 | 外部詳細リンクは未遷移。専門語と管理項目の多さは観測、新製品finding0。553.736秒はguard/driver/mask補正込みで性能sample外 |
| ⚔️足軽3：一般/隙間狙い、本人連絡とアクセス境界を確認して帰還 | 所属feed200空状態、検索で発見した非所属public TEAM紹介可/timeline403・拒否・post0/composer0、ホーム帰還。logout200/context/browser閉鎖 | menu/検索待ちtimeoutはselector/遷移要因もあり未特定、製品defectへ断定しない。完全tenant祖先の証明ではない |

各住民は別actorで1login/1context、現在DOM/画像から自律的に次手を選び、固定scenario runnerを探索と呼んでいない。主要masked画像を各担当と殿が実見し、新規製品finding0。住民2はWSL distro名不在のCLI失敗後、既定WSLでの再guardが認証前でなく最初のlanding後となった。順序逸脱を記録し、同PID/ticks/UID/cwd/source/実artifact hash/loopbackの全一致を確認して再loginせず継続した。selector仮定のtimeoutや画像mask補正は製品不具合へ計上しない。

業務データ変更0は投稿・参加退会・承認・招待・設定保存などを行わない意味で、認証login/logout POSTやsession/最終アクセス更新まで含む全DB DML0ではない。候補件数SQLのREAD ONLY/rollbackは当該照会接続の条件として分ける。

## CSP・認可・残存する限界

CSP enforce eval1件はno-waitForFunction対照でも再現し、event asset SHA/bytes/line11が既存vuedraggable polyfillと一致した。security正本03 §4.1の既知許容として分類し、CSP緩和なし・違反0とは主張しない。相対report-uri404はFE/API別originのproduction preview配線。本番edge sourceの/api/配線と区別し、稼働edge apply自体は未観測。

SYSTEM_ADMINの正常landingは/system-admin。旧helperの/dashboard固定waitは不適合だった。TEAM/ORG feedはSYSTEM_ADMINでもactive membershipが必要で、shell200はfeed許可、feed200は投稿許可を意味しない。

f157は3dbへ台帳更新main f6aeだけを通常mergeしたもので、全tracked差分はdocs/task-list.mdだけ。backend/frontend/scripts/.github/root package/config/lock/resourcesを含む全artifact入力同値を殿も独立確認した。成果物・実機・探索・性能はこのsource bridgeで継承するが、CIは新headへ継承せず、f157のBE40540339113/FE40540339178を別実行successとして確認した。TaskGateは有効成功をsupportに保持し、pull_request_targetのheadがbaseになり得るためschema top-level ciへ含めない。PR4166は管理者バイパスを使わず7610007へ通常squash mergeし、f157..761の全tracked差分0。Windows保護tracked SHA・stash20・既存backup・bounded status100、WSL未追跡43のpath/digest集合を維持してsafe FFした。実compileJavaは--rerun-tasks/--no-build-cache/--no-daemon・2worker/2GiBでexit0、64classが公式Jarと一致した。事前memory gateの2回停止はGradle開始前で、閾値を緩めず許可されたbounded retryで完走した。旧所有Java1PIDだけへSIGTERMし、既存loopが起動した新APIのUID/ticks/cwd/正確entry/exe/port inodeを確認、health1GET=200。生argv/env/ログは恒久化しない。本陣FEはsmoke2の成功観点に加え、正しいtimeline/myを観測した独立UI補完で採用確認した。schema1完了証拠と7列表は本docs変更で対応付け、本人所有資産だけのcleanupは資料PR merge・preview解放後に別途行う。参照する匿名JSONと必要画像は同docs evidence配下に配置し、ignored logsを恒久参照にしない。
本陣FEのstartup smoke1は未認証の初回応答待ちでtimeout。worker ready後の10秒確認でもheaders未着で、初回development SSRの原因は未確定。このstartup timeout自体を成功へ読み替えず、後続の認証済み実UI補完およびproductionビルドの条件固定性能sampleから分離する。

CPU沈静化後の本陣bounded GETはheaders5.504秒/status200（body破棄）。約21分の初回development起動待ちは原因未確定・未解消の限界として保持する。その後smoke2は通常login→dashboard、PERSONAL calendar heading/date cells>=28、importTheme設定とprimary色、pageErrors0/logout200/closedを確認した。一方run全体はtimeline監視先をfeed APIと誤指定したhelper不備でfailedの原状を保持する。独立1contextの通常login→sidebar Timeline操作で正準timeline/my200・data-loaded可視・pageErrors0・authCookieあり・logout200/closedを確認し、rootがsafeJSONとmasked画像を直接確認した。これらはproduction性能sampleに算入しない。

本陣FEのNode22・所有者・3000待受と、nuxt.config/theme source blobがmerge済み761に一致する証拠は `main-frontend-runtime-proof.json`、機能採用の集約は `main-adoption-summary.json`。追加画像はホームとfeedのmasked2枚だけで、ホーム画像viewportはStorage/RightNowを示しcalendar DOMのassertとは別の根拠である。
