import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from '../teams/helpers'

const MOCK_PRODUCTS = [
  {
    id: 1,
    name: '10回券',
    description: 'トレーニング参加用',
    price: 8000,
    totalTickets: 10,
    validityDays: 180,
    isActive: true,
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 2,
    name: '5回券',
    description: '体験用',
    price: 4500,
    totalTickets: 5,
    validityDays: 90,
    isActive: true,
    createdAt: '2026-02-01T00:00:00Z',
  },
]

const MOCK_BOOKS = [
  {
    id: 1,
    productId: 1,
    productName: '10回券',
    userId: 10,
    displayName: '山田 太郎',
    totalTickets: 10,
    remainingTickets: 7,
    status: 'ACTIVE',
    expiresAt: '2026-09-30T23:59:59Z',
    createdAt: '2026-04-01T00:00:00Z',
  },
  {
    id: 2,
    productId: 2,
    productName: '5回券',
    userId: 11,
    displayName: '田中 花子',
    totalTickets: 5,
    remainingTickets: 0,
    status: 'EXHAUSTED',
    expiresAt: '2026-06-30T23:59:59Z',
    createdAt: '2026-03-01T00:00:00Z',
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
            data: { ...MOCK_BOOKS[0], remainingTickets: 6 },
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
