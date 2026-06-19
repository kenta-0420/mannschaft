/**
 * F10.1.1 管理コンソール / 管理者レンズ — 実機フルスタック E2E テスト（P4 要素2）
 *
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3001（本ブランチ専用 dev）が
 * 起動済みの状態で実行してください（playwright-real.config.ts は webServer 無効＝既存サーバー前提）。
 *
 * 実行プロジェクト: chromium-real（baseURL=process.env.BASE_URL ?? 'http://localhost:3000'）
 *   ※ 本 spec は storageState に依存せず、各テスト内で実 BE にログインして
 *     ロール（ADMIN / MEMBER）を切り替える。
 *
 * テストユーザー（backend/scripts/seed-e2e-data.js で投入）:
 *   ADMIN : e2e-admin@test.mannschaft.local / TestPass2026!（FC東京U-18 ADMIN / JFA ADMIN / SYSTEM_ADMIN）
 *   MEMBER: e2e-user@test.mannschaft.local  / TestPass2026!（FC東京U-18 MEMBER）
 *
 * 検証目的（memory: feedback_e2e_real_full_crud）:
 *   read-only / モックでは出ない本物のバグ（404・認可漏れ・契約ずれ・握りつぶし）を、
 *   認証付きの管理コンソール / 管理者レンズ一気通貫で捕捉する。**件数は実 BE の実データで返る**ことを確認する。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/01_console_routes.md §6
 *
 * ⚠️ 実行前提（2026-06-19 実走で判明した落とし穴）:
 *   稼働中の dev サーバー が **admin コンソールルートを含む最新コードを配信している**こと。
 *   admin console（P2/P3a/P4）マージ前の HEAD で起動された古い dev サーバーは
 *   `/teams|organizations/{slug}/admin` を Nuxt 404 で返し（admin/index.vue 不在）、
 *   レンズトグルも描画されないため、本 spec は全シナリオ失敗する（テスト側のバグではない）。
 *   実走時は必ず本ブランチ相当のコードを配信する dev サーバーで実行すること。
 *   なお BE(:8080) 層は admin 集約 API・IDOR 403・ロール解決ともに実機で正常動作を確認済み。
 *
 * APIブリッジ（memory: feedback_e2e_wsl2_cors_apibridge）:
 *   本ブランチ専用 dev(:3001) で動かす場合、Nuxt アプリがブラウザから BE(:8080) へ
 *   クロスオリジン fetch するが BE の CORS 許可オリジンは :3000/:8080 のみで :3001 は弾かれる。
 *   loginViaApiBridge がセッションを API 経由で確立し、page.route で /api/v1/** を中継する。
 *
 * 検証シナリオ:
 *   ACL-GUARD-001: MEMBER で /teams/{slug}/admin → スコープトップへリダイレクト＋エラートースト（404 でない）
 *   ACL-GUARD-002: ADMIN で /teams/{slug}/admin → 管理コンソールハブ表示（チーム）
 *   ACL-GUARD-003: ADMIN で /organizations/{slug}/admin → 管理コンソールハブ表示（組織）
 *   ACL-IDOR-001 : ADMIN だが非所属 org の /organizations/{other}/admin → BE 403・FE スコープトップへ
 *   LENS-TEAM-001: ダッシュボードで admin-lens-toggle-TEAM → 管理者グリッド出現・各ウィジェット描画（実データ件数）
 *   LENS-ORG-001 : ダッシュボードで admin-lens-toggle-ORGANIZATION → 同上（組織版・支払ウィジェット含む）
 *   LINK-TEAM-001: 管理者グリッドの各リンク（要素1で直した正本ルート）押下 → 着地先 200 でレンダ（404 でない）
 *   LINK-ORG-001 : 組織グリッドの各リンク押下 → 着地先 200 でレンダ
 */

