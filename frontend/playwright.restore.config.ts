import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.RESTORE_UI_BASE_URL
if (!baseURL) throw new Error('RESTORE_UI_BASE_URL_REQUIRED')

// 所有専用環境用。既存real全体のsetupや.env.testを読み替えず、この契約だけを実行する。
export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: 'team-restore-ui.spec.ts',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 20_000 },
  reporter: [['junit', { outputFile: 'test-results/restore-ui.xml' }]],
  use: {
    ...devices['Desktop Chrome'],
    baseURL,
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    trace: 'off',
    video: 'off',
    screenshot: 'only-on-failure',
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
  },
})
