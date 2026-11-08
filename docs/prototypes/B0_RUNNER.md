# B0 Phase 3B runner

## CMP-261107-0551 正式3住民の観測追記準備（2026-11-08）

累積正本 `docs/evidence/CMP-261015-1510/resident-overlay.json` の旧58/58/114全fieldとトップrunId/environmentを保持し、実住民3 persona/3 journey/4 insightだけ追記して61/61/118とした。元runは `ranch64-3fe7b1454ec1c5086db2c581`、個別sourceHeadは `4ce3aa26785871ee363c90b0a514f023ca20723a`。persona/journey IDは原reportの一意IDを維持し、固定P01–P20へ変換しない。選択証拠は `docs/evidence/CMP-261107-0551/resident-observations-20261108.json`。3人全目的達成・正式探索実施完了のroot採択と、4観測の `actual-resident-artifacts` / `trusted=false` / `observed-unverified` / coverage=falseを分ける。

小領域は実寸観測有効だが今回未変更component。mobile touch直後の素材準備中は原PNGを残し、root再試行では持続欠落非再現・元直接原因UNKNOWNとする。裁定は同directoryの `resident-root-disposition.md`。新確定bug0であり、未実証観測を修復済みへ昇格しない。

ignored `.b0-local/run-cmp0551-observations-20261108.json` を正本同一copyとし、通常generatorはsparse spec未配置の初回失敗を保持後、frontend/tests展開で正規実行成功。無関係global同期diffを採用せずbaselineのb0RunOverlayだけを生成overlayへ置換し、overlay外deepEqual・正本/copy/通常生成/採用overlay deepEqualを確認した。通常validator/自己検証全体は既知snapshot参照集合不一致FAIL、baseline保持の限定出力は既存CMP行数不一致FAIL。overlay限定宣言検査と不正10例拒否の合格は全体合格ではない。静的ボードUIはroot GO下で初回118/reload118・全行deepEqual・重複0、旧114投影保持/新4を確認。現HTMLのnew4 runId/originalRunId/sourceHead保存はexact、旧版のトップrunId保存制限は今回へ持ち込まない。カードpersona/journey/evidence/登録元とdrawer元runの表示を確認し、sourceHead専用UI欄なしは保存検証と分ける。board-ui-safe.jsonに12.223秒・所有資源0を記録し、先行2失敗も保持。実装PR #4353の新CI/build/mergeと正式完了JSON・task-list更新は別ゲートであり、この追記時点はIN_PROGRESS。

既存の `beta-inventory-board-b0-coverage.json` をspecの唯一の正本として、journey単位でPlaywrightを実行します。

```powershell
node docs/prototypes/run-b0-alicization.mjs list
node docs/prototypes/run-b0-alicization.mjs dry-run B0-J7
$env:BASE_URL='http://localhost:3001'; $env:API_BASE_URL='http://localhost:8081'; $env:B0_REAL_DB='true'
$env:TEST_ADMIN_EMAIL='...'; $env:TEST_ADMIN_PASSWORD='...'
$env:TEST_USER_EMAIL='...'; $env:TEST_USER_PASSWORD='...'
$env:TEST_USER2_EMAIL='...'; $env:TEST_USER2_PASSWORD='...'
$env:B0_THREE_BROWSER_CONTEXTS='true'
node docs/prototypes/run-b0-alicization.mjs run B0-J7
```

実行には実UI・実DBと3利用者の認証条件が必要です。未充足時は`blocked`を記録して非0終了します。結果と証拠は`docs/prototypes/.b0-local/`へ保存し、認証情報は保存しません。

## 気づき箱への連携

Playwright specから気づきを残す場合は、`inventory-insight` annotationの`description`へJSONを設定します。

```ts
test.info().annotations.push({
  type: 'inventory-insight',
  description: JSON.stringify({
    id: 'B0-J3-P06-001',
    featureKey: 'village-events-attendance-response',
    featureDetail: '出欠回答',
    personaId: 'P06',
    urgency: 'urgent',
    priority: 'should',
    title: '回答後の次の操作が分からない',
    detail: '回答保存後も画面の変化が小さく、完了したか判断できない',
    page: '/teams/demo/schedules/123',
    screenshotPath: 'test-results/B0-J3-P06-001.png'
  })
});
```

