# F04.7-05 受け入れ条件・検証・置換手順

> **ステータス**: 🟡 草案（設計レビュー・裁可待ち）
> **正本入口**: [F04.7](../F04.7_gamification.md)
> **区分**: 以下は実装戦役の検証計画。今回の実機/UT実行結果ではない。

## 1. テスト可能な受け入れ条件

`UT`=単体、`IT`=実MySQL/API結合、`E2E`=実ブラウザ/本体API/DBのfixture。sourceのTX配送を観測するITはクラスの@Transactionalでcommitを封じず、fixtureを明示commitする。テスト値は02の検証専用policy、時計はClock.fixed/fake timersで制御する。

| AC | 誰が/何をしたら/観測する結果 | 主検証 |
|---|---|---|
| 01 任意 | ranch未参加/表示OFFの無料userが出欠/投稿/blog/想起を使える。機能や権利を失わず、強制dialogなし | E2E |
| 02 初期 | 同userが二tabで卵開始を同時送信し、owner一件/個体一頭/部屋一件、重複0/卵一個/egg rule snapshot固定 | IT/E2E |
| 03 罰なし | 最終利用から長期間時計を進め、balance/XP/stageが減らず死亡/streakなし | UT/IT |
| 04 成長 | activity0/残高0の無料給餌成功で同個体IDのXPだけ増え、凍結閾値ちょうどでBABY→JUVENILE→ADULT。閾値1未満/最大/overflowも確認 | UT/IT |
| 05 同時care | care週残1XPで二tab異なるkey無料給餌、XP合計1/cap超過0、双方cost0、触れ合いは可能 | IT/E2E |
| 06 給餌retry | 成功応答を喪失して同key再送、同result/care XP一回。同key別body409、最新stateを古いresultで上書きしない | IT/E2E |
| 07 出欠正常 | known native history（少数marker案採用または信頼できる既存履歴）fixtureで本人がATTENDING/PARTIAL/ABSENTを初回答、subject本人へ一回。三値同量、別scheduleも全体cap合算 | IT |
| 08 出欠除外 | UNDECIDED/代理/impersonation(originalAdminId非NULL)/管理者一括/取消/編集/代理履歴残留をfixture化。今回本人context以外0、本人有効初回答だけ一回 | IT |
| 09 出欠historical | rollout前の本人回答を更新してもwitness HISTORICALで0、firstRespondedAtだけで判定しない | IT |
| 10 TL | 通常本人投稿一回、derived共有/repost/reply/反応は0、クライアントorigin/actor偽装は対象変更不可 | IT |
| 11 Blog | known native first-publication history fixtureで手動/一括/自己承認/他editor承認/予約SYSTEM公開を各実行しauthorへ一回、actorに誤付与0 | IT/E2E |
| 12 Blog除外 | 下書き/編集/公開失敗/認可拒否/撤回→再公開/rollout前公開歴は0。同sourceのTL共有も0 | IT |
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
| 40 CSRF | 認証Cookie SameSiteStrict/HttpOnly/prodSecureを保持、cross-site mutation拒否、refresh後の正規本人操作成功 | IT/E2E |

| 41 無料成体 | activity0/残高0/チーム未所属で同日に週care枠を使い、次UTC週も任意の日に使い同個体が成体へ到達。ログイン/課金/毎日/AR不要 | IT/E2E |
| 42 交換競合 | 一商品分残高で異なるSKU二購入の同時要求、負残高なし。同SKU競合は一inventory/一消費。同key retry同result・priceVersion違い409 | IT/E2E |
| 43 inventory由来 | SHOPはlegacy fields NULL/sku価格必須、LEGACY_BADGEはlegacy ID/period必須。unique acquisitionKeyで二重増殖0、beta特典は別 | IT |
| 44 初期抽選 | LAND/SEA/AIRのみ、診断pool除外、他habitat/species/versionのbody偽装拒否、retry再抽選0、全species care量/閾値同じ | IT/E2E |
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

### 卵と選定の追加AC（算法本体は未裁可）

