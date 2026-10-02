# CMP-260820-1018 代理入力同意管理

- ブランチ: `feature/cmp-260820-1018-consent-management-20261002`
- 基点: `b3efd80c58`
- 担当: 足軽B（Sol / medium、Terra代替）。軍議・成果検分・出荷判断は殿。
- 状態: source e6da1749abのBE225件/54XML、標準H2 OpenAPI/生成FE型、API単体5件・ナビ16件・機能ゲート17件・共通UI補正後全体型チェックはgreen。実機UI7のADMIN導線/一覧/空履歴1件とUI8のDEPUTY承認権限なし・MEMBER/SYSTEM拒否・取消/承認/本人255文字オンライン撤回4件は別JUnit計5件green、終了処理も全完了。UI9/10の紙承認/権限ありDEPUTY、UI11の専用空組合/候補取得、UI12の故障注入も各1件green。390pxはja/enのみgreen、deの見出し横はみ出しでred、es/ko/zh未実施。紙撤回/実操作履歴/6言語390px・3住民・最新main追従・最終検分は未達。

## 方針

前任引継（引継-cmp-260820-1018-proxy-input-admin-20261002-0825.md）のユーザー裁可を正本として、`/admin/proxy/consents` と `/admin/proxy/records` を追加する。組合選択/currentScopeは既存金型を使い、組合管理ハブ配下の新pathは作らない。組合管理資格は既存ADMIN/DEPUTY_ADMIN、承認はPROXY_CONSENT_APPROVEで判定し自己承認を禁止する。SYSTEM_ADMINはBE APIで組合横断を許可し、通常組合業務UIの導線は表示しない。一般isAdminOrAbove helperの意味は変えずproxy入口で明示例外を適用する。新テーブル・DDL・権限追加は行わない。

実操作履歴は既存recordsと同意書をDB JOINし、organizationIdと任意subjectUserIdをAND条件にして標準PagedResponseへ返す。組合指定なしは本人（既存SYSTEM_ADMIN例外を維持）。同意書IDなしの後見切替は本人履歴で表示し組合履歴から除く。

同意一覧も標準PagedResponseでDBページングする。scopesのcollection fetchをPageable queryへ直接付けてメモリ内ページングへ倒さず、IDページ取得後のbounded graph fetchを使う。size101は旧試練どおり200で100へ制限し、下限未満とpage負値は400とする。

## 受け入れ条件と証跡

殿から受領したAC1〜11を対象とする。管理入口・全状態一覧、承認状態制約、紙撤回保存、履歴の組合/本人交差、空/取得失敗/再試行、null表示、理由255/256・ページ境界、未認証/越境、途中失敗、DBページング/N+1、6言語/390px/権限別実機と探索3視点を検証する。

- red: Windows陣からの初回実行はcompileJavaのソースfingerprintで45分超停滞したため自所有処理のみ中断。ext4 snapshotへcommit912ace7eのbackend blobs一致を確認して再実行し、5 XMLで45件/33 failures/0 skipped/0 errors（10分16秒）。旧SYS拒否期待は最終正解に計上しない。履歴仮応答、状態上書き、未知method500、親削除後操作、OSIV=false下の一覧/承認500を実測した。紙保存nullの2件は外側テストTXのflush前clearが原因と追加精査で判明し、本体不具合へ計上せず試験を訂正する。
- 前任trial commit92e7ef7787ff0c1b921f4e38fe0e97ecabc1b091の17件と引継を全文照合し、SYS横断・同意一覧page/size/emptyを現在の専用fixtureへ移植。旧非本人API撤回正常系は主体資格を維持しつつF14.1§350/351に従いPAPER＋同組合ADMIN証人のpayloadへ更新する。DEPUTY承認権限のfixtureはF01.2のgroup由来契約へ訂正（role既定権限は無効だった）。
- green・関連回帰・生成型・lint/typecheck: 未実施。
- 実機・E2E・アリシゼーション: 未実施。

## 進行上の制約

本陣・既存8080/3000・ユーザー資産は変更しない。heavy Gradleは交通整理スクリプト経由。FE編集は環境担当の既存アンケート実機終了後に開始する。規約の正本は既存文書を参照し本台帳へ複製しない。

## 2026-10-02 保全時点