`featureDetail`は任意の文字列です。`urgency`は`urgent`（急ぎ）、`normal`（通常）、`when-free`（空いた時）から指定し、省略時は`normal`になります。

`personaId`は計画に定義した`P01`〜`P20`だけを許可します。省略時は「統括／ペルソナ未特定」です。テスト失敗も統括による気づきとして記録されます。

既存runnerを使わない住民の逐次探索も、実証跡の構造化登録として既存overlayへ取り込めます。この場合は`registrationSource: actual-resident-artifacts`、`trusted: false`、`status: observed-unverified`を明示し、`personas`と`journeys`に一意の実ID・personaArchetype・元runId・sourceHead・evidencePath・目的を宣言します。気づきはその宣言に対応するpersonaId/journeyId・originalRunId・証拠を保持します。原本にIDが無い場合は登録時付与と明記し、固定P01〜P20へ偽って変換しません。既存runner/dynamic runnerの実行やcoverage合格を意味しません。`node docs/prototypes/validate-beta-inventory-board.mjs --self-test-overlay`で宣言整合と不正なID・証拠欠落の拒否を確認できます。

累積overlayの`environment.sourceHead`は登録時の環境基準です。個々の実行headとrunIdは各persona/journey宣言に保持し、異なるhead・runの観測を同じ値へ書き換えません。各journeyのsourceHead/runIdは対応するpersonaと一致し、insightのoriginalRunIdは対応journeyのrunIdと一致する必要があります。自己検証は異なる2head・2runの正規受理と、persona/journey間のhead不一致・insightの元run不一致の拒否も確認します。

実行後、ローカル結果をダッシュボードデータへ含めて再生成します。

```powershell
$env:B0_INCLUDE_LOCAL_OVERLAY='true'
python docs/prototypes/generate-beta-inventory-board-data.py
```

再生成された画面を開くと、`b0RunOverlay.insights`が既存のIndexedDBへ重複なしで取り込まれます。手入力と自動探索は同じ「気づき箱」に並び、登録元・persona・journey・runId・証拠パスを保持します。

第二陣時点の累積登録（履歴）は `docs/evidence/CMP-261015-1510/resident-overlay.json` の6 persona / 6 journey / 13 insight。第一陣 `CMP-261022-0338/resident-overlay.json` の3/3/5は全フィールドを保持し、第二陣c717の3/3/8を追記しています。再生成する際はこの正本を `docs/prototypes/.b0-local/run-october-wave2-20261026.json` へコピーし、他のrunが最新mtimeで選択されないことを確認してから上記生成を実行します。生成後は正本・ignored copy・`b0RunOverlay` の構造一致、通常validator、`--self-test-overlay` を確認します。新8件の住民画像はprivate原本へのpath参照だけで、画像実体は含めません。

2026-10-27の交代相談I03登録後は同正本に21 persona / 21 journey / 48 insightを累積する。直前の18/18/42は全フィールド保持し、3/3/6のみ追加した。今回のrun・配役の正本はprivate `resident-purpose-contract.json`、公開出所は `docs/evidence/CMP-261010-1432/resident-observations-20261027.json`。再生成用copyは `docs/prototypes/.b0-local/run-cmp1432a-observations-20261027.json`。3住民全員で探索実施完了と目的未達を分離する。通常validatorと自己検証全体は既存GitHub snapshot参照集合不一致で失敗しており、overlay限定検査の合格と混同しない。

0649第三固定追試後の新D観測は `docs/evidence/CMP-261021-0649/proofs/third-D-observations.json` を出所に、同累積正本へ1 persona / 1 journey / 1 insightだけ追加した。旧21/21/48の全フィールドと旧run/sourceを保持して22/22/49とする。新sourceは84c8、runはevidence-standard-fixed-84c8-03、ownerはcmp1432f-e48ca45535。独立Dは実施完了true/全目的false、相談状態・担当名・相談個別切替の未達を保持し、trusted=false/observed-unverified/coverage不昇格。原本にない一意IDは登録時付与で固定P01-P20へ変換しない。再生成用copyは専用WT内の `.b0-local/run-cmp0649-third-observations-20261028.json`。通常snapshot validatorの既知参照集合不一致とボードUI未検証を、overlay限定検査の成功へ読み替えない。

