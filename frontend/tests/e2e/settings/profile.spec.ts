import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_PROFILE = {
  data: {
    id: 1,
    displayName: 'テストユーザー',
    email: 'test@example.com',
    phoneNumber: '090-1234-5678',
    avatarUrl: null,
    hasPassword: true,
  },
}

test.describe('SET-001〜003: プロフィール設定', () => {
  test('SET-001: プロフィール設定ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PROFILE),
      })
    })

    await page.goto('/settings/profile')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'プロフィール設定' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SET-002: プロフィール情報フォームが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PROFILE),
      })
    })

    await page.goto('/settings/profile')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'プロフィール設定' })).toBeVisible({
      timeout: 10_000,
    })
    // プロフィール情報セクションとフォームフィールドが表示される
    await expect(page.getByText('プロフィール情報')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByRole('button', { name: '保存' }).first()).toBeVisible()
  })

  test('SET-003: アバターアップロードボタンが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PROFILE),
      })
    })

    await page.goto('/settings/profile')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'プロフィール設定' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('画像を変更')).toBeVisible({ timeout: 5_000 })
  })
})
