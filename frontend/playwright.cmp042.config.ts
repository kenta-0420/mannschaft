import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: 'survey-target-count-cmp042.real.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 360_000,
  expect: { timeout: 20_000 },
  reporter: [['list'], ['junit', { outputFile: 'test-results/cmp042/results.xml' }]],
  outputDir: 'test-results/cmp042',
  use: {
    ...devices['Desktop Chrome'],
    actionTimeout: 20_000,
    navigationTimeout: 120_000,
    baseURL: process.env.BASE_URL ?? 'http://localhost:3001',
    storageState: { cookies: [], origins: [] },
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
  },
})
