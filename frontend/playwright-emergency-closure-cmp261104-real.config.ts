import { defineConfig, devices } from '@playwright/test'

// CMP-261104-0334〜0337 緊急休業画面の確定不備4件の実機E2E。認証 setup は起動せず、spec 内で API ログインする。
// 実 BE（API_BASE_URL）と実 FE（BASE_URL）を前提とし、モックは使わない。
if (!process.env.API_BASE_URL) process.env.API_BASE_URL = 'http://localhost:8083'

export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: 'emergency-closure-cmp261104.real.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 480_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.BASE_URL ?? 'http://localhost:3003',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    trace: 'off',
    screenshot: 'only-on-failure',
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
  },
})
