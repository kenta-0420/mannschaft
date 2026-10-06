# F04.7-05 受け入れ条件・検証・置換手順

> **ステータス**: 🟡 Phase 1製造中（仕様採択済み、73AC・実機・公開は未完了）
> **正本入口**: [F04.7](../F04.7_gamification.md)
> **区分**: 以下は実装戦役の検証計画。今回の実機/UT実行結果ではない。

## 1. テスト可能な受け入れ条件

`UT`=単体、`IT`=実MySQL/API結合、`E2E`=実ブラウザ/本体API/DBのfixture。sourceのTX配送を観測するITはクラスの@Transactionalでcommitを封じず、fixtureを明示commitする。テスト値は02の検証専用policy、時計はClock.fixed/fake timersで制御する。

| AC | 誰が/何をしたら/観測する結果 | 主検証 |
|---|---|---|
| 01 任意 | ranch未参加/表示OFFの無料userが出欠/投稿/blog/想起を使える。機能や権利を失わず、強制dialogなし | E2E |
| 02 初期 | 同userが二tabで卵開始を同時送信し、owner一件/個体一頭/部屋一件、重複0/卵一個/egg rule snapshot固定 | IT/E2E |
| 03 罰なし | 最終利用から長期間時計を進め、balance/XP/stage/親密度が減らず死亡/streakなし。無料給餌/ふれあいの内部値と反応・言葉段階を確認し連打加算0（加算単位/閾値/冪等fixtureは詳細確定後） | UT/IT |
| 04 成長 | activity0/残高0の無料給餌成功で同個体IDのXPだけ増え、凍結閾値ちょうどでBABY→JUVENILE→ADULT。閾値1未満/最大/overflowも確認 | UT/IT |
| 05 同時care | care週残1XPで二tab異なるkey無料給餌、XP合計1/cap超過0、双方cost0、触れ合いは可能 | IT/E2E |
| 06 給餌retry | 成功応答を喪失して同key再送、同result/care XP一回。同key別body409、最新stateを古いresultで上書きしない | IT/E2E |
| 07 出欠正常 | known native history（少数marker案採用または信頼できる既存履歴）fixtureで本人がATTENDING/PARTIAL/ABSENTを初回答、subject本人へ一回。三値同量、別scheduleも全体cap合算 | IT |
| 08 出欠除外 | UNDECIDED/代理/impersonation(originalAdminId非NULL)/管理者一括/取消/編集/代理履歴残留をfixture化。今回本人context以外0、本人有効初回答だけ一回 | IT |
| 09 出欠historical | rollout前の本人回答を更新してもwitness HISTORICALで0、firstRespondedAtだけで判定しない | IT |
| 10 TL | 通常本人投稿一回、同ID再送追加0、新ID同内容の一定範囲完全一致は0、derived共有/repost/reply/反応は0、クライアントorigin/actor偽装は対象変更不可。正規化/比較期間/証跡/同時投稿fixtureは詳細確定後 | IT |
| 11 Blog | known native first-publication history fixtureで手動/一括/自己承認/他editor承認/予約SYSTEM公開を各実行しauthorへ一回、actorに誤付与0 | IT/E2E |
| 12 Blog除外 | 下書き/編集/公開失敗/認可拒否/撤回→再公開/rollout前公開歴は0。同sourceのTL共有も0。新記事ID同内容の一定範囲完全一致も0、意味AI判定なし、source/global上限併用。正規化/期間/証跡fixtureは詳細確定後 | IT |
| 13 AR完了 | session/attempt本体TXは成功。commit後source transport TX保存。outbox確定済みfactのretry一回、受付窓lossは成績成功のまま許容 | IT/E2E |
| 14 AR境界 | 0prompt開始400、開始だけ/途中/取消/欠落/重複prompt/他promptは0、FORGOT明示nullは成功、rating差で量差なし | UT/IT |
| 15 AR再作成 | 同entry同週の再session/回答retry/entry編集/順序変更は追加0、既存recordRecall APIだけの保存も0 | IT |
| 16 無料容量 | チーム未所属・無料userが通常quota内で十分な本人entryを持ち同日の個人想起だけでpoint週globalCapへ到達（全利用者保証ではない）、課金/日待ち/連続利用不要 | IT/E2E |
| 17 公平 | 一所属userと多数所属userに同じpolicyを与え、四source同時処理でも合算globalCap同じ。消費しても枠戻らず | IT |
| 18 UTC週 | UTC月曜境界直前/ちょうど/直後、DST/userTZ変更、遅延oldweekを検証。遅延は元週、cap二重増加0 | UT/IT |
| 19 policy | 次週effectiveAt新version、旧週rule snapshot不変。personalOFF/数値欠落/overflowのpoints publish400、care amount/cap/閾値不正400 | UT/IT |
| 20 OFF/休止 | 表示OFFは付与継続、参加pause期間factは0/replay0、resumeで旧個体維持。policy源OFFの再配送も追加0 | IT/E2E |
| 21 durable | outbox確定後poll停止/consumer commit-ACK間停止から再送一回。source本体commit-transport間crashはlost許容、本体成功をExactlyOnce保証しない | IT |
| 22 本体優先 | source game-only capture/aftercommit登録/TaskRejected/outbox/witness INSERT/queue/listener/scheduler失敗でも本体保存成功/元API500なし。metrics/失敗監査、復元不能loss許容。native source rollbackなら発行0 | IT/E2E |
| 23 lease | 二worker/lease timeout/古いACK/poison一件で他件継続、dead-letter→元ID再送でdup0 | IT |
| 24 置物 | 同旧badge/user/period adapter再実行でinventory一件。未所有/取消/不正slot拒否、取り外しでinventory保持 | IT/E2E |
| 25 beta | ranchOFF/旧points停止でもbeta grant/entitlement/badge授与/LOGIN_SUCCESS activeDaysの既存契約が通る | IT |
| 26 認可 | anonymous401、本人正常、別user command/inventory/session404（feedingにdino ID body入口なし）、他team adminも不可、source資料F00拒否でlink=null | IT/E2E |
| 27 UI | widget設定/accordionの両登録、PC/390px/two tabs/keyboard/200%zoom/dark/6言語で全導線が到達 | E2E |
| 28 motion | OS reduce動的切替/STOPPED/非表示/backgroundでasset/timer/audio停止、accordion閉/carousel非active/scope・context非active/viewport外でcomponent保持中もtimer/RAF/audio0、静止でも同給餌結果。音既定OFF | FE UT/E2E |
| 29 状態 | null未参加、空inventory、loading、取得失敗retry、balance0でもcare可、point上限/care上限、consumer遅延を別状態で表示 | FE UT/E2E |
| 30 schema | migrationから実MySQL構築、BINARY16 UUIDv7、legacyuser BIGINT、UTC roundtrip、全text utf8mb4_0900_ai_ci・列overrideなし、VARBINARY正準キーのASCII厳格roundtrip/非ASCII拒否/最大byte/大小文字完全区別、unique/checkを検証 | IT/番人 |
| 31 本体保存 | source公開/出欠/想起の認可・validation失敗でoutboxなし。source title/body/private回答がpayload/API/logへ複製されない | IT |
| 32 退会 | 申請中はアクセス/操作/獲得停止、不可逆削除0。取消で同じdinosaur ID/name/XP/残高/置物を復帰、申請中活動の遡及付与0。最終AccountPurgedEventで冪等cleanup、各運営OFFでも処理継続。旧申請通知の遅延/取消/再申請/purgeとworker競合を世代照合し、削除後の再作成0。race技術契約と証拠が未完なら未検証 | IT |
| 33 AR週跨ぎ | 日曜STARTEDはrewardWeek=null、月曜COMPLETEDの週へ付与。翌週の同complete再送でも元completedAt/週を保持し追加0 | IT |
| 34 終身dedup | attendance/TL/blogの同source factを翌週再配送しても追加0。canonical hash衝突の実文字不一致はpoisonでrollback | IT |
| 35 不参加時刻 | 未参加活動は源ACK=NOT_ENROLLED、ranch rowsなし。開始後の再送0。active期間factが配送時PAUSEDでも元週付与、pause期間factは再開後0 | IT |
| 36 件数単位 | fixture personal25×4で100到達（4ポイントで止まらない）。源count上限、global残0、disabled/dedup/pausedのcount消費表を照合 | UT/IT |
| 37 slot ABA | slot初期version0、配置→除去でversion2の空slot。古いversion0 PUTは409、同DELETE key再送は増分なし、空slot新DELETEはversion+1 | IT/E2E |
| 38 表示保存 | 開始成功後widget表示API失敗でも個体一頭保持、表示だけretry。ranch settingsでvisibilityを独立保存しない | IT/E2E |
| 39 停止境界 | activity points policyなし/停止でもfree care可、care rule自体なしまたはcare control OFFはcreate/care503。shop catalog/price正常かつshop ONなら既得pointsで購入可。本人PAUSEDはcare409、settings/readは既存owner正常 | IT/E2E |
| 40 CSRF | 認証Cookie SameSiteStrict/HttpOnly/prodSecureを保持、現行STATELESS/CSRF無効を前提に実browser Cookie/CORS/入力境界でcross-site mutation拒否を観測し、refresh後の正規本人操作成功。CSRF token403を既実装/greenと仮定しない | IT/E2E |

