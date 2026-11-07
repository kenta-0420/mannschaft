import { defineConfig, devices } from '@playwright/test'

/**
 * CMP-261012-0910（2FA 穴埋め）実機E2E 専用の独立 config。
 * 既定 config の setup 依存（real-user.setup）・webServer・storageState を使わず、
 * 検証用 BE/FE（BASE_URL / API_BASE_URL で指定）に対して各テストが自前でログインする。
 */
export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: '**/mfa-{gap-fill,alic-recheck}.real.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 1_800_000,
  expect: { timeout: 60_000 },
  reporter: [['list']],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:3011',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    screenshot: 'off',
    actionTimeout: 90_000,
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
  },
  projects: [{ name: 'chromium-mfa-real', use: { ...devices['Desktop Chrome'] } }],
})