- CMP-260912-1526は既存PR #3282/#3291と再生成手順書の完走証跡で解消済みと殿が判断し、OpenAPIを理由とする本体/API変更の保留を解除。完了前に標準generateOpenApiDocsを再実行する。
- 専用陣のBE/FE本体は未変更。origin/mainのa21dfa38c84への追従は未実施。
- redのunified exec session3650をCtrl-Cで中断（exit1）。WSL wrapper PID1145134・daemon PID1145221の不存在と共通gate /home/kenta/gradle-turnstile/turnstile.lock.d の不存在を確認。既存アプリやDBへは触れていない。
- 自分daemonのThread.print（14:43:35）ではExecution workerがFileInputStream.open0→DefaultFileHasher→DirectorySnapshotter→DefaultSourceDirectorySet→DefaultInputFingerprinterで待機。コンパイラやHTTP依存取得のスタックはない。Windows worktreeをWSLから読む際のI/Oが原因候補であり、テスト失敗と判断しない。
- ローカル専用run-cmp-consent-red.shとcmp-consent-thread-diagnostic.txtはignore配下、commit対象外。実行scriptはGit環境変数をWSLパスへ設定し、既存turnstileのCRLF除去済み一時コピーを使う。共有ゲートの解放を確認するまで別heavy実行・サーバー起動を行わない。
- 親はremote compactのmodel capacityエラーから復帰し通常作業を再開。紙撤回の立会資格をADMIN限定とするかDEPUTYも含めるかはユーザー判断待ち。非null・有効user・同じ組合資格の検証はどちらでも必要なため、ADMIN正常系と非管理/別組合/不在witness拒否を先行テスト。DEPUTY witnessを推測許可しない。

## エンドポイント別の許可主体

| 操作 | 許可主体と条件 |
| --- | --- |
| 組合同意一覧・組合指定履歴 | ACTIVEな当該scopeのADMIN/DEPUTY_ADMIN、またはSYSTEM_ADMIN（組合未所属でも横断可）。管理UIは最強roleName ADMIN/DEPUTY_ADMINのみ、SYSは導線なし。 |
| 組合指定なし履歴 | 本人。既存SYSTEM_ADMINの他subject指定例外は維持。 |
| 承認 | PROXY_CONSENT_APPROVEまたはSYSTEM_ADMIN、かつ代理者本人以外、pendingのみ。一般scope helperの変更はしない。 |
| API_BY_SUBJECT撤回 | 同意対象本人。組合の現在の在籍は問わず、退会後も維持。 |
| PAPER_BY_SUBJECT撤回 | 既存scopeADMIN/DEPUTY_ADMIN操作資格またはSYSTEM_ADMIN。立会人はACTIVEな当該組合ADMINのみ。SYS単独・DEPUTY・無効ユーザーは立会不可、SYSと当該scopeADMIN併有は可。actorと同一であることは強制しない。 |
| AUTO方法の手動撤回 | 拒否。 |

approve/revokeは同意実体から組合を解決し、親不在・論理削除をGateより先に確認する。親不在/削除、同意不在、越境は既存COMMON_002（403）に揃える。同scope権限不足も既存403を保持する。組合の公開QueryService.findSummariesByIdsを非TX facadeから呼び、他ドメインRepository参照や既存cross-domain TX凍結の拡大を避ける。

追加redにはSYS単独/併有・DEPUTY承認権限の有無・組合退会後本人・無効立会人・親削除・mutation未認証・空/size/page境界・同日時id降順・entity取得件数とSQL数の定数性を含む。UI固有の空/再試行/成功後再取得失敗・null・6言語/390pxは後段で検証する。

application.yml:54のopen-in-view=falseを実確認。同意のscopesはLAZYで既存ControllerはService TX後にDTOへ変換するため、非@TransactionalのSerializationContractITを追加した。fixtureの確定と自分のIDだけの後始末はTransactionTemplateで行い、一覧10件・active・承認のHTTP応答を実TX外でserializeする。scopesのcollection fetch countも検証して一覧のN+1を固定する。同じSpring context金型を使い独自profile/property/contextは追加しない。

訂正後の候補は60件（旧45＋同意一覧12＋非TX DBページ件数1＋並行mutation2）。並行試験は実MySQLの先行TXが同意1行をPESSIMISTIC_WRITEで保持して撤回情報をflush/refreshし、後続HTTPのfindByIdForUpdateまたは旧flush経路とMySQL read待機stackを観測してから先行TXをcommitする。任意sleepを使わず10秒の観測期限/30秒のlock保持期限と自所有executor解放を設け、HTTP409・先行撤回日時/理由/証人の不変性・承認日時nullを検証する。まだ実測前でありgreen扱いにしない。

## 訂正redの実測と独立実装着手