| 41 無料成体 | activity0/残高0/チーム未所属で同日に週care枠を使い、次UTC週も任意の日に使い同個体が成体へ到達。ログイン/課金/毎日/AR不要 | IT/E2E |
| 42 交換競合 | 一商品分残高で異なるSKU二購入の同時要求、負残高なし。同SKU競合は一inventory/一消費。同key retry同result・priceVersion違い409 | IT/E2E |
| 43 inventory由来 | SHOPはlegacy fields NULL/sku価格必須、LEGACY_BADGEはlegacy ID/period必須。unique acquisitionKeyで二重増殖0、beta特典は別 | IT |
| 44 初期抽選 | LAND/SEA/AIRのみ、共通16種×4外見catalogのhabitat該当組が全て候補で組均等抽選。旧診断pool除外0、空候補/非active組拒否、他habitat/species/versionのbody偽装拒否、retry再抽選0、全species care量/閾値同じ | IT/E2E |
| 45 admin未参加 | ranch未参加SYSTEM_ADMINがpolicy/control/care rule/retry操作でき、admin commandはowner FKなし、個人owner自動作成0 | IT |
全ACについて実装class/methodとgreen test名を実装PRへ対応付ける。表のテスト欄は予定であり、今回green証拠ではない。正常0件だけのテストで配送成功を証明しない。

## 2. 実機fixtureと耐性クリティック

人工fixture A=無料・チーム未所属、B=一チーム、C=複数チーム/組織、D=他user/他tenant、E=system admin。実ユーザーの名前・メール・投稿・学習回答を使わない。Aは必要な本人entryを同日作成/完了、B/Cは同じ全体capへ到達。代理出欠、historical公開記事、他author editor公開、予約公開SYSTEMを人工sourceで準備。