2026-10-28の第五陣fresh3登録後は同正本に25 persona / 25 journey / 54 insightを累積する。直前22/22/49の全フィールドを保持し、fresh3のみ3/3/5を追加。公開選択証拠は `docs/evidence/october-fresh3-20261028/resident-observations.json`、root制御再現は別の `root-name-only-proof.json`。3住民とも1 context生成/閉鎖、予期しない業務書込0・pageErrors0・runtime不変で探索実施完了だが、全目的達成ではない。fresh1/fresh2を転用せず、recovery-build5のsource head一致とoutput全bytes同一を区別する。再生成用copyは `docs/prototypes/.b0-local/run-october-fresh3-20261028.json`。最新mtime選択を確認し、正本/copy/生成overlay構造一致と通常validator・自己検証を行う。NAME_ONLY詳細不備はroot再現のみを根拠に別CMP-261028-0513へ未着手登録し、住民insightの未実証ラベルを変更しない。

2026-10-28の委員会目的・高齢住民の追加探索は `docs/evidence/CMP-261027-1311/additional-observations.json` を選択証拠として、累積正本へ1 persona / 1 journey / 1 insightだけ追記する。旧25/25/54の全フィールドをdeep保持し26/26/55とする。旧fresh3の3住民＋追加1のB0合計4住民と、委員会目的の一般・ADHD・高齢の3住民、メールキュー目的1住民を区別する。追加住民は一覧/詳細へ到達、活動・伝達履歴・帰還は未達。途中で目的組織名だけ補足し、不可視項目混在とlocator Timeout制約を保持する。探索実施完了true・全目的false・coveragefalse・observed-unverifiedを維持し、旧fresh3全目的falseを書き換えない。FE8f/BE1aのsame-head recovery-build5で、output全byte同一は主張しない。再生成用copyは `.b0-local/run-committee-third-resident-20261028.json`。探索stageはroot裁定で実施・観測triage・正常閉鎖を受入済みであり、全目的達成やB0 coverage合格とは区別する。

2026-10-28のアリシゼーション run-20261028（戦役 CMP-260827-1152）の住民4体は、公開選択観測 `docs/evidence/CMP-260827-1152/resident-observations.json` を出所に、同累積正本へ4 persona / 4 journey / 19 insightだけ追記する。旧26/26/55の全フィールドを保持して30/30/74とする。runIdは `alicization-cmp1152-run-20261028`、sourceHeadは `dae9f24a922cd9b06d87e4ec4d46e4f55ab65004`（`d3e0d74f2d` + 実機spec 1件）。住民は一般×中断・放棄（チームADMIN）、一般×隙間狙い（部外者）、スマホ片手×表示崩れ（MEMBER）、一般×連打（SYSTEM_ADMIN）。registrationSource=actual-resident-artifacts・trusted=false・observed-unverifiedを維持し、確定不具合2件（CMP-261028-2328・CMP-261028-2329）も住民insight側は未実証ラベルのままとする。画像実体は含めず、private原本 `.claude/worktrees/agent-a7d0f0151e64065b2/frontend/test-results/alicization/run-20261028/resident{1..4}/` へのpath参照のみ。再生成用copyは `.b0-local/run-alicization-cmp1152-20261028.json`。通常validatorは既知のGitHub snapshot参照集合不一致で失敗しており、overlay限定検査（`validateRunOverlay` と `--self-test-overlay` ブロックの抜き出し実行）の合格と混同しない。

### CMP-260908-2116 run08 の観測追加（2026-10-29）