| AC | 結果 | 主検証 |
|---|---|---|
| 46 卵時計 | ログイン/活動/正答0でelapsedだけひび進行、境界直前/ちょうど/直後とserver/client TZ差を確認。rule変更でも既存snapshotは巻き戻らない | UT/IT |
| 47 安全待機 | 約7日後未選定はEGG安全待機、損失0。選定済みでも時間未達ならEGG。両方満たすと次回openで命名導線、命名確認POSTで一度BABY、未命名はEGG安全待機、XP/points0 | IT/E2E |
| 48 孵化retry | 二tab/応答喪失/背景/STOPPEDで孵化・命名一回。GETは書込0、POSTはowner lock下でstage/name/namedAt/command同時保存。EGG care拒否、孵化後無料care | IT/E2E |
| 49 選定privacy | 未実装adapterは準備中/入力収集0、DOB/nameをpayload/log/audit/recordsへ複製0。決定的割当とrandomラベル区別、確認後訂正で自動交代0 | IT/E2E |
| 50 actor | 実actor/originalAdminIdはauth context、代理/impersonation全源0。通常editor/SYSTEM blog公開はauthorへ、client actor偽装で資格変化0 | IT |
| 51 診断公開gate | 承認済みquestionnaire/scoring/mapping versionと全64 typeCodeのspecies＋variant/承認素材（初期16 species×各4 variant）をfixture照合。一type欠落/重複、不明version、未承認素材で公開gate不成立。全64をserver採点結果→mappingへ対応付ける | UT/IT |
| 52 診断回答境界 | 承認questionnaireの全required回答でのみCOMPLETED。空配列/欠落/重複/未知question/null/型不正/上下限の1外を400、上下限ちょうどは許可。client typeCode/scoringVersion偽装では採点結果不変。session開始後のmaster変更でもsnapshotを維持 | UT/IT |
| 53 診断所有/再診断 | anonymous401、他人/不在result/tokenは同形404。未完了resultで選定不可。再診断/訂正/二tab/retryでも確認済みdinosaur ID/species/XPを維持し、points/XP増分0 | IT/E2E |
| 54 診断privacy | 質問/回答/resultと出生PIIは共有profile、訪問response、報酬outbox/records/log/auditへ出ない。素材表示のためにprivate typeCodeを公開しない。後続訪問の推測可能性は別公開裁可事項 | IT/E2E |
| 55 出生日/名 | BIRTH_STYLEで実在YYYY-MM-DDのうるう日を受け、非うるう日/不正月日/日時/TZ付き/null/欠落/空白名/名長上限+1は400。trim後1/80文字を境界確認。本名不要の説明が6言語に表示され、未承認adapterは入力収集0 | UT/IT/E2E |
| 56 決定性/正規化 | 承認済みnormalizationVersion/rule/mappingと同一正規化入力は同species。同値になるUnicode/空白fixtureと別値fixtureを承認規則に照合し、raw出生入力はDB/API/event/log/auditへ保存0。正規化内容の裁可前は公開不可 | UT/IT |
| 57 HMAC rotation | 出生commandのkeyVersion/normalizationVersionを保存し、rotation前成功→rotation後同key retryは同結果/再抽選0、別入力409。旧key/規則不明は安全に失敗し新規確定0。plain hash/raw入力を結果や監査へ露出0 | UT/IT |
| 58 style保存 | PIXEL既定、PIXEL↔PAINT_2D保存後reload/別端末で保持。null/欠落/未知enum400、version競合409、応答喪失同key再送同結果。同dinosaur ID/species/stage/XP/残高で、旧renderer timer/RAF/audio残留0 | IT/FE UT/E2E |
| 59 素材/背景抑制 | 同個体同段階の片style素材欠落/取得失敗で静止fallbackまたは段階文字/給餌を維持、別個体化0/保存style自動変更0。海の泡/雲を含めOS reduce動的切替/REDUCED/STOPPED/非表示/背景tabで移動停止、背景tab timer/RAF/audio0、復帰の高速追いつきなし | FE UT/E2E |
| 60 必要件数BIGINT | remaining=0/amount未満/丁度倍数/余り1/signed BIGINT最大かつamount=1と2をfixture化。personalRequiredCountが正確なdecimal string（最大は"9223372036854775807"）、加算overflow/JS Number精度損失0。countLimit残枠不足では満額保証と表示しない | UT/IT/FE UT |

卵の7日/ひび境界・占い対応表・診断実装範囲・初期16種×4バリエーション素材は追加裁可事項。未裁可算法をテスト済み/実装確定と表現しない。出欠の実EnumはATTENDING/PARTIAL/ABSENTを基準ソースと照合し、旧略記ATを仕様に残さない。
## 初期公開の選定3方式（最新確定範囲・内容は未裁可）

性格診断もPhase 1初期公開から必須。BIRTH_STYLE（出生情報＋選定用名の占い風決定的割当）、HABITAT_RANDOM（海/空/陸random）、DIAGNOSIS（64タイプ）の三入口を卵期間に選択する。初期は16種×各4つの色・体型・模様のバリエーション＝64タイプ、将来64種へ拡張する方針はユーザー確定。恐竜との過ごし方を想像する質問は可、牧場の設備や遊び方を知っている前提の質問は改稿する。質問/採点/64 type→species＋variant対応表、占いの具体計算/入力正規化、初期16種の名簿・4デザインの内容は詳細未確定で、24問案を承認済みとしない。公開gateは三方式の確定済みserver rule/入力validation/全64 mappingと必要素材が揃うこと。ランダムpoolの旧別pool条件との整合はユーザー確認中。診断未実装を利用可能と装わず、暫定公開で診断を後回しにしない。

