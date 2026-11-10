import { defineConfig, devices } from '@playwright/test'

// CMP-261029-0620: 本番ビルドの SW へ CDP で push を投入する E2E。BE・ログイン setup は使わない。
// 本番ビルドを起動しておき、BASE_URL で指す（例: http://localhost:3007）。
export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: 'cmp-261029-0620-web-push.real.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.BASE_URL ?? 'http://localhost:3007',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    trace: 'off',
    screenshot: 'only-on-failure',
    video: 'off',
    serviceWorkers: 'allow',
    // 既定の chromium-headless-shell は通知の許可を常に denied にし showNotification が失敗する。
    // 新しい headless（channel: 'chromium'）なら grantPermissions が効き getNotifications で確かめられる。
    channel: 'chromium',
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
  },
})
