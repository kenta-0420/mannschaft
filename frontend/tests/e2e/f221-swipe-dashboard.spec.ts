import { waitForHydratedShell } from './helpers/waitForHydratedShell'
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

/**
 * slug 移行後（PR #1413〜）の実 BE が返す slug。
 * チーム/組織の pathVariable は人間可読 slug（英字 + ハイフン）であり UUID ではない。
 */
const TEAM_SLUG = 'fc-u-18'
const ORG_SLUG = 'tokyo-fa'

/**
 * GET /api/v1/dashboard/scope-tabs（snake_case で返す = useScopeTabApi が camelCase 化）。
 *
 * @param withSlug true のとき public_id に人間可読 slug を載せる（slug 移行後の実 BE 挙動）。
 *   false のとき public_id を省略し scope_id（BIGINT）のみ（移行前 / 旧モック互換）。
 */
function mockScopeTabPage(scopeType: 'TEAM' | 'ORGANIZATION', withSlug = false, multipleScopes = false) {
  const isTeam = scopeType === 'TEAM'
  const secondaryId = isTeam ? 5002 : 6002
  const secondarySlug = isTeam ? 'fc-u-18-b' : 'tokyo-fa-b'
  return {
    items: [
      {
        scope_id: isTeam ? TEAM_ID : ORG_ID,
        ...(withSlug ? { public_id: isTeam ? TEAM_SLUG : ORG_SLUG } : {}),
        scope_type: scopeType,
        name: isTeam ? 'E2E チームA' : 'E2E 組織A',
        avatar_url: null,
        unread_count: 0,
        sort_order: 0,
      },
      ...(multipleScopes
        ? [{
            scope_id: secondaryId,
            ...(withSlug ? { public_id: secondarySlug } : {}),
            scope_type: scopeType,
            name: isTeam ? 'E2E チームB' : 'E2E 組織B',
            avatar_url: null,
            unread_count: 0,
            sort_order: 1,
          }]
        : []),
    ],
    page: 0,
    page_size: 6,
    total_pages: 1,
    total_count: multipleScopes ? 2 : 1,
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
  /** scope-tabs の public_id に slug を載せる（slug 移行後の実 BE 挙動を再現）*/
  withSlug?: boolean
  /** 複数タグを返し、未選択タグのキーボード操作を検証するためのfixture */
  multipleScopes?: boolean
  /** 容量APIの呼出回数を検証するフック */
  onStorageUsage?: () => void
  /** 容量APIを失敗させる検証用フック */
  storageUsageFailure?: boolean
  /** 警告Dialog表示用の容量mock */
  storageUsageWarning?: boolean
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
      // 未指定の一覧APIは空配列で返す。オブジェクト契約のAPIは専用fixtureで上書きする。
      body: JSON.stringify({ data: [], meta: {} }),
    })
  })

  // ストアと設定取得は配列契約。空objectを渡すとrenderが停止するため専用fixtureを返す。
  for (const endpoint of ['me/teams', 'me/organizations', 'dashboard/widgets?**']) {
    await page.route(`**/api/v1/${endpoint}`, route => route.fulfill({ json: { data: [] } }))
  }
  await page.route('**/api/v1/admin/member-permissions?**', route => route.fulfill({ json: { data: { permissions: [] } } }))

  // 容量サマリーは配列レスポンスを前提とするため、catch-allより後に専用mockを登録する。
  await page.route('**/api/v1/me/storage/usage', async (route: Route) => {
    opts.onStorageUsage?.()
    if (opts.storageUsageFailure) {
      await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: { code: 'TEST_STORAGE_FAILURE' } }) })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          scopeType: 'PERSONAL',
          scopeId: 1,
          scopeName: '個人',
          slug: null,
          usedBytes: 0,
          fileCount: 0,
          includedBytes: 1024,
          maxBytes: 1024,
          usagePercent: opts.storageUsageWarning ? 90 : 0,
        },
      ]),
    })
  })

  // 有料プラン画面の route gate が参照する公開フラグ。
  await page.route('**/api/v1/feature-flags', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: [{ flagKey: 'FEATURE_BILLING_PAYMENT_ENABLED', enabled: true }],
      }),
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
      body: JSON.stringify({ data: mockScopeTabPage(scopeType, opts.withSlug, opts.multipleScopes) }),
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
  await page.setViewportSize({ width: 390, height: 844 })
  let storageUsageCalls = 0
  await mockDashboardApis(page, { onStorageUsage: () => { storageUsageCalls += 1 } })

  await page.goto('/dashboard')
  await waitForCarousel(page)
  await expect(page.getByTestId('dashboard-storage-summary')).toHaveCount(1)
  await expect.poll(() => storageUsageCalls).toBe(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  const summaryBox = await page.getByTestId('dashboard-storage-summary').boundingBox()
  expect(summaryBox).not.toBeNull()
  expect(summaryBox!.x + summaryBox!.width).toBeLessThanOrEqual(390)

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

test('F22.1-9: 容量API失敗時もカルーセルと切替タブを維持する', async ({ page }) => {
  await loginAsMember(page)
  await mockDashboardApis(page, { storageUsageFailure: true })
  await page.goto('/dashboard')
  await waitForCarousel(page)
  await expect(page.getByTestId('storage-error')).toBeVisible()
  await expect(page.getByTestId('scope-carousel')).toBeVisible()
  await expect(page.getByTestId('scope-segment-PERSONAL')).toBeVisible()
})

test('F22.1-11: 容量カードの通常遷移と警告Dialogのプラン導線', async ({ page }) => {
  await loginAsMember(page)
  await mockDashboardApis(page)
  await page.goto('/dashboard')
  await waitForCarousel(page)
  await page.getByTestId('storage-card-0').click()
  await expect(page).toHaveURL(/\/settings\/storage/)

  await page.goto('/dashboard')
  await mockDashboardApis(page, { storageUsageWarning: true })
  await page.reload()
  await waitForCarousel(page)
  const warningCard = page.getByTestId('storage-card-0')
  await warningCard.click()
  await expect(page.getByRole('dialog')).toBeVisible()

  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toBeHidden()
  await expect(warningCard).toBeFocused()

  await warningCard.click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('button', { name: 'プランを見る' }).click()
  await expect(page).toHaveURL(/\/billing\/plans/)
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

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 7: slug 移行リグレッション — slug をパネル表示判定が受理し実コンテンツを描画
// ──────────────────────────────────────────────────────────────────────────

test('F22.1-7: scope-tabs が slug(public_id) を返すとき、チーム/組織パネルが永久スピナーにならず実コンテンツを描画する', async ({
  page,
}) => {
  // slug 移行（PR #1413〜）後、scope-tabs の public_id は人間可読 slug（fc-u-18 等）になる。
  // store は selectedTeamId に slug を入れるため、パネルの表示判定は slug を受理して
  // ダッシュボードを load しなければならない。
  //
  // 回帰の本丸: 旧実装は UUID 正規表現で id を判定していたため、slug（fc-u-18）は
  //   UUID にマッチせず else（loading=true 固定）に落ち、パネルが永久スピナーになっていた。
  //   本テストは withSlug=true で slug を返し、ウィジェットグリッド（実コンテンツ）が
  //   描画されることを検証する。旧実装ではここで permanent spinner となり失敗する。
  await loginAsMember(page)
  await mockDashboardApis(page, { withSlug: true })

  await page.goto('/dashboard')
  await waitForCarousel(page)

  // チームパネルへ
  await page.getByTestId('scope-segment-TEAM').click()
  const teamPanel = page.locator('#scope-panel-TEAM')
  // タグバーは描画される（slug ロード前から存在する静的要素）
  await expect(teamPanel.getByTestId('scope-tab-bar-TEAM')).toBeVisible()
  // 実コンテンツ（ウィジェットグリッド）が描画される = slug が load された証拠
  await expect(teamPanel.getByTestId('swipe-widget-grid-TEAM')).toBeVisible({ timeout: 20000 })
  // 永久スピナー（PageLoading）が残っていない
  await expect(teamPanel.locator('.pi-spin')).toHaveCount(0)

  // 組織パネルへ
  await page.getByTestId('scope-segment-ORGANIZATION').click()
  const orgPanel = page.locator('#scope-panel-ORGANIZATION')
  await expect(orgPanel.getByTestId('scope-tab-bar-ORGANIZATION')).toBeVisible()
  await expect(orgPanel.getByTestId('swipe-widget-grid-ORGANIZATION')).toBeVisible({
    timeout: 20000,
  })
  await expect(orgPanel.locator('.pi-spin')).toHaveCount(0)
})

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 8: slug 移行リグレッション — UUID 宛の不正なダッシュボード取得が発生しない
// ──────────────────────────────────────────────────────────────────────────

test('F22.1-8: slug ロード時に UUID 宛のダッシュボード取得が発生しない', async ({ page }) => {
  await loginAsMember(page)
  await mockDashboardApis(page, { withSlug: true })

  const uuidDashboardCalls: string[] = []
  const uuidRe =
    /\/api\/v1\/dashboard\/(team|organization)\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i
  page.on('request', (req) => {
    if (uuidRe.test(req.url())) uuidDashboardCalls.push(req.url())
  })

  await page.goto('/dashboard')
  await waitForCarousel(page)
  await page.getByTestId('scope-segment-TEAM').click()
  await expect(
    page.locator('#scope-panel-TEAM').getByTestId('swipe-widget-grid-TEAM'),
  ).toBeVisible({ timeout: 20000 })
  await page.getByTestId('scope-segment-ORGANIZATION').click()
  await expect(
    page.locator('#scope-panel-ORGANIZATION').getByTestId('swipe-widget-grid-ORGANIZATION'),
  ).toBeVisible({ timeout: 20000 })

  expect(
    uuidDashboardCalls,
    `UUID 宛のダッシュボード取得が発生した（slug 移行後は slug 宛であるべき）: ${uuidDashboardCalls.join(', ')}`,
  ).toHaveLength(0)
})

test('F22.1-9: selected scope chips open their scope pages by keyboard', async ({ page }) => {
  await loginAsMember(page)
  await mockDashboardApis(page, { withSlug: true })
  await page.goto('/dashboard')
  await waitForCarousel(page)

  const teamChip = page.getByTestId(`scope-tab-chip-TEAM-${TEAM_SLUG}`)
  await page.getByTestId('scope-segment-TEAM').click()
  await expect(page.getByTestId('scope-tab-go-to-page-TEAM')).toHaveCount(0)
  await expect(teamChip).toHaveAttribute('aria-pressed', 'true')
  await expect(teamChip).toHaveAttribute('aria-label', 'E2E チームAのページを開く')
  await teamChip.press('Enter')
  await page.waitForURL(`**/teams/${TEAM_SLUG}`)

  await page.goto('/dashboard')
  await waitForCarousel(page)
  const orgChip = page.getByTestId(`scope-tab-chip-ORGANIZATION-${ORG_SLUG}`)
  await page.getByTestId('scope-segment-ORGANIZATION').click()
  await expect(page.getByTestId('scope-tab-go-to-page-ORGANIZATION')).toHaveCount(0)
  await expect(orgChip).toHaveAttribute('aria-pressed', 'true')
  await expect(orgChip).toHaveAttribute('aria-label', 'E2E 組織Aのページを開く')
  await orgChip.press('Space')
  await page.waitForURL(`**/organizations/${ORG_SLUG}`)
})

test('F22.1-10: unselected scope chips are selected without navigation by keyboard', async ({ page }) => {
  await loginAsMember(page)
  await mockDashboardApis(page, { withSlug: true, multipleScopes: true })
  await page.goto('/dashboard')
  await waitForCarousel(page)

  await page.getByTestId('scope-segment-TEAM').click()
  const secondaryTeamChip = page.getByTestId('scope-tab-chip-TEAM-fc-u-18-b')
  await expect(secondaryTeamChip).toHaveAttribute('aria-pressed', 'false')
  await expect(secondaryTeamChip).toHaveAttribute('aria-label', 'E2E チームB')
  await secondaryTeamChip.press('Enter')
  await expect(secondaryTeamChip).toHaveAttribute('aria-pressed', 'true')
  await expect(page).toHaveURL(/\/dashboard(?:\/|$)/)

  await page.getByTestId('scope-segment-ORGANIZATION').click()
  const secondaryOrgChip = page.getByTestId('scope-tab-chip-ORGANIZATION-tokyo-fa-b')
  await expect(secondaryOrgChip).toHaveAttribute('aria-pressed', 'false')
  await expect(secondaryOrgChip).toHaveAttribute('aria-label', 'E2E 組織B')
  await secondaryOrgChip.press('Space')
  await expect(secondaryOrgChip).toHaveAttribute('aria-pressed', 'true')
  await expect(page).toHaveURL(/\/dashboard(?:\/|$)/)
})

// CMP-261016-0955: ブラウザの実寸・行位置でモバイル表示の回帰を検証する。
for (const locale of ['ja', 'de']) {
  test(`INSIGHT-I5-HOME: ${locale}のホームから自分の連絡先QRを開ける`, async ({ page, baseURL }) => {
    await page.context().addCookies([{ name: 'i18n_locale', value: locale, url: baseURL! }])
    await page.setViewportSize({ width: 360, height: 900 })
    await loginAsMember(page)
    await page.addInitScript(() => localStorage.setItem('tokenExpiresAt', String(Date.now() + 3600000)))
    await mockDashboardApis(page, { withSlug: true })
    const contactMutations: string[] = []
    const homeContactRequests: string[] = []
    page.on('request', request => {
      if (request.url().includes('/api/v1/contact-invite-tokens')) {
        if (request.method() !== 'GET') contactMutations.push(request.method())
        if (new URL(page.url()).pathname === '/dashboard') homeContactRequests.push(request.url())
      }
    })
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydratedShell(page)
    await waitForCarousel(page)
    const panel = page.locator('#scope-panel-PERSONAL')
    const qr = panel.getByRole('link', { name: locale === 'de' ? 'Mein Kontakt-QR-Code' : '自分の連絡先QR', exact: true })
    await expect(qr).toHaveAttribute('href', '/settings/contact-invite-tokens')
    if (locale === 'de') await qr.evaluate(element => { element.style.fontSize = `${parseFloat(getComputedStyle(element).fontSize) * 2}px` })
    for (const width of [360, 390]) {
      await page.setViewportSize({ width, height: 900 })
      const box = (await qr.boundingBox())!
      expect(box.width).toBeGreaterThanOrEqual(44)
      expect(box.height).toBeGreaterThanOrEqual(44)
      expect(box.x).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width).toBeLessThanOrEqual(width)
      expect(await qr.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
      const textRects = await qr.locator('span').evaluate(element => {
        const range = document.createRange()
        range.selectNodeContents(element)
        return [...range.getClientRects()].map(rect => ({ left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom }))
      })
      expect(textRects.length).toBeGreaterThan(0)
      for (const rect of textRects) {
        expect(rect.left).toBeGreaterThanOrEqual(box.x - 1)
        expect(rect.right).toBeLessThanOrEqual(box.x + box.width + 1)
        expect(rect.top).toBeGreaterThanOrEqual(box.y - 1)
        expect(rect.bottom).toBeLessThanOrEqual(box.y + box.height + 1)
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
    }
    expect(homeContactRequests).toEqual([])
    await qr.click()
    await expect(page).toHaveURL(/\/settings\/contact-invite-tokens$/)
    await expect(page.locator('h1')).toContainText(locale === 'de' ? 'Kontakt' : '連絡先QR')
    expect(contactMutations).toEqual([])
  })
}

for (const width of [360, 390, 1024]) {
  test(`INSIGHT-I6-DOTS: ${width}pxで下部ドットを44pxの領域で操作できる`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    await page.addInitScript(() => localStorage.setItem('tokenExpiresAt', String(Date.now() + 3600000)))
    await loginAsMember(page)
    await mockDashboardApis(page, { withSlug: true })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydratedShell(page)
    await waitForCarousel(page)
    const carousel = page.getByTestId('scope-carousel')
    const panels = [
      { name: '個人', panel: 'PERSONAL' },
      { name: 'チーム', panel: 'TEAM' },
      { name: '組織', panel: 'ORGANIZATION' },
    ]
    await page.getByTestId('scope-next').click()
    const metrics = []
    for (const { name, panel } of panels) {
      const dot = carousel.getByRole('button', { name, exact: true })
      await expect(dot).toHaveCount(1)
      await dot.scrollIntoViewIfNeeded()
      const box = (await dot.boundingBox())!
      metrics.push({ name, box })
      expect.soft(box.width).toBeGreaterThanOrEqual(44)
      expect.soft(box.height).toBeGreaterThanOrEqual(44)
      await dot.click()
      await expect(dot).toHaveAttribute('aria-current', 'true')
      await expect(carousel.locator('button[aria-current="true"]')).toHaveCount(1)
      await expect(page.locator(`#scope-panel-${panel}`)).toHaveAttribute('aria-hidden', 'false')
      await expect(page.getByTestId(`scope-segment-${panel}`)).toHaveAttribute('aria-selected', 'true')
    }
    for (let index = 1; index < metrics.length; index += 1) {
      const previous = metrics[index - 1]!.box
      expect(metrics[index]!.box.x).toBeGreaterThanOrEqual(previous.x + previous.width)
    }
    await testInfo.attach(`dot-hit-areas-${width}`, { body: JSON.stringify({ width, metrics }), contentType: 'application/json' })
    await testInfo.attach(`dot-controls-${width}`, { body: await page.screenshot(), contentType: 'image/png' })
    const visuals = []
    for (const { name } of panels) {
      const visual = carousel.getByRole('button', { name, exact: true }).locator('span[aria-hidden="true"]')
      await expect(visual).toHaveCount(1)
      const visualBox = (await visual.boundingBox())!
      visuals.push({ name, box: visualBox })
      expect(visualBox.width).toBe(10)
      expect(visualBox.height).toBe(10)
      expect(await visual.evaluate(element => getComputedStyle(element).borderRadius)).toBe('9999px')
    }
    await testInfo.attach(`dot-visuals-${width}`, { body: JSON.stringify({ width, visuals }), contentType: 'application/json' })
  })
}

for (const width of [360, 390, 312, 1024, 1280]) {
  test(`INSIGHT-I6: ${width}pxでスコープ切替と設定の操作領域を確保する`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await page.addInitScript(() => localStorage.setItem('tokenExpiresAt', String(Date.now() + 3600000)))
    await loginAsMember(page)
    await mockDashboardApis(page, { withSlug: true })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydratedShell(page)
    await waitForCarousel(page)
    const tablist = page.getByTestId('scope-segment-tablist')
    const previous = page.getByTestId('scope-prev')
    const next = page.getByTestId('scope-next')
    const tabs = tablist.getByRole('tab')
    const config = page.getByRole('button', { name: 'ウィジェット設定', exact: true })
    for (const target of [previous, next, config, ...await tabs.all()]) {
      await expect(target).toBeVisible()
      const box = await target.boundingBox()
      expect(box).not.toBeNull()
      expect.soft(box!.width).toBeGreaterThanOrEqual(44)
      expect.soft(box!.height).toBeGreaterThanOrEqual(44)
    }
    const tabBox = (await tablist.boundingBox())!
    const prevBox = (await previous.boundingBox())!
    const nextBox = (await next.boundingBox())!
    if (width < 768) {
      expect.soft(prevBox.y).toBeGreaterThanOrEqual(tabBox.y + tabBox.height)
      expect.soft(nextBox.y).toBeGreaterThanOrEqual(tabBox.y + tabBox.height)
    } else {
      expect(prevBox.x + prevBox.width).toBeLessThanOrEqual(tabBox.x)
      expect(nextBox.x).toBeGreaterThanOrEqual(tabBox.x + tabBox.width)
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0)
    await next.click()
    await expect(page.getByTestId('scope-segment-TEAM')).toHaveAttribute('aria-selected', 'true')
    await previous.click()
    await expect(page.getByTestId('scope-segment-PERSONAL')).toHaveAttribute('aria-selected', 'true')
    await config.click()
    await expect(page.getByRole('dialog')).toBeVisible()
  })
}

test('INSIGHT-I6-DE: 360pxのドイツ語を200%文字で読んで切り替えられる', async ({ page, baseURL }) => {
  await page.setViewportSize({ width: 360, height: 1000 })
  await page.context().addCookies([{ name: 'i18n_locale', value: 'de', url: baseURL ?? 'http://localhost:8081' }])
  await page.addInitScript(() => localStorage.setItem('tokenExpiresAt', String(Date.now() + 3600000)))
  await loginAsMember(page)
  await mockDashboardApis(page, { withSlug: true })
  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydratedShell(page)
  await waitForCarousel(page)
  await page.locator("[data-testid=\"scope-segment-tablist\"] [role=\"tab\"]").evaluateAll(elements => {
    const sizes = elements.map(element => Number.parseFloat(getComputedStyle(element).fontSize))
    elements.forEach((element, index) => (element as HTMLElement).style.setProperty('font-size', String(sizes[index]! * 2) + 'px', 'important'))
  })
  const tablist = page.getByTestId('scope-segment-tablist')
  await expect(tablist.getByRole('tab', { name: 'Organisation', exact: true })).toBeVisible()
  for (const tab of await tablist.getByRole('tab').all()) {
    const text = await tab.evaluate(element => {
      const node = [...element.childNodes].find(child => child.nodeType === Node.TEXT_NODE && child.textContent?.trim())
      if (!node) return null
      const range = document.createRange()
      range.selectNodeContents(node)
      return [...range.getClientRects()].map(rect => ({ top: rect.top, right: rect.right, left: rect.left }))
    })
    expect(text).not.toBeNull()
    expect(text).toHaveLength(1)
    const box = (await tab.boundingBox())!
    expect(text![0]!.left).toBeGreaterThanOrEqual(box.x)
    expect(text![0]!.right).toBeLessThanOrEqual(box.x + box.width)
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0)
  await tablist.getByRole('tab', { name: 'Organisation', exact: true }).click()
  await expect(page.getByTestId('scope-segment-ORGANIZATION')).toHaveAttribute('aria-selected', 'true')
})

// CMP-261019-1229: 所属がある場合のスコープナビを実描画の文字行・矩形で保護する。
for (const width of [360, 390, 1280]) {
  test.describe(`スコープナビ ${width}px`, () => {
    const mobile = width < 768
    test.use({ viewport: { width, height: 800 }, isMobile: mobile, hasTouch: mobile })

    for (const scopeType of ['TEAM', 'ORGANIZATION'] as const) {
      test(`NAV-GEOMETRY: ${scopeType}の一覧を横に読み、一覧と直接ジャンプを操作できる`, async ({ page, baseURL }, testInfo) => {
        await page.context().addCookies([{ name: 'i18n_locale', value: 'ja', url: baseURL! }])
        await page.addInitScript(() => localStorage.setItem('tokenExpiresAt', String(Date.now() + 3600000)))
        await loginAsMember(page)
        await mockDashboardApis(page, { withSlug: true })
        const team = { id: TEAM_ID, slug: TEAM_SLUG, name: 'ナビ回帰チーム', nickname1: null, role: 'MEMBER' }
        const org = { id: ORG_ID, slug: ORG_SLUG, name: 'ナビ回帰組織', nickname1: null, role: 'MEMBER' }
        await page.route('**/api/v1/me/teams', route => route.fulfill({ json: { data: [team] } }))
        await page.route('**/api/v1/me/organizations', route => route.fulfill({ json: { data: [org] } }))
        await page.route('**/api/v1/me/scope-folders**', route => route.fulfill({ json: { data: [] } }))
        await page.emulateMedia({ reducedMotion: 'reduce' })
        await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
        await waitForHydratedShell(page)
        await waitForCarousel(page)

        const scope = scopeType === 'TEAM' ? team : org
        const basePath = scopeType === 'TEAM' ? '/teams' : '/organizations'
        const openMenu = async () => {
          const trigger = page.locator(`[data-testid="scope-nav-dropdown-toggle-${scopeType}"]:visible`).first()
          if (!await trigger.isVisible()) {
            await page.getByRole('button', { name: 'メニューを開く', exact: true }).tap()
          }
          if (mobile) await trigger.tap()
          else await trigger.click()
          const hub = page.locator(`[data-testid="scope-nav-dropdown-all-hub-${scopeType}"]:visible`)
          await expect(hub).toBeVisible()
          const panel = page.locator('.p-popover:visible').filter({ has: hub })
          await expect(panel).toHaveCount(1)
          const jump = panel.getByTestId(`scope-nav-dropdown-scope-${scope.id}`)
          await expect(jump).toBeVisible()
          return { panel, hub, jump }
        }
        let menu = await openMenu()
        const all = menu.panel.getByRole('menuitem').filter({ has: page.getByText('すべて（一覧）', { exact: true }) })
        await expect(all).toHaveCount(1)
        await expect(all.locator('span')).toHaveText('すべて（一覧）')
        await expect(all).toBeVisible()
        await page.evaluate(() => document.fonts.ready.then(() => true))
        const lines = await all.locator('span').evaluate(element => {
          const range = document.createRange()
          range.selectNodeContents(element)
          return [...range.getClientRects()].map(rect => ({ top: rect.top, left: rect.left, right: rect.right }))
        })
        const foldersBox = await all.evaluate(element => {
          const box = element.parentElement!.parentElement!.getBoundingClientRect()
          return { x: box.x, y: box.y, width: box.width, height: box.height }
        })
        const jumpBox = await menu.jump.evaluate(element => {
          const box = element.parentElement!.getBoundingClientRect()
          return { x: box.x, y: box.y, width: box.width, height: box.height }
        })
        const documentSize = await page.evaluate(() => ({ width: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth, bodyWidth: document.body.scrollWidth }))
        await testInfo.attach('scope-navigation-geometry', { body: JSON.stringify({ width, scopeType, lines, foldersBox, jumpBox, documentSize }), contentType: 'application/json' })
        expect(lines, 'ALL_LIST_TEXT_HORIZONTAL: 一覧の文字が複数行に縦折返ししている').toHaveLength(1)
        if (mobile) {
          expect(jumpBox.y).toBeGreaterThanOrEqual(foldersBox.y + foldersBox.height - 1)
        } else {
          expect(Math.abs(jumpBox.y - foldersBox.y)).toBeLessThanOrEqual(1)
          expect(jumpBox.x).toBeGreaterThanOrEqual(foldersBox.x + foldersBox.width - 1)
        }
        expect(documentSize.scrollWidth).toBeLessThanOrEqual(documentSize.width)
        expect(documentSize.bodyWidth).toBeLessThanOrEqual(documentSize.width)

        if (mobile) await all.tap()
        else await all.click()
        await expect(menu.panel.getByRole('menuitem', { name: scope.name, exact: true })).toHaveCount(2)
        if (mobile) await menu.hub.tap()
        else await menu.hub.click()
        await expect(page).toHaveURL(new RegExp(`${basePath}$`))
        await expect(menu.panel).toBeHidden()
        if (mobile) await expect(page.locator('.p-drawer')).toBeHidden()
        menu = await openMenu()
        if (mobile) await menu.jump.tap()
        else await menu.jump.click()
        await expect(page).toHaveURL(new RegExp(`${basePath}/${scope.slug}$`))
      })
    }
  })
}

// happy-domではDOMPurifyの安全性を保証できないため、実ブラウザの負例をCI smokeへ置く。
for (const kind of ['bulletin', 'blog'] as const) {
  test(`F22.1-AC7: ${kind}本文のscript・イベント属性・危険URLを実Chromiumで除去する`, async ({ page }) => {
    await loginAsMember(page)
    await mockDashboardApis(page, { withSlug: true })
    const id = kind === 'bulletin' ? 301 : 302
    const dangerous = '<p>取得した本文</p><script>window.__scopePreviewXss=true</script><img src="x" onerror="window.__scopePreviewXss=true"><a href="javascript:window.__scopePreviewXss=true">危険リンク</a>'
    await page.route('**/api/v1/dashboard/team/*', route => route.fulfill({ json: { data: {
      ...MOCK_TEAM_DASHBOARD,
      teamUnreadThreads: { bulletin_threads: kind === 'bulletin' ? [{ id, title: '安全性検査', updated_at: '2026-10-23T12:00:00+09:00', is_read: true }] : [] },
      teamLatestBlogPosts: kind === 'blog' ? [{ id, slug: 'sanitize-post', title: '安全性検査', author: null, published_at: null }] : [],
    } } }))
    if (kind === 'bulletin') {
      await page.route(`**/api/v1/teams/${TEAM_ID}/bulletin/threads/${id}`, route => route.fulfill({ json: { data: {
        id, scopeType: 'TEAM', scopeId: TEAM_ID, title: '安全性検査', body: dangerous,
      } } }))
    } else {
      await page.route('**/api/v1/blog/posts/sanitize-post?**', route => route.fulfill({ json: { data: {
        id, content: { title: '安全性検査', slug: 'sanitize-post', body: `**Markdown本文**\n\n${dangerous}` },
        scope: { teamId: TEAM_ID, organizationId: null, userId: null },
      } } }))
    }
    await page.goto('/dashboard')
    await waitForCarousel(page)
    await page.getByTestId('scope-segment-TEAM').click()
    await page.getByTestId(`scope-widget-${kind}-${id}`).click()
    const body = page.getByTestId('scope-widget-preview-body')
    await expect(body).toContainText('取得した本文')
    if (kind === 'blog') await expect(body.locator('strong')).toHaveText('Markdown本文')
    await expect(body.locator('script')).toHaveCount(0)
    await expect(body.locator('[onerror]')).toHaveCount(0)
    await expect(body.locator('a[href^="javascript:"]')).toHaveCount(0)
    expect(await page.evaluate(() => Reflect.get(window, '__scopePreviewXss'))).toBeUndefined()
    await expect(page.getByTestId('scope-widget-preview-source')).toBeVisible()
  })
}
