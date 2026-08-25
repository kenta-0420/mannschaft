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

実行後、ローカル結果をダッシュボードデータへ含めて再生成します。

```powershell
$env:B0_INCLUDE_LOCAL_OVERLAY='true'
python docs/prototypes/generate-beta-inventory-board-data.py
```

再生成された画面を開くと、`b0RunOverlay.insights`が既存のIndexedDBへ重複なしで取り込まれます。手入力と自動探索は同じ「気づき箱」に並び、登録元・persona・journey・runId・証拠パスを保持します。
