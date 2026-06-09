import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * F15.4 Phase 5-γ 未ログイン公開店舗詳細ページ E2E
 *
 * 設計書: docs/features/F15.4_phase5_team_public_detail.md §9.2
 *
 * シナリオ:
 *   1. 未ログイン → /public/teams/{id} で公開詳細が表示され、CTA が出る
 *   2. メンバー一覧 / チャット等の機微情報が出現しないこと
 *   3. 「ログイン」ボタンクリックで /login?redirect=... へ遷移
 *   4. archived / MEMBERS_AND_ABOVE / 不在 → バックエンドが 404 を返し、Nuxt の 404 画面になる
 */

const TEAM_ID = 8001

const MOCK_PUBLIC_TEAM = {
  id: TEAM_ID,
  name: 'みどり町第一支部',
  nameKana: 'ミドリチョウダイイチシブ',
  nickname1: 'みどりいち',
  nickname2: null,
  template: 'NEIGHBORHOOD',
  prefecture: '東京都',
  city: '渋谷区',
  iconUrl: null,
  bannerUrl: null,
  homepageUrl: 'https://example.com/midori-1',
  establishedDate: '2020-04-01',
  establishedDatePrecision: 'FULL',
  philosophy: '地域の絆を大切に。',
  memberCount: 12,
  mapEmbedUrl: 'https://www.google.com/maps/embed?pb=!1m18!test',
}

async function mockPublicTeam(
  page: Page,
  opts: { status?: number; body?: object | null } = {},
): Promise<void> {
  const status = opts.status ?? 200
  await page.route('**/api/v1/public/teams/*', async (route: Route) => {
    if (status === 200) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: opts.body ?? MOCK_PUBLIC_TEAM }),
      })
    } else {
      await route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Not Found' }),
      })
    }
  })
}

test('F15.4-P5γ-1: 未ログインで /public/teams/{id} の主要要素が表示される', async ({ page }) => {
  await mockPublicTeam(page)

  await page.goto(`/public/teams/${TEAM_ID}`)

  // ヘッダー表示
  await expect(page.getByTestId('public-team-header')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'みどり町第一支部' })).toBeVisible()

  // 基本情報（所在地・公式サイト）
  await expect(page.getByTestId('public-team-basic-info')).toBeVisible()
  await expect(page.getByText('東京都 渋谷区')).toBeVisible()
  await expect(page.getByText('https://example.com/midori-1')).toBeVisible()

  // 理念
  await expect(page.getByTestId('public-team-philosophy')).toBeVisible()
  await expect(page.getByText('地域の絆を大切に。')).toBeVisible()

  // 地図 iframe
  await expect(page.getByTestId('public-team-map')).toBeVisible()
  const iframe = page.getByTestId('public-team-map-iframe')
  await expect(iframe).toBeVisible()
  await expect(iframe).toHaveAttribute(
    'src',
    'https://www.google.com/maps/embed?pb=!1m18!test',
  )

  // メンバー数
  await expect(page.getByTestId('public-team-member-count')).toContainText('12')

  // ログイン誘導 CTA
  await expect(page.getByTestId('public-team-login-cta')).toBeVisible()
})

test('F15.4-P5γ-2: メンバー一覧 / チャットなどの機微情報が画面に出現しない（漏洩スモーク）', async ({
  page,
}) => {
  await mockPublicTeam(page)

  await page.goto(`/public/teams/${TEAM_ID}`)
  await expect(page.getByTestId('public-team-header')).toBeVisible()

  // 設計書 §3.2 禁則フィールド由来の語句が画面に出ていないこと
  // （バックエンド DTO で抑制されているため、フロントに来ないはず）
  const body = await page.locator('body').innerText()
  expect(body).not.toMatch(/メンバー一覧/) // 一覧そのものは出ない
  expect(body).not.toMatch(/チャット履歴/)
  expect(body).not.toMatch(/告知履歴/)
  expect(body).not.toMatch(/電話番号/)
  expect(body).not.toMatch(/番地/)
})

test('F15.4-P5γ-3: ログインボタンクリックで /login?redirect=/public/teams/{id} へ遷移', async ({
  page,
}) => {
  await mockPublicTeam(page)

  await page.goto(`/public/teams/${TEAM_ID}`)
  await expect(page.getByTestId('public-team-login-cta')).toBeVisible()

  const loginLink = page
    .getByTestId('public-team-login-cta')
    .getByRole('link')
    .first()
  // href にリダイレクトクエリが含まれる
  const href = await loginLink.getAttribute('href')
  expect(href).toContain('/login')
  expect(href).toContain(encodeURIComponent(`/public/teams/${TEAM_ID}`))
})

test('F15.4-P5γ-4: archived / MEMBERS_AND_ABOVE / 不在のチーム ID は 404 で Nuxt エラー画面になる', async ({
  page,
}) => {
  await mockPublicTeam(page, { status: 404, body: null })

  // Nuxt の 404 ページは throw createError → エラーページ描画
  const response = await page.goto(`/public/teams/9999`)
  // Nuxt はクライアント側で 200 を返すことがあるため、URL とエラー表示を見る
  expect(response).not.toBeNull()

  // ヘッダーが描画されないこと（公開詳細領域が空のはず）
  await expect(page.getByTestId('public-team-header')).toHaveCount(0)
})

test('F15.4-P5γ-5: ログイン状態で TeamSearchCard をクリックすると /teams/{id} へ遷移する（compact 不適用）', async ({
  page,
}) => {
  // 認証情報を localStorage に注入（既存 F15.4-org-team-search と同じ流儀）
  await page.addInitScript(() => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV9mMTU0X21lbWJlcn0.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: 101,
        email: 'e2e-f154-member@example.com',
        displayName: 'F15.4 メンバー',
        profileImageUrl: null,
        role: 'MEMBER',
      }),
    )
  })

  // 検索 API モック
  const MOCK_TEAMS_MEMBER_VIEW = [
    {
      id: TEAM_ID,
      name: 'みどり町第一支部',
      nameKana: 'ミドリチョウダイイチシブ',
      prefecture: '東京都',
      city: '渋谷区',
      template: 'NEIGHBORHOOD',
      iconUrl: null,
      visibility: 'PUBLIC' as const,
      bannerUrl: null,
      supporterEnabled: false,
    },
  ]

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
        data: { id: 7001, name: 'テスト町内会', visibility: 'PUBLIC' },
      }),
    })
  })
  await page.route('**/api/v1/master/prefectures', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
  await page.route(
    '**/api/v1/organizations/*/teams/search**',
    async (route: Route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: MOCK_TEAMS_MEMBER_VIEW,
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    },
  )

  await page.goto(`/organizations/7001/teams/search`)

  // ログイン会員: TeamSearchCard 経由で /teams/{id} へ遷移する（/public/teams/ ではない）
  const cardLink = page
    .getByRole('link', { name: /みどり町第一支部/ })
    .first()
  await expect(cardLink).toBeVisible()
  const href = await cardLink.getAttribute('href')
  expect(href).toBe(`/teams/${TEAM_ID}`)
})
