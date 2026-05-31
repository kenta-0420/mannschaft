import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * F22.1 横スワイプ・スコープダッシュボード — E2E スモーク（CI 統合用）
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.6 / §3
 *
 * 設計思想（F15.4-org-team-search.spec.ts を手本にする）:
 *   - すべての API レスポンスを `page.route()` でモック化し、バックエンド状態に依存させない。
 *     → CI 環境（バックエンド非起動）でも決定的に動作する。
 *   - 認証は addInitScript で localStorage の currentUser を注入（PR #1000 以降の方式）。
 *     authStore.isAuthenticated = !!user のため、scope-dashboard プラグインが
 *     起動直後に scope-tabs を取りに行く。これも先回りでモックする。
 *   - スワイプ（touch 慣性）は CI で不安定なため検証対象から除外し、
 *     セグメントトグル / 左右矢印 / キーボード ←→ という決定的操作のみで切替を検証する。
 *
 * 採用シナリオ（いずれも決定的・シードレス）:
 *   F22.1-1: /dashboard がカルーセルシェルを描画し、初期 = 個人パネル（PERSONAL が aria-selected）
 *   F22.1-2: セグメントトグルで 個人→チーム→組織 と切替（aria-selected が移る・3 パネル常時 DOM）
 *   F22.1-3: 右矢印で循環（組織→個人へ wrap）。キーボード ← でも循環する
 *   F22.1-4: 個人パネルには検索フォーム・タグ行が出ない／チーム・組織パネルには出る
 *   F22.1-5: チーム検索フォーム submit で /teams/search?keyword=... へ遷移しクエリが乗る
 *   F22.1-6: localStorage(scope-dashboard) にアクティブパネルが保存され、リロードで復元される
 */

// ──────────────────────────────────────────────────────────────────────────
// フィクスチャ
// ──────────────────────────────────────────────────────────────────────────

const TEAM_ID = 5001
const ORG_ID = 6001

/** GET /api/v1/dashboard/scope-tabs（snake_case で返す = useScopeTabApi が camelCase 化）*/
function mockScopeTabPage(scopeType: 'TEAM' | 'ORGANIZATION') {
  const isTeam = scopeType === 'TEAM'
  return {
    items: [
      {
        scope_id: isTeam ? TEAM_ID : ORG_ID,
        scope_type: scopeType,
        name: isTeam ? 'E2E チームA' : 'E2E 組織A',
        avatar_url: null,
        unread_count: 0,
        sort_order: 0,
      },
    ],
    page: 0,
    page_size: 6,
    total_pages: 1,
    total_count: 1,
    has_next: false,
    has_prev: false,
  }
}

/** GET /api/v1/dashboard/team/{id}（TeamDashboardResponse 相当の最小モック）*/
const MOCK_TEAM_DASHBOARD = {
  teamUpcomingEvents: [],
  teamLatestPosts: [],
  teamUnreadThreads: { unreadCount: 0 },
  teamLatestBlogPosts: [],
  teamChatSummary: { totalUnread: 0 },
  teamCalendarSummary: { eventsThisWeek: 0 },
  teamTodo: { pendingCount: 0 },
}

/** GET /api/v1/dashboard/organization/{id}（OrgDashboardResponse 相当の最小モック）*/
const MOCK_ORG_DASHBOARD = {
  orgUpcomingEvents: [],
  orgLatestPosts: [],
  orgUnreadThreads: { unreadCount: 0 },
  orgLatestBlogPosts: [],
  orgChatSummary: { totalUnread: 0 },
  orgCalendarSummary: { eventsThisWeek: 0 },
  orgTodo: { pendingCount: 0 },
}

/** GET .../action-required（要対応サマリの最小モック）*/
const MOCK_ACTION_REQUIRED = {
  circulation: { unconfirmed_count: 0, items: [] },
  survey: { unanswered_count: 0, items: [] },
  attendance: { unanswered_count: 0, items: [] },
  total_action_count: 0,
}