E2Eは実画面でログイン→任意開始→卵の選定確認→elapsed進行→命名注意書き/入力/確認/孵化確定→無料care→成長→無料想起→ポイント反映→永久装飾交換→置物配置→設定非表示/再表示→pause/resumeを追う。実API・DB、fixtureのみ、通常成功ケースをモックで置換しない。権限なし/他tenantはURL直打ち/API直接要求も検証する。post-save reward遅延は本体成功と別表示。ネットワーク応答喪失、二tab同時care/装飾購入、背景tab、390px、OS動き抑制を含む。実装E2Eの後にアリシゼーションで導線漏れを探す。

設計レビュー直前クリティックでは「この仕様だけで実装して何が画面到達/JSON/null/認可/型/回復で壊れるか」を確認する。APIの未来宣言と現在API、legacybadge/entitlement依存、週policy、ARcomplete無実装を混同しない。

## 3. 旧実装の置換とbeta保全

1. 新source aftercommit transport/witness・育成domain・APIをfeature disabledで追加。旧ランキング/ログインポイントを新通貨へ移行しない。既存旧points/historyを消す作業は今回しない。
2. beta system badgeの授与境界を旧ポイント/RANKINGから切り離す。BetaGrantService→BetaTesterBadgeAwardService依存をService境界/adapterで維持・移設してから旧package/表の退役を検討。badge/entitlementをblind DELETE/DROPしない。
3. 既存badgeをuser+legacy badge ID+award periodで記念置物inventoryへidempotent projection。period欠落は正準空値、旧自由文/teamtitleをprivate payloadとして持ち込まない。grant取消が装飾取消へ伝わってもentitlement処理の正本はF20.1/F20.3。
4. 旧ログイン加算だけを停止し、LOGIN_SUCCESS監査/beta activeDays保持。新ranch未参加でもbetaの既存判定はそのまま。
5. 新規四source全経路/無料care/並行cap/受付loss境界/回復/認可と初期選定三方式がgreen後、運営がcare rule/少量species/SKU価格/point policyを明示登録し独立controlで小さな任意対象から公開。旧UI/旧API退役は依存確認と互換性を別実装PRで扱う。

ローカル旧points履歴の新通貨移行は不要。ただし本番/共有DBに履歴が無いと推測して削除しない。local DB resetを要求しない。既存migrationを変更しない。旧DB表の廃止はbeta保全fixture、grant再付与/取消/退会/連携の契約テストをgreenにしてから。

## 4. 性能・運用・停止

1000万userを前提にuser_idでranch owner/wallet/budget/ledger/inventoryを同じshardへ寄せる。source outboxはsource shardでlease、source facade配送。cross-domain FK/分散TX/全user集計hot rowを作らない。週capはuser行だけロック、全体owner数が増えても一つのglobal counterへ集中しない。policyは共通masterとして各shardへ版付き配布し、処理可能な同じhashであることを確認する。

GET stateは一userの小さな定数query、記録/inventoryはkeysetページング、一覧上限100。slot数は固定3、user自由増加なし。公開ゲート用SLO候補は通常負荷GET state p95<=300ms、報酬反映p95<=5秒、背景tabのscene timer/RAF/audio0、scene asset追加gzip<=150KB。値は設計の提案目標で実測保証ではない。実装PRで人工負荷/対象device/サーバー容量を記録して測定し、未達は性能改善または明示再裁可してから公開。測定未実施は実装公開gateであり設計PR完了を妨げる実行要求ではない。SSR公開ページへ新育成API/sceneを読み込ませない。

監視: source別pending/dead件数・oldestAge・lease失効・duplicate/capped/awarded・consumer latency・user lock待ち・残高不整合。本文なしの構造化eventID/ruleID/status、運営retry/停止はreason付き監査。上記SLOは運営配送停止/源障害中を除く通常時目標。停止期間の遅延も別計測し隠さない。

stopは配送pause（pending保持）と報酬pause（発生時刻期間で0）を分け、care/shopの個別controlとactivity reward/delivery pauseを独立させる。報酬kill switchでも本体source保存、既存恐竜/ポイント/装飾閲覧/無料careと既得pointsによる永久装飾交換を保持。rollback時は新テーブルを即DROPせず報酬disabled、新policy immutable履歴/outbox/ledgerを保持して回復する。seed/ルール値を戻して既存成長を減らさない。運営pause中に消えたrewardを捏造して手動ポイント加算しない。

ACK済みoutboxの短期archive/削除はcanonical witness/dedupを残したまま実装する。dead-letter/pendingは保留中に消さない。ledger/commandsはowner lifetimeで冪等性を維持し、退会時は02のDomainCleanupService契約で本人新規行を削除する。保存期間の数値は運用設定を明示登録し、無期限に本文を保存しない。

## 5. 設計/実装の完了を分ける

このPRのDoDは要件/API/DDL/状態/error/i18n/AC/非機能を備え、独立二パス6観点とE2E耐性クリティックの指摘を解消し、設計CI/検分を通すこと。運営量は未登録でも安全にdisabledなら実装可能な機構として設計を完了できる。masterの裁可案は承認後に区分を更新する。

実装DoDは `/軍議→試練red→出陣green→検分→実機→アリシゼーション` と全AC証拠。設計PRだけで本番ready、実機成功、報酬配送稼働と主張しない。

### 卵と選定のAC（構成/独自数秘採択済み、公開内容未承認）

