import { defineConfig } from '@playwright/test'

// CMP-261008-1203 の所有環境だけで実行する。共有認証 setup を呼ばない。
export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: 'activity-schedule-auto.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 15_000 },
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:3001',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    trace: 'off',
    screenshot: 'only-on-failure',
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
  },
})
