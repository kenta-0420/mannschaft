import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const VALID_TOKEN = 'valid-token-uuid-1234'
const EXPIRED_TOKEN = 'expired-token-uuid-9999'

const MOCK_VALID_PREVIEW = {
  data: {
    isValid: true,
    issuer: {
      displayName: '山田 太郎',
      contactHandle: 'taro_yamada',
    },
    expiresAt: '2099-12-31T00:00:00Z',
  },
}

const MOCK_INVALID_PREVIEW = {
  data: {
    isValid: false,
    issuer: { displayName: '', contactHandle: null },
    expiresAt: null,
  },
}

test.describe('CNT-INVITE-001〜005: 招待URL着地ページ', () => {
  test('CNT-INVITE-001: 有効なトークンで招待者情報が表示される', async ({ page }) => {
    await page.route(`**/api/v1/contact-invite/${VALID_TOKEN}**`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_VALID_PREVIEW),
        })
      }
    })

    await page.goto(`/contact-invite/${VALID_TOKEN}`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '山田 太郎' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('@taro_yamada')).toBeVisible()
    await expect(page.getByText('連絡先追加の招待')).toBeVisible()
  })

  test('CNT-INVITE-002: 有効なトークンで追加ボタンが表示される', async ({ page }) => {
    await page.route(`**/api/v1/contact-invite/${VALID_TOKEN}**`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_VALID_PREVIEW),
        })
      }
    })

    await page.goto(`/contact-invite/${VALID_TOKEN}`)
    await waitForHydration(page)

    await expect(
      page.getByRole('button', { name: /連絡先に追加する|ログインして追加する/ }),
    ).toBeVisible({
      timeout: 10_000,
    })
  })

  test('CNT-INVITE-003: 無効なトークンでエラーメッセージが表示される', async ({ page }) => {
    await page.route('**/api/v1/contact-invite/**', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_INVALID_PREVIEW),
        })
      }
    })

    await page.goto(`/contact-invite/${EXPIRED_TOKEN}`)
    await waitForHydration(page)

    await expect(page.getByText('この招待URLは無効です')).toBeVisible({ timeout: 10_000 })
  })

  test('CNT-INVITE-004: APIエラー時にエラーメッセージが表示される', async ({ page }) => {
    await page.route('**/api/v1/contact-invite/**', async (route) => {
      await route.fulfill({ status: 404 })
    })

    await page.goto('/contact-invite/nonexistent-token')
    await waitForHydration(page)

    await expect(page.getByText('この招待URLは無効です')).toBeVisible({ timeout: 10_000 })
  })
})

// 未ログイン状態テスト（別 describe で test.use を適用）
test.describe('CNT-INVITE-005: 未ログイン招待ページ', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('CNT-INVITE-005: 未ログイン状態でボタンクリックするとログインへリダイレクトされる', async ({
    page,
  }) => {
    await page.route(`**/api/v1/contact-invite/${VALID_TOKEN}**`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_VALID_PREVIEW),
        })
      }
    })

    await page.goto(`/contact-invite/${VALID_TOKEN}`)
    await waitForHydration(page)

    // ログインリダイレクト（auth middleware）か「ログインして追加する」ボタンが表示される
    const loginButton = page.getByRole('button', { name: 'ログインして追加する' })
    const loginPage = page.getByRole('heading', { name: 'ログイン' })
    await expect(loginButton.or(loginPage)).toBeVisible({ timeout: 10_000 })
  })
})
