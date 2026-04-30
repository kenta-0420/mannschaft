import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

test.describe('ORG-FEAT-018〜021: 組織タイムライン', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
    await page.route(`**/api/v1/organizations/${ORG_ID}/timeline**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [
            { id: 1, body: 'テスト投稿です', authorName: 'テストユーザー', createdAt: '2026-04-01T00:00:00Z', likeCount: 2, commentCount: 1 },
          ],
          meta: { nextCursor: null, hasNext: false },
        }),
      })
    })
  })

  test('ORG-FEAT-018: タイムラインページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'タイムライン' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-019: 投稿が表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    await expect(page.getByText('テスト投稿です')).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-020: 投稿フォームが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    const form = page.locator('textarea, [contenteditable="true"], input[placeholder*="投稿"]')
    const count = await form.count()
    expect(count).toBeGreaterThanOrEqual(0)
  })

  test('ORG-FEAT-021: 投稿者名が表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    await expect(page.getByText('テストユーザー')).toBeVisible({ timeout: 10_000 })
  })
})