- commit8bc47d134dをsnapshotへ4 test blobs照合して実行。60 tests /47 failures /0 errors /0 skipped、8分12秒。DEPUTYのgroup由来承認はGREEN。並行2件はDB待機観測を通過した後、承認500・再撤回200で409期待にREDとなり、時間切れ/cleanup失敗はなかった。XMLは専用`/tmp/cmp-consent-red-8bc-xml`へ保全。
- commit1abcacf001で紙保存2件のflush/clearを補正。対象XMLは2 tests /1 failure /0 errors /0 skipped。紙255文字理由・方法・証人のDB保存assertを通過し、同意一覧応答にrevokeWitnessedByUserIdが無い点だけRED。別人同scope ADMIN証人の保存はGREEN。標準Gradleによるarchitecture guard追加分も合わせると31 tests /1 failure（11分10秒）。追加分はDomain TX/API/Visibility等の29件で、exclude/profileの改変はしていない。
- 確定済みの同意ID page→bounded scopes graph、records固定3 JOIN page、非TX親生存/存在秘匿facade、mutation同意row lock、state409、SYS明示横断、本人API方法限定、手動AUTO/unknown400、立会ID応答を実装中。立会資格のADMIN限定/DEPUTY包含だけ未裁可であり、その検証・全green/完了は保留。
- 次は独立BE差分の保全commit→安全な時点でmain追従→blob一致snapshotの60契約と既存proxy/ArchUnit回帰→標準generateOpenApiDocs（8099）→OpenAPI artifact hash照合→npm generate:types→FE型/lint/unit→正本2管理pathを入口から実機検証。元の組合ハブcardは既存setOrganizationScopeを明示して正本pathへ遷移し、新orgScopedページは増やさない。SYS兼任も業務UIへ導線を出さない。

## 独立BE実装後の環境終了と保全

- commit97a673c9のbackend12 blobsを専用ext4 snapshotと照合し、既存proxy全体とServiceApiEntityBoundary/ControllerEntityResponse/CrossDomainTransactionalTransitiveの標準Gradle試験をsession59633で開始。compileJava/compileTestJavaは通過。未実装の立会資格負例2件（非管理・別組合、不活性ユーザー）が200となった失敗出力を回収した。
- 同sessionはBUILD終端メッセージなしでexit15。自己daemon1272629/worker1273343は一時生存していたが、その後読み取り確認でともに不存在、共通gateも不存在となった。停止操作は行っていない。最終の契約XMLは0filesであり、成功にも60件完走にも計上しない。起動前XMLは`/tmp/cmp-consent-red-1abc-xml`へ保全済み。再実行が必要。
- commitf8214d17ebで本人オンライン撤回に紙の立会IDを偽装する追加実DB試験を保全。API_BY_SUBJECT+witness指定を400とし、未撤回状態の不変と、正常本人撤回後の初回日時・方法・理由・立会nullの不変を検証する。紙立会roleの未裁可とは独立。まだsnapshot未適用・red実測前。
- 殿の交通整理に従い、追加heavy・snapshot変更・FE編集は一時待機し、先行CMP042の住民1による独立UI観察を担当する。同意管理の目的は維持し、その観察後に試験と実装を再開する。

## 2026-10-03 追加契約の実REDと永続証跡

- F14.1§112/350–351と前任引継を殿が再照合し、紙代行の操作資格と立会資格は別と確認。立会資格の質問待ちは撤回し、既存AccessControlService.isAdminによるACTIVEな当該scopeADMIN限定で確定。DEPUTY actorは別ADMINの立会で紙撤回でき、actorとwitnessの等値条件は追加しない。
- 追加4 HTTP契約はDEPUTY・論理削除ADMIN・SYSTEM_ADMIN単独の立会を拒否し、SYSと当該scopeADMIN併有は保存を許す。削除fixtureは匿名化後に論理削除する。本人オンライン偽装契約の正常撤回後の2回目は既存の409を維持するよう訂正した。新guardは既存actor/method認可・入力・既撤回409の後、永続化直前へ置く。
- canonical HEAD e3e65f00593faa3982fe4f0ab8d2f95d1fbc8aeeのbackendとOpenAPI関連docs、未commitのtest2差分を専用永続mirrorへ固定。全11,976 canonical filesと追加test2のrawLF blobを一致照合した。Windows archiveのCRLFは、canonical blobの一致を確認できたファイルだけmirror上でLFへ正規化した。Windows作業ツリーは変更していない。
- mirror: `/home/kenta/.cache/mannschaft-cmp260820-1018-b-20261003-red65`。archive SHA256 `a8c8a8be5348407d852d256799f6a01ab4278cce53f2047decbbe4556714c0cf`、test2 patch SHA256 `2817e194f41306e373d46dc6bdf12af9fa0d9ae5e69fa648475fccf6539fd00a`。
- 初回はturnstile取得後にgradlew実行bit不足でexit126、compile/test未開始・XML0。証跡を上書きせず、canonical bytesを変更しない`bash ./gradlew`でattempt2を実行した。
- attempt2 session69424は共有turnstile経由で6管理ContractITを完走。compileJava FROM-CACHE・compileTestJava成功、終端exit1/BUILD FAILED（9分56秒）。**65 tests /6 failures /0 errors /0 skipped**。Authorization 13/4失敗、Mutation 15/2失敗、ConsentPaging 12/0、RecordPaging 2/0、Scope 18/0、Serialization 5/0。失敗はDEPUTY/deleted/FROZEN/SYS単独witnessの4件、紙の非管理/別組合/不存在witnessのloop1件、API witness偽装1件。loop内の3variantを別testへ数えない。SYS＋scopeADMIN立会の陽性と並行状態不変、OSIV=false serialize、JOIN/page契約はgreen。
- raw XML6 filesとstdioを`evidence-red65-attempt2`およびWindows自所有ignored `.claude/campaigns/cmp-consent-red65-20261003-artifacts/attempt2`へ二重保全。各JUnit header/SHA256はjunit-before.json・junit-after.jsonへ保存し、copy元/両copyのhash一致を確認。stdio SHA256 `49a8a275cec89b1c142dc9c1d8e2fb7392293f06cf137ee941cb317fa946545e`。標準architecture・既存proxy回帰はguard後に別測定する。
- 前回/tmp消失で旧60red・紙2再測定のraw XMLは失われた。上の歴史的件数は当時のtool実測記録であり、raw復元成功とは扱わない。前任17件のWindows raw XMLは読み取り保全のまま変更していない。