提案構造: 本人診断sessionをserver発行しquestionnaireVersion/scoringVersionをsnapshot、回答は本人sessionへ送信、serverがvalidationと採点をしてCOMPLETED結果（provider/typeCode/mappingVersion）を不変保存する。選定確認時に本人COMPLETED結果と対応表versionを検証してspeciesを固定。clientのtypeCodeを結果として信用しない。診断結果が変わっても確認済みの同恐竜を維持する。質問/回答/診断resultはprivate、報酬outbox/共有プロフィールへ出さず、診断完了回数をpoints/XPにしない。質問/画像/算法の外部サイト利用許諾/APIは未確認で、無断複製を前提にしない。

診断session API/DTO/質問master/採点rule/結果tableの完全な契約と素材仕様は、未裁可内容を決めてから本草案へ補完する。現在の草案は選定adapterと保存/認可/同恐竜維持の境界までを示すレビュー資料で、診断本体をこのまま実装可能と主張しない。Phase 1の4〜8週は診断/64素材追加前の旧概算であり再見積が必要。全体3〜6か月も既存基盤/準備済みアートの旧前提の候補で、診断と素材次第で超える。

## 基本設計と後続モジュール設計の境界

追加AC51〜60は実装時の検証契約で、現在のgreen結果ではない。未裁可の入力/採点/素材内容に依存するACは承認fixtureを作ってから実行する。

| 領域 | 基本設計で定める境界 | 後続モジュール設計で確定する内容/公開gate |
|---|---|---|
| 診断 | Phase 1三入口必須、本人session/server採点/version固定、全64 mapping、結果private、再診断同個体 | 質問数/質問文/回答値域/採点式/同点処理、完全API/DTO/DDL、provider利用権。ユーザー回答を得て補完し、承認前公開なし |
| 出生割当 | 厳格日付/本名不要、決定性、raw非保存、版付きHMACとrotation後retry | 日付の商品範囲、算法/対応表、正規化規則、version/key管理手順。具体内容は勝手に選ばない |
| 二style素材 | 96×96 PIXEL/PAINT_2D保存切替、同個体維持、欠落fallback、motion抑制 | 初期16種×4バリエーション、成長段階と必要反応/静止素材、共通anchor/素材schema、両style制作検証。比較試作だけで公開完成としない |
| 歩行/広い牧場 | Phase 1 idle歩行なし、個体ID維持 | 後続Phaseの移動/方向/歩行素材と相互作用。現行歩行試作をPhase 1完成証拠にしない |
| 退会cleanup | 申請で停止・取消で同じ相棒復帰・最終purgeで削除は確定、機能OFFでもライフサイクル処理継続 | worker/本人mutationとのrace、tombstoneの永続場所・照合/保持、冪等再送と障害回復。具体契約とIT証拠を補完するまでgreenにしない |
| 操作/親密度 | 3ボタンとmenu、非減衰親密度を仕草・反応で表現は承認済み。卵は触る/選び方/ようす、追加通貨なし | 操作の具体導線、親密度の獲得条件/段階/保存方法、初期基本反応の種類。数値公開ゲージは未承認。1種pilotで制作量を計測して追加反応を段階化 |

基本設計の検分/CIと、上表の詳細裁可・実装UT/IT/実機・公開gateは別である。🟡草案を維持し、診断本体や64素材、Phase 1実装が完成したとは表現しない。
初回証拠AC: Blog撤回でpublishedAt=NULLになっても再公開を初回と推定しない。native firstPublishedAt/known-history未採用または証拠不明は0。attendance proxy/UNDECIDED/impersonation履歴不明のupdateもUNKNOWN0。admin commandの管理shard/各source facade scope境界をAC45で検証し、分散共通key保証を主張しない。source別TX前の取りこぼしは運営にも完全観測できない場合を含む。

Loss窓の連続AC: 本体初回commit成功→別TX witness/outbox前crash→復旧→編集/再公開/出欠update。元HTTP成功を維持し、信頼できる不変初回marker/当時actor/originが無ければ0。現在author/proxy/publishedAt=NULLから再生成禁止。sourceの本来の業務validation/row保存失敗は通常の失敗応答を維持。

### AC61 孵化時の不可逆命名（ユーザー確定）

