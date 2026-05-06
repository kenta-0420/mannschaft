import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// ORGHUB テストケース用モックデータ
const MY_ORGS = [
  {
    id: 1,
    name: 'テスト組織A',
    nickname1: null,
    iconUrl: null,
    role: 'ADMIN',
    orgType: 'GENERAL',
    memberCount: 10,
  },
]

/** マイ組織ハブ用の共通モックを設定する */
async function setupOrgHubMocks(page: import('@playwright/test').Page) {
  await page.route('**/api/v1/me/organizations', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MY_ORGS }),
    })
  })
  await page.route('**/api/v1/me/organizations/*/announcements', async (route) => {
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

test.describe('ORGHUB-001〜004: マイ組織ハブ', () => {
  // ORGHUB-001: /organizations にアクセスすると「マイ組織」ヘッダーが表示される
  test('ORGHUB-001: /organizations にアクセスすると「マイ組織」ヘッダーが表示される', async ({
    page,
  }) => {
    await setupOrgHubMocks(page)

    await page.goto('/organizations')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイ組織' })).toBeVisible({
      timeout: 10_000,
    })
  })

  // ORGHUB-002: 所属組織がグリッドカードとして表示される
  test('ORGHUB-002: 所属組織がグリッドカードとして表示される', async ({ page }) => {
    await setupOrgHubMocks(page)

    await page.goto('/organizations')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイ組織' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('テスト組織A')).toBeVisible({ timeout: 5_000 })
  })

  // ORGHUB-003: 「組織を検索」ボタンを押すと /organizations/search に遷移する
  test('ORGHUB-003: 「組織を検索」ボタンを押すと /organizations/search に遷移する', async ({
    page,
  }) => {
    await setupOrgHubMocks(page)

    // 検索ページのAPIモック
    await page.route('**/api/v1/organizations/search**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })

    await page.goto('/organizations')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイ組織' })).toBeVisible({
      timeout: 10_000,
    })
    await page.getByRole('button', { name: '組織を検索' }).click()

    await expect(page).toHaveURL(/\/organizations\/search/, { timeout: 5_000 })
  })

  // ORGHUB-004: 組織が0件のときは空状態メッセージが表示される
  test('ORGHUB-004: 組織が0件のときは空状態メッセージが表示される', async ({ page }) => {
    // 組織が空のモック
    await page.route('**/api/v1/me/organizations', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/me/organizations/*/announcements', async (route) => {
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

    await page.goto('/organizations')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイ組織' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(
      page.getByText('まだ組織に参加していません'),
    ).toBeVisible({ timeout: 5_000 })
  })
})
