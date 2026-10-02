import { defineConfig, devices } from '@playwright/test'

// 活動記録作成フロー 実機 E2E 専用設定（storageState/setup 非依存・page context で fresh login）。
export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: '**/activity-create.spec.ts',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    storageState: { cookies: [], origins: [] },
  },
  projects: [
    { name: 'chromium-real', use: { ...devices['Desktop Chrome'] } },
  ],
  timeout: 120_000,
  expect: { timeout: 10_000 },
})
