# F04.7-03 API・運営設定・認可

> **ステータス**: 🟡 草案（設計レビュー・裁可待ち）
> **正本入口**: [F04.7](../F04.7_gamification.md)
> **区分**: 新API/DTOは提案。現行APIとして案内しない。

## 1. 型・JSON共通契約

新ranchは `/api/v1/me/ranch`。全APIが認証必須、user IDは認証principalだけから取得。ownerId/userId/recipient/points/xp/occurredAtをbodyから受けて状態を書き換えない。JSONフィールドはcamelCase、EnumはUPPER_SNAKE、成功は `{data:T}`、一覧はCursorPagedResponse（`data:[]`,`meta:{nextCursor:string|null,hasNext:boolean,limit:int}`）。空一覧は200。Entityを直接返さず、不変DTOと生成OpenAPI型を使う。

UUIDはcanonical小文字ハイフン形式のstring、Java UUID/MySQL BINARY(16)。既存user IDはJava Long/MySQL BIGINT、APIに露出するときはdecimal string。新ranchのLong残高/XP/versionもdecimal stringで返し、JS Numberへ無制限変換しない（既存APIの全体設定変更なし、ranch DTOだけで明示）。slot数/件数など安全上限内intはJSON number。新日時はInstant/UTC ISO8601 `Z`、null許容欄以外は必須・null不可。既存source APIのIDは現行契約の型を維持し、envelope/sourceRefではfacadeがtypeを検証・正規化したstringを返す。

内部canonical_key/acquisition_key/canonical_source_id/canonical_scope_idはASCII形式とbyte上限を厳格検証してVARBINARYへ保存する（02）。API/envelope/sourceRefでのstring契約は維持する。sourceTypeを含む正準キー全体をASCIIに限定し、raw source本文/PIIをキーへ含めない。DB binaryのbase64化やtext列collation overrideをJSON契約として持ち込まない。

本人ranch mutationに `Idempotency-Key: UUID` 必須。scopeはranch内user+key、hashはcommandType+normalized body（path resource ID/If-Matchも含む）。同type/bodyは以前の成功結果、異なるtype/bodyは409。同キーをtab/sessionの終了で削除しない。成功keysだけを同TXで永続保存する。bodyに予期しない特権フィールドを入れても権限/量が変わらず、当該APIでは400とする。HTTP retryでポイント二重消費しない。

## 2. 本人向けAPI

| メソッド | パス | Request | Response / status |
|---|---|---|---|
| GET | `/api/v1/me/ranch` | なし | 200 RanchState。未参加はowner/dinosaur=null（GETで作らない） |
| POST | `/api/v1/me/ranch` | `{}`、Idempotency-Key | 卵/owner/部屋201、egg ruleをsnapshot保存、Location同パス。既存なら200同state、別個体を作らない |
| PUT | `/api/v1/me/ranch/settings` | RanchSettingsRequest（全field必須）、Idempotency-Key | 200 settings。version競合409。表示変更は既存widget API |
| POST | `/api/v1/me/ranch/pause` | `{version:string}`、Idempotency-Key | 200 status PAUSED。個体/ポイント保持、休止開始Instant |
| POST | `/api/v1/me/ranch/resume` | `{version:string}`、Idempotency-Key | 200 status ACTIVE。休止中fact遡及なし |
| POST | `/api/v1/me/ranch/feeding` | `{version:string}`、Idempotency-Key | 孵化済み一頭をprincipalのownerから決定（EGGは409）。新給餌201、Location `/api/v1/me/ranch/commands/{commandId}`。再送200同不変結果。FREE_BASIC/cost0、care週枠後XP0でも触れ合い可 |
| GET | `/api/v1/me/ranch/commands/{commandId}` | UUID path | 200 CommandResult。非所有/不在404 |
| GET | `/api/v1/me/ranch/records` | cursor:string?、limit:int=20、1〜100 | 200 CursorPagedResponse<Record>。body/回答内容なし |
| GET | `/api/v1/me/ranch/collectibles` | cursor:string?、limit:int=20、1〜100 | 200 inventory。復元前/0件は[] |
| PUT | `/api/v1/me/ranch/room/slots/{slotKey}` | `{inventoryId:UUID,version:string}`、Idempotency-Key | 200配置結果。所有/未取消/slot許可を検証 |
| DELETE | `/api/v1/me/ranch/room/slots/{slotKey}` | versionをIf-Matchにdecimal string、Idempotency-Key | 204。置物はinventoryへ戻り消えない。空slotでもversion一致なら204/version+1、同key再送は元204 |

