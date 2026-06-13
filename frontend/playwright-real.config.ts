import { defineConfig, devices } from '@playwright/test'
import dotenv from 'dotenv'
import path from 'path'

dotenv.config({ path: path.resolve(process.cwd(), '.env.test') })

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:3000',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
  },
  projects: [
    {
      name: 'setup-real-user',
      testMatch: /.*real-user\.setup\.ts/,
    },
    {
      name: 'setup-real-admin',
      testMatch: /.*real-admin\.setup\.ts/,
    },
    {
      name: 'chromium-real',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'tests/e2e/.auth/real-user.json',
      },
      testMatch: '**/real/**/*.spec.ts',
      dependencies: ['setup-real-user', 'setup-real-admin'],
    },
  ],
  timeout: 60_000,
  expect: { timeout: 8_000 },
  // webServer は既存サーバーを使用するため無効化
  // webServer: { ... }
})
