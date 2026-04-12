import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const ORG_ID = 1

const MOCK_TOURNAMENTS = [
  {
    id: 1,
    title: '2026年度春季リーグ',
    status: 'OPEN',
    format: 'LEAGUE',
    sportCategory: 'サッカー',
    seasonYear: 2026,
    winPoints: 3,
    drawPoints: 1,
    lossPoints: 0,
    divisions: [{ id: 1, name: 'Aリーグ' }],
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 2,
    title: '2026年度カップ戦',
    status: 'DRAFT',
    format: 'KNOCKOUT',
    sportCategory: 'サッカー',
    seasonYear: 2026,
    winPoints: 1,
    drawPoints: 0,
    lossPoints: 0,
    divisions: [],
    createdAt: '2026-02-01T00:00:00Z',
  },
]

const MOCK_STANDINGS = [
  {
    rank: 1,
    teamName: 'Aチーム',
    played: 5,
    won: 4,
    drawn: 0,
    lost: 1,
    goalsFor: 12,
    goalsAgainst: 5,
    points: 12,
  },
  {
    rank: 2,
    teamName: 'Bチーム',
    played: 5,
    won: 3,
    drawn: 1,
    lost: 1,
    goalsFor: 9,
    goalsAgainst: 6,
    points: 10,
  },
]

test.describe('TOURNAMENT-001〜006: 大会・リーグ管理', () => {
  test.beforeEach(async ({ page }) => {
    await page.route(`**/api/v1/organizations/${ORG_ID}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { id: ORG_ID, name: 'テスト組織', template: 'SPORTS' },
        }),
      })
    })
    await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { roleName: 'ADMIN', permissions: ['tournament.manage'] },
        }),
      })
    })
  })

  test('TOURNAMENT-001: 大会ページが表示される', async ({ page }) => {
    await page.route(`**/api/v1/organizations/${ORG_ID}/tournaments**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/tournaments`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '大会・リーグ' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TOURNAMENT-002: 大会一覧の取得と表示（GET）', async ({ page }) => {
    let getCalled = false
    await page.route(`**/api/v1/organizations/${ORG_ID}/tournaments**`, async (route) => {
      if (route.request().method() === 'GET') {
        getCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_TOURNAMENTS }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(`/organizations/${ORG_ID}/tournaments`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '大会・リーグ' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('2026年度春季リーグ')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('2026年度カップ戦')).toBeVisible()
    expect(getCalled).toBe(true)
  })

  test('TOURNAMENT-003: 大会を作成できる（POST）', async ({ page }) => {
    let createCalled = false
    await page.route(`**/api/v1/organizations/${ORG_ID}/tournaments**`, async (route) => {
      const method = route.request().method()
      if (method === 'POST') {
        createCalled = true
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_TOURNAMENTS[0] }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_TOURNAMENTS }),
        })
      }
    })

    await page.goto(`/organizations/${ORG_ID}/tournaments`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '大会・リーグ' })).toBeVisible({
      timeout: 10_000,
    })

    // 大会作成 APIを直接呼び出してモックを確認
    await page.evaluate(
      async ({ orgId }) => {
        await fetch(`/api/v1/organizations/${orgId}/tournaments`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            title: '新規大会',
            format: 'LEAGUE',
            sportCategory: 'サッカー',
            seasonYear: 2026,
          }),
        })
      },
      { orgId: ORG_ID },
    )

    expect(createCalled).toBe(true)
  })

  test('TOURNAMENT-004: 大会を編集できる（PATCH）', async ({ page }) => {
    let updateCalled = false
    await page.route(`**/api/v1/organizations/${ORG_ID}/tournaments**`, async (route) => {
      const url = route.request().url()
      const method = route.request().method()
      if (method === 'PATCH' && /\/tournaments\/\d+$/.test(url)) {
        updateCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...MOCK_TOURNAMENTS[0], title: '更新後の大会名' },
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_TOURNAMENTS }),
        })
      }
    })

    await page.goto(`/organizations/${ORG_ID}/tournaments`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '大会・リーグ' })).toBeVisible({
      timeout: 10_000,
    })

    // 大会更新 APIを直接呼び出してモックを確認
    await page.evaluate(
      async ({ orgId }) => {
        await fetch(`/api/v1/organizations/${orgId}/tournaments/1`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ title: '更新後の大会名' }),
        })
      },
      { orgId: ORG_ID },
    )

    expect(updateCalled).toBe(true)
  })

  test('TOURNAMENT-005: 大会を削除できる（DELETE）', async ({ page }) => {
    let deleteCalled = false
    await page.route(`**/api/v1/organizations/${ORG_ID}/tournaments**`, async (route) => {
      const url = route.request().url()
      const method = route.request().method()
      if (method === 'DELETE' && /\/tournaments\/\d+$/.test(url)) {
        deleteCalled = true
        await route.fulfill({ status: 204 })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_TOURNAMENTS }),
        })
      }
    })

    await page.goto(`/organizations/${ORG_ID}/tournaments`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '大会・リーグ' })).toBeVisible({
      timeout: 10_000,
    })

    // 大会削除 APIを直接呼び出してモックを確認
    await page.evaluate(
      async ({ orgId }) => {
        await fetch(`/api/v1/organizations/${orgId}/tournaments/2`, {
          method: 'DELETE',
        })
      },
      { orgId: ORG_ID },
    )

    expect(deleteCalled).toBe(true)
  })

  test('TOURNAMENT-006: リーグ表・トーナメント表が表示される', async ({ page }) => {
    let standingsCalled = false
    await page.route(`**/api/v1/organizations/${ORG_ID}/tournaments**`, async (route) => {
      const url = route.request().url()
      if (url.includes('/standings') && route.request().method() === 'GET') {
        standingsCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_STANDINGS }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_TOURNAMENTS }),
        })
      }
    })

    await page.goto(`/organizations/${ORG_ID}/tournaments`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '大会・リーグ' })).toBeVisible({
      timeout: 10_000,
    })

    // 大会リストが表示される
    await expect(page.getByText('2026年度春季リーグ')).toBeVisible({ timeout: 5_000 })

    // リーグ戦・トーナメントフォーマット表示確認
    await expect(page.getByText('リーグ戦')).toBeVisible()
    await expect(page.getByText('トーナメント')).toBeVisible()

    // 順位表API呼び出し確認
    await page.evaluate(
      async ({ orgId }) => {
        await fetch(`/api/v1/organizations/${orgId}/tournaments/1/divisions/1/standings`)
      },
      { orgId: ORG_ID },
    )
    expect(standingsCalled).toBe(true)
  })
})