| メソッド | パス | Request | Response / status |
|---|---|---|---|
| GET | `/api/v1/me/ranch/shop` | なし | 200 ShopItem[]、運営登録済み永久置物/現行price version。空は[] |
| POST | `/api/v1/me/ranch/purchases` | `{skuKey:string,priceVersion:string,version:string}`、Idempotency-Key | 201 PurchaseResult/Location commands/{commandId}、再送200。同SKU既所有/残高不足409 |
### 2.1 レスポンス/リクエスト型

**RanchState**:

| field | 型/null | 意味 |
|---|---|---|
| featureStatus | AVAILABLE/UNAVAILABLE、必須 | care利用可否（activity reward状態とは独立） |
| deliveryPaused | boolean、必須 | 配送一時停止（報酬停止とは別） |
| rewardsStatus | ENABLED/DISABLED/PAUSED、必須 | 活動pointsの運営状態 |
| shopAvailable | boolean、必須 | 永久装飾交換の運営状態 |
| careBudget | CareBudget/null | 未参加/有効care ruleなしはnull |
| owner | OwnerSummary|null | 未参加はnull |
| dinosaur | DinosaurSummary|null | 未参加はnull、参加済みでownerあり/dinoなしはデータ不整合で500 |
| settings | Settings|null | 未参加はnull |
| weekBudget | WeekBudget|null | 未参加または有効policy未登録はnull |
| roomSlots | Slot[]、null不可 | inventoryが無いslotはinventoryId=null |
| policyVersion | decimal string|null | 有効policyが無いとnull |
| serverTime | Instant string、必須 | 表示確認用。FEの時計で報酬を決めない |

OwnerSummary=`{id:UUID,status:ACTIVE|PAUSED,balance:string,version:string}`。DinosaurSummary=`{id:UUID,speciesKey:string|null,variantKey:string|null,habitat:LAND|SEA|AIR|null,speciesCatalogVersion:string|null,stage:EGG|BABY|JUVENILE|ADULT,name:string|null,namedAt:Instant|null,xp:string,nextStageXp:string|null,version:string}`、EGG/ADULTでnextStageXp=null、未選定EGGのspecies/habitat/catalogVersion=null。Settings=`{isVisible:boolean,viewMode:ROOM,renderStyle:PIXEL|PAINT_2D,motionMode:NORMAL|REDUCED|STOPPED,isSoundEnabled:boolean,soundVolume:int,version:string}`。isVisibleは既存dashboard widget visibility正本の投影。RanchSettingsRequest=`{renderStyle:PIXEL|PAINT_2D,motionMode:NORMAL|REDUCED|STOPPED,isSoundEnabled:boolean,soundVolume:int,version:string}` だけを受け、volume0〜100、null/欠落拒否。renderStyleはownerに永続保存し初期PIXEL。切替はowner version競合/冪等契約に従い、dinosaur ID/選定/成長/残高を変えない。viewModeはROOM固定。ranch TX内で別domain表示Repositoryを更新しない。開始後widget表示を別APIで設定し、失敗時も作成個体を維持して表示更新だけretryする。

