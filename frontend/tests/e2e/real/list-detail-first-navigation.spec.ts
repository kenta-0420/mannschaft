/**
 * 一覧から詳細への初回 SPA 遷移の実機回帰テスト。
 *
 * 空の BrowserContext から実バックエンドへログインし、初めて読み込む詳細チャンクへ
 * 一覧カードのクリックで遷移する。直アクセスや page.reload() では、初回チャンク読込と
 * page transition の競合を再現できないため代用しない。
 */
import { expect, test, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const USER = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}

interface TeamListItem {
  name: string
  nickname1: string | null
  slug: string
  iconUrl: string | null
}

interface VillageListItem {
  id: string
  name: string
}

interface OrganizationListItem {
  name: string
  nickname1: string | null
  slug: string
}

async function settleOn(page: Page, path: RegExp, heading: string): Promise<void> {
  await expect(page).toHaveURL(path, { timeout: 20_000 })
  await expect(page.getByRole('heading', { name: heading }).first()).toBeVisible({
    timeout: 30_000,
  })
  // 詳細データ取得・描画が完了した後にも、遷移元へ戻されていないことを検証する。
  await expect(page).toHaveURL(path)
}

test.describe('一覧から詳細への初回 SPA 遷移', () => {
  test.use({ storageState: { cookies: [], origins: [] } })
  test.setTimeout(120_000)

  test.beforeEach(async ({ page }) => {
    await loginViaApi(page, USER, { apiBaseUrl: API_BASE_URL })
  })

  test('チーム一覧は初回クリックでも詳細に留まり、MinIO失敗は遷移へ影響しない', async ({ page }) => {
    const response = await page.request.get(`${API_BASE_URL}/api/v1/me/teams?limit=200`)
    expect(response.status()).toBe(200)
    const body = await response.json() as { data: TeamListItem[] }
    const target = body.data.find(team => team.slug)
    expect(target, '所属チームが1件以上必要').toBeTruthy()

    // 実API応答のうち画像URLだけを停止中MinIOへ向ける。業務データ・詳細APIは実BEを使う。
    await page.route('**/api/v1/me/teams**', async (route) => {
      const upstream = await route.fetch()
      const json = await upstream.json() as { data: TeamListItem[] }
      await route.fulfill({
        response: upstream,
        json: {
          ...json,
          data: json.data.map(team => team.slug === target!.slug
            ? { ...team, iconUrl: 'http://localhost:9000/mannschaft-storage/navigation-test.png' }
            : team),
        },
      })
    })
    await page.route('http://localhost:9000/**', route => route.abort('connectionrefused'))

    await page.goto('/teams')
    await waitForHydration(page)
    const cardLabel = target!.nickname1 || target!.name
    const card = page.getByText(cardLabel, { exact: true }).first()
    await expect(card).toBeVisible({ timeout: 30_000 })
    // 一覧初期表示の応答加工は完了済み。戻る操作時の再取得には実応答を使い、
    // テスト終了時に route.fetch が残らないようハンドラを解除する。
    await page.unroute('**/api/v1/me/teams**')

    await card.click()
    await settleOn(page, new RegExp(`/teams/${target!.slug}(?:[/?#]|$)`), cardLabel)

    await page.goBack()
    await expect(page).toHaveURL(/\/teams(?:[?#]|$)/, { timeout: 20_000 })
    await expect(card).toBeVisible({ timeout: 20_000 })
    await card.click()
    await settleOn(page, new RegExp(`/teams/${target!.slug}(?:[/?#]|$)`), cardLabel)
  })

  test('村一覧は初回クリックで掲示板へ遷移して留まり、再クリックも成功する', async ({ page }) => {
    const response = await page.request.get(`${API_BASE_URL}/api/v1/villages/search?page=0&size=20`)
    expect(response.status()).toBe(200)
    const body = await response.json() as { content: VillageListItem[] }
    const target = body.content[0]
    expect(target, '村が1件以上必要').toBeTruthy()

    await page.goto('/villages')
    await waitForHydration(page)
    const card = page.getByText(target!.name, { exact: true }).first()
    await expect(card).toBeVisible({ timeout: 30_000 })

    await card.click()
    await settleOn(
      page,
      new RegExp(`/villages/${target!.id}/bulletin(?:[?#]|$)`),
      target!.name,
    )

    // `/villages/{id}` → bulletin は replace redirect のため、履歴上の一覧も置換される。
    // 一覧を再訪し、同じカードを再クリックして2回目も確認する。
    await page.goto('/villages')
    await waitForHydration(page)
    await expect(page).toHaveURL(/\/villages(?:[?#]|$)/, { timeout: 20_000 })
    await expect(card).toBeVisible({ timeout: 20_000 })
    await card.click()
    await settleOn(
      page,
      new RegExp(`/villages/${target!.id}/bulletin(?:[?#]|$)`),
      target!.name,
    )
  })

  test('組織一覧は初回クリックでも詳細に留まり、再クリックも成功する', async ({ page }) => {
    const response = await page.request.get(`${API_BASE_URL}/api/v1/me/organizations`)
    expect(response.status()).toBe(200)
    const body = await response.json() as { data: OrganizationListItem[] }
    const target = body.data.find(org => org.slug)
    expect(target, '所属組織が1件以上必要').toBeTruthy()

    await page.goto('/organizations')
    await waitForHydration(page)
    const cardLabel = target!.nickname1 || target!.name
    const card = page.getByText(cardLabel, { exact: true }).first()
    await expect(card).toBeVisible({ timeout: 30_000 })

    await card.click()
    await settleOn(
      page,
      new RegExp(`/organizations/${target!.slug}(?:[/?#]|$)`),
      cardLabel,
    )

    await page.goBack()
    await expect(page).toHaveURL(/\/organizations(?:[?#]|$)/, { timeout: 20_000 })
    await expect(card).toBeVisible({ timeout: 20_000 })
    await card.click()
    await settleOn(
      page,
      new RegExp(`/organizations/${target!.slug}(?:[/?#]|$)`),
      cardLabel,
    )
  })
})
