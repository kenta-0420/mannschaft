/**
 * F10.1.1 管理コンソール / 管理者レンズ — 実機フルスタック E2E テスト（P4 要素2）
 *
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が
 * 起動済みの状態で実行してください（playwright-real.config.ts は webServer 無効＝既存サーバー前提）。
 *
 * 実行プロジェクト: chromium-real（baseURL=http://localhost:3000）
 *   ※ 本 spec は storageState に依存せず、各テスト内で実 BE にログインして
 *     ロール（ADMIN / MEMBER）を切り替える。f089-billing-crud-roles.spec.ts の作法に倣う。
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
 * 検証シナリオ:
 *   ACL-GUARD-001: MEMBER で /teams/{slug}/admin → スコープトップへリダイレクト＋エラートースト（404 でない）
 *   ACL-GUARD-002: ADMIN で /teams/{slug}/admin → 管理コンソールハブ表示（チーム）
 *   ACL-GUARD-003: ADMIN で /organizations/{slug}/admin → 管理コンソールハブ表示（組織）
 *   ACL-IDOR-001 : ADMIN だが非所属 org の /organizations/{other}/admin → BE 403・FE スコープトップへ
 *   LENS-TEAM-001: ダッシュボードで admin-lens-toggle-TEAM → 管理者グリッド出現・各ウィジェット testid 描画（実データ件数）
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

async function apiLogin(api: APIRequestContext, email: string, password: string): Promise<string> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `apiLogin(${email}) は 200`).toBe(200)
  return (await res.json()).data.accessToken as string
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

/**
 * /login フォームから実ブラウザセッションを確立する（PrimeVue InputText 対応）。
 * dev:3000 → BE:8080 は CORS で許可されているため API ブリッジは不要。
 */
async function loginUI(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login')
  await waitForHydration(page)
  const emailInput = page.locator('input#email')
  await emailInput.click()
  await emailInput.pressSequentially(email, { delay: 10 })
  const passwordInput = page.locator('input[type="password"]')
  await passwordInput.click()
  await passwordInput.pressSequentially(password, { delay: 10 })
  await page.getByRole('button', { name: 'ログイン' }).click()
  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 30_000,
    waitUntil: 'commit',
  })
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
 * 管理者レンズトグルが描画されるまで待つ。
 * トグルは「実 slug 確定 かつ ADMIN/DEPUTY」のときのみ DOM に出る。
 */
async function gotoDashboardScope(page: Page, scope: 'TEAM' | 'ORGANIZATION'): Promise<void> {
  await page.goto('/dashboard')
  await waitForHydration(page)
  // カルーセルのセグメントタブで目的スコープへ切替（PERSONAL→TEAM→ORGANIZATION）
  const segment = page.getByTestId(`scope-segment-${scope}`)
  await expect(segment, `スコープセグメント ${scope} が存在すること`).toBeVisible({ timeout: 20_000 })
  await segment.click()
  // トグル出現を待つ（loadTabs による slug 解決＋権限取得の完了を含む）
  await expect(
    page.getByTestId(`admin-lens-toggle-${scope}`),
    `管理者レンズトグル(${scope})が ADMIN に描画されること`,
  ).toBeVisible({ timeout: 20_000 })
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
  adminToken = await apiLogin(sharedApi, ADMIN_EMAIL, ADMIN_PASSWORD)
  teamSlug = await resolveAdminTeamSlug(sharedApi, adminToken)

  const orgRes = await sharedApi.get(`${BE_API}/me/organizations`, { headers: authHeaders(adminToken) })
  const orgs = (await orgRes.json()).data as Array<{ slug: string; role: string }>
  const adminOrg = orgs.find((o) => o.role === 'ADMIN')
  expect(adminOrg, 'ADMIN ロールの組織が存在すること').toBeTruthy()
  orgSlug = adminOrg!.slug

  foreignOrgSlug = await resolveForeignOrgSlug(
    sharedApi,
    adminToken,
    orgs.map((o) => o.slug),
  )
})

test.afterAll(async () => {
  await sharedApi.dispose()
})

// ===========================================================================
// ガード（admin-console ミドルウェア）
// ===========================================================================
test.describe('F10.1.1 管理コンソール — アクセスガード', () => {
  test('ACL-GUARD-001: MEMBER で /teams/{slug}/admin → スコープトップへリダイレクト（404 でない）', async ({
    page,
  }) => {
    await loginUI(page, MEMBER_EMAIL, MEMBER_PASSWORD)
    await page.goto(`/teams/${teamSlug}/admin`)

    // ミドルウェアが権限不足を検知し、スコープトップ /teams/{slug} へ navigateTo する
    await page.waitForURL(new RegExp(`/teams/${teamSlug}(\\?|/?$)`), { timeout: 20_000 })
    const url = page.url()
    // 404 で弾かれていない（プロジェクト慣習：存在秘匿しない）
    expect(url, '404 ページではない').not.toMatch(/\/404|error/)
    // /admin ハブには留まっていない
    expect(new URL(url).pathname, '/admin ハブに留まっていない').not.toMatch(/\/admin\/?$/)

    // エラートースト「管理者権限が必要です」が表示される（i18n: adminConsole.middleware.accessDeniedBody）
    await expect(
      page.getByText('管理者権限が必要です'),
      'アクセス拒否トーストが表示されること',
    ).toBeVisible({ timeout: 10_000 })
  })

  test('ACL-GUARD-002: ADMIN で /teams/{slug}/admin → チーム管理コンソールハブ表示', async ({ page }) => {
    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
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
    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
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
    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
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
    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await gotoDashboardScope(page, 'TEAM')

    // トグル押下で管理者レンズ ON → グリッドへシート差替
    await page.getByTestId('admin-lens-toggle-TEAM').click()
    await expect(
      page.getByTestId('admin-widget-grid-TEAM'),
      '管理者ウィジェットグリッド(TEAM)が描画されること',
    ).toBeVisible({ timeout: 15_000 })

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
    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await gotoDashboardScope(page, 'ORGANIZATION')

    await page.getByTestId('admin-lens-toggle-ORGANIZATION').click()
    await expect(
      page.getByTestId('admin-widget-grid-ORGANIZATION'),
      '管理者ウィジェットグリッド(ORG)が描画されること',
    ).toBeVisible({ timeout: 15_000 })

    await expect(page.getByTestId('admin-approvals-total-ORGANIZATION')).toBeVisible({ timeout: 15_000 })
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
    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await gotoDashboardScope(page, 'TEAM')
    await page.getByTestId('admin-lens-toggle-TEAM').click()
    await expect(page.getByTestId('admin-widget-grid-TEAM')).toBeVisible({ timeout: 15_000 })

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
      await gotoDashboardScope(page, 'TEAM')
      await page.getByTestId('admin-lens-toggle-TEAM').click()
      await expect(page.getByTestId('admin-widget-grid-TEAM')).toBeVisible({ timeout: 15_000 })
    }
  })

  test('LINK-ORG-001: 組織管理者グリッドの各リンク → 正本ルートへ着地（404 でない）', async ({ page }) => {
    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await gotoDashboardScope(page, 'ORGANIZATION')
    await page.getByTestId('admin-lens-toggle-ORGANIZATION').click()
    await expect(page.getByTestId('admin-widget-grid-ORGANIZATION')).toBeVisible({ timeout: 15_000 })

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
      await gotoDashboardScope(page, 'ORGANIZATION')
      await page.getByTestId('admin-lens-toggle-ORGANIZATION').click()
      await expect(page.getByTestId('admin-widget-grid-ORGANIZATION')).toBeVisible({ timeout: 15_000 })
    }
  })
})
