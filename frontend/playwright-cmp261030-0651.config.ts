import { defineConfig, devices } from '@playwright/test'

// 認証 setup を起動せず、既存 dev サーバー（既定 3001）の検証専用ページだけを使う。
export default defineConfig({
  testDir: './tests/e2e/mobile',
  testMatch: 'emergency-closure-layout.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 120_000,
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