| AC | 結果 | 主検証 |
|---|---|---|
| 46 卵時計 | ログイン/活動/正答0でelapsedだけひび進行、境界直前/ちょうど/直後とserver/client TZ差を確認。rule変更でも既存snapshotは巻き戻らない | UT/IT |
| 47 安全待機 | 約7日後未選定はEGG安全待機、損失0。選定済みでも時間未達ならEGG。両方満たすと次回openで命名導線、命名確認POSTで一度BABY、未命名はEGG安全待機、XP/points0 | IT/E2E |
| 48 孵化retry | 二tab/応答喪失/背景/STOPPEDで孵化・命名一回。GETは書込0、POSTはowner lock下でstage/name/namedAt/command同時保存。EGG care拒否、孵化後無料care | IT/E2E |
| 49 選定privacy | 未実装adapterは準備中/入力収集0、DOB/nameをpayload/log/audit/recordsへ複製0。決定的割当とrandomラベル区別、確認後訂正で自動交代0 | IT/E2E |
| 50 actor | 実actor/originalAdminIdはauth context、代理/impersonation全源0。通常editor/SYSTEM blog公開はauthorへ、client actor偽装で資格変化0 | IT |
| 51 診断公開gate | 承認済みquestionnaire/scoring/mapping versionと全64 typeCodeのspecies＋variant/承認素材（初期16 species×各4 variant）をfixture照合。一type欠落/重複、不明version、未承認素材で公開gate不成立。全64をserver採点結果→mappingへ対応付ける | UT/IT |
| 52 診断回答境界 | 承認questionnaireは6軸×4問/24問/5段階。全required回答と同点軸だけの追加二択（最大6）の完了でのみCOMPLETED、保留/別方式で勝手な同点解消0。空配列/欠落/重複/未知question/null/型不正/上下限の1外を400、上下限ちょうどは許可。client typeCode/scoringVersion偽装では採点結果不変。session開始後のmaster変更でもsnapshotを維持 | UT/IT |
| 53 診断所有/再診断 | anonymous401、他人/不在result/tokenは同形404。未完了resultで選定不可。再診断/訂正/二tab/retryでも確認済みdinosaur ID/species/XPを維持し、points/XP増分0 | IT/E2E |
| 54 診断privacy | 質問/回答/resultと出生PIIは共有profile、訪問response、報酬outbox/records/log/auditへ出ない。素材表示のためにprivate typeCodeを公開しない。後続訪問の推測可能性は別公開裁可事項 | IT/E2E |
| 55 本人プロフィール | principal由来の本人氏名/生年月日のみauth読取facade取得、他user指定拒否、nickname/恐竜名/任意名代用0。欠損時補完→確認→出生結果の導線、確認中profile変更で再確認。6言語の利用説明。既存profile DTOにbirthDateが無い状態から連携完成と見なさない | UT/IT/E2E（詳細契約確定後） |
| 56 決定性/正規化 | 同承認normalizationVersion/rule/mappingと同じサーバー取得本人プロフィールで同species＋variant。Unicode/空白/日付fixtureは承認規則へ照合、元姓名/カナ/DOBはauth既存保存のみで診断/ranch/result/履歴/event/log/audit複製0。両数1〜9反復還元/11・22・33特別保持なし/本人カナ版付きヘボン式を採択契約に照合する | UT/IT（詳細契約確定後） |
| 57 版照合/再送 | 確認profile版/指紋と確定時の照合、二tab/競合/応答喪失を検証。成功後profile変更→同key retryでも保存済みresultと同恐竜アバター、現在profileで再計算0。未知確認版/参照は安全失敗。03の全profile更新revision++/opaque UUID参照/TTL10分/既存HMAC rotation fail closedをfixture化、旧client出生body HMAC前提を使わない | UT/IT（詳細契約確定後） |
| 58 style保存 | PIXEL既定、PIXEL↔PAINT_2D保存後reload/別端末で保持。null/欠落/未知enum400、version競合409、応答喪失同key再送同結果。同dinosaur ID/species/stage/XP/残高で、旧renderer timer/RAF/audio残留0 | IT/FE UT/E2E |
| 59 素材/背景抑制 | 同個体同段階の片style素材欠落/取得失敗で静止fallbackまたは段階文字/給餌を維持、別個体化0/保存style自動変更0。海の泡/雲を含めOS reduce動的切替/REDUCED/STOPPED/非表示/背景tabで移動停止、背景tab timer/RAF/audio0、復帰の高速追いつきなし | FE UT/E2E |
| 60 必要件数BIGINT | remaining=0/amount未満/丁度倍数/余り1/signed BIGINT最大かつamount=1と2をfixture化。personalRequiredCountが正確なdecimal string（最大は"9223372036854775807"）、加算overflow/JS Number精度損失0。countLimit残枠不足では満額保証と表示しない | UT/IT/FE UT |

卵の約7日elapsed/三方式/独自数秘/24問構成は採択済み。ひび具体境界は開発snapshot値、質問内容/全対応表/初期16種×4外見素材は承認待ち。未裁可算法をテスト済み/実装確定と表現しない。出欠の実EnumはATTENDING/PARTIAL/ABSENTを基準ソースと照合し、旧略記ATを仕様に残さない。
## 採択済み契約と未完了gate

三方式/独自数秘/24問構成/本人結果独立/命名は01、source配送とDDLは02、API/auth guard/確認参照は03、画面/両styleは04を正本とする。質問の公開内容、全恐竜デザイン/対応表、親密度と運営値の本番調整は未承認。TL/Blogの同本人・同機能・同UTC週の完全一致初回のみは2026-10-04ユーザー裁可済み。実装は部分製造で全体合格ではない。初回証拠とtransport loss窓はAC07/09/11/21/22/31/72で現在状態からの資格捏造なしを検証する。