interface MockOptions {
  /** 検索 API（scope-tabs）に渡された URL を観測するフック */
  onSearchTabs?: (url: URL) => void
}

/**
 * F22.1 ダッシュボードが必要とする API をすべてモックする。
 *
 * 個人パネルおよびレイアウト（NotificationBell / ScopeNav 等）が大量の widget API を
 * 発火するため、まず広域キャッチオール（/api/v1/**）で安全な空レスポンスを返し、
 * 続けて F22.1 固有のエンドポイントを個別に上書きする。
 * Playwright の route は「後勝ち」のため、固有モックを後に登録する。
 */
async function mockDashboardApis(page: Page, opts: MockOptions = {}): Promise<void> {
  // --- 広域キャッチオール: 未指定 API はすべて空 data で 200 を返す ---
  await page.route('**/api/v1/**', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      // data を null・配列・オブジェクトのいずれでも受けられるよう空オブジェクトで返す。
      // 多くの widget は data 不在をローディング解除＆空状態として扱う。
      body: JSON.stringify({ data: {}, meta: {} }),
    })
  })

  // --- 認証リフレッシュ（401 連鎖でログアウトさせない）---
  await page.route('**/api/v1/auth/refresh', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { accessToken: 'mock-e2e-token', refreshToken: 'mock-e2e-refresh' },
      }),
    })
  })

  // --- scope-tabs（タグ一覧）---
  await page.route('**/api/v1/dashboard/scope-tabs?**', async (route: Route) => {
    const url = new URL(route.request().url())
    opts.onSearchTabs?.(url)
    const scopeType = (url.searchParams.get('scopeType') as 'TEAM' | 'ORGANIZATION') ?? 'TEAM'
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: mockScopeTabPage(scopeType) }),
    })
  })

  // --- チームダッシュボード ---
  await page.route('**/api/v1/dashboard/team/*', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_TEAM_DASHBOARD }),
    })
  })

  // --- 組織ダッシュボード ---
  await page.route('**/api/v1/dashboard/organization/*', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_ORG_DASHBOARD }),
    })
  })

  // --- 要対応サマリ（team / organization 両方）---
  await page.route('**/api/v1/dashboard/*/*/action-required', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_ACTION_REQUIRED }),
    })
  })
}

/** 認証会員として localStorage に currentUser を注入する（PR #1000 以降の方式）。 */
async function loginAsMember(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: 201,
        email: 'e2e-f221@example.com',
        fullName: 'F22.1 メンバー',
        profileImageUrl: null,
        systemRole: undefined,
      }),
    )
  })
}

/**
 * レイアウト（<header>）とカルーセル本体がマウントされるまで待機する。
 * default.vue は onMounted で isMounted=true に切り替わって <header> を描画する。
 */
async function waitForCarousel(page: Page): Promise<void> {
  // Nuxt dev サーバはルート初回ヒット時にオンデマンドコンパイルするため、
  // 1 本目のテストは header 出現まで数十秒かかることがある（splash の loading が表示される）。
  // CI でも初回ルートコンパイルのコストを払うため、余裕を持って 60 秒待つ。
  await page.locator('header').waitFor({ state: 'visible', timeout: 60000 })
  await page
    .getByTestId('scope-carousel')
    .waitFor({ state: 'visible', timeout: 30000 })
}

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 1: カルーセルシェル描画 + 初期 = 個人パネル
// ──────────────────────────────────────────────────────────────────────────