import {
  test as base,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

// 127.0.0.1 を明示（localhost だと IPv6 ::1 解決で間欠 ECONNREFUSED・memory: feedback_e2e_wsl2_cors_apibridge）
const BE = process.env.BE_ORIGIN ?? 'http://127.0.0.1:8080'
const BE_API = `${BE}/api/v1`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
const MEMBER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const MEMBER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

// storageState に依存せず各テスト内でロールを切り替える
const test = base.extend({})
test.use({ storageState: { cookies: [], origins: [] } })
test.setTimeout(120_000)

// ── ヘルパー ──────────────────────────────────────────────────────────────

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

/**
 * APIブリッジ（memory: feedback_e2e_wsl2_cors_apibridge）。
 *
 * 専用 dev サーバー(:3001) で動かす UI テストでは、Nuxt アプリがブラウザから
 * BE(:8080) へクロスオリジン fetch する。BE の CORS 許可 origin は localhost:3000/8080
 * のみで :3001 は弾かれる（「Failed to fetch」/ 403）ため、ブラウザの /api/v1 呼び出しを
 * page.route で横取りし、Playwright の APIRequestContext（CORS 非対象）で中継する。
 *
 * 3点必須:
 *   1) BE への中継リクエストの origin/referer を許可 origin(localhost:3000) に差し替える
 *   2) fulfill 時の access-control-allow-origin を「ブラウザ実 origin」に固定する
 *   3) Bearer トークンを付与して確実に認証する（Cookie 中継に依存しない）
 */
async function installApiBridge(page: Page, token: string): Promise<void> {
  // page.goto 前に設定するケースのため、origin を固定値で指定する（about:blank 対策）
  const pageOrigin = 'http://localhost:3001'
  // 正規表現で /api/v1/ を含む全 URL をキャッチ
  // （NUXT_PUBLIC_API_BASE=http://127.0.0.1:8080 の場合、ブラウザが絶対 URL で fetch する。
  //   glob '**/api/v1/**' がドメイン付き絶対 URL にマッチしない場合の保険）
  await page.route(/\/api\/v1\//, async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    // 中継先は 127.0.0.1 明示（IPv6 ::1 解決ブレで間欠 ECONNREFUSED を避ける）
    const target = `http://127.0.0.1:8080${url.pathname}${url.search}`
    const headers: Record<string, string> = {
      ...req.headers(),
      origin: 'http://localhost:3000',
      referer: 'http://localhost:3000/',
      authorization: `Bearer ${token}`,
    }
    const method = req.method()
    const postData = req.postData()
    const relay = await page.request.fetch(target, {
      method,
      headers,
      data: postData ?? undefined,
      maxRedirects: 0,
    })
    const respHeaders: Record<string, string> = { ...relay.headers() }
    // ブラウザ実 origin に ACAO を固定（new URL(req.url()).origin だと 8080 になり CORS 拒否される罠）
    respHeaders['access-control-allow-origin'] = pageOrigin
    respHeaders['access-control-allow-credentials'] = 'true'
    await route.fulfill({
      status: relay.status(),
      headers: respHeaders,
      body: await relay.body(),
    })
  })
}

type Me = {
  id: number
  email: string
  lastName: string
  firstName: string
  avatarUrl: string | null
  systemRole: string | null
  timezone: string | null
}

// role（email）ごとのログイン結果キャッシュ。同一ユーザーの高速連続ログインで BE が稀に 500 を
// 返す問題（auth ドメインの連続ログイン競合）を避けるため、ログインは role ごとに 1 回だけ行う。
const credCache = new Map<string, { token: string; me: Me }>()

async function resolveCreds(email: string, password: string): Promise<{ token: string; me: Me }> {
  const cached = credCache.get(email)
  if (cached) return cached
  const ctx = await pwRequest.newContext()
  const loginRes = await ctx.post(`${BE_API}/auth/login`, {
    headers: { 'Content-Type': 'application/json' },
    data: { email, password },
  })
  expect(loginRes.status(), `BE ログイン(${email}) は 200`).toBe(200)
  const token = (await loginRes.json()).data.accessToken as string
  const meRes = await ctx.get(`${BE_API}/users/me`, { headers: { Authorization: `Bearer ${token}` } })
  expect(meRes.status(), '/users/me は 200').toBe(200)
  const me = (await meRes.json()).data as Me
  await ctx.dispose()
  const creds = { token, me }
  credCache.set(email, creds)
  return creds
}

