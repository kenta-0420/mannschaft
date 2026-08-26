import { defineConfig, devices } from '@playwright/test'
import dotenv from 'dotenv'
import path from 'path'

dotenv.config({ path: path.resolve(process.cwd(), '.env.test') })

// .env.test は .gitignore 対象（追跡外）のため、未設定環境では API_BASE_URL が
// 空文字（baseURL相対）になり fixtures/auth.ts の loginViaApi が Nuxt(3000) に
// /api/v1/auth/login を投げて 404 → setup が落ちて後続が全skipのまま exit code 0 になる
// （偽の緑）。実機構成は本陣ポート規約（BE=8080）を既定値として明示する。
if (!process.env.API_BASE_URL) {
  process.env.API_BASE_URL = 'http://localhost:8080'
}

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