WeekBudget=`{weekStartsOn:YYYY-MM-DD,weekEndsAt:Instant,globalCap:string,awardedTotal:string,remaining:string,policyVersion:string,personalRequiredCount:decimal string,personalCompletedCount:int}`。全源/所属数でcapは同じ。personalRequiredCountは非負remainingと正personalAmountから、整数除算のquotient + (remainderが0なら0、他は1)で求めdecimal stringで返す。remaining+amount-1や浮動小数点ceilを使わず、signed BIGINT最大でもoverflow/精度損失を起こさない。remaining=0は"0"。必要件数は残量の理論値で、countLimit残枠/quota不足なら個人想起だけの満額到達を保証しない。UIに毎日やらないと減るような表現を置かない。

FeedingResult=`{commandId:UUID,dinosaurId:UUID,careKind:FREE_BASIC,costPoints:string(常に0),gainedXp:string,isGrowthCapped:boolean,stageBefore:enum,stageAfter:enum,balanceAfter:string,ruleVersion:string,completedAt:Instant}`。resultは不変の取引時点で、現在stateとは別。slot response=`{slotKey:SHELF_1|SHELF_2|SHELF_3,inventoryId:UUID|null,version:string}`。開始時に三slot行をinventoryId=NULL/version=0で作成し、PUT/DELETEごとversion+1。DELETEは行を削除せずNULLへ戻しversionを維持、空slotへの再PUTも同counterで競合検査。Record=`{id:UUID,kind:REWARD|CARE|PURCHASE,sourceType:enum|null,deltaPoints:string,deltaXp:string,occurredAt:Instant,sourceLink:SourceLink|null}`。sourceLinkはF00再判定後にのみ `{kind:enum,id:string,url:string}`、本文/名称は返さない。

Inventory=`{id:UUID,collectibleKey:string,labelKey:string,assetKey:string,acquisitionKind:SHOP|LEGACY_BADGE,awardedAt:Instant,isRevoked:boolean,placedSlotKey:string|null}`。旧team名/旧badge自由説明を非メンバーに返さない。既存system badgeを運営承認label/assetへadapterする。Phase 1はuser uploadした置物asset/URLを受けない。

CareBudget=`{weekStartsOn:DATE,weekEndsAt:Instant,weeklyCapXp:string,awardedXp:string,remainingXp:string,amountXp:string,ruleVersion:string}`。activity pointsのWeekBudgetとは別。ShopItem=`{skuKey:string,collectibleKey:string,labelKey:string,assetKey:string,pricePoints:string,priceVersion:string,isOwned:boolean}`、価格/ruleはserver確定。PurchaseResult=`{commandId:UUID,inventoryId:UUID,skuKey:string,costPoints:string,priceVersion:string,balanceAfter:string,completedAt:Instant}`、不変結果。
## 3. 想起session API（新規reflection内契約）

既存 `/api/v1/me/reflections` 配下に追加し、既存recordRecall API自体は挙動を変えない。entryIdは現行ReflectionEntryControllerと同じUUID stringで厳格検証（Long併用なし）。新sessionIdはUUID。

| メソッド | パス | 契約 |
|---|---|---|
| POST | `/api/v1/me/reflections/entries/{entryId}/recall-sessions` | `{}` + Idempotency-Key。201session snapshot。entry本人所有必須、ranch owner不要、0prompt400。sourceRefからuser偽装不可 |
| GET | `/api/v1/me/reflections/recall-sessions/{sessionId}` | 200本人session。resume用。非所有/不在404 |
| PUT | `/api/v1/me/reflections/recall-sessions/{sessionId}/answers` | `{version:string,answers:Answer[]}` + Idempotency-Key。200途中保存。報酬なし |
| POST | `/api/v1/me/reflections/recall-sessions/{sessionId}/complete` | `{version:string,answers:Answer[],selfRating:REMEMBERED\|PARTIAL\|FORGOT}` + Idempotency-Key。200completed session/原文開示。全required回答後だけfact |
| POST | `/api/v1/me/reflections/recall-sessions/{sessionId}/cancel` | `{version:string}` + Idempotency-Key。200cancelled、報酬なし |