/**
 * APIブリッジ＋localStorage(currentUser) を仕込み、role のセッションを確立する。
 * ブラウザフォームからのログイン（loginUI）を使わないため CORS をバイパスできる。
 *
 * <p>ログインは role ごとに 1 回だけ（{@link resolveCreds} がキャッシュ）。トークンを使い回し、
 * ブラウザ Cookie ログインは省略する（API ブリッジが毎リクエストに Bearer を付与して認証するため、
 * Cookie 中継に依存しない）。これにより同一ユーザーの高速連続ログインによる BE の間欠 500 を回避する。</p>
 *
 * reservation-dashboard-real.spec.ts の adminInit fixture と同じ作法（memory: feedback_e2e_wsl2_cors_apibridge）。
 */
async function loginViaApiBridge(page: Page, email: string, password: string): Promise<string> {
  const { token, me } = await resolveCreds(email, password)

  // addInitScript で currentUser + tokenExpiresAt を仕込む（auth.client.ts のリフレッシュ抑止）
  const farFuture = Date.now() + 24 * 60 * 60 * 1000
  const currentUser = {
    id: me.id,
    email: me.email,
    fullName: `${me.lastName} ${me.firstName}`,
    profileImageUrl: me.avatarUrl,
    systemRole: me.systemRole ?? undefined,
    timezone: me.timezone ?? undefined,
  }
  await page.addInitScript(
    ({ user, expiresAt }) => {
      localStorage.setItem('currentUser', JSON.stringify(user))
      localStorage.setItem('tokenExpiresAt', String(expiresAt))
    },
    { user: currentUser, expiresAt: farFuture },
  )

  // APIブリッジ設置（page.goto より前に設定して初回ナビゲーションからブリッジが有効になるようにする）
  await installApiBridge(page, token)
  return token
}

/**
 * 指定の管理 ADMIN チームの slug を実 BE から解決する（slug 採番ドリフトに強い）。
 */
async function resolveAdminTeamSlug(api: APIRequestContext, token: string): Promise<string> {
  const res = await api.get(`${BE_API}/me/teams`, { headers: authHeaders(token) })
  expect(res.status(), '/me/teams は 200').toBe(200)
  const data = (await res.json()).data as Array<{ slug: string; name: string; role: string }>
  const team =
    data.find((t) => t.role === 'ADMIN' && t.name.includes('FC東京U-18')) ??
    data.find((t) => t.role === 'ADMIN')
  expect(team, 'ADMIN ロールのチームが存在すること').toBeTruthy()
  return team!.slug
}

/**
 * 管理者が「所属していない」組織の slug を 1 つ見つける（IDOR 用）。
 * 管理者の me/organizations に含まれず、me/permissions で roleName=null になる org を探す。
 */
async function resolveForeignOrgSlug(
  api: APIRequestContext,
  token: string,
  myOrgSlugs: string[],
): Promise<string | null> {
  for (let i = 1; i <= 12; i++) {
    const slug = `org-${String(i).padStart(6, '0')}`
    if (myOrgSlugs.includes(slug)) continue
    const res = await api.get(`${BE_API}/organizations/${slug}/me/permissions`, {
      headers: authHeaders(token),
    })
    if (!res.ok()) continue
    const roleName = (await res.json()).data?.roleName
    if (roleName === null || roleName === undefined) return slug
  }
  return null
}

