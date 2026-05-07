import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// TEAMHUB テストケース用モックデータ
const MY_TEAMS = [
  {
    id: 1,
    name: 'テストチームA',
    nickname1: null,
    iconUrl: null,
    role: 'ADMIN',
    template: 'SPORTS',
    memberCount: 5,
  },
  {
    id: 2,
    name: 'テストチームB',
    nickname1: 'チームB',
    iconUrl: null,
    role: 'MEMBER',
    template: 'GENERAL',
    memberCount: 3,
  },
]

/** マイチームハブ用の共通モックを設定する */
async function setupTeamHubMocks(page: import('@playwright/test').Page) {
  await page.route('**/api/v1/me/teams', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MY_TEAMS }),
    })
  })
  await page.route('**/api/v1/me/teams/*/announcements', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
  await page.route('**/api/v1/me/scope-folders**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
}

test.describe('TEAMHUB-001〜005: マイチームハブ', () => {
  // TEAMHUB-001: /teams にアクセスすると「マイチーム」ヘッダーが表示される
  test('TEAMHUB-001: /teams にアクセスすると「マイチーム」ヘッダーが表示される', async ({
    page,
  }) => {
    await setupTeamHubMocks(page)

    await page.goto('/teams')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイチーム' })).toBeVisible({
      timeout: 10_000,
    })
  })

  // TEAMHUB-002: 所属チームがグリッドカードとして表示される
  test('TEAMHUB-002: 所属チームがグリッドカードとして表示される', async ({ page }) => {
    await setupTeamHubMocks(page)

    await page.goto('/teams')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイチーム' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('テストチームA')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('チームB')).toBeVisible({ timeout: 5_000 })
  })

  // TEAMHUB-003: 「チームを作成」ボタンが存在する
  test('TEAMHUB-003: 「チームを作成」ボタンが存在する', async ({ page }) => {
    await setupTeamHubMocks(page)

    await page.goto('/teams')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイチーム' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: 'チームを作成' })).toBeVisible({
      timeout: 5_000,
    })
  })

  // TEAMHUB-004: 「チームを検索」ボタンを押すと /teams/search に遷移する
  test('TEAMHUB-004: 「チームを検索」ボタンを押すと /teams/search に遷移する', async ({
    page,
  }) => {
    await setupTeamHubMocks(page)

    // 検索ページのAPIモック
    await page.route('**/api/v1/teams/search**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })

    await page.goto('/teams')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイチーム' })).toBeVisible({
      timeout: 10_000,
    })
    await page.getByRole('button', { name: 'チームを検索' }).click()

    await expect(page).toHaveURL(/\/teams\/search/, { timeout: 5_000 })
  })

  // TEAMHUB-005: チームが0件のときは空状態メッセージが表示される
  test('TEAMHUB-005: チームが0件のときは空状態メッセージが表示される', async ({ page }) => {
    // チームが空のモック
    await page.route('**/api/v1/me/teams', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/me/teams/*/announcements', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/me/scope-folders**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/teams')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイチーム' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(
      page.getByText('まだチームに参加していません'),
    ).toBeVisible({ timeout: 5_000 })
  })
})
