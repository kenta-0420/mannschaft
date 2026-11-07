import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

const MOCK_PROFILE = {
  data: {
    id: 1,
    email: 'test@example.com',
    displayName: 'テストユーザー',
    profileImageUrl: null,
    locale: 'ja',
    timezone: 'Asia/Tokyo',
  },
}

test.describe('I18N-001〜003: 多言語対応', () => {
  test('I18N-001: ログインページがデフォルト日本語で表示される', async ({ page }) => {
    await page.goto('/login')
    await waitForHydration(page)

    await expect(page.locator('label[for="email"]')).toHaveText('メールアドレス', {
      timeout: 5_000,
    })
    await expect(page.getByRole('button', { name: 'ログイン' })).toBeVisible()
  })

  test('I18N-002: 言語設定ページに全言語オプションが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PROFILE),
      })
    })

    await page.goto('/settings/language')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '言語・タイムゾーン' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('表示言語')).toBeVisible({ timeout: 5_000 })
  })

  test('I18N-003: 言語切替保存ボタンが表示され操作できる', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PROFILE),
      })
    })

    await page.goto('/settings/language')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '言語・タイムゾーン' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: '保存' })).toBeVisible()
    await expect(page.getByRole('button', { name: '保存' })).toBeEnabled()
  })

  test('I18N-004: 英語CookieをSSR・hydrate・reload後も同一URLで保持する', async ({ page, baseURL }) => {
    if (!baseURL) throw new Error('locale Cookie試験には既存baseURLが必要です')
    const failures: string[] = []
    page.on('pageerror', (error) => failures.push(error.message))
    page.on('console', (message) => {
      if (['error', 'warning'].includes(message.type()) && /i18n|locale|hydration/i.test(message.text())) {
        failures.push(message.text())
      }
    })
    await page.context().addCookies([
      { name: 'i18n_locale', value: 'en', url: new URL('/', baseURL).href, sameSite: 'Lax' },
    ])

    const response = await page.goto('/login')
    if (!response) throw new Error('ログイン画面のSSR responseがありません')
    expect(response.status()).toBe(200)
    expect(await response.text()).toMatch(/<label\b[^>]*\bfor="email"[^>]*>\s*Email address\s*<\/label>/)
    await waitForHydration(page)
    await expect(page.locator('label[for="email"]')).toHaveText('Email address')
    await expect(page.getByRole('button', { name: 'Login', exact: true })).toBeVisible()
    expect(new URL(page.url()).pathname).toBe('/login')

    await page.reload()
    await waitForHydration(page)
    await expect(page.locator('label[for="email"]')).toHaveText('Email address')
    expect(new URL(page.url()).pathname).toBe('/login')
    const localeCookies = (await page.context().cookies()).filter((cookie) => cookie.name === 'i18n_locale')
    expect(localeCookies.map((cookie) => cookie.value)).toEqual(['en'])
    expect(failures).toEqual([])
  })
})
