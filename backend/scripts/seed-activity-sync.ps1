$ErrorActionPreference = 'Stop'
# 専用 DB に限る。3306 / mannschaft への暗黙フォールバックは禁止。
$expected = @{ E2E_DB_PORT = '13310'; E2E_DB_NAME = 'cmp2610071510'; E2E_DB_USER = 'cmp_test'; E2E_DB_PASSWORD = 'cmp-test-only' }
foreach ($entry in $expected.GetEnumerator()) {
  if ([Environment]::GetEnvironmentVariable($entry.Key) -ne $entry.Value) {
    throw "専用 DB の環境変数 $($entry.Key) を指定してください"
  }
}
if ($env:ACTIVITY_SYNC_INTEGRATION_READY -ne '1') { throw '統合 BE の起動・migration 確認後だけ seed 可能です' }
node (Join-Path $PSScriptRoot 'seed-e2e-data.js')
if ($LASTEXITCODE -ne 0) { throw "seed 失敗: $LASTEXITCODE" }
