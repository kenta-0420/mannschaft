import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

test.describe('ORG-FEAT-014〜017: 組織イベント', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
    await page.route(`**/api/v1/organizations/${ORG_ID}/events**`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              { id: 1, title: 'テストイベント', startAt: '2026-05-01T10:00:00Z', endAt: '2026-05-01T18:00:00Z', location: '東京', participantCount: 3 },
            ],
            meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
          }),
        })
      } else {
        await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ data: { id: 2 } }) })
      }
    })
  })

  test('ORG-FEAT-014: イベント一覧ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/events`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'イベント' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-015: イベントが一覧に表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/events`)
    await waitForHydration(page)
    await expect(page.getByText('テストイベント')).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-016: イベント作成ボタンが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/events`)
    await waitForHydration(page)
    const btn = page.getByRole('button', { name: /作成|追加|新規/ })
    const count = await btn.count()
    expect(count).toBeGreaterThan(0)
  })

  test('ORG-FEAT-017: イベント詳細画面が表示される', async ({ page }) => {
    await page.route(`**/api/v1/organizations/${ORG_ID}/events/1**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { id: 1, title: 'テストイベント', startAt: '2026-05-01T10:00:00Z', endAt: '2026-05-01T18:00:00Z', location: '東京', description: 'テスト説明' },
        }),
      })
    })
    await page.goto(`/organizations/${ORG_ID}/events/1`)
    await waitForHydration(page)
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })
  })
})
