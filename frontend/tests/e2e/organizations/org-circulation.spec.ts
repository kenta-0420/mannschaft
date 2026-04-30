import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

test.describe('ORG-FEAT-034〜037: 組織回覧板', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
    await page.route(`**/api/v1/organizations/${ORG_ID}/circulations**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [{ id: 1, title: 'テスト回覧', status: 'IN_PROGRESS', confirmedCount: 3, totalCount: 10, createdAt: '2026-04-01T00:00:00Z' }],
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    })
  })

  test('ORG-FEAT-034: 回覧板ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/circulation`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '回覧板' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-035: 回覧が一覧に表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/circulation`)
    await waitForHydration(page)
    await expect(page.getByText('テスト回覧')).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-036: 回覧作成ボタンが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/circulation`)
    await waitForHydration(page)
    const btn = page.getByRole('button', { name: /作成|追加|新規/ })
    await expect(btn.first()).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-037: 回覧作成ダイアログが開く', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/circulation`)
    await waitForHydration(page)
    const btn = page.getByRole('button', { name: /作成|追加|新規/ })
    if (await btn.count() > 0) {
      await btn.first().click()
      await expect(page.locator('[role="dialog"]')).toBeVisible({ timeout: 10_000 })
    }
  })
})
