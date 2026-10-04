// 配置候補frontend/playwright-village-history.config.ts。専用CI所有FE/BEだけで使用。
import { defineConfig, devices } from '@playwright/test'

const base = process.env.BASE_URL
const api = process.env.API_BASE_URL
if (!base || !api || !process.env.VILLAGE_HISTORY_FIXTURE_MANIFEST)
  throw new Error('DEDICATED_HISTORY_RUNTIME_UNCONFIGURED')
for (const value of [base, api]) {
  const url = new URL(value)
  if (url.protocol !== 'http:' || url.hostname !== 'localhost' || !url.port
      || url.username || url.password || url.pathname !== '/' || url.search || url.hash)
    throw new Error('DEDICATED_HISTORY_ORIGIN_INVALID')
}

export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: ['village-history-real.spec.ts', 'village-moderation-real.spec.ts'],
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  globalTimeout: 240_000,
  expect: { timeout: 8_000 },
  reporter: [['list'], ['json', { outputFile: process.env.VH_UI_PHASE === 'moderation'
    ? 'build/village-history-real/moderation-actual.json' : 'build/village-history-real/actual.json' }]],
  use: {
    baseURL: base,
    storageState: { cookies: [], origins: [] },
    trace: 'off', video: 'off', screenshot: 'off',
    locale: 'ja-JP', timezoneId: 'Asia/Tokyo', viewport: { width: 1280, height: 800 },
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
  },
  projects: [{ name: 'chromium-village-history', use: { ...devices['Desktop Chrome'] } }],
  // setup依存/storageState既資産/webServer既定・reuseExistingServerは使わない。
})