Answer=`{promptId:UUID,state:ANSWERED|FORGOT,text:string|null}`。ANSWEREDはtrim後非空で本体上限以下、FORGOTはtext=null、全prompt IDはsnapshot内で一意、重複/追加/欠落400。途中保存では欠落可、completeでは全件必須。Session=`{id:UUID,entryId:string,status:STARTED|COMPLETED|CANCELLED,version:string,prompts:Prompt[],answers:Answer[],selfRating:enum|null,startedAt:Instant,completedAt:Instant|null,rewardWeek:DATE|null,expiresAt:Instant|null,original:ReflectionEntryResponse|null}`。expiresAtはPhase 1では常にnull（期限なし、削除/所有喪失時完了不可）。rewardWeekはSTARTED/CANCELLEDでnull、COMPLETED初遷移のserverTime週を固定し再送で変えない。originalはCOMPLETED後のowner権限でのみ返す、取消時null。selfRatingの高低で報酬は変えない。ranch未参加でもこの学習フローは使える。

## 4. 運営APIとルール

SYSTEM_ADMIN限定。新permissionを使う場合は権限catalog/Flyway/正規ガードを登録し、未登録文字列で許可しない。一般userの `/me` へSYSTEM_ADMINでも他人IDを渡せない。

| メソッド | パス | Request / Response |
|---|---|---|
| GET | `/api/v1/system-admin/ranch/policies` | policy summary一覧、cursor方式 |
| POST | `/api/v1/system-admin/ranch/policies` | 完全policy+effectiveAt。201不変version。次のUTC週境界以降のみ |
| PUT | `/api/v1/system-admin/ranch/operational-controls` | `{version:string,isCareEnabled:boolean,isShopEnabled:boolean,isDeliveryPaused:boolean,isRewardsPaused:boolean,reasonCode:string}`。200状態/有効時刻 |
| GET | `/api/v1/system-admin/ranch/outbox-health` | sourceごとのpending/deadCount/oldestAge。本文なし |
| POST | `/api/v1/system-admin/ranch/outboxes/{sourceType}/{eventId}/retry` | `{reasonCode:string}` + Idempotency-Key。200同event再送予約。scopeType詐称/不在404 |

PolicyRequest=`{effectiveAt:Instant,enabled:boolean,globalWeeklyCap:string,sources:SourceRule[4],delivery:{batchSize:int,leaseSeconds:int,maxAttempts:int,initialBackoffSeconds:int,maxBackoffSeconds:int},reasonCode:string}`。SourceRule=`{sourceType:四enum,enabled:boolean,amountPoints:string,countLimit:int}`。全源を一回ずつ必須、欠落/重複拒否。無効源も量/容量の型を検証。globalCap>0かつenabledならpersonalON。全利用者quotaに対する満額capacity invariantは設けない。care ruleとshop価格はpoint policyから独立。delivery各値は正でinitial<=max、batch<=運営安全上限（実装時負荷測定で値登録）、retry/lease値未登録でenabled拒否。Response=`{id:UUID,version:string,contentHash:string,effectiveAt:Instant,settings:PolicyRequest,publishedAt:Instant,publishedBy:string}`、公開済み変更DELETEなし。

初期policyはenabled=false。数値は運営登録/裁可で決める。金銭購入によるポイント/速度/上限増加、team管理者の任意個人加算/減算APIを設けない。停止/retry/新policyを監査し、event ID、rule ID、reasonCode、時刻、操作主体を残す。本文や学習回答は監査payloadへ複製しない。


運営care rule API: GET `/api/v1/system-admin/ranch/care-rules`、POST同パス。Request=`{effectiveAt:Instant,amountXp:string,weeklyCapXp:string,juvenileXp:string,adultXp:string,reasonCode:string}`、次UTC週以降、不変version/hash、量正・juvenile<adultを検証。care ruleがpoints policyなしでも成体到達を保証する。同日週careを使える回数/通常rate limit設定を公開gateで確認する。species/少数SKUは初期運営承認catalog seedで十分で、巨大catalog管理UIを作らない。SKU価格更新は新priceVersionのcatalog公開、過去purchaseの価格snapshotを変更しない。