通常generatorの出力はprivate比較証拠へSHA付きで保持した。今回の目的は観測反映だけであり、最新task-listに由来する無関係なcampaign派生同期は含めないというroot裁定により、生成JSはbaselineの全fieldを保持してb0RunOverlayだけを正本へ置換した。outside-overlay baseline deepEqualとoverlay正本deepEqualを限定確認し、通常global validatorの既存GitHubスナップショット不整合FAILは保持する。実UIは既存decisionのB4で新4件と登録元・元run・sourceを確認し、reloadの既存autoloadによる再取込後も4件で非重複だった。新ブラウザは専有context・profileだけを閉鎖した。cleanupは完了したが実74.024秒で予定reserve60秒を超過したため、上限遵守は主張しない（全経過102.758秒）。

A/B/D各1 persona・journeyと4件の未検証観測を累積正本へ追加した。元run: CMP-260908-2116-revalidation-20261029-08、sourceHead: ac49b5af784b79bade786ac0c82b9bb3822289f3。標準run exit1・finish未作成・全goalsReached=false・終了目標時刻（closeby）超過を保持し、固定試験の合格で探索の未達を置き換えない。出所・選別観測・root裏取りは docs/evidence/CMP-260908-2116/proofs/resident-observations.json に記録した。actual-resident-artifacts / trusted=false / observed-unverified として登録し、B0coverage・製品修正完了を主張しない。既存トップrunIdと旧値を保持し、ignored .b0-local/run-cmp2116-observations-20261029.json を正本copyとして通常generatorへ渡す。

### CMP-260910-1555 run11 の観測追加（2026-10-30）

A/B/Cの独立3 persona・3 journey・3 insightを、元run `CMP-260910-1555-revalidation-20261030-11`・source `588407f98b2b7aecd99897c26bdd3b6129768a82` のまま累積正本へ追加した。旧36/36/79の全フィールド/元run/head/トップrunIdを保持し39/39/82とする。選別出所は `docs/evidence/CMP-260910-1555/resident-observations-20261030.json`。A/C目的達成、B目的未達と探索実施完了を分離し、actual-resident-artifacts / trusted=false / observed-unverified / coverage=false / 親1555未完了を維持する。固定58件中57pass・必須全greenでもoptional deputy menu未達や住民観測を昇格させない。

生成JSは最新main baselineを保持しb0RunOverlayだけを正本へ置換する。ignored `.b0-local/run-cmp1555-observations-20261030.json` は再生成可能な正本同一copyだけとし、最新mtime選択・全構造一致・overlay外deepEqualを検証する。通常validatorの既存GitHub snapshot不整合FAILとoverlay限定checkを分離する。ボード実UIは既存autoloadの初回82件→reload後82件・全行deepEqualで、新3cardと旧79件のimport投影字段保持を確認した（原本全fields保持は別構造検査）。検証後のworktree切替/sparse後にprivate helper原本が不在となり、消失時点/完全再現性は未確認。事故は正常cleanup/削除許可とは扱わず、元safe/画像識別子SHAと制限を選別観測へ保持した。

専有Windows Playwright/Chromiumでボード取込・再読込を実測し、選別画像1枚をrootが実視認した。証拠はprivate artifact `cmp1555-board-ui-3326722d4d91/board-ui-safe.json`（SHA256 `8dafb9f0e92e15b1117290bb31f3cf26297be77426bbc8640b7f6c283a55a1cf`）。全経過28.230秒、撤収18.874秒で固定作業180秒・撤収120秒・全体300秒以内。既知4世代live0、専用profile不在・ownport解放、context/browser/server閉鎖を確認した。任意Windows handles全域/全期間追跡は主張しない。既存importerは全件のstored runIdにトップ旧値 `october-wave2-login-resident-overlay-20261026` を用いるため、originalRunIdPrecisionPassed=false。元run `CMP-260910-1555-revalidation-20261030-11` はdetail/選別証拠に保持されるが、保存runIdの精度まで包括合格とはしない。住民未実証・B目的未達・coverage=false・親1555未完了とhelper原本不在の制限を維持する。

### CMP-261011-0035 F01.2.1 組織チームグループ戦役の住民観測追加（2026-11-01）

