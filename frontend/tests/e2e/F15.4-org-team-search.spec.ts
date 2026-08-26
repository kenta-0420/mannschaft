import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * F15.4 組織内チーム（店舗）検索 — E2E 一連シナリオ
 *
 * 設計書: docs/features/F15.4_team_store_search_within_org.md §11.4
 *
 * シナリオ:
 *   1. 未ログイン → 公開組織 → 検索 → 結果表示 → カードクリックで詳細遷移しない（CTA 表示）
 *   2. ログイン会員（組織メンバー） → 検索 → カードから詳細遷移可
 *   3. 都道府県のみ指定で検索成功
 *   4. 不正な orgId（存在しない数値）で 404 状態を確認
 *   5. PRIVATE 組織（未ログイン）にアクセスして 404 状態を確認
 *
 * 設計思想:
 *   - F15.3 等の既存 E2E と同じく `page.route()` で API レスポンスをモック化して安定動作させる。
 *   - dev サーバ + バックエンドの状態に依存しない（CI 環境でも同一動作）。
 *   - 認証注入は PR #1000 以降の方式に準拠: addInitScript で localStorage の currentUser を
 *     書き込み、/api/v1/auth/refresh を route.fulfill() でモックして in-memory トークンを注入する。
 */

// ──────────────────────────────────────────────────────────────────────────
// テスト共通フィクスチャ
// ──────────────────────────────────────────────────────────────────────────

const PUBLIC_ORG_ID = 7001
const PRIVATE_ORG_ID = 7002
const NOT_FOUND_ORG_ID = 9999999

// Wave 3-B: OrganizationResponse ネスト構造に合わせたモック
const MOCK_PUBLIC_ORG = {
  id: PUBLIC_ORG_ID,
  basicInfo: {
    name: 'テスト町内会（公開）',
    nameKana: null,
    nickname1: null,
    nickname2: null,
  },
  hierarchy: {
    orgType: 'NEIGHBORHOOD',
    parentOrganizationId: null,
  },
  location: {
    prefecture: null,
    city: null,
  },
  visibility: {
    visibility: 'PUBLIC',
    hierarchyVisibility: 'NONE',
    supporterEnabled: false,
  },
  metadata: {
    version: 1,
    memberCount: 0,
    iconUrl: null,
    bannerUrl: null,
  },
  timestamps: {
    archivedAt: null,
    createdAt: '2024-01-01T00:00:00',
  },
}

const MOCK_TEAMS_PUBLIC = [
  {
    id: 8001,
    slug: 'midori-1',
    name: 'みどり町第一支部',
    nameKana: 'ミドリチョウダイイチシブ',
    prefecture: '東京都',
    city: '渋谷区',
    template: 'NEIGHBORHOOD',
    iconUrl: null,
  },
  {
    id: 8002,
    slug: 'midori-2',
    name: 'みどり町第二支部',
    nameKana: 'ミドリチョウダイニシブ',
    prefecture: '東京都',
    city: '世田谷区',
    template: 'NEIGHBORHOOD',
    iconUrl: null,
  },
]

const MOCK_TEAMS_MEMBER_VIEW = MOCK_TEAMS_PUBLIC.map((t) => ({
  ...t,
  visibility: 'PUBLIC' as const,
  bannerUrl: null,
  supporterEnabled: false,
}))

const MOCK_PREFECTURES = [
  { code: '13', name: '東京都' },
  { code: '14', name: '神奈川県' },
]

const MOCK_CITIES_TOKYO = [
  { code: '13113', name: '渋谷区' },
  { code: '13112', name: '世田谷区' },
]

// ──────────────────────────────────────────────────────────────────────────
// モックヘルパ
// ──────────────────────────────────────────────────────────────────────────

interface SearchMockOptions {
  /** 組織情報取得時のステータス */
  organizationStatus?: number
  /** 検索結果（item 配列）。null 時はステータスベースの応答 */
  teamItems?: Array<Record<string, unknown>> | null
  /** 検索 API 自体のステータス（404 / 429 検証用） */
  searchStatus?: number
  /** 受信した検索クエリを観測するためのフック */
  onSearch?: (url: URL) => void
}

