import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

test.describe('ORG-FEAT-001〜005: 組織スケジュール', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
    await page.route(`**/api/v1/organizations/${ORG_ID}/schedules**`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              { id: 1, title: 'テスト予定', startAt: '2026-04-10T10:00:00Z', endAt: '2026-04-10T12:00:00Z', allDay: false },
            ],
            meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
          }),
        })
      } else {
        await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ data: { id: 2 } }) })
      }
    })
  })

  test('ORG-FEAT-001: スケジュール一覧が表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/schedule`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'スケジュール' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-002: 予定が一覧に表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/schedule`)
    await waitForHydration(page)
    await expect(page.getByText('テスト予定')).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-003: 新規予定作成ボタンが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/schedule`)
    await waitForHydration(page)
    const btn = page.getByRole('button', { name: /作成|追加|新規/ })
    const count = await btn.count()
    expect(count).toBeGreaterThan(0)
  })

  test('ORG-FEAT-004: 予定作成ボタンをクリックするとダイアログが開く', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/schedule`)
    await waitForHydration(page)
    const btn = page.getByRole('button', { name: /作成|追加|新規/ })
    if (await btn.count() > 0) {
      await btn.first().click()
      await expect(page.locator('[role="dialog"]')).toBeVisible({ timeout: 10_000 })
    }
  })

  test('ORG-FEAT-005: スケジュールページにカレンダーまたはリストが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/schedule`)
    await waitForHydration(page)
    // カレンダーまたはリストビューが表示されること
    const content = page.locator('.fc, [class*="calendar"], [class*="schedule"], table, [role="grid"]')
    await expect(content.first()).toBeVisible({ timeout: 10_000 })
  })
})