戦役 CMP-261011-0035 のアリシゼーション2 run を、公開選択観測 `docs/evidence/CMP-261011-0035/resident-observations.json` を出所に、累積正本 `docs/evidence/CMP-261015-1510/resident-overlay.json` へ追記した。旧42 persona / 42 journey / 91 insight の全フィールドを保持し、10/10/17を追加して52/52/108とする。registrationSource=actual-resident-artifacts・trusted=false・observed-unverifiedを維持し、B0 coverageや固定P01〜P20への変換は行わない。IDは登録時付与（persona `ALIC-CMP0035-*`、journeyは各persona末尾 `-J1`）で、原観測にjourneyIdはない。

- run A `alicization-cmp0035-run-20261028`（初回、住民7体 alic-xa/xm/out/to/ta/ua/ya、観測8件）: sourceHeadは原本に検証headの明記がなく、戦役台帳の #4009 流し直し記述（`69de853d1f`）を根拠に `69de853d1fbad5744d6deb9cab98516be633cf9e` として登録した。人格・攻め口・目的は原本に記載がなく、役割と操作画像名からの推定を各宣言に明記した（部外者 OUT は観測なし）。観測者の割当も所在からの推定付与で、画像は暫定紐付け。殿の裏取りは1・2が誤報、3〜6が仕様どおり（改善案）、7が確定欠陥（チーム権限グループ一覧の内部コード直出し、PR #4206 で修正済み）、8が設計未定義の空状態（範囲外 UX 課題）。
- run B `alicization-cmp0035-run-20261030`（再確認、main `0f8354b879e6f58103e07d76606928ea499a4dbf`、住民3体 ta/to/ya）: 一般×素直は告知5件読了・未読0、スマホ片手×表示崩れは横パンなし、多言語en×隙間狙いは途中中断し殿が API で代行したため実施完了とはしない。確定2件（組織告知一覧の宛先漏れ PR #4240、390px で告知カードに1件しか見えない PR #4239）も住民insight側は未実証ラベルのままとする。範囲外の気づき（管理者ビュー切替ポップオーバーの被り、タップ対象の小ささ、戻る二重、初期スケルトン約5秒、組織の権限グループ画面に作成・編集ダイアログなし）は台帳の列挙を保持し、観測者割当は推定。

画像実体は含めず、private 原本 `.claude/evidence/CMP-261011-0035-alicization/`（版管理の非追跡。`alic1028/{xa,xm,out,to,ta,ua,ya,verify}/`・`alic1030/{r1,r2,r3,prep}/`、初回8観測の裏取り報告 `verify-report-alic1028.md`）への path 参照のみとする。再生成用copyは ignored `docs/prototypes/.b0-local/run-cmp0035-observations-20261101.json`（正本同一）。`beta-inventory-board-data.js` は通常generatorの出力ではなく、baselineの全fieldを保持して `b0RunOverlay` だけを正本へ置換した（baseline側は82件で旧正本91件に未追随だったため、今回で108件へ揃う）。通常validatorと自己検証全体は既知のGitHub snapshot参照集合不一致（CMP正本行数と参照抽出数）で失敗しており、overlay限定検査（`validateRunOverlay` の抜き出し実行と不正なjourney/sourceHeadの拒否、正本とdata.jsのdeepEqual）の合格と混同しない。

### CMP-261021-0717 UI9 住民観測（2026-11-05）

UI9の独立3住民観測を `docs/evidence/CMP-261021-0717/resident-observations-20261105.json` へ選別記録し、累積正本 `docs/evidence/CMP-261015-1510/resident-overlay.json` の52/52/108を全保持して、3 persona / 3 journey / 3 insightを追記し55/55/111とした。元runは `CMP-261021-0717-ui9-20261105`、個別sourceHeadは `dc977b6724958111c149140ab83f0094b433bb78`。3件とも `actual-resident-artifacts` / `trusted=false` / `observed-unverified` とし、coverage・欠陥確定・task-list完了へ昇格しない。A/Bに原persona/journey IDがない部分は登録時付与を明記。Cの原ID `resident-c` / `resident-c-ui9` を衝突なしでそのまま使う。累積overlayの既存トップrunIdとenvironmentを変更しない。

