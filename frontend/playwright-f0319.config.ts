import { defineConfig, devices } from '@playwright/test'

/**
 * F03.19（統合カレンダービュー）実機 E2E 専用の Playwright 設定。
 *
 * 既定の playwright-real.config.ts を使わない理由（playwright-f0318.config.ts と同じ方針）:
 *   - setup プロジェクト（storageState 生成）へ依存させたくない。本戦役の spec は
 *     ファイル内で自前ログインする（1ファイル=1ログイン）ため、setup が落ちると
 *     テストが全 skip になり「偽の緑」を生む。
 *   - BE/FE の向き先を検証用（BE=8081 / FE=3001）へ明示的に指すため。本陣の
 *     8080/3000 には古い版が動いており、そちらを向くと「走ったが古い実装を検証した」
 *     という最悪の偽陽性になる。
 *
 * 実行例:
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 \
 *     npx playwright test --config=playwright-f0319.config.ts
 *
 * 既定値を検証用ポートにしてあるのは、環境変数の指定漏れで本陣（8080/3000）へ
 * 静かに向いてしまうのを防ぐため。
 */
export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: '**/f0319-unified-calendar.real.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:3001',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    ...devices['Desktop Chrome'],
    // 週ビューの時間グリッド（全高 1152px）と終日帯を同時に扱うため、
    // 既定より縦に広いビューポートを使う。md(768px) 以上でないと週ビュー自体が
    // 描画されない（calendar.vue の `hidden md:grid`）ため、横幅も十分に取る。
    viewport: { width: 1440, height: 1000 },
  },
  timeout: 180_000,
  expect: { timeout: 10_000 },
})
