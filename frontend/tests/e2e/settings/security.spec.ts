import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// 本物の UA 文字列を使う（parseUserAgent が正しくブラウザ・OS を検出できるように）
// parseUserAgent('Chrome on Windows') は browser='Chrome', os='' (Windows NT を含まない) になり
// deviceLabel が 'Chrome' のみ表示される。本物の UA を使うことで 'Windows の Chrome' と表示される。
const REAL_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'

const MOCK_SESSIONS = {
  data: [
    {
      id: 1,
      ipAddress: '192.168.1.1',
      userAgent: REAL_UA,
      isCurrent: true,
      createdAt: '2026-03-01T10:00:00Z',
      lastActiveAt: '2026-04-04T10:00:00Z',
    },
  ],
}

const MOCK_WEBAUTHN = { data: [] }

// /api/v1/settings/nav のレスポンス: features を返さないと useNavSettingsStore.loadFromServer が
// undefined.filter() でクラッシュする
const MOCK_NAV_SETTINGS = {
  data: {
    features: [],
  },
}

// /api/v1/users/me の最低限レスポンス: useAuthStore や other composables が参照するフィールドを含む
const MOCK_ME = {
  data: {
    id: 1,
    email: 'test@example.com',
    firstName: 'テスト',
    lastName: 'ユーザー',
    avatarUrl: null,
    systemRole: null,
    timezone: 'Asia/Tokyo',
    twoFaEnabled: false,
    hasPassword: true,
  },
}

// インボックスサマリ: byState が空オブジェクトで OK（summaryByState は Record<string, number>）
const MOCK_INBOX_SUMMARY = {
  data: {
    byState: {},
    byPriority: {},
    bySourceType: {},
  },
}

// ページネーション形式の空レスポンス（通知系 API が content/items を参照するため）
const MOCK_PAGINATED_EMPTY = {
  data: {
    content: [],
    totalElements: 0,
    totalPages: 0,
    size: 20,
    number: 0,
  },
}

async function mockSecurityApis(page: import('@playwright/test').Page) {
  // キャッチオール: 未モックのAPIエンドポイントが500/401を返してページ状態に影響しないよう空データを返す
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
  // 特定のモックは後から登録して上書きする（Playwrightは後着優先）
  // ナビゲーション設定: { data: { features: [] } } の shape が必要
  await page.route('**/api/v1/settings/nav**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_NAV_SETTINGS),
    })
  })
  // ユーザー情報: 最低限のフィールドを返す
  await page.route('**/api/v1/users/me**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_ME),
    })
  })
  // インボックスサマリ: { byState: {}, ... } の shape が必要
  await page.route('**/api/v1/inbox/summary**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_INBOX_SUMMARY),
    })
  })
  // 通知系API（content 配列を参照するもの）
  await page.route('**/api/v1/notifications**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_PAGINATED_EMPTY),
    })
  })
  // セッション一覧
  await page.route('**/api/v1/auth/sessions**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_SESSIONS),
    })
  })
  // WebAuthn資格情報
  await page.route('**/api/v1/auth/webauthn/credentials**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_WEBAUTHN),
    })
  })
}

test.describe('SET-006〜008: セキュリティ設定', () => {
  test('SET-006: セキュリティページが表示される', async ({ page }) => {
    await mockSecurityApis(page)
    await page.goto('/settings/security')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'セキュリティ' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SET-007: セッション一覧が表示される', async ({ page }) => {
    await mockSecurityApis(page)
    await page.goto('/settings/security')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'セキュリティ' })).toBeVisible({
      timeout: 10_000,
    })
    // parseUserAgent(REAL_UA) → browser='Chrome', os='Windows'
    // deviceLabel: t('settings.security.session.device_label', { os: 'Windows', browser: 'Chrome' })
    //   = 'Windows の Chrome'（ja/settings.json: "device_label": "{os} の {browser}"）
    await expect(page.getByText('Windows の Chrome')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('現在')).toBeVisible({ timeout: 5_000 })
  })

  test('SET-008: 2FAセットアップボタンが表示される', async ({ page }) => {
    await mockSecurityApis(page)
    await page.goto('/settings/security')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'セキュリティ' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: '2FAをセットアップ' })).toBeVisible({
      timeout: 10_000,
    })
  })
})
