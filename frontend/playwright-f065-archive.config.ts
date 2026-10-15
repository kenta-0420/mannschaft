/**
 * F06.5 Phase 3 アーカイブ&分類 E2E 専用 Playwright 設定。
 *
 * - storageState 依存なし（beforeEach で直接 BE API ログイン）
 * - BASE_URL: http://localhost:3000（本陣 FE）
 * - BE_ORIGIN: http://localhost:8080
 */
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
      name: 'chromium-f065-archive',
      use: {
        ...devices['Desktop Chrome'],
      },
      testMatch: '**/reflection/reflection-archive.spec.ts',
    },
  ],
  timeout: 60_000,
  expect: { timeout: 10_000 },
})
