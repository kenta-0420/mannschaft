/**
 * F06.5 Phase 3 越境認可 E2E 専用 config
 *
 * - Nuxt dev サーバー不要（BE 直接呼び出し）
 * - setup（storageState 生成）不要（spec 内で直接ログイン）
 * - testMatch: reflection-authz-phase3.spec.ts のみ
 */
import { defineConfig, devices } from '@playwright/test'
import dotenv from 'dotenv'
import path from 'path'

dotenv.config({ path: path.resolve(process.cwd(), '.env.test') })

export default defineConfig({
  testDir: './tests/e2e/real/reflection',
  testMatch: '**/reflection-authz-phase3.spec.ts',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    // BE を直接指定（Nuxt 経由なし）
    baseURL: process.env.BE_ORIGIN ?? 'http://localhost:8080',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
  },
  projects: [
    {
      name: 'chromium-authz-phase3',
      use: {
        ...devices['Desktop Chrome'],
        // storageState なし（spec 内で直接ログイン）
      },
      testMatch: '**/reflection-authz-phase3.spec.ts',
    },
  ],
  timeout: 60_000,
  expect: { timeout: 8_000 },
})