test('F22.1-1: /dashboard がカルーセルを描画し、初期は個人パネル（PERSONAL が選択）', async ({
  page,
}) => {
  await loginAsMember(page)
  await mockDashboardApis(page)

  await page.goto('/dashboard')
  await waitForCarousel(page)

  // セグメントトグル（個人/チーム/組織）が存在する
  await expect(page.getByTestId('scope-segment-PERSONAL')).toBeVisible()
  await expect(page.getByTestId('scope-segment-TEAM')).toBeVisible()
  await expect(page.getByTestId('scope-segment-ORGANIZATION')).toBeVisible()

  // 初期アクティブは PERSONAL
  await expect(page.getByTestId('scope-segment-PERSONAL')).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.getByTestId('scope-segment-TEAM')).toHaveAttribute(
    'aria-selected',
    'false',
  )

  // 3 パネルが常時 DOM 上に存在する（v-show 不使用・再描画なし）
  await expect(page.locator('#scope-panel-PERSONAL')).toHaveCount(1)
  await expect(page.locator('#scope-panel-TEAM')).toHaveCount(1)
  await expect(page.locator('#scope-panel-ORGANIZATION')).toHaveCount(1)
})

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 2: セグメントトグルで 個人→チーム→組織 切替
// ──────────────────────────────────────────────────────────────────────────

test('F22.1-2: セグメントトグルで個人→チーム→組織と切り替わり aria-selected が移る', async ({
  page,
}) => {
  await loginAsMember(page)
  await mockDashboardApis(page)

  await page.goto('/dashboard')
  await waitForCarousel(page)

  // チームへ
  await page.getByTestId('scope-segment-TEAM').click()
  await expect(page.getByTestId('scope-segment-TEAM')).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.getByTestId('scope-segment-PERSONAL')).toHaveAttribute(
    'aria-selected',
    'false',
  )

  // 組織へ
  await page.getByTestId('scope-segment-ORGANIZATION').click()
  await expect(page.getByTestId('scope-segment-ORGANIZATION')).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.getByTestId('scope-segment-TEAM')).toHaveAttribute(
    'aria-selected',
    'false',
  )
})

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 3: 左右矢印 / キーボードで循環
// ──────────────────────────────────────────────────────────────────────────

test('F22.1-3: 右矢印ボタンで循環し、キーボード ← でも逆方向に循環する', async ({
  page,
}) => {
  await loginAsMember(page)
  await mockDashboardApis(page)

  await page.goto('/dashboard')
  await waitForCarousel(page)

  // 右矢印 3 回で 個人→チーム→組織→個人（循環して個人へ戻る）
  await page.getByTestId('scope-next').click()
  await expect(page.getByTestId('scope-segment-TEAM')).toHaveAttribute('aria-selected', 'true')
  await page.getByTestId('scope-next').click()
  await expect(page.getByTestId('scope-segment-ORGANIZATION')).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await page.getByTestId('scope-next').click()
  await expect(page.getByTestId('scope-segment-PERSONAL')).toHaveAttribute(
    'aria-selected',
    'true',
  )

  // キーボード ←（個人 → 組織へ循環）。入力フォーカスを持たない body にフォーカスを当てる。
  await page.locator('body').click({ position: { x: 5, y: 5 } })
  await page.keyboard.press('ArrowLeft')
  await expect(page.getByTestId('scope-segment-ORGANIZATION')).toHaveAttribute(
    'aria-selected',
    'true',
  )
})

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 4: 個人パネルに検索/タグ無し・チーム/組織パネルに有り
// ──────────────────────────────────────────────────────────────────────────