adminの冪等scopeは管理shardまたは各source facadeごと（分散共通scopeではない）。clientは操作別新key、同retryだけ同key。別facadeへ同keyを使うことはこの409保証外。reflection commandもreflection内scope。管理mutationはranch_admin_commands（owner不要）でuser+key/hashを保存し、policy/control/care ruleの管理shard TXと一括。source retryのみsource facade内のsource admin commandとoutbox更新を同TX、SYSTEM_ADMIN監査。同じretry key別event/type/bodyも409。未参加adminへ個人ownerを作らない。
## 5. 認可・F00・セキュリティ

`common/security/SelfScopedEndpoint` は実在しmethod対象/value必須。root/本人collectionのIDを受けないendpointだけへ理由を付ける（feedingも一頭をprincipalから決定）。commandId/slotKey/inventoryId等を検索に受けるendpointには一括でself markerを付けず、RanchAccessGuardがresource ID+principal user IDを同時条件にlookupして本人所有を確定し、正規resource-owner例外/EP契約ITを登録する。ARはReflectionAccessGuardとsession owner guardを適用。架空のContentVisibilityResolverを作って本人の残高を公開コンテンツ扱いしない。sourceの資料リンク/記事/投稿の閲覧は既存F00 ContentVisibilityResolver/ContentVisibilityCheckerを必ず経由し、独自role比較をしない。退会・source削除・所属離脱・可視性変更後はリンクをnullにし、元報酬台帳を戻さない。源事実はsourceドメインの本来認可成功後のみ生成する。

他人のdinosaur/inventory/command/session UUIDは404。同じレスポンス形で存在を秘匿する。認証なし401。残高不足409。feature OFFでも予定/出欠/投稿/ブログ/学習へのアクセス制限を追加しない。

| 状態 | create/free care | shop purchase | settings/置物 | pause/resume | read |
|---|---|---|---|---|---|
| care control ON/有効rule/本人ACTIVE | 可、ポイント不要 | shop ON/残高/価格検証 | 可 | 冪等可 | 可 |
| 本人PAUSED | 既存create200、care409 | 可（休止は獲得/成長停止） | 可 | 再開可 | 可 |
| reward/delivery pause、points policyなし/disabled | care可、元care rule使用 | 既得残高で可 | 可 | 可 | 可、point budgetなしならnull |
| care control OFF/有効care ruleなし | create/care503 | shop ONなら既得残高で可 | 既存ownerは可 | 可 | 可、careBudgetなしならnull |
| shop control OFF | care状態による | 503 | 可 | 可 | 可、shopAvailable=false |

独立global mutation stopは初期に設けない。featureStatusはcare control/ruleの利用可否、rewardsStatusはpoints policy enabled/運営報酬pause、deliveryPausedは配送だけ。三つを混ぜない。care control ONの初期公開gateは有効care rule、占い風の承認済み決定的rule、LAND/SEA/AIR random各pool、server検証の診断question/scoring version、全64 type×species mappingと承認assetが全て揃うこと。小random poolだけでは公開を許可しない。shop ONは承認SKU/不変価格存在を検証。初期care/shop=false、points enabled=false、運営明示登録後に独立有効化する。
既存useApi/認証refresh/Cookie方針を再利用し新トークン保管を作らない。認証Cookieは既存SameSite=Strict/HttpOnly、production Secure、cross-site mutation拒否を回帰試験する。CSPを広げない。assetsは運営固定の同origin/既存配信許可先だけ、user URL/HTML/SVGアップロードを初期に受けない。恐竜名は孵化時に必須で確定後変更不可。名前はtext bindingでescapeして表示し、HTMLとして描画しない。ログに本文/回答/secretなし。APIはowner単位rate limit、retryの429にRetry-After。rate limitは複数device合算でcapとは独立、二重付与防止はDB。