async function mockApis(page: Page, opts: SearchMockOptions = {}): Promise<void> {
  const organizationStatus = opts.organizationStatus ?? 200
  const searchStatus = opts.searchStatus ?? 200
  const teamItems = opts.teamItems === undefined ? MOCK_TEAMS_PUBLIC : opts.teamItems

  // 組織情報
  await page.route('**/api/v1/organizations/*', async (route: Route) => {
    const url = new URL(route.request().url())
    // /organizations/search や /organizations/{id}/teams/search 等は除外
    if (!/\/organizations\/\d+$/.test(url.pathname)) {
      await route.continue()
      return
    }
    if (organizationStatus === 200) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_PUBLIC_ORG }),
      })
    } else {
      await route.fulfill({
        status: organizationStatus,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Not Found' }),
      })
    }
  })

  // 都道府県マスタ
  await page.route('**/api/v1/master/prefectures', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_PREFECTURES }),
    })
  })

  // 市区町村マスタ
  await page.route('**/api/v1/master/prefectures/*/cities', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_CITIES_TOKYO }),
    })
  })

  // 検索 API
  await page.route(
    '**/api/v1/organizations/*/teams/search**',
    async (route: Route) => {
      const url = new URL(route.request().url())
      opts.onSearch?.(url)
      if (searchStatus !== 200) {
        await route.fulfill({
          status: searchStatus,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'Error' }),
        })
        return
      }
      const items = teamItems ?? []
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: items,
          meta: {
            page: 0,
            size: 20,
            totalElements: items.length,
            totalPages: items.length > 0 ? 1 : 0,
          },
        }),
      })
    },
  )
}

/**
 * 認証ペイロードを localStorage に注入する。
 *
 * PR #1000 以降: accessToken は HttpOnly Cookie 管理に移行したため localStorage に保存しない。
 * currentUser だけ設定して isAuthenticated = true にする。
 * レイアウトの NotificationBell / ScopeNavDropdown 等が発する未モック API コールで
 * 401 → リフレッシュ → ログアウト の連鎖が起きないよう refresh エンドポイントもモックする。
 */
async function loginAsMember(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: 101,
        email: 'e2e-f154-member@example.com',
        fullName: 'F15.4 メンバー',
        profileImageUrl: null,
        systemRole: undefined,
      }),
    )
  })
  // レイアウトコンポーネントが発する未モック 401 でログアウトしないようリフレッシュをモック
  await page.route('**/api/v1/auth/refresh', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { accessToken: 'mock-e2e-access-token', refreshToken: 'mock-e2e-refresh-token' },
      }),
    })
  })
}

/**
 * レイアウトがマウント完了するまで待機する。
 *
 * default.vue は SSR 直後 `isMounted=false` でローディングスピナーを表示し、
 * クライアント側 onMounted で `isMounted=true` に切り替わって <header> が現れる。
 * Nuxt dev mode のコールドスタートでは hydration に数秒かかるため、
 * `goto()` 直後に <header> の出現を最大 30 秒待つことで
 * タイムアウト起因の誤失敗を防ぐ。
 */
async function waitForLayoutMounted(page: Page): Promise<void> {
  await page.locator('header').waitFor({ state: 'visible', timeout: 30000 })
}

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 1: 未ログイン → 公開組織 → 検索 → 結果 → カードクリックで詳細遷移しない
// ──────────────────────────────────────────────────────────────────────────

test('F15.4-1: 未ログインで公開組織のチームを検索し、カードに「ログイン CTA」が表示され、クリックで /public/teams/{slug} へ遷移する', async ({
  page,
}) => {
  // Phase 5-γ で公開詳細ページが新設されたため、本シナリオではカードクリック後の遷移先 API も
  // モックしておく（未ログイン公開 API）。slug ベースのルーティングに統一済み。
  await mockApis(page, { teamItems: MOCK_TEAMS_PUBLIC })
  await page.route('**/api/v1/public/teams/*', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          id: 8001,
          slug: 'midori-1',
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
        },
      }),
    })
  })

  await page.goto(`/organizations/${PUBLIC_ORG_ID}/teams/search`)
  await waitForLayoutMounted(page)

  // タイトルが表示される
  await expect(page.getByRole('heading', { name: '店舗を検索' })).toBeVisible()

  // 結果カードが描画される
  const card = page.getByText('みどり町第一支部', { exact: true })
  await expect(card).toBeVisible()

  // 未ログインなので「ログインしてください」CTA が表示されている
  await expect(
    page.getByText('詳細を見るにはログインしてください').first(),
  ).toBeVisible()

  // Phase 5-γ: カードクリックで /public/teams/{slug} へ遷移する（id ベースは廃止）
  await card.click()
  await expect(page).toHaveURL(/\/public\/teams\/midori-1/)
})

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 2: ログイン会員（組織メンバー） → 検索 → 詳細遷移可
// ──────────────────────────────────────────────────────────────────────────