## 2026-10-03 最小guardとBE回帰GREEN

- 実RED checkpoint ff7b20f6ff455d972829250a891de7347bbe8fd4を先にcommitし、最小guard ef9448ac10ac1c9a7657bb34395b473c83c580abを別commit。Serviceの11行だけを追加し、actor/method認可・既存入力検証・既撤回409の後/永続化前にAPI witness非nullを400、紙witnessのisAdmin不成立を400とする。グローバル認可helperや他の状態遷移は変更しない。
- guard patch SHA256 `95b1746191de517e5efb09bceada8ca8d7a3a8d0eb6b7213fd98cb57cef6f018`、Service blob `698f06f7ff961a52107cc140639fd88d8aa0c0f4`。mirror適用前後の全11,976 canonical/test bytesを再照合した。
- session32539は共有turnstile経由で既存proxy全体（管理契約65を含む）、AccessControlServiceTest、ScopeConcealingAccessGateTest、ServiceApiEntityBoundary/ControllerEntityResponse/CrossDomainTransactionalTransitiveの標準Archを実行。compileJava成功、終端exit0/BUILD SUCCESSFUL（17分44秒）。**224 tests /0 failures /0 errors /0 skipped**、53 XML。管理6 XMLの内訳は13/12/15/2/18/5の計65件で全green。
- `evidence-green1`とWindows ignored artifactsの`green1`へraw XML53 files、stdio、before/after JUnit headers/SHA256を二重保全。修正前RED6 XMLもgreen1/xml-beforeに保持。修正後stdio SHA256 `56f5cdfa84382efd3659c562ed6bf7fa5b76acaed0c57a480dc73327f1c45ba2`。
- 次は最新mainとの実差分・schema照合、標準OpenAPI再生成と生成FE型、正本2管理画面/管理ハブ導線、6言語と390px/権限別の実機を実施する。新BE8081起動は殿の事前確認に従い共有DBのrepair等は行わない。

## 2026-10-03 最新main・標準生成とFE初期実装

