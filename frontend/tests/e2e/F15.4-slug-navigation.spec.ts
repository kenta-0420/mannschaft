import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * slug 遷移再発防止 E2E — team/org 詳細への遷移が slug ベースになっていることを検証
 *
 * 真因: 一覧/グリッド/作成後リダイレクトの遷移が UUID(id) で URL を組み立てていたため
 *       /teams/{UUID} → 404 になっていた。
 * 対策: 各レスポンス型の slug フィールドを使うよう全遷移箇所を修正し、
 *       本テストで「href 属性が slug ベースになっていること」を保証する。
 *
 * シナリオ:
 *   SLUG-001: 組織内チーム検索カード（ログイン済）が /teams/{slug} を href に持つ
 *   SLUG-002: 公開チーム検索カード（未ログイン）が /public/teams/{slug} を href に持つ
 *
 * 公開詳細ページの描画検証は F15.4-phase5-public-team-detail.spec.ts が担う。
 * 本ファイルは遷移 href が slug ベースであることのみを保証する。
 *
 * すべてモック方式（バックエンド非依存・CI 安定）。
 */

// ─── フィクスチャ ───────────────────────────────────────────────────────────

const PUBLIC_ORG_ID = 7001
const TEAM_SLUG = 'midori-1'
const TEAM_ID = 8001

/** 検索 API が返す TeamSearchItem（メンバー向け詳細版 = TeamSearchResult）。slug を含む。 */
const MOCK_TEAM_MEMBER_VIEW = {
  id: TEAM_ID,
  slug: TEAM_SLUG,
  name: 'みどり町第一支部',
  nameKana: 'ミドリチョウダイイチシブ',
  prefecture: '東京都',
  city: '渋谷区',
  prefectureCode: '13',
  cityCode: null,
  template: 'NEIGHBORHOOD',
  iconUrl: null,
  visibility: 'PUBLIC' as const,
  bannerUrl: null,
  supporterEnabled: false,
}

/** 検索 API が返す TeamPublicSummary（未ログイン向け抑制版）。slug を含む。 */
const MOCK_TEAM_PUBLIC_SUMMARY = {
  id: TEAM_ID,
  slug: TEAM_SLUG,
  name: 'みどり町第一支部',
  nameKana: 'ミドリチョウダイイチシブ',
  prefecture: '東京都',
  city: '渋谷区',
  prefectureCode: '13',
  cityCode: null,
  template: 'NEIGHBORHOOD',
  iconUrl: null,
}

/** 公開詳細ページ API のモックレスポンス。 */
const MOCK_PUBLIC_TEAM_DETAIL = {
  id: TEAM_ID,
  slug: TEAM_SLUG,
  name: 'みどり町第一支部',
  nameKana: 'ミドリチョウダイイチシブ',
  nickname1: null,
  nickname2: null,
  template: 'NEIGHBORHOOD',
  prefecture: '東京都',
  city: '渋谷区',
  iconUrl: null,
  bannerUrl: null,
  homepageUrl: null,
  establishedDate: null,
  establishedDatePrecision: null,
  philosophy: null,
  memberCount: 5,
  mapEmbedUrl: null,
}

// ─── ヘルパ ─────────────────────────────────────────────────────────────────

/** ログイン状態の注入（localStorage + refresh モック）。 */
async function loginAsMember(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: 101,
        email: 'e2e-slug-nav@example.com',
        fullName: 'slug nav テスト',
        profileImageUrl: null,
        systemRole: undefined,
      }),
    )
  })
  await page.route('**/api/v1/auth/refresh', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { accessToken: 'mock-token', refreshToken: 'mock-refresh' },
      }),
    })
  })
}

/** layout header が描画されるまで待機（Nuxt hydration 完了待ち）。 */
async function waitForLayout(page: Page): Promise<void> {
  await page.locator('header').waitFor({ state: 'visible', timeout: 30000 })
}

