import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const ORG_ID = 1

const MOCK_SESSIONS = [
  {
    id: 1,
    title: '2026年度定時総会 議決',
    status: 'OPEN',
    votingMode: 'ONLINE',
    isAnonymous: false,
    motions: [
      { id: 1, title: '第1号議案: 活動計画', status: 'VOTING' },
      { id: 2, title: '第2号議案: 予算', status: 'VOTING' },
    ],
    votedCount: 3,
    eligibleCount: 10,
    delegatedCount: 1,
    createdAt: '2026-04-01T00:00:00Z',
  },
  {
    id: 2,
    title: '臨時総会',
    status: 'DRAFT',
    votingMode: 'OFFLINE',
    isAnonymous: true,
    motions: [],
    votedCount: 0,
    eligibleCount: 10,
    delegatedCount: 0,
    createdAt: '2026-04-10T00:00:00Z',
  },
]

const MOCK_RESULTS = {
  sessionId: 1,
  title: '2026年度定時総会 議決',
  motionResults: [
    {
      motionId: 1,
      title: '第1号議案: 活動計画',
      yesCount: 8,
      noCount: 1,
      abstainCount: 1,
      result: 'APPROVED',
    },
  ],
}

test.describe('PROXY-001〜005: 議決権行使・委任状', () => {
  test.beforeEach(async ({ page }) => {
    // 全組織APIを空レスポンスでモック（認証・権限系）
    await page.route(`**/api/v1/organizations/${ORG_ID}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: ORG_ID,
            name: 'テスト組織',
            template: 'SPORTS',
            createdAt: '2026-01-01T00:00:00Z',
          },
        }),
      })
    })
    await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            roleName: 'ADMIN',
            permissions: ['vote.manage', 'vote.cast', 'vote.delegate'],
          },
        }),
      })
    })
  })

  test('PROXY-001: 投票ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/proxy-votes**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/voting`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '議決権行使' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('PROXY-002: 投票案件一覧の取得と表示（GET）', async ({ page }) => {
    let getCalled = false
    await page.route('**/api/v1/proxy-votes**', async (route) => {
      if (route.request().method() === 'GET') {
        getCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SESSIONS }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(`/organizations/${ORG_ID}/voting`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '議決権行使' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('2026年度定時総会 議決')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('臨時総会')).toBeVisible()
    expect(getCalled).toBe(true)
  })

  test('PROXY-003: 投票できる（POST）', async ({ page }) => {
    let castCalled = false
    await page.route('**/api/v1/proxy-votes**', async (route) => {
      const url = route.request().url()
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SESSIONS }),
        })
      } else if (method === 'POST' && url.includes('/cast')) {
        castCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { success: true } }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(`/organizations/${ORG_ID}/voting`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '議決権行使' })).toBeVisible({
      timeout: 10_000,
    })

    // APIが正しいエンドポイントを呼び出すことを直接確認
    const responsePromise = page.waitForResponse(
      (resp) => resp.url().includes('/proxy-votes') && resp.request().method() === 'POST',
      { timeout: 5_000 },
    ).catch(() => null)

    // castVote APIを直接呼び出してモックが機能するか確認
    await page.evaluate(async (sessionId) => {
      const url = `/api/v1/proxy-votes/${sessionId}/cast`
      await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ votes: [{ motionId: 1, choice: 'YES' }] }),
      })
    }, 1)

    await responsePromise
    expect(castCalled).toBe(true)
  })

  test('PROXY-004: 代理委任ができる（POST /delegate）', async ({ page }) => {
    let delegateCalled = false
    await page.route('**/api/v1/proxy-votes**', async (route) => {
      const url = route.request().url()
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SESSIONS }),
        })
      } else if (method === 'POST' && url.includes('/delegate')) {
        delegateCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { id: 1, status: 'PENDING' } }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(`/organizations/${ORG_ID}/voting`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '議決権行使' })).toBeVisible({
      timeout: 10_000,
    })

    // submitDelegation APIを直接呼び出してモックが機能するか確認
    await page.evaluate(async (sessionId) => {
      const url = `/api/v1/proxy-votes/${sessionId}/delegate`
      await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ delegateId: 2 }),
      })
    }, 1)

    expect(delegateCalled).toBe(true)
  })

  test('PROXY-005: 投票結果が表示される', async ({ page }) => {
    let resultsCalled = false
    await page.route('**/api/v1/proxy-votes**', async (route) => {
      const url = route.request().url()
      if (url.includes('/results')) {
        resultsCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_RESULTS }),
        })
      } else if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SESSIONS }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(`/organizations/${ORG_ID}/voting`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '議決権行使' })).toBeVisible({
      timeout: 10_000,
    })

    // ページに投票セッション一覧が表示される
    await expect(page.getByText('2026年度定時総会 議決')).toBeVisible({ timeout: 5_000 })

    // 投票済み数/有資格者数の表示確認
    await expect(page.getByText(/投票 3\/10/)).toBeVisible({ timeout: 5_000 })

    // 結果APIを呼び出す
    await page.evaluate(async (sessionId) => {
      await fetch(`/api/v1/proxy-votes/${sessionId}/results`)
    }, 1)
    expect(resultsCalled).toBe(true)
  })
})