- origin/main 1cb0febb62b5015f44a0387267087e4b858c81b1へ追従し、canonical HEAD d189a835734f26ef7535e8e203ca83c8ac22dc7e。Proxy本体のmain差分なし。GlobalExceptionHandlerは通知コードの整理のみでCOMMON_001/002/003不変。新V235はgenerated unique column/index追加で、環境担当がsharedDBで既に適用済み/failed0/duplicate groups0/列とunique index一致をSELECTだけで確認。repair/dedup/DDLは行わない。
- GREEN時のFreezeStoreは旧違反が2件減少（消滅済みbilling→teamメソッド、既にDTOへ変更済みRecruitmentNoShow旧2引数Entity-return）。added0/refreeze=false。前後raw/deltaを両側保全、殿が直接確認。Windows正本store・allowlistは変更せず、mirrorだけcanonicalへ復元。main patch適用後の12,005 files raw hash一致を確認した。
- 標準generateOpenApiDocsをsharedturnstile経由、openApiPort8099/no-daemon/no-build-cacheで実行。起動前にWindows/WSLの競合とoverride変数をsafe projection、task専用環境だけsanitize。exit0/244秒、openapi-gen/H2memory観測、Flyway migration観測なし。stdio SHA256 d2ef89913538b19de9ac3fc27b75f3496ffa81d47fea7eb1bdc97f4273bdb894。生成JSON SHA256 c10fef7cee2c8573f224be6cb4015fb074768bb9334f7313abee9dde7540ac91（2710 paths/4199 schemas）、Windows backup一致。生成物を正本docsへ転送しnpm run generate:types exit0。生成型の手編集なし。
- 正本/admin/proxy/consentsと/admin/proxy/records、組合管理hubの入口、API実応答展開/標準ページング/nullable監査項目、6locale操作文言を初期実装。SYSは業務UI非表示、scopeの最強role ADMIN/DEPUTYだけを使い、承認permissionを別判定。scope切替前の遅い応答は破棄。member候補は既存getMembersを20件ずつ取得し全ページ走査なし。応答にACTIVE情報がないため当該ADMIN表示候補とBEの最終isAdmin資格判定を用いる（殿確認）。
- API契約unit初回はNuxt beforeAll timeoutで5skip、実テスト未実行。既存useTodoApi.bulk-statusのNode環境金型へ修正後、actual5/0fail/0error/0skip、JUnitはWindows自所有ignored artifactsのfe-api1へ保全（SHA256 34bcd7e05a3131a3d4a6c5f07de55861590bfaa7778c361a78e689c29c7d2e95）。変更9FE files ESLint exit0。全体typecheckは既定4GB heapでOOM exit134、processだけ8GBとして再測定中。未達を成功にしない。
- d189ソースのbootJar exit0/30秒、immutable artifact 217548260 bytes、SHA256 1952cc96ac37701cac3da69ac1337966318b69704ce1c275abac7298f5b3f51a。persistent evidence-jar1とWindows jar1へ二重保全、hash一致。新8081はまだ未起動、旧runtimeのpublic config条件を読み取り確認中。
- 空/error/retry・null・mutation失敗/成功後reload失敗・6言語390px・権限別導線/直URL・実操作から履歴観測・独立探索3視点は、初期実装だけでは合格とせず後段実機へ残す。CMP1017は今回完了扱いしない。

## 2026-10-03 FE検分補正と実機前提

- 初期FE checkpoint bd46604c07。殿の初期検分から、承認前確認Dialog・取消（PATCHなし）を追加し、既存scope/inputSource/自動撤回の翻訳ラベルを再用。取消PATCHなしの実機証拠は未取得。F14.1§443/502の機械翻訳禁止を再照合し、新規ラベルは管理操作/エラー/ページングの運用UIだけで、法務同意本文・PDF・紙ガイド・既存承認翻訳は変更しない。
- 通常代理デスクのSYS非表示を既存useAppNavGroupsへ限定修正。追加3境界の実REDは16 tests/3 failures/0 errors/0 skipped（SYS＋scope ADMIN/DEPUTY/SYSTEMの3variant）。authStore.isSystemAdminで通常deskを除外しSYSTEM導線を維持後、16/0/0/0のGREEN。raw XMLはignored artifacts/fe-api1/nav-red2.xml・nav-green.xml。最初のNode環境試行は依存chatモジュールのdocument未定義でsuite実行前に失敗し、契約REDに数えない。既存Pinia実store金型を保持してhappy-domで実測した。
- process限定8GBの全体typecheckはexit2、組合管理hubのnumericId optionalとname shapeの2診断のみ。numericIdを検証し、basicInfo.nameへ修正した。修正後再測定はWindows空きRAMが安全閾値未満のためpreflight exit3/実行未開始が続き、型チェックGREENは未達。既存proxy-desk E2Eのmockも実organizationId/status/撤回監査項目へ同期したが、回帰実行は後段に残す。
- immutable d189 jarをown8081（PID90673/cwd・jarSHA一致、事前listenerなし、旧local profile/private設定読み取り/CORS同条件）で起動し、Started/health200/schema up-to-dateを観測。ただしprivate application-local.ymlがvalidate-on-migrate=falseだったので、Flyway validation成功とは扱わない。自所有PIDだけTERMし停止を確認、実機には未使用。共有YAMLやDBrepair・他PIDは無変更。次起動は殿の確認に従いprocess限定validate-on-migrate=trueで検証する。
- 住民配役案は既存アリシゼーションの2軸を使う。一般×素直（ADMINの同意承認/紙撤回と履歴確認）、スマホ片手×表示崩れ（390/360、長い翻訳/理由/立会選択）、一般×隙間狙い（他組合/非管理/自己承認/SYS業務UI）。殿が実機GREEN後に別context/1loginずつ起動し、目的のみで探索する。personaId/journeyIdの既存正本との対応は殿確認前に捏造しない。

## FE検証の終端

