import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.describe('SYSADMIN-006: システム管理者 追加テスト', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/system-admin/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
  })

  test('SYSADMIN-006: ビジネス分析ページが表示される', async ({ page }) => {
    await page.goto('/system-admin/analytics')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ビジネス分析' })).toBeVisible({ timeout: 10_000 })
  })
})