新ranchはorganization_idを持たずuser IDでシャード。user-owned repositoryは全クエリでuser ID絞込み。organization-scoped後続PhaseはAbstractTenantAwareRepositoryへ分離し、Phase 1のuser rowへorg権限を混ぜない。user退会時のtombstone/DomainCleanupService/源outboxも含む削除順序は02 §5の契約。金銭交換無しなので会計保存義務として誤分類しない。owner有効中は貯蓄/dedupを失効させず、アカウント削除後の同一活動再発行/孤児再作成を禁止する。

## 6. エラー予約案

新prefix `RANCH` を登録し、基準コミットに同prefixが無いことを採番時確認して最大+1（初回001）から予約する。**確定はmerge時に再確認**。旧GAMIFICATION_001〜010は変更/再利用しない。reflectionの新codeは既存reflection最大+1を実装時予約し、下記意味へ対応する。Severity.WARNのclient errorを500へ分類しない。

| 予約 | 意味 | HTTP | Severity |
|---|---|---|---|
| RANCH_001 | 本人所有resource不在/越境 | 404 | WARN |
| RANCH_002 | 残高不足 | 409 | WARN |
| RANCH_003 | 同Idempotency-Key別body | 409 | WARN |
| RANCH_004 | 有効policy/機能の利用停止 | 503（明示mapping） | WARN |
| RANCH_005 | 未所有/取消済み置物（不在と同じ形） | 404 | WARN |
| RANCH_006 | 不正slot/値/care rule/価格 | 400 | WARN |
| RANCH_007 | 設定/個体/価格version競合・既所有SKU | 409 | WARN |
| RANCH_008 | 想定外のowner/個体/台帳不整合 | 500 | ERROR |
| RANCH_009 | ranch本人永続保存不能 | 500 | ERROR |
| RANCH_010 | 所有user mutationのrate超過 | 429 | WARN |

COMMON_001は型/必須/不正JSON（WARN/400）、COMMON_003は共通楽観競合（WARN/409）に再利用可。基準コミットcommon/ErrorResponseの返却は `{error:{code:string,message:string,fieldErrors:[{field:string,message:string}]}}`、fieldErrorsは常に配列（対象なし[]、null/省略なし）。成功common/ApiResponse={data:T}、common/CursorPagedResponse={data:T[],meta:{nextCursor:string|null,hasNext:boolean,limit:int}}。useApiはwrapperをunwrapしないためFEはresponse.data、useErrorHandlerはFetchError.data.errorを扱う。内部SQL/stack/source本文/ID入力値をechoしない。上限到達はAPI errorでなく正常0decision、運営履歴ではCAPPEDとして観測する。

PromptDTO=`{id:UUID,kind:FREE_TEXT|QA,direction:QUESTION_TO_ANSWER|ANSWER_TO_QUESTION|FREE,promptText:string,required:boolean}`。全field必須/null不可、idはsession開始時サーバー発行、required=true。prompt数1〜100、promptText1〜10000 Unicode文字、回答textはtrim後1〜10000文字。超過entryのsession開始400で無報酬、切り捨てない。FREE_TEXTはdirection=FREEの一prompt。Session.originalは開始時ReflectionEntryResponseのsnapshot（OpenAPI components.schemas.ReflectionEntryResponseへの$ref）。reflection側original_snapshot JSONへ保存し、complete後だけ開示。開始後編集されたcurrent entry内容へ差し替えず、source outboxへ複製しない。原文開示にも取得時本人所有を再照合する。

既存ソース型根拠: `backend/src/main/java/com/mannschaft/app/reflection/controller/ReflectionEntryController.java` のGET `/api/v1/me/reflections/entries/{entryId}` とPOST `/entries/{entryId}/recall` は `ReflectionEntryResponse`（`reflection/dto/ReflectionEntryResponse.java`）。資料REFLECTION_ENTRYは既存UUID ReferenceTypeとして `common/visibility/ContentVisibilityChecker.java:336` のcanViewUuid、`:364` filterAccessibleUuid、`:413` decideUuidへ渡す。canViewUuid=falseならsourceLink=null。存在しないassertCanViewUuidは作らない。sourceLink.urlは既存frontendルートhelperから生成し推測URLを返さない。resource Controller→RanchService→RanchAccessGuardの具体的depth2呼出を `AuthzControllerGuardArchTest.java:220-295` とendpoint契約ITで検証する。self markerはIDなしの自己root/settingsだけへvalue理由を付ける。