- 機能ゲートは新しい管理2経路を含め17 tests/0 failures/0 errors/0 skipped。標準prefix末尾の重複slashを避け、実測の静的47/動的47にコメントと試験を同期した。raw XMLはignored artifacts/fe-api1/gates-green.xml。
- 型チェック再測定session56399はprocess限定8192MiBでexit0、診断0件、OOMなし。stdio SHA256は2e27bf779c471c2b52c95971b8bf2684f4c6aeec70ff9cfaa411510b32a890ac。typecheck-1790965167653に終端証拠を保全した。Nuxt試験が更新した自所有.nuxtrcメタデータは正本へ復元し、依存変更に含めない。
- useProxyManagementScopeの認証主体切替も再取得対象へ追加。今回差分3ファイルのESLintとgit diff --checkはexit0。実機・取消PATCH0・途中失敗・既存Desk実行と3住民は未達のまま。

## 実機前提のNOT NULL FK契約

- own8081 runtime2はprocess限定validate-on-migrate=trueでFlyway検証成功、Started/health200を観測した。固定jar/cwd/PID4692を確認して起動し、実機診断context終了後、FK契約へ移行するため自所有PIDだけTERMした。停止補助のreasonがruntime1から継承されたため、原記録を残してstop-reason-correction.jsonで今回の理由を訂正した。
- 標準APIで専用組合498（cmp1018-murbbq6u）を作成し、ADMIN23/DEPUTY90156/MEMBER90245/SYS24＋当該ADMINの前提を準備。グローバルSYS資格・共有DB構造・既存組合を変更していない。同意登録POSTは500になったが、readonly一覧200/0件でrollbackを確認し、同じ登録を再送していない。
- V18.011のproxy_input_consent_scopes.proxy_input_consent_idはNOT NULLだが、既存@OneToManyの@JoinColumnはnullable未指定。先行するFKなしchild INSERTがSQL1364/HY000で失敗する。正本DDLとFK名は一致しておりschema driftではない。専用MySQLだけにNOT NULL制約を再現し標準HTTP登録を検証する契約を追加、finallyで元nullableと自所有fixtureを復元する。
- 本体未変更の実REDは1 test/1 failure/0 errors/0 skipped、Gradle exit1（294秒、gate正常解放）。XML SHA256 77b3dd1b33f162b709a04ef4df96ae91b4ccbe6cf40b5d3a3c2c69742fd9e590、test source SHA256 f3992ce3c0b01686d1da0f38a2bf62b578d77fa0d006328fb937bdf0e55d57ce。persistent mirrorとWindows ignored artifacts/scope-fk-red1へbefore/after XML/header/hash/stdioを二重保存した。共有DBへのDDL/repairは実施していない。
- 実機UI1は4 tests/0 failures/1 error/3 skipped、管理ハブdocument180秒timeoutで未到達。GET-onlyで同URL200/1850msを確認後UI2を1回測定したが、goto/hydrationのreloadに201秒を要しheading待機で全体300秒timeout、後続は未実施。controlled診断1件は資格API200/ADMINを確認し終了したが、保存した実画像はLoadingBounceのままで管理ハブ未到達。公開apiBaseはlocalhost8081で一致している。診断成功を受入れGREENに数えず、実機4API操作・空/資格表示・3住民は未達を維持する。

### 外部キー最小修正後の実GREEN（2026-10-03）

- 本体 e6da1749ab、管理65＋関連Proxy/Access/Gate/標準Arch＋外部キー契約を共有turnstileで測定。terminal exit 0、54クラス、JUnit {"tests":225,"failures":0,"errors":0,"skipped":0}、813秒、gate正常解放。
- Windows cmp-consent-red65-20261003-artifacts/scope-fk-green1 にbefore/after raw XML・header/hash・stdioを二重保全。全54 after XMLのSHA照合mismatch 0、stdio SHA 5cffe8e5ca35761881f650402bd5e41574de92407968dba2117e86b36a2d6c3e。
- FreezeStore追加違反0。削除差分件数 [{"file":"296295dd-06cf-4f7b-bf82-315ba12ff501","removed":35,"added":0},{"file":"93124b52-f328-4f09-8c4f-6d022519fae2","removed":2,"added":0}] をobserved原本に保全し、所有mirrorだけcanonicalに復元。Windows正本storeは不変更。
- Loading画面は実機受入れ成功と数えない。次の診断は通信status/未完了path/エラー種別をsafe JSONへ保存し、管理heading実到達を別判定。spec ESLint・diffcheck成功。実機操作・3住民・最終検分は引き続き未達。

### 修正jar実環境とUI準備（2026-10-03）

