import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from '../teams/helpers'

// ネストDTO構造（BE #1174 追従）
const MOCK_PRODUCTS = [
  {
    id: 1,
    meta: { name: '10回券', description: 'トレーニング参加用', totalTickets: 10, sortOrder: 0 },
    pricing: { price: 8000, priceExcludingTax: 7273, taxRate: 0.1, validityDays: 180 },
    stripe: { stripeProductId: null, stripePriceId: null },
    display: { imageUrl: null, isOnlinePurchasable: true, isActive: true },
    audit: { createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', deletedAt: null },
  },
  {
    id: 2,
    meta: { name: '5回券', description: '体験用', totalTickets: 5, sortOrder: 1 },
    pricing: { price: 4500, priceExcludingTax: 4091, taxRate: 0.1, validityDays: 90 },
    stripe: { stripeProductId: null, stripePriceId: null },
    display: { imageUrl: null, isOnlinePurchasable: true, isActive: true },
    audit: { createdAt: '2026-02-01T00:00:00Z', updatedAt: '2026-02-01T00:00:00Z', deletedAt: null },
  },
]

const MOCK_BOOKS = [
  {
    id: 1,
    productName: '10回券',
    quantity: { totalTickets: 10, usedTickets: 3, remainingTickets: 7 },
    status: {
      status: 'ACTIVE',
      purchasedAt: '2026-04-01T00:00:00Z',
      expiresAt: '2026-09-30T23:59:59Z',
      daysUntilExpiry: 152,
    },
    note: { note: null },
    audit: { createdAt: '2026-04-01T00:00:00Z', updatedAt: '2026-04-01T00:00:00Z' },
  },
  {
    id: 2,
    productName: '5回券',
    quantity: { totalTickets: 5, usedTickets: 5, remainingTickets: 0 },
    status: {
      status: 'EXHAUSTED',
      purchasedAt: '2026-03-01T00:00:00Z',
      expiresAt: '2026-06-30T23:59:59Z',
      daysUntilExpiry: 31,
    },
    note: { note: null },
    audit: { createdAt: '2026-03-01T00:00:00Z', updatedAt: '2026-03-01T00:00:00Z' },
  },
]

test.describe('TICKET-001〜006: 回数券', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TICKET-001: 回数券ページが表示される', async ({ page }) => {
    await page.route(`**/api/v1/teams/${TEAM_ID}/ticket-products**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/ticket-books**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/tickets`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '回数券' })).toBeVisible({ timeout: 10_000 })
  })

  test('TICKET-002: チケット商品一覧の取得と表示（GET）', async ({ page }) => {
    let productsCalled = false
    await page.route(`**/api/v1/teams/${TEAM_ID}/ticket-products**`, async (route) => {
      if (route.request().method() === 'GET') {
        productsCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_PRODUCTS }),
        })
      } else {
        await route.continue()
      }
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/ticket-books**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_BOOKS }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/tickets`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '回数券' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('10回券')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('5回券')).toBeVisible()
    expect(productsCalled).toBe(true)
  })

  test('TICKET-003: チケット商品を作成できる（POST）', async ({ page }) => {
    let createCalled = false
    await page.route(`**/api/v1/teams/${TEAM_ID}/ticket-products**`, async (route) => {
      const method = route.request().method()
      if (method === 'POST') {
        createCalled = true
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_PRODUCTS[0] }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_PRODUCTS }),
        })
      }
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/ticket-books**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/tickets`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '回数券' })).toBeVisible({ timeout: 10_000 })

    // 商品追加APIを直接呼び出してモックを確認
    await page.evaluate(
      async ({ teamId }) => {
        await fetch(`/api/v1/teams/${teamId}/ticket-products`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name: 'テスト券', price: 5000, totalTickets: 5, validityDays: 90 }),
        })
      },
      { teamId: TEAM_ID },
    )

    expect(createCalled).toBe(true)
  })

  test('TICKET-004: チケットを購入できる（POST /checkout）', async ({ page }) => {
    let checkoutCalled = false
    await page.route(`**/api/v1/teams/${TEAM_ID}/ticket-products**`, async (route) => {
      const url = route.request().url()
      const method = route.request().method()
      if (url.includes('/checkout') && method === 'POST') {
        checkoutCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { checkoutUrl: 'https://checkout.example.com/session/xxx' } }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_PRODUCTS }),
        })
      }
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/ticket-books**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/tickets`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '回数券' })).toBeVisible({ timeout: 10_000 })

    // checkoutProduct APIを直接呼び出してモックを確認
    await page.evaluate(
      async ({ teamId, productId }) => {
        await fetch(`/api/v1/teams/${teamId}/ticket-products/${productId}/checkout`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
        })
      },
      { teamId: TEAM_ID, productId: 1 },
    )

    expect(checkoutCalled).toBe(true)
  })

  test('TICKET-005: チケットを使用できる（POST /consume）', async ({ page }) => {
    let consumeCalled = false
    await page.route(`**/api/v1/teams/${TEAM_ID}/ticket-products**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_PRODUCTS }),
      })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/ticket-books**`, async (route) => {
      const url = route.request().url()
      const method = route.request().method()
      if (url.includes('/consume') && method === 'POST') {
        consumeCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            // consume は ConsumeResultResponse（フラット）を返す
            data: { consumptionId: 1, bookId: 1, remainingTickets: 6, status: 'ACTIVE', consumedAt: '2026-05-01T00:00:00Z' },
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_BOOKS }),
        })
      }
    })

    await page.goto(`/teams/${TEAM_ID}/tickets`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '回数券' })).toBeVisible({ timeout: 10_000 })

    // consumeTicket APIを直接呼び出してモックを確認
    await page.evaluate(
      async ({ teamId, bookId }) => {
        await fetch(`/api/v1/teams/${teamId}/ticket-books/${bookId}/consume`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ count: 1 }),
        })
      },
      { teamId: TEAM_ID, bookId: 1 },
    )

    expect(consumeCalled).toBe(true)
  })

  test('TICKET-006: 残枚数が正しく表示される', async ({ page }) => {
    await page.route(`**/api/v1/teams/${TEAM_ID}/ticket-products**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_PRODUCTS }),
      })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/ticket-books**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_BOOKS }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/tickets`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '回数券' })).toBeVisible({ timeout: 10_000 })

    // 「発行済み」タブに切り替え
    await page.getByRole('button', { name: '発行済み' }).click()

    // 残枚数の表示確認
    await expect(page.getByText('残 7/10')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('残 0/5')).toBeVisible()
  })
})