選択画像はresident A予算画面、Bマイシフト、C管理者拒否の3枚を同ディレクトリへコピーし、raw JSONのprivate原本pathは証跡内に保持する。Aは公開・アーカイブ表示を確認したが予算消化の戻りを理解できず、一般会計0円は消化額と別の観測として既知CMP-261021-0652と分離。rootの03:32:06Z read-only照合ではARCHIVED/deleted=null、消化3/4がCANCELLED 9,600/12,000（SHIFT_DELETED、03:14:14Z）、allocation2 consumed0/deleted=null。Bの初回12月空表示はA公開より前で正常。月表示の復帰、未提出入力消失、アーカイブ後のシフト/交代導線、取消説明未発見は住民観測として保持し、原因を断定しない。strict selector/timeoutは自動操作失敗で、アプリ例外としない。Cの別組織404・予算管理者拒否はroot確認済み既存挙動。推測不存在は認可成功と扱わず、書込みAPI認可も未検証。

rootは3住民の原session正常終了、cleanup=removed、専用scratchと内部source copyの撤去を確認。元source worktreeは保持している。root API metadataでGitHub environment 0、本番environment作成なし、本番操作0も確認済み。選択画像6枚はrootが視認し、固定read-only 4 SELECTで住民fixture/DB後状態を確認した。新規確定bug 0、blocker昇格0。通常validatorの既知snapshot不一致FAIL、overlay限定検査、自己検証、静的board UI確認は別々に報告し、UI確認はrootのGO前には実行しない。既存importerの保存runIdがoverlayトップ旧runIdを使う精度制限も維持する。この記録時点のCMP-261021-0717は実装中。実装PRのmerge後に同docs PRへ正式完了JSON/MDとtask-list更新を追加する。

### CMP-261021-0717 UI9 静的ボード取込確認（2026-11-05）

静的ボードUIの最終版をroot承認下で1回確認した（先行失敗は別記）。HTML SHA256 `64d62b5cae580815a09d353844cb45d44232417bfeaf9473953b22dcbca220d4`（Git blob OID `5939210c58225795e1c168052903f5b4293b206d`）、生成JS SHA256 `355c66e99a952de8de9ffb5850367e2403023bef0ce6bc91705edee8ba2d8c8b`。外部要求遮断0、製品アプリ起動なし。初回111件・reload後111件、全行deepEqual、旧108件projection保持、新3件はすべてB4で表示し、persona/journey/run/evidence/screenshotPath/featureKey/stage/sourceの各表示確認成功。選択3 PNG pathと「自動テストのスクショ証拠あり」表示も確認した。board結果画像は `docs/evidence/CMP-261021-0717/board-new-three.png`（SHA256 `e50d7af44c2dd782fde9420c429de77734fa011e534c2a6f17438c80b5cf6606`）。UI receiptは `board-ui-20261105.json` に選別記録した。

初回UIは旧featureKeyがcanonical capabilityに無く、未設定stageへfallbackしたため新3件がB4検索で見つからず失敗した（receipt SHA256 `695478ce29b22ce92b707307d3185c00b0f4c117a065c6c3bc6436d370f25c30`）。新3件のみを既存capability keyへ合わせてB4解決し、再確認は全成功（receipt SHA256 `37efbc8824c455fd6850f1dea3a41a727547cf8dc2c3fbd2ba84cb54e771231f`）。最終runは50.060秒、撤収30.399秒で各予算180秒/120秒/全体300秒内。既知browser generation live0、profile不在、port解放、各client/server正常終了を確認し、generation snapshot欠落なし。外部通信は0件だった。

保存済みrunIdは累積overlayのトップ旧runIdとなる既存importer仕様のため `originalRunIdPrecisionPassed=false` を維持する。元runIdは各カード詳細・証拠に保持する。これは静的ボードの証拠であり、製品実機・DB検証やB0 coverage合格を意味しない。実装PR #4291が未mergeのため、task-listと正式完了JSON/MDはこのdocs PRにまだ含めない。docs PRもコードPRの全gate/merge後に正式完了内容を追加するまではdraftとする。

### CMP-261008-1253 R24 部分住民観測（2026-11-09）