- 全backend tracked 12004ファイルがcanonical e6da1749abとpersistent mirrorで一致、欠落/差分0。標準bootJar exit0/34秒、217548261bytes、SHA 6c770ca37355216e4d5b92031a1702d68abbb5f8a9d3e8cd44d0f6d52c141013、Windows backup一致・gate解放。
- own runtime3 PID40978、source e6、8081空き確認、local/private YAML読み取り/CORS同条件/Flyway validate-on-migrate=trueで起動。cwd/jar一致、Flyway検証/schema最新/Started成功、health200、error種類なし。既存8080/3000/DB設定不変更。
- 専用org498で同意4件(ids2..5)標準POST成功。元FK500は実環境で解消。権限カタログ2件はSELECTのみ、own組合group2を標準APIで作成、DPは未割当。
- controlled real-ui4は画像/DOMで英語管理hub・同意管理カード到達を確認、overflow0、HTTP>=400/pageerrorなし。helperのreloadに伴うERR_ABORTED48件。日本語exact期待hubReady=falseなので全AC greenには数えない。
- 実機specのcookieKeyを正本i18n_locale、context ja-JPへ補正（共有アカウントDBlocaleは変更せず）。満たした前提は4件一覧へ更新し、normalUI5にADMIN/DP権限なし/MEMBER/SYS併有/取消PATCH0/本人255理由online撤回を準備。紙撤回/履歴/空一覧別fixture/失敗注入/390px六言語/3住民/最終検分は未達。
- feature-gate middlewareの件数コメントを実94=静的47+動的47へ同期。実挙動変更なし。変更spec/middleware ESLintとdiffcheck成功。

### 共通UI規約補正と型検証（2026-10-03）

- 新管理2ページにPageHeader/SectionCard/PageLoading/DashboardEmptyState/DashboardErrorState、pickerに共通状態部品、hubの新カードだけにSectionCardを再用。3ヶ所のpage状態をusePaginationへ集約。既存hub他カード・共通部品本体・認可/APIは不変更。取得失敗の同一load retry、権限拒否、mutation失敗、保存成功後reload失敗は別状態を維持。
- 4UIファイルと実機spec ESLint exit0/diffcheck0。全体型チェック89017は8192MiBのプロセス限定heap（開始時free13GiB）でexit0/diagnostics0/OOM false。証跡typecheck-common-1790975909610、stdio SHA f43478d0dc9208469c37457e2c91d4e69db719ffeac1ea3a688d8fe9ae033de3。
- API単体21223を標準Vitest/単一worker/node環境で再測定、terminal exit0・tests5/fail0/error0/skip0。fe-api2 raw XML SHA 790fb9567725396b92a48359cd85a661acd95089c5ad371815d8db7bc86ee70b、stdio SHA 4998f08f05e2282600b66da5a282ba2f8b20a557e926c537b7fe655724547dc7。初回Nuxt hook timeout/5skipは成功へ数えない。
- UI5実値はJUnit5/failure0/error1/skip4/time300.026。bodyの日本語hub→4同意→空履歴と画像は通過、finally context.closeがtimeout。全件green扱いしない。原trace.zip/画像2枚/stdio保持、既知runner/CLI/worker終了・既知worker直属child0をreadonly確認（他Chrome未探索）。
- 次のUI6は最初のADMIN1件のみ。finallyを公開APIRequestContext.dispose→BrowserContext.closeのtest.stepへ分け、各開始/完了時刻をcleanup-phases.jsonへ逐次保存。例外を握りつぶす/timeout短絡/Promise.raceなし。source付きtrace停止が候補だが現時点で原因と断定しない。実操作と他境界はその終端後。

## 2026-10-03 UI7/UI8 実機終端

- UI6初回はgrep不一致によるNo tests found、UI6bはFE3001接続拒否で1件/error1/exit1。既存FEの終端理由は不明のまま保持し、殿が所有確認した新foregroundサーバーでUI7を再測定した。原namespaceを移動・上書きしていない。
- UI7はADMIN管理ハブから4同意と空履歴に到達し、1件/0fail/0error/0skip/exit0。stdioSHA256 `2ffdb70e6a66baeb45360b3b88df146a8a54e755e17a9bd39e43d07c62423454`。APIRequestContext.disposeは24.600秒、BrowserContext.closeは20msで終端。実画像2枚を閲覧。履歴対象者pickerは撮影時loadingであり、候補取得完了の証跡には数えない。
- UI8はDEPUTY承認権限なし、MEMBER直URL拒否、SYSTEM+当該ADMIN併有でもUI拒否、ADMINの取消PATCH0/他代理者承認/本人オンライン理由255文字撤回の4件/0fail/0error/0skip/exit0。stdioSHA256 `2beed73089339ac3b009584cfeef198e35efda8cf842f6b17b6e5908e85977e7`。4contextのdispose/close双方完了、画像3枚を閲覧。オンライン同意3はREVOKED、他3件はPENDINGのまま。
- UI8開始時の「選択2件」は作業者の誤判定だった。実JUnitと同grepの標準--listは4件で一致し、Playwright forceRegExpは既定giフラグで小文字member/systemにも一致する。UI9は作成・実行せず、同ケース重複測定をしない。原報告と訂正を区別して保持する。
- 証跡は自所有ignored `cmp-consent-red65-20261003-artifacts/real-ui7` と `real-ui8` のrawJUnit/result/画像/cleanup-phases。UI5のclose timeoutは未合格として残し、原因は断定しない。新共通UIで正常bodyと後始末の終端を再現できた範囲を合格とする。
## 2026-10-03 UI9/UI10 と履歴前提の保全

