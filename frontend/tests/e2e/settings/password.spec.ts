import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_PROFILE = {
  data: {
    id: 1,
    displayName: 'テストユーザー',
    email: 'test@example.com',
    hasPassword: true,
  },
}

test.describe('SET-004〜005: パスワード変更', () => {
  test('SET-004: パスワード変更ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PROFILE),
      })
    })

    await page.goto('/settings/password')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'パスワード変更' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('現在のパスワード')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('新しいパスワード').first()).toBeVisible()
  })

  test('SET-005: パスワードバリデーションエラーが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PROFILE),
      })
    })

    await page.goto('/settings/password')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'パスワード変更' })).toBeVisible({
      timeout: 10_000,
    })

    // 短いパスワードを入力してバリデーションエラーを確認
    const newPassInputs = page.locator('input[type="password"]')
    await newPassInputs.nth(1).fill('short')
    await newPassInputs.nth(2).fill('other')

    await expect(page.getByText('パスワードは8文字以上で入力してください')).toBeVisible({
      timeout: 3_000,
    })
  })
})
