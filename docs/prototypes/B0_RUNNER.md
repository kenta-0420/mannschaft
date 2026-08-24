# B0 Phase 3B runner

既存の `beta-inventory-board-b0-coverage.json` をspecの唯一の正本として、journey単位でPlaywrightを実行します。

```powershell
node docs/prototypes/run-b0-alicization.mjs list
node docs/prototypes/run-b0-alicization.mjs dry-run B0-J7
$env:BASE_URL='http://localhost:3001'; $env:API_BASE_URL='http://localhost:8081'; $env:B0_REAL_DB='true'
$env:B0_ADMIN_EMAIL='...'; $env:B0_USER1_EMAIL='...'; $env:B0_USER2_EMAIL='...'
node docs/prototypes/run-b0-alicization.mjs run B0-J7
```

実行には実UI・実DBと3利用者の認証条件が必要です。未充足時は`blocked`を記録して非0終了します。結果と証拠は`docs/prototypes/.b0-local/`へ保存し、認証情報は保存しません。