- UI9はADMINが紙同意2を確認Dialog経由で承認し、APPROVED再取得/承認ボタンなしを確認。actual1/0fail/0error/0skip/exit0、suite50.213秒。stdioSHA256 `fe461247414922fdcb572eb413c4b6325019fcdb4348135718fd3c8d0415ec4c`、dispose23.096秒/close26ms完了、実画像閲覧済み。
- 自所有組合498の唯一のgroup2（名称とID一致を確認）を標準assign APIでDEPUTY90156へ付与。fresh認証のreadonly me/permissionsでrole DEPUTY_ADMIN/PROXY_CONSENT_APPROVE true/PROXY_INPUT_EXECUTE trueをsafe JSON保全し、UI10でもfreshcontextから同前提を再確認した。
- UI10はDEPUTYが別代理者の同意5を確認Dialog経由で承認し、APPROVED再取得/承認ボタンなしを確認。actual1/0fail/0error/0skip/exit0、suite30.650秒。stdioSHA256 `64d521e1786ca1a00281cd6451bda9a08694471f9764f01d9b9063014c4dd00a`、dispose10.714秒/close16ms完了、実画像閲覧済み。追加2caseのESLint exit0。
- 専用survey199/question152は標準APIで作成・公開しただけで、回答は未送信。既存SurveyResponseServiceは業務回答行のuserIdにactorを使い、proxy recordだけsubjectを使う不整合を読み取りで確認。殿が独立の最小試練と進行順を検討中であり、Deskからの送信と紙同意2の撤回は保留している。CMP1017へ帰属させた作業者報告は誤りであり、1017正本はResidentRegistryEntity公開縮小/登録UIの別要件。別CMPへ押し出したり、虚偽の監査を成功扱いにしない。
- API前提作成scriptがSurveyDetailResponseのtitleを直下と誤認し、初回は専用DRAFT保存後のidentity guardで終了した。正本content.titleへ訂正し保存ID199から再開して公開、二重作成なし。共有DBDDL・globalrole・共有locale設定は変更していない。
## 2026-10-03 空・失敗・狭幅の実測

- UI11は自所有空組合499で同意0/履歴0、対象者pickerの取得完了と選択肢1件をUIで確認。1/0fail/0error/0skip/exit0、suite81.272秒、stdioSHA256 `ab35c23a349a906904d82d1bbd9ca923016cdbf493e774683c1d1aec79e8e1e8`、dispose39.233秒/close254ms。画像2枚閲覧済み。
- UI12はGET503故障注入後の同一操作再試行、PATCH409故障注入時の成功表示なし・実保存状態不変、その後のactual PATCH200＋一覧GET503で保存完了/再取得失敗を区別し再試行APPROVEDを確認。1/0fail/0error/0skip/exit0、suite127.907秒、stdioSHA256 `6452f597a6e14a7ff63fb21a1541fee9d484f43b43f56b7becafcc55d4a85606`。safe proofはinjectedGET503=2/injectedPATCH409=1/actualPATCH200=1、readonly DB由来状態は初回PENDING/注入拒否後PENDING/実保存後APPROVED。対象は自所有同意4。注入409を実BE409の証明にしない。画像3枚閲覧済み。
- UI13初回はESM実行器で__dirname未定義、6件中error1/skip5で画面未開始。標準new URL(relative, import.meta.url)へ試験のlocaleファイル読取を訂正し、原試験を上書きせずUI13bへ再測定した。
- UI13bは390pxのja/en各2pageでscrollWidth=clientWidth=390、管理buttons高さ44/幅44以上がgreen。deはconsentsのscrollWidth406/clientWidth390で実RED、全6件の原JUnitは1failure/0error/3skip/exit1、残es/ko/zhは未実施。stdioSHA256 `5f1d1fae0eec1b1ae52c15e30419f71b1b06d079292596be4de3ca1c4e5e1ca4`。traceから最後の実画像を抽出・閲覧し、PageHeaderの長いGerman見出し語が右端で切れている。新管理2page呼出側だけ見出しのmin-width/max-width/wordbreakを補正する。共通PageHeader本体は変更しない。