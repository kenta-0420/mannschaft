import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

test.describe('ORG-FEAT-038〜041: 組織議決権行使', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
    await page.route(`**/api/v1/organizations/${ORG_ID}/proxy-votes**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [{ id: 1, title: 'テスト議案', status: 'OPEN', voteCount: 5, createdAt: '2026-04-01T00:00:00Z' }],
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    })
  })

  test('ORG-FEAT-038: 議決権行使ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/voting`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '議決権行使' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-039: 議案が一覧に表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/voting`)
    await waitForHydration(page)
    await expect(page.getByText('テスト議案')).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-040: 議案作成ボタンが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/voting`)
    await waitForHydration(page)
    const btn = page.getByRole('button', { name: /作成|追加|新規/ })
    const count = await btn.count()
    expect(count).toBeGreaterThan(0)
  })

  test('ORG-FEAT-041: 議案作成ダイアログが開く', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/voting`)
    await waitForHydration(page)
    const btn = page.getByRole('button', { name: /作成|追加|新規/ })
    if (await btn.count() > 0) {
      await btn.first().click()
      await expect(page.locator('[role="dialog"]')).toBeVisible({ timeout: 10_000 })
    }
  })
})
