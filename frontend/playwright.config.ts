import { defineConfig, devices } from '@playwright/test'
import dotenv from 'dotenv'
import path from 'path'

dotenv.config({ path: path.resolve(process.cwd(), '.env.test') })

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3000'

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'on-failure' }]],

  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'off',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
  },

  projects: [
    // Setup: 一般ユーザー認証状態を保存
    {
      name: 'setup-user',
      testMatch: /.*user\.setup\.ts/,
    },
    // Setup: 管理者認証状態を保存
    {
      name: 'setup-admin',
      testMatch: /.*admin\.setup\.ts/,
    },
    // メインテスト: 一般ユーザー setup 完了後に実行
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'tests/e2e/.auth/user.json',
      },
      dependencies: ['setup-user'],
    },
    // 管理者権限テスト（admin/ 配下のみ実行）
    {
      name: 'chromium-admin',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'tests/e2e/.auth/admin.json',
      },
      dependencies: ['setup-admin'],
      testMatch: '**/admin/**/*.spec.ts',
    },
  ],

  timeout: 30_000,
  expect: {
    timeout: 5_000,
  },

  // テスト実行前に dev サーバーを起動する場合は有効化
  webServer: {
    command: 'npm run dev',
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
