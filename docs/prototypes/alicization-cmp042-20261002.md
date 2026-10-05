# CMP-042 実機・3住民観察と補完QA

2026-10-02〜03 JSTに実施。B0-J4 / featureKey `survey` / runId `cmp042-20261002-r2`。本記録は今回のgoal・role・viewportの範囲であり、各personaの全属性、J4全区間、B0 runner全体の成功を表さない。

## 検証環境と境界

専用localhost FE3001 / BE8081、実DB・Cookie認証・実UIを使用。既存loginViaApiを使い、APIは準備・本人確認・readonly永続化確認・後始末に限定した。4アカウントは架空の `@test.mannschaft.local`、非SYSTEM_ADMINを事前確認。既存所属やglobal roleには変更を加えず、自所有PRIVATE組織だけでADMIN/MEMBERを付与した。通知は自所有surveyIdの受信者に限定して確認し、外部メール送達を成功条件にしていない。

分母仕様は2026-08-15裁可の公開時固定。PR #2826（`f4da6884f2`）の専用IT12件・survey333件・当時のBE CI greenを既存契約として利用した。DEPUTY許可/不許可は既存SurveyManageSurveysAuthzITの契約に依存し、今回のUIはADMIN/member/別組織の3視点で確認した。今回のdocs-safe skipや最新mainのsecret scanをBE greenの代用にしていない。

最終準備mainは `6c0bd78216c0c1beda40fa21b07a39b3734d5920`。実機baselineとの当該Survey API・frontend application sourceの互換は殿が確認した。最新lock依存の差分はPR CIの範囲であり、最新main全体のBE再検証を主張しない。CSS修正はSurvey関連4ファイルのclass/PTのみ（原commit `e3e65f00593faa3982fe4f0ab8d2f95d1fbc8aee`）。共通Header/nav、認可、API、分母、部分再読込の挙動は変更していない。

出荷準備でmain `1cb0febb62b5015f44a0387267087e4b858c81b1`へrebaseした。6c0からの当該Survey・frontend app・BE build差分はなく、確認通知の別戦役変更を保全した。PRのrequired CIはこの追従後headで別途確認する。

## 独立した初回3住民

共有fixtureはsurvey196 / 組織`cmp042-mur08pdz`、公開時2・後加入後現在3。各住民は別context・1login。以下の初回結果と後段controlled QAを混同しない。

| 住民 / persona | 実観察の結果 | 未達・制約 |
| --- | --- | --- |
| 1 / P10 / ADMIN23 / 一般×素直 | 回答者一覧・結果・督促を実UI操作。session7414 exit0、CLI 1pass。子一覧2/全3・結果2に対し上部0/2が残ることを画像014で確認 | standalone XMLなし。自身の回答・再督促・closeなし。全console種別未採取。5 PC条件の125%はviewport近似で実ブラウザ倍率未証明 |
| 2 / P06 / MEMBER8 / スマホ片手×表示崩れ | 390×844・360×800、mobile/touch。実UI回答POST201、回答済み・固定分母1/2を確認。Badge文字切れと送信130×42を画像・DOMで観測 | 初回末尾の戻るをbuttonと仮定したrunner不整合でexit1。初回return goal未達。共通menu等は別範囲の未確認候補 |
| 3 / P08 / 後加入MEMBER90245 / 一般×連打 | 1280×720。1回のUI回答後、回答済み・2/2・フォーム非表示を画像03で確認 | session94239 exit1、240秒deadline。存在しないsubmitへのisEnabled待機が原因。HTTP201未保存、rapid retry未実施。製品double-submit欠陥とは判定しない |

住民1の上部summary staleはCMP-261003-0121へ未着手として登録した（2026-10-03 01:21 JST）。詳細ページ`responseCountLabel`は`survey.stats`参照（`frontend/app/pages/surveys/[surveyId].vue`）、`SurveyRespondentsList.vue`の`loadRespondents`・refreshは子データだけを再取得する。これは固定分母のDB破損の証拠ではない。初回住民3の連打耐性をcontrolled QAで実証済みとは扱わない。