既知不足: `frontend/app/types/api.ts` の手書き共通型はBE wrapper/errorの実体と差がある。新ranchはBE正本OpenAPI生成型だけを使用し、グローバル手書き型をこの設計で変更した扱いにしない。

### 卵/選定API追補（方式詳細は未裁可）

RanchState.assignment=`{availableMethods:AssignmentMethod[],selectionConfirmed:boolean,confirmedMethod:AssignmentMethod|null}`（未参加null）。DinosaurSummary.egg=`{startedAt:Instant,readyAt:Instant,crackStage:INTACT|SMALL_CRACK|WIDE_CRACK|READY,hatchReady:boolean,hatchedAt:Instant|null}`、EGG以外egg=null。serverTimeによるelapsedだけ、client timestampで進めない。

| メソッド | パス | Request | Response / status |
|---|---|---|---|
| PUT | `/api/v1/me/ranch/assignment` | `{method:enum,habitat:enum|null,birthDate:LocalDate|null,selectionName:string|null,diagnosisToken:string|null,version:string}`、Idempotency-Key | 200確認済みassignment。未承認adapter503、確認後別入力409、同key同body元結果 |
| POST | `/api/v1/me/ranch/hatch` | `{version:string,name:string,nameConfirmed:true}`、Idempotency-Key | ready AND selection confirmed AND命名確認のみ200孵化・命名を同TX保存。未成熟/未選定409、名前境界/確認不備400。同key再送同結果、別名再送/改名409、XP0 |

methodごとに不要inputはnull必須。HABITAT_RANDOMはLAND/SEA/AIRのみ、birthDate/selectionName/tokenはnull。BIRTH_STYLEは厳格YYYY-MM-DD LocalDate/選定用名trim1〜80文字、実算法/利用可能化は別裁可。DIAGNOSISはserver検証済みtokenでprovider/type/mappingを確定する初期必須契約（内容未裁可）、client typeCodeを科学的結果と認定しない。raw出生入力は計算後捨てresultはspecies/variant/method/version/confirmedAtだけ。名前は本名不要、入力説明は占い風の楽しみであり科学的判定と主張しない。未実装方式を「準備中」と表示して入力収集しない。訂正で自動相棒変更なし。

出生入力の境界: birthDateは日付だけを厳格解析し、存在しない日/月、非うるう年の2月29日、日時/TZ付き、null/欠落をBIRTH_STYLEでは400にする。許容年齢/未来日など商品上の範囲は後続モジュール設計で裁可する。選定用名は本名不要。Unicode/空白等の正規化規則とnormalizationVersionを後続で確定し、承認済みruleVersion/normalizationVersion/対応表の同一組と同一正規化入力は同じspecies＋variantを返す。raw inputを永続化せず、冪等比較用HMACには正規化されたcommand/path/versionと出生入力を含め、keyVersionとnormalizationVersionをcommandへ保存する。key rotation後も保存済み版のkey/規則で旧commandのretryを比較できるようowner lifetime中保持し、未知key/規則で再抽選や新規確定しない。plain hashやraw入力をAPI/result/records/outbox/log/auditへ出さない。算法・正規化の具体内容とkey管理手順は未確定で、承認前に入力収集/公開しない。

診断のvalidation境界: server発行本人sessionのquestionnaireVersion/scoringVersionを固定し、完了時の全required回答、question IDの一意性と所属、回答値の型/null/上下限をserverで検証する。空回答、欠落、重複、未知question、範囲外で完了を作らず400。本人COMPLETED resultだけを選定に利用し、他人/不在resultは同形404、client typeCode/scoringVersionの偽装で採点結果を変えない。承認versionの全64 typeCodeに対応species/assetがあることを公開gateで検証し、未対応を別type/別個体へ置換しない。具体API/DTO/値域は後続モジュール設計で補完する。
## 初期公開の選定3方式（最新確定範囲・内容は未裁可）

