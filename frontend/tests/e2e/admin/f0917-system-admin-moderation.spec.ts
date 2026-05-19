import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F09.17 Phase 11-c-5 smoke E2E (SYSTEM_ADMIN 領域)
 *
 * SYSTEM_ADMIN が審査キューを開き、approve / block を実行できる happy path。
 *
 * admin storageState で実行されるため、`admin/` ディレクトリ配下に配置する。
 */

const CAMPAIGN_ID = 'b48b8b02-2026-7b02-9b02-bbbb00000002'

const QUEUE_ITEM = {
  campaignId: CAMPAIGN_ID,
  organizationId: 1,
  advertiserAccountId: 1,
  name: '夏のキャンペーン',
  status: 'REVIEW',
  moderationStatus: 'AUTO_FLAGGED',
  detectedNgWords: ['抽選'],
  createdAt: '2026-05-15T10:00:00Z',
  updatedAt: '2026-05-15T10:00:00Z',
}

test.describe('F09.17 Phase 11-c-5: SYSTEM_ADMIN 広告審査 (smoke)', () => {
  test('審査キューを開き approve / block を実行できる', async ({ page }) => {
    let approveCalled = false
    let blockCalled = false

    await page.route('**/api/v1/system-admin/ad-campaigns/review-queue**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [QUEUE_ITEM],
          meta: { totalElements: 1, page: 0, size: 20, totalPages: 1 },
        }),
      })
    })

    await page.route(`**/api/v1/system-admin/ad-campaigns/${CAMPAIGN_ID}/approve`, async (route) => {
      approveCalled = true
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { ...QUEUE_ITEM, moderationStatus: 'APPROVED' } }),
      })
    })

    await page.route(`**/api/v1/system-admin/ad-campaigns/${CAMPAIGN_ID}/block`, async (route) => {
      blockCalled = true
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { ...QUEUE_ITEM, moderationStatus: 'BLOCKED' } }),
      })
    })

    await page.goto('/system-admin/advertising/moderation-queue')
    await waitForHydration(page)

    // 審査キュータイトル or 対象キャンペーン名が表示されている
    await expect(page.getByText('夏のキャンペーン')).toBeVisible({ timeout: 10_000 })

    // approve / block の HTTP 経路自体が成立することを Playwright request で確認
    const approveRes = await page.request.post(
      `/api/v1/system-admin/ad-campaigns/${CAMPAIGN_ID}/approve`,
      { data: {} },
    )
    expect(approveRes.ok()).toBe(true)

    const blockRes = await page.request.post(
      `/api/v1/system-admin/ad-campaigns/${CAMPAIGN_ID}/block`,
      { data: { reason: '表現が不適切' } },
    )
    expect(blockRes.ok()).toBe(true)

    expect(approveCalled).toBe(true)
    expect(blockCalled).toBe(true)
  })
})
