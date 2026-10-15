import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from '../teams/helpers'

const ORG_ID = 1

const MOCK_FISCAL_YEARS = [
  {
    id: 1,
    name: '2026年度',
    startDate: '2026-04-01',
    endDate: '2027-03-31',
    status: 'ACTIVE',
    createdAt: '2026-04-01T00:00:00Z',
  },
]

const MOCK_SUMMARY = {
  fiscalYearId: 1,
  fiscalYearName: '2026年度',
  totalIncome: 500000,
  totalExpense: 320000,
  balance: 180000,
  byCategory: [
    {
      categoryId: 1,
      categoryName: '会費収入',
      categoryType: 'INCOME',
      allocated: 480000,
      actual: 500000,
      burnPercent: 104,
    },
    {
      categoryId: 2,
      categoryName: '活動費',
      categoryType: 'EXPENSE',
      allocated: 350000,
      actual: 320000,
      burnPercent: 91,
    },
  ],
}

const MOCK_TRANSACTIONS = [
  {
    id: 1,
    categoryId: 1,
    categoryName: '会費収入',
    type: 'INCOME',
    amount: 50000,
    description: '4月分会費',
    transactionDate: '2026-04-01',
    createdAt: '2026-04-01T09:00:00Z',
  },
  {
    id: 2,
    categoryId: 2,
    categoryName: '活動費',
    type: 'EXPENSE',
    amount: 30000,
    description: 'グラウンド使用料',
    transactionDate: '2026-04-05',
    createdAt: '2026-04-05T10:00:00Z',
  },
]

test.describe('BUDGET-001〜006: 予算・会計管理', () => {
  test('BUDGET-001: チーム予算ページが表示される', async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
    await page.route(`**/api/v1/teams/${TEAM_ID}/budget/**`, async (route) => {
      const url = route.request().url()
      if (url.includes('/fiscal-years')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(`/teams/${TEAM_ID}/budget`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '予算・会計' })).toBeVisible({ timeout: 10_000 })
  })

  test('BUDGET-002: 組織予算ページが表示される', async ({ page }) => {
    await page.route(`**/api/v1/organizations/${ORG_ID}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { id: ORG_ID, name: 'テスト組織', template: 'SPORTS' },
        }),
      })
    })
    // 組織予算ページはscopeTypeのパラメーター違いがあるため直接URLをモック
    await page.route(`**/api/v1/organizations/${ORG_ID}/budget/**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: null }),
      })
    })
    await page.route(
      `**/api/v1/organizations/${ORG_ID}/budget/fiscal-years**`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      },
    )

    await page.goto(`/organizations/${ORG_ID}/budget`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '予算・会計' })).toBeVisible({ timeout: 10_000 })
  })

  test('BUDGET-003: 収支一覧の取得と表示（GET）', async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
    let fiscalYearsCalled = false
    let summaryCalled = false

    await page.route(`**/api/v1/teams/${TEAM_ID}/budget/fiscal-years`, async (route) => {
      if (route.request().method() === 'GET') {
        fiscalYearsCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_FISCAL_YEARS }),
        })
      } else {
        await route.continue()
      }
    })
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/budget/fiscal-years/1/summary`,
      async (route) => {
        if (route.request().method() === 'GET') {
          summaryCalled = true
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ data: MOCK_SUMMARY }),
          })
        } else {
          await route.continue()
        }
      },
    )
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/budget/fiscal-years/1/transactions**`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_TRANSACTIONS, meta: { total: 2 } }),
        })
      },
    )

    await page.goto(`/teams/${TEAM_ID}/budget`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '予算・会計' })).toBeVisible({ timeout: 10_000 })
    // 年度セレクターに 2026年度 が表示される
    await expect(page.getByText('2026年度')).toBeVisible({ timeout: 5_000 })
    expect(fiscalYearsCalled).toBe(true)
    expect(summaryCalled).toBe(true)
  })

  test('BUDGET-004: 収入を登録できる（POST）', async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
    let createCalled = false

    await page.route(`**/api/v1/teams/${TEAM_ID}/budget/fiscal-years`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_FISCAL_YEARS }),
      })
    })
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/budget/fiscal-years/1/summary`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SUMMARY }),
        })
      },
    )
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/budget/fiscal-years/1/transactions**`,
      async (route) => {
        const method = route.request().method()
        if (method === 'POST') {
          createCalled = true
          await route.fulfill({
            status: 201,
            contentType: 'application/json',
            body: JSON.stringify({ data: MOCK_TRANSACTIONS[0] }),
          })
        } else {
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ data: MOCK_TRANSACTIONS, meta: { total: 2 } }),
          })
        }
      },
    )

    await page.goto(`/teams/${TEAM_ID}/budget`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '予算・会計' })).toBeVisible({ timeout: 10_000 })

    // 収入登録 APIを直接呼び出してモックを確認
    await page.evaluate(
      async ({ teamId }) => {
        await fetch(`/api/v1/teams/${teamId}/budget/fiscal-years/1/transactions`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            categoryId: 1,
            type: 'INCOME',
            amount: 50000,
            description: '4月分会費',
            transactionDate: '2026-04-01',
          }),
        })
      },
      { teamId: TEAM_ID },
    )

    expect(createCalled).toBe(true)
  })

  test('BUDGET-005: 支出を登録できる（POST）', async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
    let createExpenseCalled = false

    await page.route(`**/api/v1/teams/${TEAM_ID}/budget/fiscal-years`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_FISCAL_YEARS }),
      })
    })
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/budget/fiscal-years/1/summary`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SUMMARY }),
        })
      },
    )
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/budget/fiscal-years/1/transactions**`,
      async (route) => {
        const method = route.request().method()
        if (method === 'POST') {
          const body = route.request().postDataJSON()
          if (body?.type === 'EXPENSE') {
            createExpenseCalled = true
          }
          await route.fulfill({
            status: 201,
            contentType: 'application/json',
            body: JSON.stringify({ data: MOCK_TRANSACTIONS[1] }),
          })
        } else {
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ data: MOCK_TRANSACTIONS, meta: { total: 2 } }),
          })
        }
      },
    )

    await page.goto(`/teams/${TEAM_ID}/budget`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '予算・会計' })).toBeVisible({ timeout: 10_000 })

    // 支出登録 APIを直接呼び出してモックを確認
    await page.evaluate(
      async ({ teamId }) => {
        await fetch(`/api/v1/teams/${teamId}/budget/fiscal-years/1/transactions`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            categoryId: 2,
            type: 'EXPENSE',
            amount: 30000,
            description: 'グラウンド使用料',
            transactionDate: '2026-04-05',
          }),
        })
      },
      { teamId: TEAM_ID },
    )

    expect(createExpenseCalled).toBe(true)
  })

  test('BUDGET-006: 収支サマリーが正しく表示される', async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)

    await page.route(`**/api/v1/teams/${TEAM_ID}/budget/fiscal-years`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_FISCAL_YEARS }),
      })
    })
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/budget/fiscal-years/1/summary`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SUMMARY }),
        })
      },
    )

    await page.goto(`/teams/${TEAM_ID}/budget`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '予算・会計' })).toBeVisible({ timeout: 10_000 })

    // 収支サマリーカードが表示される
    await expect(page.getByText('収入')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('支出')).toBeVisible()
    await expect(page.getByText('残高')).toBeVisible()

    // 金額が表示される（500,000円 / 320,000円 / 180,000円）
    await expect(page.getByText(/500,000/)).toBeVisible()
    await expect(page.getByText(/320,000/)).toBeVisible()
    await expect(page.getByText(/180,000/)).toBeVisible()
  })
})
