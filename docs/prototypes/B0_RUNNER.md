# B0 Phase 3B runner

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
