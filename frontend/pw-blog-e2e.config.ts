/**
 * ブログE2E実機テスト用 Playwright 設定
 *
 * storageState への依存なし（各テスト内で自前ログイン）
 * FE dev server が http://localhost:3000 で稼働中であること前提
 */
import { defineConfig, devices } from '@playwright/test'

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3000'

export default defineConfig({
  testDir: './tests/e2e/real/blog',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report-blog', open: 'never' }]],

  use: {
    baseURL: BASE_URL,
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    screenshot: 'on',
    trace: 'on',
  },

  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        storageState: { cookies: [], origins: [] },
      },
    },
  ],

  timeout: 90_000,
  expect: {
    timeout: 10_000,
  },

  // 既存 dev server を再利用（起動しない）
  webServer: {
    command: 'npm run dev',
    url: BASE_URL,
    reuseExistingServer: true,
    timeout: 30_000,
  },
})