## Controlled QAと限定CSSのafter確認

beforeのBadgeはclientHeight24 / scrollHeight30、flexShrink1 / whiteSpace normalで文字切れ。送信42px、回答者再読込32px、タブ42px、狭幅督促の縦折返しを殿が実画像・sourceで裏付けた。共通Header本体を変えず、Survey呼出側の折返し・nowrap/shrinkと対象操作の最小高さを修正した。CMP-261003-0119へ限定UI問題を完了として登録した（2026-10-03 01:19 JST）。

after-r2はfresh2context各1login（回答済みlate、未回答ADMIN）。JUnit 1件、fail/error/skip 0、session16215 exit0。17実画像を担当者が閲覧し、殿も代表4枚を閲覧した。測定幅360/390/1024/1152/1280/1440/1920、高さ800/844/その他900。全条件でmobile/touch contextを使用したため、広幅はviewport近似である。

測定対象の送信130×44、回答者再読込44×44、タブ高さ44、督促100×44・nowrapを確認。Badgeは64×24、clientHeight=scrollHeight=24・1行。7条件のdocument横overflow0。チーム内訳再読込・共通nav等の寸法は今回の完了主張に含めない。未回答ADMINのフォーム採取ではPOSTせず、readonly myResponses0・全体2/固定2を確認した。

実DOMのaのtext/hrefから戻るを選択し、`/organizations/cmp042-mur08pdz/surveys`の実一覧表示まで到達。回答済みフォーム非表示と戻り先を補完したが、初回personaを成功へ書き換えてはいない。API2/context2は全dispose完了、物理cleanup JSON failures空。

最終レビューの長ラベル疑義は別途診断した。360幅の実測PageHeader inner296pxと、padding/Cardを追加しないResultsPanel呼出構造を照合し、無認証loginページの現行CSS・fonts.ready後の臨時DOMで6localeを計測。現行/44pxのみ/元classすべて横overflow0、ドイツ語はtitle106.72＋button185.92=292.64pxで1行だった。実画像を視認して疑義を棄却し、コード追加はしていない。これは業務UIの再実行や実ResultsPanel header自体のbbox採取を主張するものではない。

## 機能回帰と後始末

CSS後regression2はsession74542 exit0、JUnit tests1・fail/error/skip 0（overall372.150652秒、testは360秒上限内）。公開snapshot2→加入current3→退会current2でも分母2を維持。現在在籍者への督促2、後加入通知1・退会者通知0をownsurveyだけで永続化確認し、後加入者UI回答201→管理側再読込1/2・結果1件を確認した。画像06はrefresh pendingを含むため一覧完了の証拠にはせず、POST/toastと通知永続化を督促の根拠にする。

regression2のsurvey198 / `cmp042-mur4ueeb`は個別DELETE204、Browser3/API4すべて完了・物理cleanup failures空。共有persona fixture196も本人・exact slug/id/ownershipを再確認して個別DELETE204、全context/API終了、2026-10-03 00:50:44 JST完了。DB一括削除や既存組織の変更はない。

通常chromium-realでも同じ待機境界になるようspec内にtest360秒・expect20秒・action20秒・navigation120秒を設定。lint・strict standalone TSはexit0、通常configの--listは対象specと既存setupの2件を収集しただけで実行成功ではない。metadata変更後の実機再走は同じboundsを保持するため省略した。

## 失敗履歴と残存限界

run1〜4の失敗、run5 green、controlled beforeのlocator失敗、after-r1未測定を保全。run5はJUnit greenと実画像8枚があるが成功summary/cleanupの物理JSON未保存。CSS後は物理JSON保存へ最小改善しregression2で確認した。

