import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_USERS = {
  data: [
    {
      id: 1,
      email: 'user1@example.com',
      displayName: 'テストユーザー1',
      role: 'MEMBER',
      createdAt: '2026-01-01T00:00:00Z',
      yabai: false,
    },
  ],
}

test.describe('ADMIN-002〜003: ユーザー管理', () => {
  test('ADMIN-002: ユーザー管理ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/system-admin/dashboard/users**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_USERS),
      })
    })

    await page.goto('/admin/users')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'ユーザー管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-003: ユーザー一覧が表示される', async ({ page }) => {
    await page.route('**/api/v1/system-admin/dashboard/users**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_USERS),
      })
    })

    await page.goto('/admin/users')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'ユーザー管理' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('user1@example.com')).toBeVisible({ timeout: 5_000 })
  })
})