### AC61 孵化時の不可逆命名（ユーザー確定）

| AC | 結果 | 主検証 |
|---|---|---|
| 61 命名 | 孵化時のみ1〜10文字、注意書き・入力・確認・戻る・保存の順。空/null/欠落/空白のみ/不可視のみ/改行/制御文字/11文字/保存上限超/確認falseは400でEGG維持。1/10文字、日本語・結合文字・絵文字の書記素fixtureをFE/server照合。二tab別名で一件だけ確定、同key同名retryで同不変結果、別名/新key改名409、他人操作不可、GET更新0。同名を別userに付けることは可。reload/成長/style切替/休止再開で元名保持。命名途中離脱・応答喪失・IME・keyboard・STOPPED・6言語の注意書きと10文字表示・HTML文字のescape・本人氏名非流用を実機確認 | UT/IT/FE UT/E2E |

### 今回の裁可に伴う検証補完

AC51/53/58で16 species×4 variantの全64組・type対応・版固定・成長/再診断/style切替後の同外見維持を検証する。AC32は申請時削除の旧文言を撤去し、取消復帰と最終削除を別fixtureへ分ける。親密度は放置/休止/未ログインで下がらず、反応差だけで本体権利/成長/報酬差が発生しない条件を詳細化する。追加の愛着command/保存unitは02/03、追加試験はAC64で追跡する。未実装/未検証をgreenとしない。

### 追加AC62: 自分の結果を恐竜アバターから見返す

| AC | 結果 | 主検証 |
|---|---|---|
| 62 本人結果閲覧 | 64診断/占い各0件・一方式のみ完成・両完成・履歴ありの導線。卵/孵化後、再読込/別端末、PAUSED/非表示/STOPPED/care・報酬停止/残高0でも読取可。退会申請で拒否・取消で同result復帰・最終削除でcleanup。anonymous401、他人/不在/未完了同形404、不正cursor400。raw DOB/name/回答の返却・共有・ログ0。版変更でも過去snapshot維持、再診断で恐竜アバターID/名前/species/variant/XP/親密度/points不変。6言語・keyboard・画面到達を検証 | UT/IT/FE UT/E2E |

ACは追補込み73件。追加結果閲覧はユーザー確定したPhase 1要件。全ACの主検証欄は計画で、実装class/methodとgreen実テスト名の照合は未完了。技術契約は02/03に統合し、質問内容/対応表/素材を未決として残す。source完全一致の比較範囲は2026-10-04裁可済み、製造と検証は未完了。

対応表を決める時期はユーザー確定: 診断/占いと具体的な恐竜との対応表は恐竜のデザイン完成後に作成する。基本設計の完了に実際の割り当てデータは要求しない。版付きmappingの入出力・未登録時は有効化不可・旧個体を置換しない境界は設計で維持する。16種名簿/4外見/素材制作と対応表作成を後続タスクに分け、公開前にはAC51の全64対応を検証する。基本設計の完了と素材完成・初期公開可能の判定を混同しない。

## 採択済み商品仕様の参照

三方式・独自数秘・本人の分身・同個体維持・96論理ドット/2D・無料成体の正本は[01 商品契約](01_product_phases.md)。

## 追補AC63〜73（軍議採択、全体green未証明）