FE再起動1の終了原因はunknown。再起動2は誤ったsparse ledger WTのrecruitment.ts欠落ENOENTであり、原因を同一視しない。正しい非sparse BWTの復旧後もdocument-first warmは120秒未測定。既存200資産3件をGET-onlyで各1回warmし、その後document200/body完了6387msを確認してafter-r2へ進んだ。

CSS後regression1はlate初フォーム20秒待機を超過して失敗。document後約33秒でdetail/thread開始、detail200・my-response200/空、権限拒否・既回答・製品fatalの根拠はなかった。late初フォームだけ90秒へ限定してregression2を1回実行した。他assert20秒・全体360秒・データ条件は不変。dev runtimeの初期hydration遅延という検証制約であり、性能欠陥を断定しない。

## 証跡の所在とAC対応

証跡はsecret/body/cookieを公開proofへ転載せず、各担当所有のignored領域に保全した。以下はworktree相対パスで、GitHub CI artifactではない。

- 住民1（BWT）: `.claude/campaigns/cmp-consent-resident1-artifacts/report.md`、007〜014の画像/寸法。CLI終端を根拠としXML数を捏造しない。
- 住民2（E2E WT）: `frontend/test-results/alicization/cmp042-20261002/resident2/` のreport・readonly・XML・画像。
- 住民3（E2E WT）: 同配下`resident3/evidence/manifest.json`・`results.xml`・`03-after-first-submit.png`。
- controlled: 同配下`root-verification/`のbefore、`after-r1/`、`after-r2/results.xml`・`http.json`・`cleanup.json`・17画像・`warm-assets-first.json`。
- 機能回帰: `frontend/test-results/cmp042-after-r2-regression2-20261003/` のXML・8画像・`cmp042-summary.json`・`cmp042-cleanup.json`。
- 共有fixture後始末: `root-verification/after-r2/cleanup-persona-fixture.json`。

| AC | 対応する実証 |
| --- | --- |
| 公開時分母固定・現在所属増減 | 専用IT AC1〜12既存green＋regression2 snapshot2/current3→2/UI1/2/永続化 |
| 現在所属の未回答・督促 | 実UI再読込・督促POST/toast、ownsurveyの通知受信者永続化 |
| ADMIN/member/別組織境界 | 非SYS本人とownscope role assert、member回答可/管理非表示、outsider403。DEPUTYは既存契約IT依存 |
| 利用者導線・表示 | 独立3住民の部分実測＋controlled after-r2で戻り先・対象44px・Badgeを補完 |
| 中途失敗・所有データ保全 | 失敗元を保持するfinally、個別DELETE HTTPと全disposeの物理JSON |

B0ローカルmanual overlayは`docs/prototypes/.b0-local/run-cmp042-20261002-r2.json`の既存insight record keysで登録した。payloadは上記限定UI問題（P06）と上部stale（P10）の2 insights、journeys空。J4専用runner proof未整備のためjourney成功を生成せず、板全面再生成は未実施。SHA256は`145e5da7572b9ab52132fc3ebf4329097d50aecf6145c939d27018272f22aa18`。本陣の同相対パスへ衝突確認付きでコピー保全した。

上記WT相対証跡は、本陣`C:/Claude/mannschaft/.claude/campaigns/cmp042-evidence-20261003-final/`へcopyのみで保全済み。住民1は`resident1/`、Scout persona/controlledは`scout/alicization-cmp042/`、最終回帰は`scout/core-regression2/`、CSS後失敗は`scout/core-after-css-fail/`、初期runは`scout/core-original/`・`scout/evidence-cmp042-*/`へ対応する。292ファイルのsource/archive SHA256全件一致を`hash-manifest.json`へ保存（manifest SHA256 `afc7895881fcfea3e9f07b285810c8f8cda1f06e022b3c5bd7ffc829fbeb7255`）。診断中に更新したlocale-layoutの旧コピー2件は上書きせず、final版をhash suffix付きで別保存した。原証跡・他担当資産は移動削除していない。