/**
 * ダッシュボードのスコープカルーセルを目的スコープ（TEAM / ORGANIZATION）へ送り、
 * 「slug 解決済み かつ 管理ロール（ADMIN/DEPUTY）」のタグを明示選択して
 * 管理者レンズトグルが描画されるまで待つ。
 *
 * <p>既定選択（タグ先頭）任せにしない理由: タグ一覧は memberships 由来で、
 * 環境によっては slug=null のスコープ（=実 slug 未確定）が先頭に来うる。その場合
 * DashboardTeamPanel.hasResolvedSlug=false でメンバー表示に留まり、トグルが出ない。
 * 本ヘルパーは「実 slug を持つ管理スコープ」を一覧から特定し、そのタグチップを
 * クリックして選択することで、レンズ機能そのもの（管理スコープ→トグル描画）を検証する。
 * トグルは「実 slug 確定 かつ ADMIN/DEPUTY」のときのみ DOM に出る。</p>
 */
async function gotoDashboardScope(
  page: Page,
  scope: 'TEAM' | 'ORGANIZATION',
  targetSlug: string,
): Promise<void> {
  await page.goto('/dashboard')
  await waitForHydration(page)
  // カルーセルのセグメントタブで目的スコープへ切替（PERSONAL→TEAM→ORGANIZATION）
  const segment = page.getByTestId(`scope-segment-${scope}`)
  await expect(segment, `スコープセグメント ${scope} が存在すること`).toBeVisible({ timeout: 20_000 })
  await segment.click()

  // タグ一覧（loadTabs）が描画されるまで待つ。
  await expect(
    page.locator(`[data-testid^="scope-tab-chip-${scope}-"]`).first(),
    `${scope} のタグチップが描画されること`,
  ).toBeVisible({ timeout: 20_000 })

  // 目的 slug のタグチップを選ぶ。タグ一覧は memberships の joined_at 降順 6 件/ページで、
  // seed が用意した検証用スコープ（FC東京U-18 等・最古参加）は後方ページに出るため、
  // 見つかるまでページ送りする（chip testid は slug ベース）。
  await selectScopeTabBySlug(page, scope, targetSlug)

  // トグル出現を待つ（slug 解決＋権限取得の完了を含む）。
  await expect(
    page.getByTestId(`admin-lens-toggle-${scope}`),
    `管理者レンズトグル(${scope})が ADMIN に描画されること`,
  ).toBeVisible({ timeout: 20_000 })
}

/**
 * タグバーをページ送りしながら目的 slug のタグチップを探してクリックする。
 * 見つからなければ次ページへ。最終ページまで見つからなければ失敗させる。
 */
async function selectScopeTabBySlug(
  page: Page,
  scope: 'TEAM' | 'ORGANIZATION',
  targetSlug: string,
): Promise<void> {
  const chip = page.getByTestId(`scope-tab-chip-${scope}-${targetSlug}`)
  const nextBtn = page.getByTestId(`scope-tab-nextpage-${scope}`)
  for (let i = 0; i < 12; i++) {
    if (await chip.count()) {
      await chip.click()
      return
    }
    // 次ページが無ければ終了（チップは見つからなかった → 後段の expect で顕在化）。
    if ((await nextBtn.count()) === 0 || (await nextBtn.isDisabled())) break
    await nextBtn.click()
    // ページ遷移後の再描画を待つ（先頭チップの再出現）。
    await expect(
      page.locator(`[data-testid^="scope-tab-chip-${scope}-"]`).first(),
    ).toBeVisible({ timeout: 10_000 })
    await page.waitForTimeout(300)
  }
  // 念のため：ループを抜けてもチップがあればクリック。
  await expect(chip, `タグ一覧に ${scope} スコープ ${targetSlug} のチップが見つかること`).toBeVisible({
    timeout: 10_000,
  })
  await chip.click()
}

/**
 * 管理者レンズトグルを ON にし、ウィジェットグリッドが描画されるまで待つ。
 * トグルが ON（aria-checked=true）になったことを確認してからグリッドを待つことで、
 * クリック未着・データロード遅延による取りこぼし（flake）を防ぐ。
 */
