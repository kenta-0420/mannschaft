import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const ORG_ID = 1

const MOCK_ACCOUNT_ACTIVE = {
  id: 1,
  orgId: ORG_ID,
  companyName: 'テスト株式会社',
  contactEmail: 'ads@test.com',
  billingMethod: 'STRIPE',
  status: 'ACTIVE',
  creditLimit: 100000,
  createdAt: '2026-01-01T00:00:00Z',
}

const MOCK_OVERVIEW = {
  activeCampaigns: 2,
  totalCampaigns: 5,
  totalImpressions: 12500,
  totalClicks: 300,
  avgCtr: 2.4,
  totalCost: 50000,
  monthlyBudgetUsedPct: 65,
  campaigns: [
    {
      campaignId: 1,
      campaignName: 'テストキャンペーン',
      status: 'ACTIVE',
      impressions: 12500,
      clicks: 300,
      ctr: 2.4,
      cost: 50000,
    },
  ],
}

test.describe('ORG-010〜013: 広告主機能', () => {
  test('ORG-010: 広告主未登録状態で登録ボタンが表示される', async ({ page }) => {
    await page.route('**/api/v1/advertiser/account**', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Not Found' }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/advertiser`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '広告主ダッシュボード' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('link', { name: '広告主登録' })).toBeVisible()
    await expect(page.getByRole('link', { name: '料金シミュレーター' })).toBeVisible()
  })

  test('ORG-011: 広告主登録フォームが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/advertiser/register`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '広告主登録' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByPlaceholder('株式会社サンプル')).toBeVisible()
    await expect(page.getByPlaceholder('ads@example.com')).toBeVisible()
    await expect(page.getByRole('button', { name: '登録申請' })).toBeVisible()
  })

  test('ORG-012: 広告主登録フォームを入力して送信できる', async ({ page }) => {
    await page.route('**/api/v1/advertiser/register**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ACCOUNT_ACTIVE }),
      })
    })
    // リダイレクト先でも404にならないようモック
    await page.route('**/api/v1/advertiser/account**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ACCOUNT_ACTIVE }),
      })
    })
    await page.route('**/api/v1/advertiser/overview**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_OVERVIEW }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/advertiser/register`)
    await waitForHydration(page)

    const companyInput = page.getByPlaceholder('株式会社サンプル')
    await companyInput.click()
    await companyInput.pressSequentially('テスト株式会社', { delay: 10 })

    const emailInput = page.getByPlaceholder('ads@example.com')
    await emailInput.click()
    await emailInput.pressSequentially('ads@test.com', { delay: 10 })

    await page.getByRole('button', { name: '登録申請' }).click()

    // 登録後は /advertiser へリダイレクト
    await page.waitForURL(`**/organizations/${ORG_ID}/advertiser`, { timeout: 10_000 })
  })

  test('ORG-013: 広告主登録済み状態でダッシュボードにKPIが表示される', async ({ page }) => {
    await page.route('**/api/v1/advertiser/account**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ACCOUNT_ACTIVE }),
      })
    })
    await page.route('**/api/v1/advertiser/overview**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_OVERVIEW }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/advertiser`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '広告主ダッシュボード' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('テスト株式会社')).toBeVisible()
    // KPIカード
    await expect(page.getByText('キャンペーン').first()).toBeVisible()
    await expect(page.getByText('インプレッション')).toBeVisible()
    await expect(page.getByText('広告費（税抜）')).toBeVisible()
    // ナビゲーション
    await expect(page.getByText('請求書')).toBeVisible()
    await expect(page.getByText('料金シミュレーター')).toBeVisible()
  })
})