/** 組織内チーム検索ページに必要な最低限のモック群。 */
async function mockOrgTeamSearchApis(
  page: Page,
  teamItems: Array<Record<string, unknown>>,
): Promise<void> {
  // 組織情報
  await page.route('**/api/v1/organizations/*', async (route: Route) => {
    const url = new URL(route.request().url())
    if (!/\/organizations\/\d+$/.test(url.pathname)) {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          id: PUBLIC_ORG_ID,
          slug: 'test-neighborhood',
          basicInfo: { name: 'テスト町内会（公開）', nameKana: null, nickname1: null, nickname2: null },
          hierarchy: { orgType: 'NEIGHBORHOOD', parentOrganizationId: null },
          location: { prefecture: null, city: null },
          visibility: { visibility: 'PUBLIC', hierarchyVisibility: 'NONE', supporterEnabled: false },
          metadata: { version: 1, memberCount: 0, iconUrl: null, bannerUrl: null },
          timestamps: { archivedAt: null, createdAt: '2024-01-01T00:00:00' },
        },
      }),
    })
  })
  // 都道府県マスタ
  await page.route('**/api/v1/master/prefectures', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [{ code: '13', name: '東京都' }] }),
    })
  })
  // 市区町村マスタ
  await page.route('**/api/v1/master/prefectures/*/cities', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
  // 検索 API
  await page.route('**/api/v1/organizations/*/teams/search**', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: teamItems,
        meta: { page: 0, size: 20, totalElements: teamItems.length, totalPages: 1 },
      }),
    })
  })
}

// ─── テスト ─────────────────────────────────────────────────────────────────

/**
 * SLUG-001: ログイン済みメンバーの検索結果カードが /teams/{slug} href を持つ。
 *
 * slug 移行後の TeamSearchCard は team.slug を使う。
 * 旧実装（team.id = 数値 UUID）では /teams/8001 という数値パスになり、404 になっていた。
 */
test('SLUG-001: ログイン済み検索カードの href が /teams/{slug} になっている', async ({ page }) => {
  await loginAsMember(page)
  await mockOrgTeamSearchApis(page, [MOCK_TEAM_MEMBER_VIEW])

  await page.goto(`/organizations/${PUBLIC_ORG_ID}/teams/search`)
  await waitForLayout(page)

  // カード（NuxtLink）が表示される
  const cardLink = page.getByRole('link', { name: /みどり町第一支部/ }).first()
  await expect(cardLink).toBeVisible()

  // href が数値 ID ではなく slug になっていること
  const href = await cardLink.getAttribute('href')
  expect(href, 'slug 遷移になっていること（数値 ID は 404 になる）').toBe(`/teams/${TEAM_SLUG}`)
  expect(href, '数値 ID が混入していないこと').not.toContain(`/teams/${TEAM_ID}`)
})

/**
 * SLUG-002: 未ログインの検索結果カードが /public/teams/{slug} href を持つ。
 *
 * 旧実装では /public/teams/8001（数値）となり、slug ルートが存在しないため 404 になっていた。
 */
test('SLUG-002: 未ログイン検索カードの href が /public/teams/{slug} になっている', async ({ page }) => {
  await mockOrgTeamSearchApis(page, [MOCK_TEAM_PUBLIC_SUMMARY])
  // 公開詳細ページ API もモック（カードクリック後の遷移先）
  await page.route('**/api/v1/public/teams/*', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_PUBLIC_TEAM_DETAIL }),
    })
  })

  await page.goto(`/organizations/${PUBLIC_ORG_ID}/teams/search`)
  await waitForLayout(page)

  const cardLink = page.getByRole('link', { name: /みどり町第一支部/ }).first()
  await expect(cardLink).toBeVisible()

  const href = await cardLink.getAttribute('href')
  expect(href, 'slug 遷移になっていること（数値 ID は 404 になる）').toBe(`/public/teams/${TEAM_SLUG}`)
  expect(href, '数値 ID が混入していないこと').not.toContain(`/public/teams/${TEAM_ID}`)
})