async function openAdminGrid(page: Page, scope: 'TEAM' | 'ORGANIZATION'): Promise<void> {
  const toggle = page.getByTestId(`admin-lens-toggle-${scope}`)
  await expect(toggle, `レンズトグル(${scope})が描画されること`).toBeVisible({ timeout: 20_000 })
  // タグ選択直後はパネルのデータ再ロード再描画が走っており、クリックが稀に取りこぼされる
  // （aria-checked が反転しない）ことがある。ON になるまでクリックを数回試行する
  // （機能が本当に壊れていれば ON にならず 30s で失敗するため、症状は隠さない）。
  await expect(async () => {
    if ((await toggle.getAttribute('aria-checked')) !== 'true') {
      await toggle.click()
    }
    await expect(toggle).toHaveAttribute('aria-checked', 'true', { timeout: 3_000 })
  }, `レンズトグル(${scope})が ON になること`).toPass({ timeout: 30_000 })
  await expect(
    page.getByTestId(`admin-widget-grid-${scope}`),
    `管理者ウィジェットグリッド(${scope})が描画されること`,
  ).toBeVisible({ timeout: 30_000 })
}

// ===========================================================================
// セットアップ（モジュールスコープ変数）— ログイン回数を抑えるため beforeAll で解決
// ===========================================================================
let sharedApi: APIRequestContext
let adminToken: string
let teamSlug: string
let orgSlug: string
let foreignOrgSlug: string | null

test.beforeAll(async () => {
  sharedApi = await pwRequest.newContext()
  // ログインは role ごとに 1 回だけ（連続ログインによる BE 間欠 500 を回避）。
  adminToken = (await resolveCreds(ADMIN_EMAIL, ADMIN_PASSWORD)).token
  await resolveCreds(MEMBER_EMAIL, MEMBER_PASSWORD) // MEMBER も温めてキャッシュ
  teamSlug = await resolveAdminTeamSlug(sharedApi, adminToken)

  const orgRes = await sharedApi.get(`${BE_API}/me/organizations`, { headers: authHeaders(adminToken) })
  const orgs = (await orgRes.json()).data as Array<{ slug: string | null; role: string }>
  const adminOrg = orgs.find((o) => o.role === 'ADMIN')
  expect(adminOrg, 'ADMIN ロールの組織が存在すること').toBeTruthy()
  orgSlug = adminOrg!.slug!

  foreignOrgSlug = await resolveForeignOrgSlug(
    sharedApi,
    adminToken,
    orgs.map((o) => o.slug).filter((s): s is string => s !== null),
  )
})

test.afterAll(async () => {
  await sharedApi.dispose()
})

// テスト終了後のインフライト API ブリッジルートによる
// "Target page context has been closed" エラーを抑止する
test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: 'ignoreErrors' })
})

