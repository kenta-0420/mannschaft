import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.describe('ADMIN-001: 管理ダッシュボード', () => {
  test('ADMIN-001: 管理者ダッシュボードページが表示される', async ({ page }) => {
    await page.route('**/api/v1/system-admin/dashboard**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: {} }),
      })
    })
    await page.route('**/api/v1/teams/**modules**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/organizations/**modules**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/admin/dashboard')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '管理者ダッシュボード' })).toBeVisible({
      timeout: 10_000,
    })
  })
})