| 追補AC | 観測する結果 | 実装責務 | UT / IT / UI / E2E追跡 |
|---|---|---|---|
| 63 結果導線の独立 | ranch未参加でも本人診断を開始/結果読取。非表示時も設定から一覧/詳細/未診断/再診断へ到達、ranch開始を強制しない。既存resultは0件200、他人/未完了404 | DIAGNOSIS+FE-QUIZ | UT63-snapshot / IT63-unenrolledResult / UI63-settingsResults / REAL63-hiddenEntry |
| 64 非減衰指標の保存 | versioned affinity unit(kind+UTC日dev値)でfeeding/touch各初回だけ加算、別key/二tab/連打0。週XP枠後も反応可、egg touchはXP0、pause/放置で減少0、権利/成長倍率/points差0 | CORE Care/Affinity | UT64-unit±1 / IT64-concurrentUnit / UI64-reactionNoGauge / REAL64-cappedTouch |
| 65 出生確認能力の境界 | ACTIVE先lock→PRIMARY成功lookup→live profile検証の順、MICROSのDB/JSON/HMAC一致。本人revision/HMAC用途/nonce/withdrawalAttemptId/expiryを検査。TTL直前/丁度/後、未知/他人ref、鍵rotationで旧ref拒否、全profile変更経路後の旧ref拒否。診断作成・Ranch出生選定それぞれの成功PRIMARY replayをlive検査より優先し、成功同key再送はprofile変更後も元result。初回出生選定はlive確認ref revision R=OwnedResult内部sourceProfileRevision、DIAGNOSIS=null/BIRTH_STYLE>=0を照合。新refで旧profile由来resultは409、内部metadataは公開Summaryに追加せず、本人履歴閲覧は維持。name/DOB/kana/refのlog複製0 | AUTH+DIAGNOSIS | UT65-signatureExpiry / IT65-profileWritePathsRace / UI65-reconfirm / REAL65-twoTabProfile |
| 66 退会状態の新旧照合 | request→cancel→re-requestを逆順配送しても古い通知は無効。既存attemptIdと最新auth状態で照合し、PURGING barrierとconsumer/command/latequeue競合後再作成0。partial cleanup失敗→retryは同じ削除結果、OFF下も実行、取消は元PAUSED保持。出生結果保存/初回選定とprofile更新/申請を競合させても、ACTIVE lock・同revision照合・成功replay優先を維持しguard再帰0 | AUTH+全cleanup | UT66-withdrawalAttemptOrder / IT66-purgeBarrierRace / UI66-cancelRestores / REAL66-lifecycle |
| 67 完全一致証跡 | 同本人・同機能・同UTC週で新ID同内容は最初一件だけ（2026-10-04裁可）。NFC/改行/前後空白/添付/記事title比較fixtureと同時winnerを照合。窓±1/keyrotation/loss/retentionを明示、本文複製0、源外/想起意味比較0 | TL/CMS | UT67-normalizationMatrix / IT67-duplicateRaceLoss / UI=—source内 / REAL67-newIdDuplicate |
| 68 私的一覧keyset | records/inventory/results 0/1/100/101件、limit0/1/100/101、同timestamp cursor、別user/filter/tamper cursor400。completedAt MICROSのDB/JSON/cursor一致で重複0、data量でquery数が増えずbounded keyset、raw source/name/回答なし | CORE/DIAGNOSIS | UT68-cursorBinding / IT68-queryCount / UI68-emptyNextPage / REAL68-pagination |
| 69 診断回答とtie版 | 全3→tie6、正負同点、tie0/1/6、23/24/25問、未知/重複/boolean/0/6拒否。tie表示後回答改版→古tie409。保留再開同snapshot、cancel後complete不可、二tab/retry一result/無報酬 | DIAGNOSIS | UT69-sixAxisMatrix / IT69-answerRevisionRace / UI69-holdResume / REAL69-tieResume |
| 70 公開coverage | 全64type×16種4外見、3habitat候補、EGG/BABY/JUVENILE/ADULT×両style×基本反応/静止fallbackをmanifest照合。type欠落/重複/非approved/未登録rule/pool空で有効化拒否、fixture catalogはprod登録不可 | OPS/FE manifest | UT70-coverage / IT70-enableRejected / UI70-preparing / REAL70-unavailable |
| 71 scene資源停止 | carousel非active/accordion閉/card collapsed/viewport外/context非active/document hiddenを個別判定し保持DOM中timer/RAF/audio0。初回有効表示前asset/audio fetch0、OS reduceは背景停止/短い色表情許可、STOPPED全静止、復帰catchupなし | FE-RENDER+FE-WIDGET | UT71-eachVisibilityCause / IT=—scene内 / UI71-resourceCounts / REAL71-browserMotion |
| 72 transport容量と終端 | bounded queue満杯でも本体HTTP成功/非blocking、CallerRunsなし。lease満了±1/古tokenACK拒否、poison隔離、shutdown loss、transport後restart重複0。源ごとhealth/retry本文なし、source identity偽装拒否 | SOURCE+OPS | UT72-backoffJitter / IT72-queueAndLeaseFailureMatrix / UI72-health / REAL72-sourceSavePriority |
| 73 start後表示失敗とsave衝突 | start成功→widgetPUT失敗は同個体維持、表示のみretry。settings409は再取得しuser再操作、応答喪失元key維持、null/errorを空成功へ握り潰さない。slot/命名dialog focus復帰 | CORE/FE-WIDGET/QUIZ | UT73-stateTransitions / IT73-widgetSeparateTX / UI73-conflictRecovery / REAL73-startResponseLoss |

## 完全性消込み表

| 攻め口 | 試練対象 | 該当/消込み根拠 |
|---|---|---|
| 正常 | 01/02/04/07/11/13/16/17/24/41/44/47/51/55/58/61/62、63/69/73 | 無料未所属で全導線、三方式、4source、本人結果と同個体 |
| 空/0/null | 14/29/39/44/47/52/55/60/61/62、63/68/70 | null未参加、0prompt/empty lists/欠損PII/空catalog・必要件数0 |
| 境界 | 04/05/18/19/30/36/37/46/52/56/60/61、64/65/66/67/68/70/72 | cap N±1、UTC瞬間、grapheme/byte、tie0..6、TTL/rotation/世代/ページ上限 |
| 途中失敗 | 06/13/21/22/23/31/38/42/48/57、65/66/67/69/72/73 | rollback/aftercommit reject/crash/古ACK/応答喪失/部分cleanup/公開gate |
| 認可 | 08/26/40/49/50/53/54/55/57/62、63/65/66/68/71 | 本人guard、全role/別tenant、source identity、private cursor、PII leakage |
| 性能 | 23/27/28/30/59/60、67/68/70/71/72 | source bounded queue/lease、query一定数/keyset、非active timer0・asset lazy、BIGINT |

全類型が該当するため「該当なし」で省いた類型はない。既存ACの広い文をこの表だけで充足扱いにせず、分割fixtureごとのred/green実名を台帳へ残す。source別failure matrixとrole別実機証拠を併せる。

## 製造・検証の現在地（2026-10-03）

全Phase 1製造・検分・実機・アリシゼーションと最終commit/push/mergeの作業方針はユーザー裁可済み。これは公開内容や検証合格の裁可ではない。設計の歴史調査基準b3efd80は旧実装の説明用、軍議基準mainは38f8264212c2a3730ce645cebf40502d2d16338e。