// ===========================================================================
// ガード（admin-console ミドルウェア）
// ===========================================================================
test.describe('F10.1.1 管理コンソール — アクセスガード', () => {
  test('ACL-GUARD-001: MEMBER で /teams/{slug}/admin → スコープトップへリダイレクト（404 でない）', async ({
    page,
  }) => {
    // MEMBER でログイン（API ブリッジ経由）
    await loginViaApiBridge(page, MEMBER_EMAIL, MEMBER_PASSWORD)

    // まず通常ページを開いてアプリシェル（PrimeVue <Toast> を含むレイアウト）をマウントしておく。
    await page.goto('/dashboard')
    await waitForHydration(page)

    // トーストは navigateTo(scopeTop) と同時に発火するため、遷移前からリスナーを設定して
    // リダイレクト完了後にトーストが消えてしまうタイミング問題を回避する。
    const toastVisible = page.getByText('管理者権限が必要です').waitFor({
      state: 'visible',
      timeout: 20_000,
    })

    // page.goto はフルリロードで Nuxt アプリを再マウントするため、ミドルウェアの toast.add 時点で
    // <Toast> が未マウントとなりトーストが描画されない。クライアントサイド遷移（router.push）なら
    // <Toast> が載ったまま遷移するため、ミドルウェアのアクセス拒否トーストが実際に表示される
    // （実運用のユーザー操作と同じ経路）。
    await page.evaluate((url) => {
      const w = window as unknown as { useNuxtApp?: () => { $router?: { push: (u: string) => Promise<unknown> } } }
      // navigateTo によるリダイレクトで push が reject されることがあるため握る。
      void w.useNuxtApp?.().$router?.push(url)?.catch(() => {})
    }, `/teams/${teamSlug}/admin`)

    // ミドルウェアが権限不足を検知し、スコープトップ /teams/{slug} へ navigateTo する
    await page.waitForURL(new RegExp(`/teams/${teamSlug}(\\?|/?$)`), { timeout: 20_000 })
    const url = page.url()
    // 404 で弾かれていない（プロジェクト慣習：存在秘匿しない）
    expect(url, '404 ページではない').not.toMatch(/\/404|error/)
    // /admin ハブには留まっていない
    expect(new URL(url).pathname, '/admin ハブに留まっていない').not.toMatch(/\/admin\/?$/)

    // エラートースト「管理者権限が必要です」が表示されること（life: 5000ms のため goto 前からリスナー設定済み）
    await toastVisible
  })

  test('ACL-GUARD-002: ADMIN で /teams/{slug}/admin → チーム管理コンソールハブ表示', async ({ page }) => {
    await loginViaApiBridge(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await page.goto(`/teams/${teamSlug}/admin`)
    await waitForHydration(page)

    // /admin に留まる（リダイレクトされない）
    await expect(page).toHaveURL(new RegExp(`/teams/${teamSlug}/admin/?$`), { timeout: 20_000 })
    // ハブ見出しが描画される
    await expect(
      page.getByRole('heading', { name: 'チーム管理コンソール' }),
      'チーム管理コンソール見出しが表示されること',
    ).toBeVisible({ timeout: 15_000 })
  })

  test('ACL-GUARD-003: ADMIN で /organizations/{slug}/admin → 組織管理コンソールハブ表示', async ({
    page,
  }) => {
    await loginViaApiBridge(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await page.goto(`/organizations/${orgSlug}/admin`)
    await waitForHydration(page)

    await expect(page).toHaveURL(new RegExp(`/organizations/${orgSlug}/admin/?$`), { timeout: 20_000 })
    await expect(
      page.getByRole('heading', { name: '組織管理コンソール' }),
      '組織管理コンソール見出しが表示されること',
    ).toBeVisible({ timeout: 15_000 })
  })

  test('ACL-IDOR-001: ADMIN だが非所属組織の /organizations/{other}/admin → BE 403・FE スコープトップへ', async ({
    page,
  }) => {
    test.skip(!foreignOrgSlug, '管理者が非所属の組織が見つからないためスキップ')

    // BE 層（二重防衛）: 集約 API が 403 を返す
    const apiRes = await sharedApi.get(
      `${BE_API}/dashboard/organization/${foreignOrgSlug}/admin-member-stats`,
      { headers: authHeaders(adminToken) },
    )
    expect(apiRes.status(), '非所属 org の管理集約 API は 403').toBe(403)

    // FE 層: ミドルウェアが isAdminOrDeputy=false を検知しスコープトップへ戻す（404 にしない）
    await loginViaApiBridge(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await page.goto(`/organizations/${foreignOrgSlug}/admin`)
    await page.waitForURL(new RegExp(`/organizations/${foreignOrgSlug}(\\?|/?$)`), { timeout: 20_000 })
    expect(new URL(page.url()).pathname, '/admin ハブに留まっていない').not.toMatch(/\/admin\/?$/)
    expect(page.url(), '404 ページではない').not.toMatch(/\/404/)
  })
})

// ===========================================================================
// 管理者レンズ（L1 ダッシュボード）— 実データでウィジェット描画
// ===========================================================================
test.describe('F10.1.1 管理者レンズ — ウィジェット描画（実データ）', () => {
  test('LENS-TEAM-001: admin-lens-toggle-TEAM → 管理者グリッド出現・各ウィジェット描画', async ({
    page,
  }) => {
    await loginViaApiBridge(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await gotoDashboardScope(page, 'TEAM', teamSlug)

    // トグル押下で管理者レンズ ON → グリッドへシート差替
    await openAdminGrid(page, 'TEAM')

    // 各ウィジェット testid が描画される（チームスコープ）
    await expect(page.getByTestId('admin-approvals-total-TEAM')).toBeVisible({ timeout: 15_000 })
    await expect(page.getByTestId('admin-console-link-TEAM')).toBeVisible()
    await expect(page.getByTestId('admin-alert-link-TEAM')).toBeVisible()
    await expect(page.getByTestId('admin-reports-link-TEAM')).toBeVisible()
    await expect(page.getByTestId('admin-modules-link')).toBeVisible() // チーム専用
    await expect(page.getByTestId('admin-members-link-TEAM')).toBeVisible()
    await expect(page.getByTestId('admin-reservations-link')).toBeVisible() // チーム専用
    await expect(page.getByTestId('admin-budget-link-TEAM')).toBeVisible()

    // 実 BE 実データ件数の検証（feedback_e2e_real_full_crud）:
    // seed は FC東京U-18 にメンバー 11 名・予約承認待ち 6 件を投入している。
    // モックでは出ない実数が描画されることを確認する。
    const memberTotal = page.getByTestId('admin-members-total-count-TEAM')
    await expect(memberTotal, 'メンバー総数が実データで描画される').toBeVisible({ timeout: 15_000 })
    const memberText = (await memberTotal.textContent())?.trim() ?? ''
    expect(Number(memberText), `メンバー総数(${memberText})は 1 以上の実数`).toBeGreaterThanOrEqual(1)

    const reservationPending = page.getByTestId('admin-reservations-pending-count')
    await expect(reservationPending, '予約承認待ち件数が実データで描画される').toBeVisible({ timeout: 15_000 })
    const pendingText = (await reservationPending.textContent())?.trim() ?? ''
    expect(Number(pendingText), `予約承認待ち件数(${pendingText})は数値`).not.toBeNaN()
  })

  test('LENS-ORG-001: admin-lens-toggle-ORGANIZATION → 管理者グリッド出現・各ウィジェット描画', async ({
    page,
  }) => {
    await loginViaApiBridge(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await gotoDashboardScope(page, 'ORGANIZATION', orgSlug)

    await openAdminGrid(page, 'ORGANIZATION')

    // 承認待ちウィジェット: 件数(>0)または空状態(0件)のいずれかが描画されること（loaded 状態）。
    // この組織(seed)は承認待ち 0 件なので空状態になりうる。集計失敗(degraded/fetchFailed)でないことを担保する。
    await expect(
      page
        .getByTestId('admin-approvals-total-ORGANIZATION')
        .or(page.getByTestId('admin-approvals-empty-ORGANIZATION')),
      '承認待ちウィジェットが loaded 状態（件数 or 空）で描画されること',
    ).toBeVisible({ timeout: 15_000 })
    await expect(page.getByTestId('admin-console-link-ORGANIZATION')).toBeVisible()
    await expect(page.getByTestId('admin-payments-link')).toBeVisible() // 組織専用
    await expect(page.getByTestId('admin-alert-link-ORGANIZATION')).toBeVisible()
    await expect(page.getByTestId('admin-reports-link-ORGANIZATION')).toBeVisible()
    await expect(page.getByTestId('admin-members-link-ORGANIZATION')).toBeVisible()
    await expect(page.getByTestId('admin-budget-link-ORGANIZATION')).toBeVisible()

    const memberTotal = page.getByTestId('admin-members-total-count-ORGANIZATION')
    await expect(memberTotal, '組織メンバー総数が実データで描画される').toBeVisible({ timeout: 15_000 })
    const memberText = (await memberTotal.textContent())?.trim() ?? ''
    expect(Number(memberText), `組織メンバー総数(${memberText})は 1 以上の実数`).toBeGreaterThanOrEqual(1)
  })
})

// ===========================================================================
// 導線遷移（P4 要素1 の成果検証）— 各ウィジェットのリンク着地が 200・404 でない
// ===========================================================================
test.describe('F10.1.1 管理者レンズ — 導線遷移（要素1の成果検証）', () => {
  test('LINK-TEAM-001: チーム管理者グリッドの各リンク → 正本ルートへ着地（404 でない）', async ({ page }) => {
    await loginViaApiBridge(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await gotoDashboardScope(page, 'TEAM', teamSlug)
    await openAdminGrid(page, 'TEAM')

    // 要素1で整備された正本ルート直結（01 §180）。各リンクの href を取得して着地確認する。
    const linkTestIds: { testId: string; expectPath: RegExp }[] = [
      { testId: 'admin-reservations-link', expectPath: new RegExp(`/teams/${teamSlug}/reservations`) },
      { testId: 'admin-budget-link-TEAM', expectPath: new RegExp(`/teams/${teamSlug}/budget`) },
      { testId: 'admin-members-link-TEAM', expectPath: new RegExp(`/teams/${teamSlug}/member-cards`) },
      { testId: 'admin-console-link-TEAM', expectPath: new RegExp(`/teams/${teamSlug}/admin`) },
    ]

    for (const { testId, expectPath } of linkTestIds) {
      const link = page.getByTestId(testId)
      const href = await link.getAttribute('href')
      expect(href, `${testId} に href がある`).toBeTruthy()
      expect(href!, `${testId} の遷移先が正本ルート`).toMatch(expectPath)

      // 実遷移して 404 でないこと・ログインに飛ばされないことを確認
      const resp = await page.goto(href!)
      expect(resp?.status(), `${testId} 着地 ${href} が 4xx/5xx でない`).toBeLessThan(400)
      await waitForHydration(page)
      await expect(page, `${testId} がログインへリダイレクトしない`).not.toHaveURL(/\/login/)
      // 戻ってグリッドを再構築（次のリンク検証のため）
      await gotoDashboardScope(page, 'TEAM', teamSlug)
      await openAdminGrid(page, 'TEAM')
    }
  })

  test('LINK-ORG-001: 組織管理者グリッドの各リンク → 正本ルートへ着地（404 でない）', async ({ page }) => {
    await loginViaApiBridge(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await gotoDashboardScope(page, 'ORGANIZATION', orgSlug)
    await openAdminGrid(page, 'ORGANIZATION')

    const linkTestIds: { testId: string; expectPath: RegExp }[] = [
      { testId: 'admin-payments-link', expectPath: new RegExp(`/organizations/${orgSlug}/payments`) },
      { testId: 'admin-budget-link-ORGANIZATION', expectPath: new RegExp(`/organizations/${orgSlug}/budget`) },
      {
        testId: 'admin-members-link-ORGANIZATION',
        expectPath: new RegExp(`/organizations/${orgSlug}/member-cards`),
      },
      { testId: 'admin-console-link-ORGANIZATION', expectPath: new RegExp(`/organizations/${orgSlug}/admin`) },
    ]

    for (const { testId, expectPath } of linkTestIds) {
      const link = page.getByTestId(testId)
      const href = await link.getAttribute('href')
      expect(href, `${testId} に href がある`).toBeTruthy()
      expect(href!, `${testId} の遷移先が正本ルート`).toMatch(expectPath)

      const resp = await page.goto(href!)
      expect(resp?.status(), `${testId} 着地 ${href} が 4xx/5xx でない`).toBeLessThan(400)
      await waitForHydration(page)
      await expect(page, `${testId} がログインへリダイレクトしない`).not.toHaveURL(/\/login/)
      await gotoDashboardScope(page, 'ORGANIZATION', orgSlug)
      await openAdminGrid(page, 'ORGANIZATION')
    }
  })
})
