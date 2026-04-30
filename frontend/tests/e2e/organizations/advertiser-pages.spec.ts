import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

test.describe('ORG-ADV-001〜005: 組織広告主サブページ表示テスト', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
    // 広告主系APIモック
    await page.route('**/api/v1/organizations/1/advertiser/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })
  })

  test('ORG-ADV-001: 与信枠 増額申請ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/advertiser/credit-limit-requests`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: /与信枠/ })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-ADV-002: 請求書ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/advertiser/invoices`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '請求書' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-ADV-003: 料金シミュレーターページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/advertiser/rate-simulator`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '料金シミュレーター' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-ADV-004: 定期レポートページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/advertiser/report-schedules`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '定期レポート' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-ADV-005: キャンペーン詳細ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/organizations/1/advertiser/campaigns/1**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { id: 1, campaignName: 'テストキャンペーン', status: 'ACTIVE' },
        }),
      })
    })
    await page.goto(`/organizations/${ORG_ID}/advertiser/campaigns/1`)
    await waitForHydration(page)
    await expect(page.getByText(/キャンペーン/)).toBeVisible({ timeout: 10_000 })
  })
})