当時の担当報告ではRANCH-CORE骨格61ファイルのcommitは2a6a1810、AUTH/DIAGNOSISの41pathは未commitでstubを含み、FEの17UTはローカルcomponent/純粋計算のみgreen、型チェック未完了だった。BEも:compileJava通過後、compileTestJava時のWSL EIOでtest未到達との報告だった。WSL疎通回復後は旧/tmpがすべて不存在で原因未確認（親担当からのWSL再起動・削除は実施していない）、旧native commitと試験logの実体を現在確認できない。これらは歴史報告であり、現在の保全・合格証拠ではない。親担当はbase38（38f8264212c2a3730ce645cebf40502d2d16338e）のcleanな新/var/tmp/mannschaft-ranch-20261003-koko配下4worktreeを作成し、Windows保存scriptsから再構成・再試験中。17FE試験も再実行必須。全checks/73AC照合/実Security401・403・IDOR/実MySQL race/実機E2E/住民探索/mergeの合格は未証明。draft PR #3617（当時記録head ddaf52）は未mergeの記録で、現在headの証拠とは分ける。資料・静的HTML検査をアプリや実機の合格として扱わない。

活動consumer・運営/商品/slot/旧badge統合・四源transport・AR実画面・最終purge・全素材manifest/対応表が残る。TL/Blogの新ID同内容完全一致は、同user・同feature・同UTC週で最初一件だけとする方針が2026-10-04にユーザー裁可された。本文・タイトル・添付の組合せとNFC・改行・前後空白の正規化を実装fixtureへ対応付ける。未製造・未検証を公開可能と扱わず、他の製造は継続する。

### 採択済みauth admissionの検証追跡

AC65/66/72の並行/途中失敗fixtureに、非TX Guard→single Semaphore.tryAcquire→別REQUIRES_NEW Runner proxy→PRIMARY read/writer順次→commit/rollback後finally permit復帰を含める。P不明/P<2は503、P2/3/4/5/50のG式と2G≤P、上限即503/callback0、ambient TX/再帰拒否、成功/認可失敗/callback例外/commit失敗のpermit復帰を検証する。既存共有pool他経路の完全予約を保証せず、3秒connection timeoutも観測する。設計採用済み、製造/実MySQL試験未実行。

## Ranch STRING enum の段階展開

`docs/development/persisted_enum_deployment.md` の二段階手順に従う。今回の17定数は既存テーブルの値の改名ではなく、新設Ranchテーブルの初期集合である。互換性台帳への登録は旧タスク退場の証明ではない。

| enum | DB列 | 列長・CHECK | 定数 |
|---|---|---|---|
| `AssignmentMethod` | `ranch_dinosaurs.assignment_method` | VARCHAR(30)、`chk_ranch_dinosaurs_8` | HABITAT_RANDOM / BIRTH_STYLE / DIAGNOSIS |
| `DinosaurStage` | `ranch_dinosaurs.stage` | VARCHAR(20)、`chk_ranch_dinosaurs_3` | EGG / BABY / JUVENILE / ADULT |
| `Habitat` | `ranch_dinosaurs.habitat` | VARCHAR(8)、`chk_ranch_dinosaurs_2` | LAND / SEA / AIR |
| `MotionMode` | `ranch_owners.motion_mode` | VARCHAR(20)、`chk_ranch_owners_5` | NORMAL / REDUCED / STOPPED |
| `ParticipationStatus` | `ranch_owners.status` | VARCHAR(20)、`chk_ranch_owners_2` | ACTIVE / PAUSED |
| `RenderStyle` | `ranch_owners.render_style` | VARCHAR(20)、`chk_ranch_owners_4` | PIXEL / PAINT_2D |

第1段階では定数と読取り互換性を全API・worker・batchへ配布する。開発用fixtureは本番OFFのままとし、Ranch開始・選定・孵化・設定など全書込み経路を公開しない。全タスクの旧versionが0になった時刻・環境・image digestをリリース記録へ残す。第2段階で別リリースとして本人書込みを有効化し、新規行の再読込とrollback下限を確認する。機能フラグをOFFにするだけでは、書込み済み新値を読めない旧バイナリへrollbackできない。

## 2026-10-07 検証checkpoint（B845）

以下はB845時点の証跡の限定範囲を記録する。親担当の最終検分・押印とmerge判断は未済。上記2026-10-03の記録と失敗履歴は当時の記録として保持する。73AC本文・完了条件を変更せず、全体green・本番ready・正式公開・戦役完了は宣言しない。PR #3652は入力進捗記録時点でDraft・未merge。

### 入力証跡とCIの確認範囲

