import { defineConfig, devices } from '@playwright/test'

/**
 * CMP-261105-1507（N+1 戦役で見つかった既存不具合6件の修正）実機E2E 専用設定。
 * 検証用 BE 8081 / 検証用 FE 3017 を既定にする（本陣 8080/3000 は使わない）。
 * ログイン・前提データ作成・後始末は spec / globalSetup / globalTeardown で行うため setup プロジェクトは持たない。
 */
process.env.API_BASE_URL ||= 'http://localhost:8081'
process.env.BASE_URL ||= 'http://localhost:3017'

export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: 'cmp1507-regression.real.spec.ts',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  globalSetup: './tests/e2e/real/helpers/cmp1507-global-setup.ts',
  // 後始末は n1p1 と同じ記録ファイル方式（記録の逆順に削除し、204/404 以外は失敗させる）
  globalTeardown: './tests/e2e/real/helpers/n1p1-global-teardown.ts',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.BASE_URL,
    screenshot: 'only-on-failure',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
  },
  timeout: 300_000,
  expect: { timeout: 15_000 },
})