| AC | 結果 | 主検証 |
|---|---|---|
| 61 命名 | 孵化時のみ1〜10文字、注意書き・入力・確認・戻る・保存の順。空/null/欠落/空白のみ/不可視のみ/改行/制御文字/11文字/保存上限超/確認falseは400でEGG維持。1/10文字、日本語・結合文字・絵文字の書記素fixtureをFE/server照合。二tab別名で一件だけ確定、同key同名retryで同不変結果、別名/新key改名409、他人操作不可、GET更新0。同名を別userに付けることは可。reload/成長/style切替/休止再開で元名保持。命名途中離脱・応答喪失・IME・keyboard・STOPPED・6言語の注意書きと10文字表示・HTML文字のescape・選定用名非流用を実機確認 | UT/IT/FE UT/E2E |

### 今回の裁可に伴う検証補完

AC51/53/58で16 species×4 variantの全64組・type対応・版固定・成長/再診断/style切替後の同外見維持を検証する。AC32は申請時削除の旧文言を撤去し、取消復帰と最終削除を別fixtureへ分ける。親密度は放置/休止/未ログインで下がらず、反応差だけで本体権利/成長/報酬差が発生しない条件を詳細化する。追加の親密度command/保存契約・テストIDは詳細設計後に登録する。未実装/未検証をgreenとしない。

## 2026-10-03の追加裁可

初期16種×各4バリエーション＝64タイプ、将来64種へ拡張。生年月日＋名前は固定の割当方式にし、既存占いと対応できる方式を優先して検討（数秘術を参考にする方向は採用、具体計算/対応表は未確定）。退会取消で同じ相棒を戻し、最終アカウント削除で消去。孵化後の3ボタンと非減衰親密度の仕草・反応表現を採用。相棒との過ごし方の質問は可、牧場機能の知識を前提にした質問は改稿する。素材・動作の大量生成を一度に要求せず、制作時間/品質を1種pilotで確認する計画案を用意する。

## 相棒の位置づけ・自分の診断結果（2026-10-03ユーザー確定）

恐竜は「自分から生まれた相棒の精霊」が恐竜の姿をとった存在として扱う。牧場主は本人の分身、相棒は本人から生まれた別の存在で、別エンティティ・同一個体の継続という技術境界は維持する。診断はユーザー自身の好み・傾向を振り返るための結果であり、相棒の性格や能力の診断に読み替えない。占い・精霊は作品内の表現で、科学的な性格測定や超自然的効果を保証する説明にしない。

64タイプ診断と生年月日・名前の占いは、相棒の「ようす」から本人がいつでも見返せる。未実施の方式は「未診断」と表示し、別方式の結果を捏造しない。結果閲覧の主目的を「相棒を選んだ時の記録」としない。未実施の診断は後から実施でき、再診断も新しい本人の結果を作るだけで、確定済み相棒の個体・種・外見・名前・成長・親密度を変えない。診断実施・閲覧・再診断をポイントや育成条件にしない。誕生に使った結果との内部参照は、同個体維持のための記録としてUIの主題から分ける。

数秘術を参考にした相棒占いの方向と、初期反応を待機・食べる・短い喜びに絞る案を採用する。日本語名の正規化・数の扱い・恐竜対応表、質問案の各設問・採点・同点処理は未確定。一般的な相棒との過ごし方を想像する質問を残し、牧場機能の予備知識を要求しない。16種×4外見の具体名簿/デザイン、ランダムの旧別pool条件との整合も別の未確定事項として維持する。

### 追加AC62: 自分の結果を相棒から見返す

| AC | 結果 | 主検証 |
|---|---|---|
| 62 本人結果閲覧 | 64診断/占い各0件・一方式のみ完成・両完成・履歴ありの導線。卵/孵化後、再読込/別端末、PAUSED/非表示/STOPPED/care・報酬停止/残高0でも読取可。退会申請で拒否・取消で同result復帰・最終削除でcleanup。anonymous401、他人/不在/未完了同形404、不正cursor400。raw DOB/name/回答の返却・共有・ログ0。版変更でも過去snapshot維持、再診断で相棒ID/名前/species/variant/XP/親密度/points不変。6言語・keyboard・画面到達を検証 | UT/IT/FE UT/E2E |

ACは合計62件。追加結果閲覧はユーザー確定したPhase 1要件、API/保存案は未実装で検証未実行。基本の物語・商品方針は合意が進んだが、診断の具体採点/同点、占い入力正規化、親密度の保存・加算契約、報酬源の初回/再投稿と退会raceの詳細補完・検分が残る。報酬量・成長量の数値調整は運営設定へ遅らせられるが、未定の技術契約や正式検分を完了扱いにしない。

対応表を決める時期はユーザー確定: 診断/占いと具体的な恐竜との対応表は恐竜のデザイン完成後に作成する。基本設計の完了に実際の割り当てデータは要求しない。版付きmappingの入出力・未登録時は有効化不可・旧個体を置換しない境界は設計で維持する。16種名簿/4外見/素材制作と対応表作成を後続タスクに分け、公開前にはAC51の全64対応を検証する。基本設計の完了と素材完成・初期公開可能の判定を混同しない。
