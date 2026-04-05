import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

test.describe('ORG-FEAT-022〜025: 組織フォーム', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
    await page.route(`**/api/v1/organizations/${ORG_ID}/forms**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [{ id: 1, title: 'テストフォーム', status: 'PUBLISHED', responseCount: 3 }],
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    })
  })

  test('ORG-FEAT-022: フォーム一覧が表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/forms`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'フォーム' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-023: フォームが一覧に表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/forms`)
    await waitForHydration(page)
    await expect(page.getByText('テストフォーム')).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-024: フォーム作成ボタンが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/forms`)
    await waitForHydration(page)
    const btn = page.getByRole('button', { name: /作成|追加|新規/ })
    const count = await btn.count()
    expect(count).toBeGreaterThan(0)
  })

  test('ORG-FEAT-025: フォームテンプレートページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/forms/templates`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'フォームテンプレート' })).toBeVisible({ timeout: 10_000 })
  })
})