前回登録時点の履歴（最新採用は次段）: 実結果:Cの人数表現を原表示「0件未割当」へ訂正後、通常generatorを1回実行してexit0の全量680 campaignを最終採用した。生成JS SHA256は `7f72c3725b73184e511c4b704ff161060e5317ec2d56e01b0195a9f1584eb938`。この新bytesで通常validator/自己検証全体を再実行し、L46 GitHub参照集合不一致で各exit1。602行baseline保持案は現台帳680行の78行を欠落させるため棄却し、途中候補のL37 CMP行数不一致FAILは `b0-overlay-checks-r24.json` に保存した。最終証拠は `b0-final-normal-output-r25.json`。overlay限定のコード抜き出し自己検証も取り直してexit0（empty・複数head/run受理、不正10例拒否）。正本/copy/通常生成overlayのdeepEqual、旧全field/トップrunId/environment保持を確認した。global snapshot同期済や全体greenへ読み替えない。

現在統合 `fa5aa4fae6b5ac04c1fbe632028f1c4b8d8129b1`／main `7dde81a72240a61650cafd0f78c4ee848da9df87` 追従後、通常generatorを1回実行して680 campaign（台帳CMP 680行／一意680件）を採用した。最新proofは `b0-main-follow-normal-output-r25.json`、生成JS SHA256 `06c1aee980e985a4ab7270c38bade1e4879a1f366ce51ad9cd7c52c88fd2c2b6`。実機はf938、初回de0は履歴、fresh CIは後続。64/64/120・旧61/61/118全field・run/environment・観測内容は不変。新bytesの通常validator/自己検証全体を各1回実行しactual結果を新proofへ保存した。overlay/validator hash不変により既存抜出しself-test exit0を同値再利用し重複実行しない。旧proofと候補を保持し、global検査とoverlay限定検査を区別する。

旧61 persona / 61 journey / 118 insightの全fieldとトップrunId/environmentをdeep保持し、実3 persona / 3 journey / B・Cの実観測2 insightだけ追加して64/64/120とした。選別証拠は `docs/evidence/CMP-261008-1253/resident-observations-r24-20261108.json`、部分採択は同directoryの `r24-partial-evidence.md`。persona/journey IDは登録時付与、原runId（r21 prefix）とsource f938を保持し、新integration de0cと混同しない。actual-resident-artifacts / trusted=false / observed-unverified / coverage=falseを維持する。A達成・B未達・C読み取り範囲達成、約44分、初期HTTP metadata中継の制約を保持する。新規確定bug0、正式完了・Issue close・台帳状態変更は行わない。ignored `.b0-local/run-cmp1253-r24-observations-20261108.json` は累積正本同一copy。生成とvalidatorの実結果は選別検証 `b0-overlay-checks-r24.json` へ記録し、既知global snapshot不一致FAILとoverlay限定検査を分ける。


### CMP-261008-1253 R25 静的ボードUI受入（2026-11-09）

静的ボードUIはRoot受入済み。`docs/evidence/CMP-261008-1253/board-ui-r25-20261109.json` に初回120/reload120、全25field deep一致、旧118原本/IDB投影保持、新B/CのB4カード表示を記録した。prototypeボード画像 `board-r25-B.png`・`board-r25-C.png` は原bytes/hashを保持しRoot実閲覧済み。住民private PNG本体は公開していない。current HTMLはrunId=originalRunId優先でoriginalRunId/sourceHead/trusted/sourceStatus/sourceEvidencePathも実保存し、今回120件のsource保持を確認した。run値そのものはカード本文に表示されない。generic originalRunIdPrecisionPassed=falseは全歴史runの意味精度を一括保証しない意味で、新B/C保存契約passedと分ける。旧版の常にトップrunIdを保存する制限を今回へ持ち込まない。fresh local IndexedDBを使用し、製品app/API/SQL/deployは0。total28.488秒/cleanup4.071秒、全cleanup検査合格、native Node/outer exit0をRootが確認した。旧2失敗は各Node1/cleanup正常/PNG0のhelper起因履歴として保持し、製品redへ昇格しない。最新通常生成/通常validator L46 FAIL/overlay限定passと、この静的UI passは別判定である。R24の3run/source f938/約44分/A達成・B未達・C読取範囲のみ/PDF取得未知/trusted=falseは不変。