test('F15.4-2: ログイン会員はカードから詳細遷移できる（CTA は出ない）', async ({
  page,
}) => {
  await loginAsMember(page)
  // ログイン会員にはメンバー向け詳細 DTO（visibility あり）を返す
  await mockApis(page, { teamItems: MOCK_TEAMS_MEMBER_VIEW })

  // チーム詳細遷移先の API をモック（dev mode でのコンポーネントコンパイル後に呼ばれる）
  // API は slug ベース（/api/v1/teams/{slug}）に統一済み
  await page.route('**/api/v1/teams/midori-1/me/permissions', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { roleName: 'GUEST', permissions: [] } }),
    })
  })
  await page.route('**/api/v1/teams/midori-1', async (route: Route) => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/api/v1/teams/midori-1')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: 8001,
            slug: 'midori-1',
            name: 'みどり町第一支部',
            nameKana: 'ミドリチョウダイイチシブ',
            nickname1: null,
            nickname2: null,
            template: 'NEIGHBORHOOD',
            prefecture: '東京都',
            city: '渋谷区',
            iconUrl: null,
            bannerUrl: null,
            supporterEnabled: false,
            supporterCount: 0,
            memberCount: 5,
          },
        }),
      })
    } else {
      await route.continue()
    }
  })

  await page.goto(`/organizations/${PUBLIC_ORG_ID}/teams/search`)
  await waitForLayoutMounted(page)

  // 結果カードが描画される
  const cardLink = page
    .getByRole('link', { name: /みどり町第一支部/ })
    .first()
  await expect(cardLink).toBeVisible()

  // CTA は表示されない
  await expect(
    page.getByText('詳細を見るにはログインしてください'),
  ).toHaveCount(0)

  // 注: メンバー数表示は将来課題（設計書 §11.4 NOTE）。本テストでは検証範囲外。

  // カードリンクが会員向け詳細 URL（/teams/{slug}）を指していること（id ベースは廃止・/public/teams/ でもない）
  await expect(cardLink).toHaveAttribute('href', '/teams/midori-1')

  // カードクリックで /teams/{slug} に遷移する（dev mode のコンポーネントコンパイル待ちのため長めのタイムアウト）
  await cardLink.click()
  await page.waitForURL(/\/teams\/midori-1/, { timeout: 30000 })
})

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 3: 都道府県のみ指定で検索成功
// ──────────────────────────────────────────────────────────────────────────

test('F15.4-3: 都道府県のみ指定で検索が成功し、クエリパラメータが API に渡る', async ({
  page,
}) => {
  let observedPrefecture: string | null = null
  let searchCallCount = 0

  await mockApis(page, {
    teamItems: MOCK_TEAMS_PUBLIC,
    onSearch: (url) => {
      searchCallCount++
      // F22.1 足場C第三陣: org検索はコード化され prefectureCode（JISコード）を送る（旧: prefecture 名称）
      const pref = url.searchParams.get('prefectureCode')
      if (pref) observedPrefecture = pref
    },
  })

  await page.goto(`/organizations/${PUBLIC_ORG_ID}/teams/search`)
  await waitForLayoutMounted(page)

  // 初回の onMounted 検索を待つ
  await expect(page.getByText('みどり町第一支部', { exact: true })).toBeVisible()
  const initialCount = searchCallCount

  // 都道府県プルダウンで「東京都」を選択
  // PrimeVue Select は <label> と combobox が aria 関連付けされていないため、
  // 名前付き combobox の role 指定で探索する。
  await page.getByRole('combobox', { name: /都道府県/i }).click()
  await page.getByRole('option', { name: '東京都', exact: true }).click()

  // 再検索（onFilterChange）で API が呼ばれる
  await expect.poll(() => searchCallCount).toBeGreaterThan(initialCount)

  // バックエンドには prefectureCode=13（東京都の JIS コード）が渡る（FE はコードを直接送信・dual-support）
  expect(observedPrefecture).toBe('13')

  // 結果が表示されている
  await expect(page.getByText('みどり町第一支部', { exact: true })).toBeVisible()
})

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 4: 不正な orgId（存在しない数値）で 404 エラー表示
// ──────────────────────────────────────────────────────────────────────────

test('F15.4-4: 存在しない orgId にアクセスすると 404 エラーメッセージが表示される', async ({
  page,
}) => {
  await mockApis(page, {
    organizationStatus: 404,
    searchStatus: 404,
    teamItems: null,
  })

  await page.goto(`/organizations/${NOT_FOUND_ORG_ID}/teams/search`)
  await waitForLayoutMounted(page)

  // 設計書 §5.1: 404 時は OrganizationNotFoundError をスローし、
  // 「指定された組織が見つかりませんでした」メッセージを表示する。
  // ページ本文の <p> と toast の <span> 両方に同テキストが出るため first() で一意化。
  await expect(
    page.getByText('指定された組織が見つかりませんでした').first(),
  ).toBeVisible()

  // 再試行ボタンも表示される
  await expect(page.getByRole('button', { name: '再試行' })).toBeVisible()
})

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 5: PRIVATE 組織（未ログイン）にアクセスして 404 表示
// ──────────────────────────────────────────────────────────────────────────