test('F22.1-4: 個人パネル内に検索フォーム・タグ行が無く、チーム/組織パネル内には有る', async ({
  page,
}) => {
  await loginAsMember(page)
  await mockDashboardApis(page)

  await page.goto('/dashboard')
  await waitForCarousel(page)

  // 設計上 3 パネルは常時 DOM 上にマウントされ、非アクティブパネルは translateX で
  // 画面外に退避される（再描画なし）。したがって TEAM/ORG パネルの検索フォーム自体は
  // DOM に存在するが「個人パネルの内側には無い」ことを #scope-panel-PERSONAL に
  // スコープして検証する（設計書 03 §3.9 個人パネルにタグ行/検索なし）。
  const personalPanel = page.locator('#scope-panel-PERSONAL')
  await expect(personalPanel.getByTestId('scope-search-form-TEAM')).toHaveCount(0)
  await expect(personalPanel.getByTestId('scope-search-form-ORGANIZATION')).toHaveCount(0)
  await expect(personalPanel.getByTestId('scope-tab-bar-TEAM')).toHaveCount(0)
  await expect(personalPanel.getByTestId('scope-tab-bar-ORGANIZATION')).toHaveCount(0)

  // 逆に TEAM パネルの内側には検索フォーム・タグ行が存在する。
  const teamPanel = page.locator('#scope-panel-TEAM')
  await expect(teamPanel.getByTestId('scope-search-form-TEAM')).toHaveCount(1)
  await expect(teamPanel.getByTestId('scope-tab-bar-TEAM')).toHaveCount(1)

  // 組織パネルの内側にも同様に存在する。
  const orgPanel = page.locator('#scope-panel-ORGANIZATION')
  await expect(orgPanel.getByTestId('scope-search-form-ORGANIZATION')).toHaveCount(1)
  await expect(orgPanel.getByTestId('scope-tab-bar-ORGANIZATION')).toHaveCount(1)

  // チームへ切替: チーム検索フォーム・タグ行が可視になる
  await page.getByTestId('scope-segment-TEAM').click()
  await expect(teamPanel.getByTestId('scope-search-form-TEAM')).toBeVisible()
  await expect(teamPanel.getByTestId('scope-tab-bar-TEAM')).toBeVisible()

  // 組織へ切替: 組織検索フォーム・タグ行が可視になる
  await page.getByTestId('scope-segment-ORGANIZATION').click()
  await expect(orgPanel.getByTestId('scope-search-form-ORGANIZATION')).toBeVisible()
  await expect(orgPanel.getByTestId('scope-tab-bar-ORGANIZATION')).toBeVisible()
})

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 5: チーム検索フォーム submit で /teams/search?keyword=... へ遷移
// ──────────────────────────────────────────────────────────────────────────

test('F22.1-5: チーム検索フォームに入力して送信すると /teams/search?keyword=... へ遷移する', async ({
  page,
}) => {
  await loginAsMember(page)
  await mockDashboardApis(page)

  await page.goto('/dashboard')
  await waitForCarousel(page)

  // チームパネルへ
  await page.getByTestId('scope-segment-TEAM').click()
  const form = page.getByTestId('scope-search-form-TEAM')
  await expect(form).toBeVisible()

  // キーワードを入力して送信（フォーム内の InputText に入力）
  await form.locator('input').fill('サッカー')
  await form.locator('input').press('Enter')

  // /teams/search へ keyword クエリ付きで遷移する
  await page.waitForURL(/\/teams\/search\?.*keyword=/, { timeout: 30000 })
  const dest = new URL(page.url())
  expect(dest.pathname).toBe('/teams/search')
  expect(dest.searchParams.get('keyword')).toBe('サッカー')
})

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 6: localStorage(scope-dashboard) にアクティブパネルが保存・リロードで復元
// ──────────────────────────────────────────────────────────────────────────

test('F22.1-6: アクティブパネルが localStorage に保存され、リロード後も復元される', async ({
  page,
}) => {
  await loginAsMember(page)
  await mockDashboardApis(page)

  await page.goto('/dashboard')
  await waitForCarousel(page)

  // 組織パネルへ切替
  await page.getByTestId('scope-segment-ORGANIZATION').click()
  await expect(page.getByTestId('scope-segment-ORGANIZATION')).toHaveAttribute(
    'aria-selected',
    'true',
  )

  // localStorage に activePanel=ORGANIZATION が保存されている
  await expect
    .poll(async () => {
      return page.evaluate(() => {
        const raw = localStorage.getItem('scope-dashboard')
        if (!raw) return null
        try {
          return (JSON.parse(raw) as { activePanel?: string }).activePanel ?? null
        } catch {
          return null
        }
      })
    })
    .toBe('ORGANIZATION')

  // リロード後も組織パネルが復元される（プラグインが loadFromStorage で復元）
  await page.reload()
  await waitForCarousel(page)
  await expect(page.getByTestId('scope-segment-ORGANIZATION')).toHaveAttribute(
    'aria-selected',
    'true',
  )
})
