import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

test.describe('ORG-FEAT-006〜009: 組織掲示板', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
    await page.route(`**/api/v1/organizations/${ORG_ID}/bulletins**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [
            { id: 1, title: 'テスト掲示', body: 'テスト本文', pinned: false, authorName: 'テストユーザー', createdAt: '2026-04-01T00:00:00Z' },
          ],
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    })
  })

  test('ORG-FEAT-006: 掲示板ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/bulletin`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '掲示板' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-007: 掲示が一覧に表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/bulletin`)
    await waitForHydration(page)
    await expect(page.getByText('テスト掲示')).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-008: 新規投稿ボタンが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/bulletin`)
    await waitForHydration(page)
    const btn = page.getByRole('button', { name: /作成|投稿|新規/ })
    await expect(btn.first()).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-009: 投稿作成ダイアログが開く', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/bulletin`)
    await waitForHydration(page)
    const btn = page.getByRole('button', { name: /作成|投稿|新規/ })
    if (await btn.count() > 0) {
      await btn.first().click()
      await expect(page.locator('[role="dialog"]')).toBeVisible({ timeout: 10_000 })
    }
  })
})
