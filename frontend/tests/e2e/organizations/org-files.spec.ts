import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

test.describe('ORG-FEAT-026〜029: 組織ファイル共有', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
    await page.route(`**/api/v1/organizations/${ORG_ID}/files**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [{ id: 1, name: 'テストファイル.pdf', size: 1024, mimeType: 'application/pdf', uploadedBy: 'テストユーザー', createdAt: '2026-04-01T00:00:00Z' }],
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    })
  })

  test('ORG-FEAT-026: ファイル共有ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/files`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ファイル共有' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-027: ファイルが一覧に表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/files`)
    await waitForHydration(page)
    await expect(page.getByText('テストファイル.pdf')).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-028: アップロードボタンが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/files`)
    await waitForHydration(page)
    const btn = page.getByRole('button', { name: /アップロード|追加|新規|フォルダ/ })
    await expect(btn.first()).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-029: ファイル一覧にファイル名が表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/files`)
    await waitForHydration(page)
    await expect(page.getByText('テストファイル')).toBeVisible({ timeout: 10_000 })
  })
})
