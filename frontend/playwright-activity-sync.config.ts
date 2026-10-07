import { defineConfig, devices } from '@playwright/test'

// 専用戦役の実 FE/BE のみ。共用サーバーへのフォールバックを許さない。
if (process.env.BASE_URL !== 'http://localhost:3001'
  || process.env.API_BASE_URL !== 'http://localhost:8081'
  || process.env.ACTIVITY_SYNC_INTEGRATION_READY !== '1') {
  throw new Error('統合成果の起動確認後に BASE_URL=3001 / API_BASE_URL=8081 / ACTIVITY_SYNC_INTEGRATION_READY=1 を指定してください')
}

export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: '**/activity-schedule-sync.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  outputDir: 'test-results/activity-schedule-sync',
  reporter: [['list'], ['html', { outputFolder: 'playwright-report/activity-schedule-sync', open: 'never' }]],
  use: {
    baseURL: process.env.BASE_URL,
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    storageState: { cookies: [], origins: [] },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium-real', use: { ...devices['Desktop Chrome'] } }],
})