test('F15.4-5: 未ログインで PRIVATE 組織にアクセスするとバックエンドが 404 を返し、組織未検出表示になる', async ({
  page,
}) => {
  // バックエンドの権限制御により、PRIVATE 組織は未ログインに対して 404 を返す想定
  // （設計書 §3.2: 未ログイン者には PRIVATE 組織の存在を漏洩させないため 404 で隠蔽）
  await mockApis(page, {
    organizationStatus: 404,
    searchStatus: 404,
    teamItems: null,
  })

  await page.goto(`/organizations/${PRIVATE_ORG_ID}/teams/search`)
  await waitForLayoutMounted(page)

  // 本文と toast の二重マッチを first() で一意化
  await expect(
    page.getByText('指定された組織が見つかりませんでした').first(),
  ).toBeVisible()
})

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 6 (Phase 3): 組織トップ「所属チーム」タブの「店舗を検索」ボタン導線
// ──────────────────────────────────────────────────────────────────────────

/**
 * 組織トップページ (/organizations/{id}) で必要となる追加 API のモック。
 *
 * - GET /api/v1/organizations/{id}/teams         : OrgTeamGrid 用
 * - GET /api/v1/organizations/{id}/ancestors     : 上位組織パンくず
 * - GET /api/v1/organizations/{id}/children      : 下位組織タブ
 * - GET /api/v1/organizations/{id}/me/permissions: useRoleAccess
 * - GET /api/v1/organizations/{id}/follow/status : サポーター状態
 */
async function mockOrgTopApis(page: Page): Promise<void> {
  // 所属チーム一覧
  await page.route(
    '**/api/v1/organizations/*/teams',
    async (route: Route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_TEAMS_PUBLIC }),
      })
    },
  )

  // 上位組織（祖先）
  await page.route(
    '**/api/v1/organizations/*/ancestors',
    async (route: Route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { depth: 0, truncated: false },
        }),
      })
    },
  )

  // 下位組織
  await page.route(
    '**/api/v1/organizations/*/children**',
    async (route: Route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { nextCursor: null, hasNext: false },
        }),
      })
    },
  )

  // 自身の権限（未ログイン or PUBLIC 閲覧者は roleName=null）
  await page.route(
    '**/api/v1/organizations/*/me/permissions',
    async (route: Route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            roleName: null,
            permissions: [],
          },
        }),
      })
    },
  )

  // サポーター状態
  await page.route(
    '**/api/v1/organizations/*/follow/status',
    async (route: Route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { status: 'NONE' } }),
      })
    },
  )
}

test('F15.4-6: 組織トップ「所属チーム」タブで「店舗を検索」ボタンが見え、クリックすると検索ページへ遷移する', async ({
  page,
}) => {
  // 組織トップ /organizations/{id} は auth middleware で認証必須のため
  // ログインメンバーとして localStorage に認証情報を注入する
  await loginAsMember(page)
  // 組織トップで必要となる API 一式を先にモック
  await mockOrgTopApis(page)
  // 既存ヘルパで /organizations/{id} と検索 API もモック（PUBLIC 組織）
  await mockApis(page, { teamItems: MOCK_TEAMS_PUBLIC })

  await page.goto(`/organizations/${PUBLIC_ORG_ID}`)
  await waitForLayoutMounted(page)

  // 組織名（タイトル）が表示されるまで待つ
  await expect(
    page.getByRole('heading', { name: 'テスト町内会（公開）' }),
  ).toBeVisible()

  // 「所属チーム」タブを開く（タブ value=3）
  await page.getByRole('tab', { name: '所属チーム' }).click()

  // 「店舗を検索」ボタン（NuxtLink, aria-label="店舗を検索"）が見える
  const searchLink = page.getByRole('link', { name: '店舗を検索' })
  await expect(searchLink).toBeVisible()
  // href は /organizations/{id}/teams/search を指す
  await expect(searchLink).toHaveAttribute(
    'href',
    `/organizations/${PUBLIC_ORG_ID}/teams/search`,
  )

  // クリックで検索ページへ遷移する
  await searchLink.click()
  await expect(page).toHaveURL(
    new RegExp(`/organizations/${PUBLIC_ORG_ID}/teams/search`),
  )
  // 検索ページのタイトル（heading）が描画される
  await expect(page.getByRole('heading', { name: '店舗を検索' })).toBeVisible()
})