性格診断もPhase 1初期公開から必須。BIRTH_STYLE（出生情報＋選定用名の占い風決定的割当）、HABITAT_RANDOM（海/空/陸random）、DIAGNOSIS（64タイプ）の三入口を卵期間に選択する。初期は16種×各4つの色・体型・模様のバリエーション＝64タイプ、将来64種へ拡張する方針はユーザー確定。恐竜との過ごし方を想像する質問は可、牧場の設備や遊び方を知っている前提の質問は改稿する。質問/採点/64 type→species＋variant対応表、占い方式/入力正規化、初期16種の名簿・4デザインの内容は詳細未確定で、24問案を承認済みとしない。公開gateは三方式の確定済みserver rule/入力validation/全64 mappingと必要素材が揃うこと。ランダムpoolの旧別pool条件との整合はユーザー確認中。診断未実装を利用可能と装わず、暫定公開で診断を後回しにしない。

提案構造: 本人診断sessionをserver発行しquestionnaireVersion/scoringVersionをsnapshot、回答は本人sessionへ送信、serverがvalidationと採点をしてCOMPLETED結果（provider/typeCode/mappingVersion）を不変保存する。選定確認時に本人COMPLETED結果と対応表versionを検証してspeciesを固定。clientのtypeCodeを結果として信用しない。診断結果が変わっても確認済みの同恐竜を維持する。質問/回答/診断resultはprivate、報酬outbox/共有プロフィールへ出さず、診断完了回数をpoints/XPにしない。質問/画像/算法の外部サイト利用許諾/APIは未確認で、無断複製を前提にしない。

診断session API/DTO/質問master/採点rule/結果tableの完全な契約と素材仕様は、未裁可内容を決めてから本草案へ補完する。現在の草案は選定adapterと保存/認可/同恐竜維持の境界までを示すレビュー資料で、診断本体をこのまま実装可能と主張しない。Phase 1の4〜8週は診断/64素材追加前の旧概算であり再見積が必要。全体3〜6か月も既存基盤/準備済みアートの旧前提の候補で、診断と素材次第で超える。

### 孵化・命名の追加契約（2026-10-03）

nameは02の正規化・1〜10書記素・保存上限をserverで検証する。nameConfirmedは明示確認の要求で、client trueだけで長さや所有検証を省略しない。欠落/null/空名/11文字/nameConfirmed欠落・falseは400、状態はEGGのまま。command hashには正規化名と確認値を含める。同key同bodyは既存不変result、同key別名は409。別keyの既孵化要求は既存名と同名の場合のみ200同個体、異なる名は409で元名不変。二tabの異なる名前はlock下で先に成功した一件だけを確定する。競合した画面は再取得して確定名を表示する。

DinosaurSummaryはEGGでname/namedAt=null、BABY以降で必須。孵化responseはdinosaur ID/stage/name/namedAt/hatchedAt/versionを含む不変HatchResultとし、同key再送で成長後のstateへ置換しない。現在stateはGETで別取得する。孵化後のnew key同名再要求は最新stateを返し、この成功commandも保存する。Settingsや選定APIにnameを渡すと400、rename endpointなし。表示OFF/style変更/成長/休止再開でも名前は変わらない。GETには書込を追加せず、出生割当用名を恐竜名として自動保存しない。

## 2026-10-03の追加裁可

初期16種×各4バリエーション＝64タイプ、将来64種へ拡張。生年月日＋名前は固定の割当方式にし、既存占いと対応できる方式を優先して検討（具体方式/対応表は未採用）。退会取消で同じ相棒を戻し、最終アカウント削除で消去。孵化後の3ボタンと非減衰親密度の仕草・反応表現を採用。相棒との過ごし方の質問は可、牧場機能の知識を前提にした質問は改稿する。素材・動作の大量生成を一度に要求せず、制作時間/品質を1種pilotで確認する計画案を用意する。
