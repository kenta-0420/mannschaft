import { defineConfig, devices } from '@playwright/test'

// 専用fixture・既存サーバーだけを使い、共有認証setupを起動しない。
export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: 'password-generator.real.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 300_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.BASE_URL ?? 'http://localhost:3001',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
  },
})
