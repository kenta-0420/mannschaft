import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:3001',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
  },
  projects: [{
    name: 'chromium-cmp005',
    use: { ...devices['Desktop Chrome'] },
    testMatch: '**/real/cmp005-bulletin-undo.spec.ts',
  }],
  timeout: 300_000,
  expect: { timeout: 15_000 },
})
