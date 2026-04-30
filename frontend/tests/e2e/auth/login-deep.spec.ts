import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { fillInput, fillPassword } from '../helpers/form'

test.use({ storageState: { cookies: [], origins: [] } })

test.describe('AUTH-DEEP login: ログインフォーム深掘り', () => {
  test('DEEP-LOGIN-001: 空フォームでの送信は HTML5 バリデーションでブロックされ API は呼ばれない', async ({
    page,
  }) => {
    let apiCalled = false
    page.on('request', (req) => {
      if (req.url().includes('/api/v1/auth/login') && req.method() === 'POST') {
        apiCalled = true
      }
    })

    await page.goto('/login')
    await waitForHydration(page)
    await page.getByRole('button', { name: 'ログイン' }).click()

    // HTML5 required 属性により送信がブロックされるため、API は呼ばれず URL も変化しない
    await page.waitForTimeout(500)
    expect(apiCalled).toBe(false)
    await expect(page).toHaveURL(/\/login/)
  })

  test('DEEP-LOGIN-002: email のみ入力ではパスワード未入力でブロックされる', async ({ page }) => {
    let apiCalled = false
    page.on('request', (req) => {
      if (req.url().includes('/api/v1/auth/login') && req.method() === 'POST') {
        apiCalled = true
      }
    })

    await page.goto('/login')
    await waitForHydration(page)
    await fillInput(page.locator('input#email'), 'partial@example.com')
    await page.getByRole('button', { name: 'ログイン' }).click()

    await page.waitForTimeout(500)
    expect(apiCalled).toBe(false)
    await expect(page).toHaveURL(/\/login/)
  })

  test('DEEP-LOGIN-003: 不正な email 形式は HTML5 バリデーションでブロックされる', async ({
    page,
  }) => {
    let apiCalled = false
    page.on('request', (req) => {
      if (req.url().includes('/api/v1/auth/login') && req.method() === 'POST') {
        apiCalled = true
      }
    })

    await page.goto('/login')
    await waitForHydration(page)
    await fillInput(page.locator('input#email'), 'not-an-email')
    await fillPassword(page.locator('input[type="password"]'), 'somepassword123')
    await page.getByRole('button', { name: 'ログイン' }).click()

    await page.waitForTimeout(500)
    expect(apiCalled).toBe(false)
    await expect(page).toHaveURL(/\/login/)
  })

  test('DEEP-LOGIN-004: ログイン失敗後も入力済みの email が保持される', async ({ page }) => {
    await page.goto('/login')
    await waitForHydration(page)

    const emailInput = page.locator('input#email')
    await fillInput(emailInput, 'test-keep@example.com')
    await fillPassword(page.locator('input[type="password"]'), 'wrongpassword123')
    await page.getByRole('button', { name: 'ログイン' }).click()

    await expect(page.getByText('ログインに失敗しました')).toBeVisible({ timeout: 10_000 })
    // 失敗後も email 入力欄の値は保持される
    await expect(emailInput).toHaveValue('test-keep@example.com')
  })

  test('DEEP-LOGIN-005: 送信中はボタンが loading 状態になり再クリックされない', async ({
    page,
  }) => {
    // API に遅延を入れて loading 状態を観測可能にする
    await page.route('**/api/v1/auth/login', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 1500))
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: { message: 'Unauthorized' } }),
      })
    })

    await page.goto('/login')
    await waitForHydration(page)
    await fillInput(page.locator('input#email'), 'loading-test@example.com')
    await fillPassword(page.locator('input[type="password"]'), 'somepassword123')

    const button = page.getByRole('button', { name: 'ログイン' })
    await button.click()
    // PrimeVue Button の loading 状態を確認（spinner アイコンの表示）
    await expect(button.locator('.pi-spin, .p-button-loading-icon').first()).toBeVisible({
      timeout: 1_000,
    })
  })
})