作業領域の相対パスとして以下を参照した。これらのローカル証跡は製品リポジトリへの収録済みを意味しない。公開参照は[PR #3652](https://github.com/kenta-0420/mannschaft/pull/3652)、[Backend CI run 37496082771](https://github.com/kenta-0420/mannschaft/actions/runs/37496082771)のJUnit artifacts（6 shardとArchUnit）、[Frontend CI run 37496082739](https://github.com/kenta-0420/mannschaft/actions/runs/37496082739)のjob 112380863445ログ。CIの公開参照とローカルで保存した限定照合を併記する。

- `outputs/mannschaft-ranch-phase1-progress-20261006.md`（v26実機・住民探索完了・四活動事前停止までの時点別記録）
- `work/ci-resume-20261006/root-ci-evidence-adoption-b845c0.json`（親検分済み、`wholeAcceptanceComplete=false`）
- `work/ci-resume-20261006/ci-evidence-index-candidate.json`（元候補の照合範囲・workflow HEADとmerge checkoutの区別）
- `work/recovery-phase1/diagnosis/resume-plan-20261006/merge-publication-boundary-b845c0-candidate-01/`（`classification.md`・`candidate-manifest.json`のA/B/C境界案、分類は親未採択）

PR/workflow HEADは `b845c0152e1d7230af09f5c4783136401d2e86f7`。13 CI workflowは完了し必須ジョブ成功、Backend全6 shard・Non-JST・各ゲート・集約成功。schedule/main-onlyの2ジョブは条件skip。Frontendの監査・lint・型検査・Vitestは成功し、503ファイル／5,008テスト成功を観測した。ただしFrontend jobの実checkoutはPR merge ref `c2d89e28c8937e70a9a0b8c316d0e7b6b6f87cab`であり、B845単独checkoutでの再実行とは表現しない。

親採択されたBackend照合は71直接JUnit一致＋12 source method／表示名／parameterized名の照合で計83候補。PASSは各assertの範囲に限る。Frontendの候補は9 specファイルの成功証跡で、13候補行・14展開例を含むが、ファイル進捗だけからmethod単位PASSを推定しない。元候補indexの未採択表記と、後続の親採択JSONを時点別に区別する。いずれも73AC全条件の合格に昇格しない。extractorがJSON生成後にKeyErrorとなった旧失敗と、v2 exit 0の記録も保持する。

### 実機・住民探索の限定結果

v26/stage17は親がactual BE/FE所有関係とHEAD/JARを採択。新人工本人のSMOKE 2件・選定1件・命名/描画/給餌1件・診断保存1件が成功し、成功直後readinessによる4baselineを登録した。匿名warmupのDCL30秒超過は保持し、login mount観測・private牧場GET0・ログイン/保存0のwarmupをAC成功へ算入しない。旧ownerのbaselineを新ownerで実行したとは扱わない。

- 住民A（1280×900/en）はDashboard→Village（空）→My Page→Settingsで部屋へ到達。Interactの本人分身に触れた反応と、無料枠後のFood「Growth: 0 XP」を確認した。初見の寄り道は導線の観測として保持し、成体画像の準備中表示は正式ADULT素材未制作と区別する。
- 住民B（1280×900/en）は本人結果閲覧とCalendar離脱後の同じ結果再表示を確認。6軸の判定側が分かりにくいfindingは別担当が最小修正を製造中で、修正・検証完了にはしない。
- 住民C（390×844）はMotion=Stop motionを選びSave後の表示と、予約の空状態ガイドを確認。reload/再訪保持と実アニメーション停止は未確認。

3住民は別contextで自律探索を完了しbrokerは終了。full-page画像と上記viewportだけの観測で、全端末・tap 44px・全localeを網羅した証拠ではない。画像のマゼンタはinput/textareaを伏せるscreenshot mask、selector構文による操作失敗はtool/runtime errorであり、製品不具合と混同しない。

### 未完了AC・mergeと正式公開の境界

全73ACのclass/method・実green・assert範囲・実UI証跡の最終照合は未完了。次の部分成功・保留を受入条件の削除や完了へ読み替えない。

| 範囲 | 現在の証拠と未完了条件 |
|---|---|
| 四活動・報酬・認可（AC07〜17/26/31/40/50/67/72など） | v26 run-activityはUI実行前の`LOCALE_BYTE_BOUND` guard拒否で0 proof groups、選択7認可caseはNOT_STARTED。実Linux/Git LFはcommon.json 134600 bytes・4locale合計163727 bytes、serialized labels 228107 bytes。Windows CRLF観測はcommon.json 137398 bytes・4locale合計166915 bytesで、サイズを混同しない。実Linux commonも旧131072上限を超えるguardfindingであり、v26時点のpayload262144 guardは維持。報酬成功・製品不具合の証拠にせず、TL編集・非公開組織の実機境界も未実証として残す。 |
| care・無料成体（AC05/41/64など） | care実機1件と住民の枠後反応は限定成功。残1XP calculator assertは原子二tab競合を代用せず、同entityへのXP追加UTだけで次UTC週budget/無料未所属の任意利用を証明しない。 |
| 運営・認可・退会（AC26/40/45/66など） | ownerを作らないadmin読取200はpublish/control/retry全操作を代用しない。MockMvc/仮principalは実browser Cookie/CORS/他tenant直打ちを代用せず、purge rollback ITだけで全世代/PURGING barrier競合を網羅しない。 |
| 表示・motion・結果（AC27/28/58/59/62/63/69/71など） | settings到達・結果再表示とCの保存後表示は限定成功。全6言語/keyboard/zoom/dark/二tab、reload/別端末保持、個別非active timer/RAF/audio停止、診断判定側findingの修正確認は未完了。 |
| 正式公開（AC44/49/51/52/53/56/58/59/70など） | 正式質問文/翻訳/採点、全64type mapping、16種×4外見、全stage×両style×反応/静止素材は未承認・未完成。fixtureのsynthetic64/pack受理と欠落拒否はgate機構の限定証拠で正式coverage完成ではない。公開gateはclosed。 |
| 性能・運用（§4/AC23/68/72など） | QA配送一点を全range/本番SLO承認へ拡張しない。通常負荷p95/gzip等の公開前実測と、明記された二worker/基本failure matrix/0・1・100・101 keysetは必要証拠として残す。 |

境界候補のAはmerge前の基盤・安全契約と明記基礎fixture、Bは後続の正式素材/原稿/mapping/運営値の制作・裁可、Cは基礎fixture成立後の追加組合せ/長時間soak/将来規模測定を分けた案である。同じACに複数区分があり、親最終判断欄は未採択。B/Cへの分類案だけでAの認可・失敗回復・基本競合・個別visibility等を免除しない。通常負荷の公開前SLOも残し、merge可否と正式公開可否を分ける。台帳close・README完成・受入条件撤回はこのcheckpointの対象外。
