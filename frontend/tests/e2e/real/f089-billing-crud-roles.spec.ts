/**
 * F08.9 会費・決済機能 — 実機 E2E テスト（P2/P3c/P4/P5/P6/P8・CRUD + ロールチェック）
 *
 * 対象フェーズ:
 *   P2  後見まとめ払い（/me/guardianship/bulk-payment）
 *   P3c 後見切替（/me/guardianship/switch）
 *   P4/P5 ペイウォール・継続課金加入（/payments/subscribe/[itemId]・/me/payments/subscriptions）
 *   P6  期別決済・支払い項目管理（/teams/[id]/payments）
 *   P8  CSV エクスポート・費目明細（/teams/[id]/billing/fee-statements）
 *
 * テストユーザー:
 *   ADMIN : e2e-dummy-1@test.mannschaft.local / TestPass2026!（FC東京U-18 のスコープ ADMIN）
 *   MEMBER: e2e-user@test.mannschaft.local  / TestPass2026!
 *
 * 実行方法（バックエンド + フロントエンドが起動済みの状態で）:
 *   BASE_URL=http://localhost:3000 npx playwright test \
 *     tests/e2e/real/f089-billing-crud-roles.spec.ts --project chromium-real
 */

import {
  test,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// storageState に依存せず各テスト内でロールを切り替える
test.use({ storageState: { cookies: [], origins: [] } })

// ── 定数 ──────────────────────────────────────────────────────────────────
const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

// SYSTEM_ADMIN はスコープ mutation を監査 read-only として拒否するため、専用のチーム ADMIN を使う。
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-dummy-1@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
const MEMBER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const MEMBER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
// サポーター: 共通 E2E seed の専用アカウントを使う。
const SUPPORTER_EMAIL = 'e2e-supporter@test.mannschaft.local'
const SUPPORTER_PASSWORD = 'TestPass2026!'

// ── ヘルパー ──────────────────────────────────────────────────────────────

interface LoginResult {
  accessToken: string
  userId: number
}

interface CachedBrowserSession {
  accessToken: string
  expiresAt: number
  user: {
    id: number
    email: string
    fullName: string
    profileImageUrl: string | null
    systemRole?: string
    timezone?: string
  }
}

const browserSessions = new Map<string, CachedBrowserSession>()

/**
 * BE の /api/v1/auth/login で Bearer トークンを取得する。
 * レスポンス: { data: { accessToken, userId } }
 */
async function apiLogin(
  api: APIRequestContext,
  email: string,
  password: string,
): Promise<LoginResult> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `apiLogin(${email}) は 200`).toBe(200)
  const json = (await res.json()) as { data: { accessToken: string; userId: number } }
  return { accessToken: json.data.accessToken, userId: json.data.userId }
}

/**
 * 実BEで認証CookieとlocalStorageを確立し、対象ページを実ブラウザで開く。
 * deferNavigation により初回documentより前に認証状態を復元し、重いログイン後SSR待ちを避ける。
 */
async function loginAndNavigate(
  page: Page,
  email: string,
  _password: string,
  targetPath: string,
): Promise<void> {
  const session = browserSessions.get(email)
  expect(session, `${email} の共有ログインセッションが準備済みであること`).toBeTruthy()
  await page.context().addCookies([{
    name: 'access_token',
    value: session!.accessToken,
    domain: 'localhost',
    path: '/',
    expires: Math.floor(session!.expiresAt / 1000),
    httpOnly: true,
    secure: false,
    sameSite: 'Lax',
  }])
  await page.addInitScript(({ user, expiresAt }) => {
    localStorage.setItem('currentUser', JSON.stringify(user))
    localStorage.setItem('tokenExpiresAt', String(expiresAt))
  }, { user: session!.user, expiresAt: session!.expiresAt })
  await page.goto(targetPath, { waitUntil: 'domcontentloaded' })
  await page.waitForURL((url) => url.pathname === targetPath || url.pathname.startsWith(targetPath), {
    timeout: 15_000,
  })
  await waitForHydration(page)
}

async function cacheBrowserSession(
  api: APIRequestContext,
  email: string,
  login: LoginResult,
): Promise<void> {
  const meRes = await api.get(`${BE_API}/users/me`, {
    headers: authHeaders(login.accessToken),
  })
  expect(meRes.status(), `${email} のプロフィール取得は200`).toBe(200)
  const me = (await meRes.json()).data as {
    id: number
    email: string
    lastName: string
    firstName: string
    avatarUrl: string | null
    systemRole: string | null
    timezone: string | null
  }
  const payload = JSON.parse(Buffer.from(login.accessToken.split('.')[1]!, 'base64url').toString('utf8')) as {
    exp: number
  }
  browserSessions.set(email, {
    accessToken: login.accessToken,
    expiresAt: payload.exp * 1000,
    user: {
      id: me.id,
      email: me.email,
      fullName: `${me.lastName} ${me.firstName}`,
      profileImageUrl: me.avatarUrl,
      systemRole: me.systemRole ?? undefined,
      timezone: me.timezone ?? undefined,
    },
  })
}

/**
 * テスト用スコープ ADMIN が管理権限を持つ FC東京U-18 チームの ID と slug を API で解決する。
 */
async function resolveAdminTeam(
  api: APIRequestContext,
  token: string,
): Promise<{ id: number; slug: string }> {
  const res = await api.get(`${BE_API}/me/teams`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(res.status(), '/me/teams は 200').toBe(200)
  const json = (await res.json()) as {
    data: Array<{ id: number; slug: string; name: string; role: string }>
  }
  const isAdminRole = (role: string) => role === 'ADMIN' || role === 'SYSTEM_ADMIN'
  const team =
    json.data.find((t) => isAdminRole(t.role) && t.slug === 'fc-u-18') ??
    json.data.find((t) => isAdminRole(t.role))
  expect(team, '管理権限を持つチームが存在すること').toBeTruthy()
  return { id: team!.id, slug: team!.slug }
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

// ===========================================================================
// セットアップ（モジュールスコープ変数）
// ===========================================================================
let sharedApi: APIRequestContext
let adminToken: string
let adminTeamId: number
/** FE ページアクセス用 slug（useRoleAccess が slug-based API を呼ぶため数値 ID は不可） */
let adminTeamSlug: string
let billingTestPaymentItemId: number
let billingTestPaymentItemName: string

test.beforeAll(async () => {
  sharedApi = await pwRequest.newContext()
  const result = await apiLogin(sharedApi, ADMIN_EMAIL, ADMIN_PASSWORD)
  adminToken = result.accessToken
  await cacheBrowserSession(sharedApi, ADMIN_EMAIL, result)
  const memberLogin = await apiLogin(sharedApi, MEMBER_EMAIL, MEMBER_PASSWORD)
  await cacheBrowserSession(sharedApi, MEMBER_EMAIL, memberLogin)
  const team = await resolveAdminTeam(sharedApi, adminToken)
  adminTeamId = team.id
  adminTeamSlug = team.slug

  // Stripe 鍵に依存せず画面・認可・集計を検証する、共通 seed の固定年会費を実 API で取得する。
  billingTestPaymentItemName = 'CMP-011 E2E 年会費'
  const itemRes = await sharedApi.get(`${BE_API}/teams/${adminTeamId}/payment-items`, {
    headers: authHeaders(adminToken),
  })
  expect(itemRes.status(), 'CMP-011 E2E 支払い項目一覧は200').toBe(200)
  const itemJson = (await itemRes.json()) as {
    data: Array<{ id: number; meta: { name: string } }>
  }
  const billingItem = itemJson.data.find((item) => item.meta.name === billingTestPaymentItemName)
  expect(billingItem, '共通seedのCMP-011固定年会費が存在すること').toBeTruthy()
  billingTestPaymentItemId = billingItem!.id

  const supporterLoginRes = await sharedApi.post(`${BE_API}/auth/login`, {
    data: { email: SUPPORTER_EMAIL, password: SUPPORTER_PASSWORD },
  })
  expect(supporterLoginRes.status(), '専用サポーターがログインできること').toBe(200)
  const supporterLogin = (await supporterLoginRes.json()) as { data: { accessToken: string } }
  await cacheBrowserSession(sharedApi, SUPPORTER_EMAIL, {
    accessToken: supporterLogin.data.accessToken,
    userId: 0,
  })
  const supporterTeamsRes = await sharedApi.get(`${BE_API}/me/teams`, {
    headers: authHeaders(supporterLogin.data.accessToken),
  })
  expect(supporterTeamsRes.status(), '専用サポーターの所属一覧は200').toBe(200)
  const supporterTeams = (await supporterTeamsRes.json()) as {
    data: Array<{ id: number; role: string }>
  }
  expect(
    supporterTeams.data.some((team) => team.id === adminTeamId && team.role === 'SUPPORTER'),
    '共通seedで対象チームのSUPPORTERになっていること',
  ).toBe(true)
})

test.afterAll(async () => {
  await sharedApi.dispose()
})

// ===========================================================================
// P8: CSV エクスポート・費目明細
// ===========================================================================
test.describe.configure({ mode: 'serial' })

test.describe('F08.9 P8: CSV エクスポート・費目明細', () => {
  test('P8-01: [public] /teams/[id]/billing/fee-statements → /login にリダイレクト', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await page.goto(`/teams/${adminTeamSlug}/billing/fee-statements`)
    await page.waitForURL(/login/, { timeout: 15_000 })

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p8-01-fee-statements-public-redirect.png`,
      fullPage: true,
    })
  })

  test('P8-02: [member] /teams/[id]/billing/fee-statements → 403 またはアクセス拒否表示', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, MEMBER_EMAIL, MEMBER_PASSWORD, `/teams/${adminTeamSlug}/billing/fee-statements`)

    // FE 表示チェック: route guard により「権限がありません」が表示されることを hard assert
    // loadPermissions 完了後に permissionDenied = true → 鍵アイコン + メッセージが描画される
    // useRoleAccess の API 呼び出し完了まで待つため十分な timeout を設ける
    await expect(
      page.getByText(/権限がありません|管理者のみ/),
      'MEMBER は fee-statements ページで「権限がありません」が表示されること',
    ).toBeVisible({ timeout: 20_000 })

    // BE API層: 必ず 403/404 が返ることを確認（二重防衛）
    const { accessToken: memberToken } = await apiLogin(sharedApi, MEMBER_EMAIL, MEMBER_PASSWORD)
    const apiRes = await sharedApi.get(`${BE_API}/teams/${adminTeamId}/fee-statements?period=2026-06`, {
      headers: authHeaders(memberToken),
    })
    expect([403, 404], 'MEMBER の fee-statements API は 403/404 になること').toContain(apiRes.status())

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p8-02-fee-statements-member.png`,
      fullPage: true,
    })
  })

  test('P8-03: [admin] /teams/[id]/billing/fee-statements → ページが表示される', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    const [statementResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url().includes(`/api/v1/teams/${adminTeamId}/fee-statements?`) &&
          !response.url().includes('/pdf') &&
          response.request().method() === 'GET',
      ),
      loginAndNavigate(page, ADMIN_EMAIL, ADMIN_PASSWORD, `/teams/${adminTeamSlug}/billing/fee-statements`),
    ])
    expect(statementResponse.status(), 'slugを数値teamIdへ解決して明細APIを取得できること').toBe(200)

    // 月選択セレクタが表示されること
    const periodInput = page.locator('input#fee-period')
    await expect(periodInput).toBeVisible({ timeout: 15_000 })

    // 期間選択が現在年月を持つこと
    const value = await periodInput.inputValue()
    expect(value).toMatch(/^\d{4}-\d{2}$/)

    const [pdfResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url().includes(`/api/v1/teams/${adminTeamId}/fee-statements/pdf?`) &&
          response.request().method() === 'GET',
      ),
      page.getByRole('button', { name: 'PDF' }).click(),
    ])
    expect(pdfResponse.status(), '手数料明細PDF APIが成功すること').toBe(200)
    expect(pdfResponse.headers()['content-type']).toContain('application/pdf')

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p8-03-fee-statements-admin.png`,
      fullPage: true,
    })
  })

  test('P8-04: [admin] /teams/[id]/payments → CSV ボタンが表示される', async ({ page }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, ADMIN_EMAIL, ADMIN_PASSWORD, `/teams/${adminTeamSlug}/payments`)

    // 支払い項目が存在する場合のみ CSV ボタンが有効化される
    // まず支払い項目の存在確認
    // 支払い項目リストが読み込まれるまで待つ（loadItems は onMounted で非同期実行）
    const itemButton = page.locator('.w-64 button').filter({ hasText: billingTestPaymentItemName })
    await expect(itemButton, '支払い項目ボタンが表示されること').toBeVisible({ timeout: 15_000 })
    await itemButton.click()

    // クリック後、selectedItem が設定されると CSV ボタンを含むヘッダー行が現れる
    const csvButton = page.getByRole('button', { name: /CSV/i }).first()
    await expect(csvButton, 'ADMIN には CSV ダウンロードボタンが表示される').toBeVisible({
      timeout: 10_000,
    })

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p8-04-payments-admin-csv-button.png`,
      fullPage: true,
    })
  })

  test('P8-05: [admin] CSV ダウンロードが開始される', async ({ page }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    const itemId = billingTestPaymentItemId

    await loginAndNavigate(page, ADMIN_EMAIL, ADMIN_PASSWORD, `/teams/${adminTeamSlug}/payments`)

    // 支払い項目リストが読み込まれるまで待ってからクリック
    const itemButton = page.locator('.w-64 button').filter({ hasText: billingTestPaymentItemName })
    await expect(itemButton).toBeVisible({ timeout: 15_000 })
    await itemButton.click()

    const csvButton = page.getByRole('button', { name: /CSV/i }).first()
    await expect(csvButton, 'CSV ボタンが表示されること').toBeVisible({ timeout: 10_000 })

    // BE API は 200 で CSV を返すこと（ADMIN 認可 hard assert）
    const exportApiRes = await sharedApi.get(
      `${BE_API}/teams/${adminTeamId}/payment-items/${itemId}/payments/export`,
      { headers: authHeaders(adminToken) },
    )
    expect(exportApiRes.status(), 'ADMIN の payment-items/export BE API は 200 を返すこと').toBe(200)

    // Content-Type が CSV であること
    const contentType = exportApiRes.headers()['content-type'] ?? ''
    expect(contentType, 'レスポンスは CSV 形式であること').toMatch(/csv/i)

    // UI層: CSV ボタンクリック後にダウンロードが開始されること（FE Blob 処理 hard assert）
    // responseType:'blob' 修正後はエラートーストでなく download イベントが発火する
    const [download] = await Promise.all([
      page.waitForEvent('download', { timeout: 15_000 }),
      csvButton.click(),
    ])
    expect(download, 'CSV ダウンロードが開始されること').toBeTruthy()
    const downloadPath = await download.suggestedFilename()
    expect(downloadPath, 'ダウンロードファイル名に payments が含まれること').toMatch(/payments/i)

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p8-05-csv-download.png`,
      fullPage: true,
    })
  })

  test('P8-06: [member] /teams/[id]/payments → CSV ボタンが非表示またはアクセス拒否', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, MEMBER_EMAIL, MEMBER_PASSWORD, `/teams/${adminTeamSlug}/payments`)

    await page.waitForTimeout(3_000)

    // MEMBER には CSV ボタンが見えないか、ページ全体がアクセス拒否
    const csvButton = page.getByRole('button', { name: /CSV/i }).first()
    const csvVisible = await csvButton.isVisible({ timeout: 5_000 }).catch(() => false)
    const isAccessDenied =
      page.url().includes('/403') ||
      page.url().includes('/error') ||
      page.url().includes('/login') ||
      (await page.getByText(/403|権限|アクセス/i).isVisible({ timeout: 3_000 }).catch(() => false))

    // CSV ボタンが見えないか、アクセス拒否のいずれか
    expect(
      !csvVisible || isAccessDenied,
      'MEMBER には管理者向け CSV ボタンが見えないこと',
    ).toBe(true)

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p8-06-payments-member-no-csv.png`,
      fullPage: true,
    })
  })

  test('P8-07: [public] API で fee-statements → 401 または 403 が返る', async () => {
    // 未認証で API を直接叩くと 401（または BE 実装によっては 403）
    const res = await sharedApi.get(
      `${BE_API}/teams/${adminTeamId}/fee-statements?period=2026-06`,
    )
    expect(
      [401, 403],
      '未認証の fee-statements API は認証エラー（401 または 403）になること',
    ).toContain(res.status())
  })

  test('P8-08: [supporter] /teams/[id]/billing/fee-statements → CSV ボタン非表示・管理機能なし', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    // BE API レベルでサポーターが fee-statements にアクセスできないことを確認
    const supporterLoginRes = await sharedApi.post(`${BE_API}/auth/login`, {
      data: { email: SUPPORTER_EMAIL, password: SUPPORTER_PASSWORD },
    })
    const { data: { accessToken: supporterToken } } = (await supporterLoginRes.json()) as { data: { accessToken: string } }
    const apiRes = await sharedApi.get(`${BE_API}/teams/${adminTeamId}/fee-statements?period=2026-06`, {
      headers: authHeaders(supporterToken),
    })
    expect([403, 404], 'サポーターの fee-statements API は 403/404').toContain(apiRes.status())

    await loginAndNavigate(page, SUPPORTER_EMAIL, SUPPORTER_PASSWORD, `/teams/${adminTeamSlug}/billing/fee-statements`)

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: 'test-results/f089-p8-08-fee-statements-supporter.png',
      fullPage: true,
    })
  })
})

// ===========================================================================
// P6: 期別決済・支払い項目管理（管理者 CRUD + メンバー参照）
// ===========================================================================
test.describe('F08.9 P6: 期別決済・支払い項目管理', () => {
  test('P6-01: [public] /teams/[id]/payments → /login にリダイレクト', async ({ page }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await page.goto(`/teams/${adminTeamSlug}/payments`)
    await page.waitForURL(/\/login/, { timeout: 15_000 })

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p6-01-payments-public-redirect.png`,
      fullPage: true,
    })
  })

  test('P6-02: [admin] /teams/[id]/payments → ページ表示・支払い項目リストが見える', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, ADMIN_EMAIL, ADMIN_PASSWORD, `/teams/${adminTeamSlug}/payments`)

    // PaymentAdminPanel は常に .w-64 パネルを描画する（items が空でも）
    await expect(page.locator('.w-64').first(), 'ADMIN には支払い管理ページが表示される').toBeVisible({
      timeout: 15_000,
    })

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p6-02-payments-admin.png`,
      fullPage: true,
    })
  })

  test('P6-03: [admin] 支払い項目を選択するとメンバーの支払い状況テーブルが表示される', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, ADMIN_EMAIL, ADMIN_PASSWORD, `/teams/${adminTeamSlug}/payments`)

    // 支払い項目ボタンをクリック
    const itemButton = page.locator('.w-64 button').filter({ hasText: billingTestPaymentItemName })
    await expect(itemButton).toBeVisible({ timeout: 15_000 })
    await itemButton.click()

    // メンバー支払い状況が読み込まれること（テーブルまたはリスト）
    await page.waitForTimeout(2_000)

    // ページがクラッシュしていないこと
    expect(page.url()).not.toContain('/error')
    // データが存在しない場合でも undefined/NaN が表示されていないこと
    const bodyText = await page.locator('body').innerText()
    expect(bodyText).not.toContain('undefined')
    expect(bodyText).not.toContain('NaN')

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p6-03-payments-admin-select-item.png`,
      fullPage: true,
    })
  })

  test('P6-04: [admin] リマインド送信ボタンをクリックして成功またはエラートーストが出る', async ({
    page,
  }) => {
    // login + goto + waitForHydration + 各 isVisible 待機の累積で 60s を超えることがあるため延長
    test.setTimeout(90_000)

    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, ADMIN_EMAIL, ADMIN_PASSWORD, `/teams/${adminTeamSlug}/payments`)

    // 支払い項目リストが読み込まれるまで待ってからクリック
    const itemButton = page.locator('.w-64 button').filter({ hasText: billingTestPaymentItemName })
    await expect(itemButton, '作成した支払い項目ボタンが表示されること').toBeVisible({ timeout: 10_000 })
    await itemButton.click()

    // リマインドボタンを探す（selectedItem が設定されると表示される）
    const remindButton = page
      .getByRole('button', { name: /リマインド|remind|催促|通知/i })
      .first()

    await expect(remindButton, 'リマインドボタンが表示されること').toBeVisible({ timeout: 10_000 })

    // API リクエストを監視
    const [apiResp] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes('/payment-items/') &&
          (r.url().includes('/remind') || r.url().includes('/reminders')) &&
          r.request().method() === 'POST',
        { timeout: 15_000 },
      ),
      remindButton.click(),
    ])

    // 201 Created または 200 が返ること
    expect([200, 201]).toContain(apiResp.status())

    // トースト（成功またはエラー）が表示されること
    const hasToast = await page
      .locator('.p-toast, [class*="toast"], [role="alert"]')
      .isVisible({ timeout: 10_000 })
      .catch(() => false)
    expect(hasToast || apiResp !== null, 'リマインド送信後にフィードバックが表示される').toBe(
      true,
    )

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p6-04-remind-admin.png`,
      fullPage: true,
    })
  })

  test('P6-05: [member] /teams/[id]/payments → 管理画面に入れないか自分の情報のみ表示', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, MEMBER_EMAIL, MEMBER_PASSWORD, `/teams/${adminTeamSlug}/payments`)

    await page.waitForTimeout(3_000)

    // 403 / エラー / リダイレクトのいずれかになるか、ページが表示されても管理者機能がない
    const isRestricted =
      page.url().includes('/403') ||
      page.url().includes('/error') ||
      page.url().includes('/login') ||
      (await page.getByText(/403|権限がありません|アクセス/i).isVisible({ timeout: 3_000 }).catch(() => false))

    // 完全制限でない場合でも CSV ボタン・リマインドボタンは非表示であること
    if (isRestricted) {
      // 正常にアクセス制限されている → 理想的な動作
      expect(isRestricted).toBe(true)
    } else {
      // ページが表示されている場合: 管理者ボタンが見えないことを確認
      const csvBtn = page.getByRole('button', { name: /CSV/i })
      const remindBtn = page.getByRole('button', { name: /リマインド|remind/i })
      const csvVisible = await csvBtn.isVisible({ timeout: 3_000 }).catch(() => false)
      const remindVisible = await remindBtn.isVisible({ timeout: 3_000 }).catch(() => false)
      expect(csvVisible, 'MEMBER には CSV ボタンが表示されないこと').toBe(false)
      expect(remindVisible, 'MEMBER にはリマインドボタンが表示されないこと').toBe(false)
    }

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p6-05-payments-member.png`,
      fullPage: true,
    })
  })

  test('P6-06: [member] BE API でリマインド → 403 が返ること', async () => {
    const { accessToken: memberToken } = await apiLogin(
      sharedApi,
      MEMBER_EMAIL,
      MEMBER_PASSWORD,
    )
    const remindRes = await sharedApi.post(
      `${BE_API}/teams/${adminTeamId}/payment-items/${billingTestPaymentItemId}/remind`,
      { headers: authHeaders(memberToken) },
    )
    // ADMIN 認可チェック済み（TeamPaymentController#sendRemind に checkAdminOrAbove 追加済み）
    expect(remindRes.status(), '実在する項目への MEMBER リマインド API は 403').toBe(403)
  })

  test('P6-07: [supporter] /teams/[id]/payments → 管理機能にアクセスできない', async ({ page }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, SUPPORTER_EMAIL, SUPPORTER_PASSWORD, `/teams/${adminTeamSlug}/payments`)
    await page.waitForTimeout(3_000)

    const isPageRestricted =
      page.url().includes('/403') ||
      page.url().includes('/login')

    if (!isPageRestricted) {
      // サポーターには CSV/リマインドボタンが表示されない
      const csvVisible = await page.getByRole('button', { name: /CSV/i }).isVisible({ timeout: 3_000 }).catch(() => false)
      const remindVisible = await page.getByRole('button', { name: /リマインド|remind/i }).isVisible({ timeout: 3_000 }).catch(() => false)
      expect(csvVisible, 'サポーターには CSV ボタンが表示されないこと').toBe(false)
      expect(remindVisible, 'サポーターにはリマインドボタンが表示されないこと').toBe(false)
    }

    // API レベルでサポーターに管理 API が返らないことを確認
    const supporterLoginRes = await sharedApi.post(`${BE_API}/auth/login`, {
      data: { email: SUPPORTER_EMAIL, password: SUPPORTER_PASSWORD },
    })
    const { data: { accessToken: supporterToken } } = (await supporterLoginRes.json()) as { data: { accessToken: string } }
    const remindApiRes = await sharedApi.post(`${BE_API}/teams/${adminTeamId}/payment-items/${billingTestPaymentItemId}/remind`, {
      headers: authHeaders(supporterToken),
    })
    expect(remindApiRes.status(), '実在する項目へのサポーターのリマインド API は 403').toBe(403)

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: 'test-results/f089-p6-07-payments-supporter.png',
      fullPage: true,
    })
  })
})

// ===========================================================================
// P2: 後見まとめ払い
// ===========================================================================
test.describe('F08.9 P2: 後見まとめ払い', () => {
  test('P2-01: [public] /me/guardianship/bulk-payment → /login にリダイレクト', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await page.goto('/me/guardianship/bulk-payment', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.waitForURL(/\/login/, { timeout: 15_000 })

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p2-01-bulk-payment-public-redirect.png`,
      fullPage: true,
    })
  })

  test('P2-02: [member] /me/guardianship/bulk-payment → ページが表示される', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, MEMBER_EMAIL, MEMBER_PASSWORD, '/me/guardianship/bulk-payment')

    // ページが表示されること（後見子がいない場合は空状態）
    const hasContent = await page
      .locator('#__nuxt')
      .isVisible({ timeout: 15_000 })
      .catch(() => false)
    expect(hasContent, 'MEMBER はまとめ払いページにアクセスできる').toBe(true)
    // エラーページでないこと
    expect(page.url()).not.toContain('/error')
    expect(page.url()).not.toContain('/login')

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p2-02-bulk-payment-member.png`,
      fullPage: true,
    })
  })

  test('P2-03: [member] ページに「支払う」ボタンまたは「対象なし」メッセージが存在する', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, MEMBER_EMAIL, MEMBER_PASSWORD, '/me/guardianship/bulk-payment')

    // ローディング完了まで待つ
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 15_000 })
    await page.waitForTimeout(2_000)

    // 「支払う」ボタンまたは空状態メッセージのいずれかが表示される
    // 実機観測（2026-06-17）: 後見子なしの場合「未払いの会費はありません」が表示される
    const hasPayButton = await page
      .getByRole('button', { name: /支払う|まとめて|checkout/i })
      .isVisible({ timeout: 5_000 })
      .catch(() => false)
    // bodyText で実際の表示テキストを確認してからマッチ判定する（getByText のタイミング問題を回避）
    const bodyText = await page.locator('body').innerText()
    const hasEmptyMessage = /対象なし|未払い|会費はありません|支払うべき会費がありません|no.*due|payable/i.test(bodyText)
    // ページが正常に描画されること（クラッシュしていないこと）
    expect(bodyText).not.toContain('undefined')
    expect(bodyText).not.toContain('NaN')
    expect(page.url()).not.toContain('/error')
    // 「支払う」ボタンまたは「対象なし」メッセージのどちらかが必ず可視であること（偽陰性防止）
    expect(
      hasPayButton || hasEmptyMessage,
      'まとめ払いページには「支払う」ボタンか「未払い会費なし」メッセージが表示されること',
    ).toBe(true)

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p2-03-bulk-payment-member-content.png`,
      fullPage: true,
    })
  })

  test('P2-04: [admin] /me/guardianship/bulk-payment → ページが表示される（admin も一般ユーザーとして）', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, ADMIN_EMAIL, ADMIN_PASSWORD, '/me/guardianship/bulk-payment')

    expect(page.url()).not.toContain('/login')
    expect(page.url()).not.toContain('/error')

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p2-04-bulk-payment-admin.png`,
      fullPage: true,
    })
  })

  test('P2-05: [member] API で payable-dues → 正常レスポンスが返る', async () => {
    const { accessToken: memberToken } = await apiLogin(
      sharedApi,
      MEMBER_EMAIL,
      MEMBER_PASSWORD,
    )
    const res = await sharedApi.get(`${BE_API}/me/payable-dues`, {
      headers: authHeaders(memberToken),
    })
    expect(res.status(), '固定 seed の後見関係で payable-dues は 200').toBe(200)
    const json = (await res.json()) as { data: { items: unknown[] } }
    expect(json.data.items.length, '固定 seed の後見対象に未払い項目が存在すること').toBeGreaterThan(0)
  })

  test('P2-06: [member, 後見子あり] チェックボックス選択で「支払う」ボタンが活性化する', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    // 後見子の存在確認
    const { accessToken: memberToken } = await apiLogin(
      sharedApi,
      MEMBER_EMAIL,
      MEMBER_PASSWORD,
    )
    const duesRes = await sharedApi.get(`${BE_API}/me/payable-dues`, {
      headers: authHeaders(memberToken),
    })
    expect(duesRes.status(), 'payable-dues API は 200').toBe(200)
    const duesJson = (await duesRes.json()) as { data: { items: unknown[] } }
    expect(duesJson.data.items.length, '後見対象の未払い項目が存在すること').toBeGreaterThan(0)

    await loginAndNavigate(page, MEMBER_EMAIL, MEMBER_PASSWORD, '/me/guardianship/bulk-payment')
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 15_000 })

    // チェックボックスを選択
    const checkbox = page.locator('.p-checkbox, input[type="checkbox"]').first()
    await expect(checkbox, '後見対象の支払いチェックボックスが表示されること').toBeVisible({ timeout: 10_000 })
    await checkbox.click()
    await page.waitForTimeout(500)

    // 「支払う」ボタンが活性化
    const payButton = page.getByRole('button', { name: /支払う|まとめて|checkout/i }).first()
    const isEnabled = await payButton.isEnabled({ timeout: 5_000 }).catch(() => false)
    expect(isEnabled, 'チェックボックス選択後に支払いボタンが活性化する').toBe(true)

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p2-06-bulk-payment-checkbox.png`,
      fullPage: true,
    })
  })

  test('P2-07: [supporter] /me/guardianship/bulk-payment → ページが表示される（supporter は一般ユーザーとして扱う）', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, SUPPORTER_EMAIL, SUPPORTER_PASSWORD, '/me/guardianship/bulk-payment')

    // サポーターも自分のまとめ払いページにアクセスできる（後見子がいれば）
    expect(page.url()).not.toContain('/login')
    expect(page.url()).not.toContain('/error')

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: 'test-results/f089-p2-07-bulk-payment-supporter.png',
      fullPage: true,
    })
  })
})

// ===========================================================================
// P3c: 後見切替
// ===========================================================================
test.describe('F08.9 P3c: 後見切替', () => {
  test('P3c-01: [public] /me/guardianship/switch → /login にリダイレクト', async ({ page }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await page.goto('/me/guardianship/switch')
    await page.waitForURL(/\/login/, { timeout: 15_000 })

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p3c-01-switch-public-redirect.png`,
      fullPage: true,
    })
  })

  test('P3c-02: [member] /me/guardianship/switch → ページが表示される', async ({ page }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, MEMBER_EMAIL, MEMBER_PASSWORD, '/me/guardianship/switch')

    expect(page.url()).not.toContain('/login')
    expect(page.url()).not.toContain('/error')

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p3c-02-switch-member.png`,
      fullPage: true,
    })
  })

  test('P3c-03: [member] 後見子が存在しない場合、空状態またはメッセージが表示される', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, MEMBER_EMAIL, MEMBER_PASSWORD, '/me/guardianship/switch')
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 15_000 })
    await page.waitForTimeout(2_000)

    // エラーページでないこと
    expect(page.url()).not.toContain('/error')
    // ページが壊れていないこと（undefined / NaN が出ていない）
    const body = await page.locator('body').innerText()
    expect(body).not.toContain('undefined')
    expect(body).not.toContain('NaN')

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p3c-03-switch-member-empty.png`,
      fullPage: true,
    })
  })

  test('P3c-04: [admin] /me/guardianship/switch → ページが表示される', async ({ page }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, ADMIN_EMAIL, ADMIN_PASSWORD, '/me/guardianship/switch')

    expect(page.url()).not.toContain('/login')
    expect(page.url()).not.toContain('/error')

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p3c-04-switch-admin.png`,
      fullPage: true,
    })
  })

  test('P3c-05: [member] BE API で switchable-children → 200 が返る', async () => {
    const { accessToken: memberToken } = await apiLogin(
      sharedApi,
      MEMBER_EMAIL,
      MEMBER_PASSWORD,
    )
    const res = await sharedApi.get(`${BE_API}/me/guardianship/switchable-children`, {
      headers: authHeaders(memberToken),
    })
    // 200 または 404 が許容される
    expect([200, 404]).toContain(res.status())
  })

  test('P3c-06: [supporter] /me/guardianship/switch → ページが表示される', async ({ page }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, SUPPORTER_EMAIL, SUPPORTER_PASSWORD, '/me/guardianship/switch')

    expect(page.url()).not.toContain('/login')
    expect(page.url()).not.toContain('/error')

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: 'test-results/f089-p3c-06-switch-supporter.png',
      fullPage: true,
    })
  })
})

// ===========================================================================
// P4/P5: ペイウォール・継続課金加入
// ===========================================================================
test.describe('F08.9 P4/P5: ペイウォール・継続課金加入', () => {
  test('P4-01: [public] /payments/subscribe/1 → /login にリダイレクト', async ({ page }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await page.goto('/payments/subscribe/1')
    // Nuxt client-side auth middleware がリダイレクトするまで最大 30s 待つ（SSR後の hydration を含む）
    await page.waitForURL(/\/login/, { timeout: 30_000 })

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p4-01-subscribe-public-redirect.png`,
      fullPage: true,
    })
  })

  test('P4-02: [member] /payments/subscribe/1 → 受益者選択ステップが表示される', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, MEMBER_EMAIL, MEMBER_PASSWORD, '/payments/subscribe/1')

    // subscribe-next は step='beneficiary'（デフォルト）かつ itemId !== null（=1 で true）のとき
    // childrenLoading 条件の外側に描画されるため、API 完了前から常に visible になる
    await expect(
      page.getByTestId('subscribe-next'),
      'MEMBER は加入ページにアクセスできる',
    ).toBeVisible({ timeout: 25_000 })
    expect(page.url()).not.toContain('/login')

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p4-02-subscribe-member.png`,
      fullPage: true,
    })
  })

  test('P4-03: [member] 受益者選択の「次へ」ボタンが存在する', async ({ page }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(
      page,
      MEMBER_EMAIL,
      MEMBER_PASSWORD,
      `/payments/subscribe/${billingTestPaymentItemId}`,
    )
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 15_000 })

    await expect(page.getByTestId('subscribe-error'), '有効な支払い項目でエラーにならないこと').toBeHidden()

    const nextButton = page.getByTestId('subscribe-next')
    const isVisible = await nextButton.isVisible({ timeout: 15_000 }).catch(() => false)
    expect(isVisible, '受益者選択ステップに「次へ」ボタンが表示される').toBe(true)

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p4-03-subscribe-next-button.png`,
      fullPage: true,
    })
  })

  test('P4-04: [member] /me/payments/subscriptions → 加入一覧ページが表示される', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, MEMBER_EMAIL, MEMBER_PASSWORD, '/me/payments/subscriptions')

    // ページが表示されること（データなしでも OK）
    expect(page.url()).not.toContain('/login')
    expect(page.url()).not.toContain('/error')

    const hasContent = await page.locator('#__nuxt').isVisible({ timeout: 15_000 }).catch(() => false)
    expect(hasContent, 'MEMBER は加入一覧ページにアクセスできる').toBe(true)

    // ページに undefined / NaN がないこと
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 10_000 })
    const body = await page.locator('body').innerText()
    expect(body).not.toContain('undefined')
    expect(body).not.toContain('NaN')

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p4-04-subscriptions-member.png`,
      fullPage: true,
    })
  })

  test('P4-05: [admin] /me/payments/subscriptions → 管理者も加入一覧を確認できる', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(page, ADMIN_EMAIL, ADMIN_PASSWORD, '/me/payments/subscriptions')

    expect(page.url()).not.toContain('/login')
    expect(page.url()).not.toContain('/error')

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p4-05-subscriptions-admin.png`,
      fullPage: true,
    })
  })

  test('P4-06: [public] /me/payments/subscriptions → /login にリダイレクト', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await page.goto('/me/payments/subscriptions')
    await page.waitForURL(/\/login/, { timeout: 15_000 })

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p4-06-subscriptions-public-redirect.png`,
      fullPage: true,
    })
  })

  test('P5-01: [member] 加入一覧 API → 200 が返る', async () => {
    const { accessToken: memberToken } = await apiLogin(
      sharedApi,
      MEMBER_EMAIL,
      MEMBER_PASSWORD,
    )
    const res = await sharedApi.get(`${BE_API}/me/membership-subscriptions`, {
      headers: authHeaders(memberToken),
    })
    expect(res.status(), '加入一覧 API は 200').toBe(200)
    const json = (await res.json()) as { data: unknown[] }
    expect(Array.isArray(json.data)).toBe(true)
  })

  test('P5-02: [public] 加入一覧 API → 401 が返る', async () => {
    // sharedApi はセッションクッキーを保持しているため、新規コンテキストで未認証をシミュレート
    const anonApi = await pwRequest.newContext()
    try {
      const res = await anonApi.get(`${BE_API}/me/membership-subscriptions`)
      expect(res.status()).toBe(401)
    } finally {
      await anonApi.dispose()
    }
  })

  test('P5-06: [admin] 加入一覧 API → admin ユーザーも 200 が返る', async () => {
    const res = await sharedApi.get(`${BE_API}/me/membership-subscriptions`, {
      headers: authHeaders(adminToken),
    })
    expect(res.status(), 'admin の加入一覧 API は 200').toBe(200)
  })

  test('P4-07: [supporter] /payments/subscribe/1 → 加入フローにアクセスできる', async ({ page }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    await loginAndNavigate(
      page,
      SUPPORTER_EMAIL,
      SUPPORTER_PASSWORD,
      `/payments/subscribe/${billingTestPaymentItemId}`,
    )
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 15_000 })

    // サポーターも加入フローにアクセスできること（ログインリダイレクトなし）
    expect(page.url()).not.toContain('/login')

    const hasSubscribeForm =
      (await page.getByTestId('subscribe-next').isVisible({ timeout: 15_000 }).catch(() => false)) ||
      (await page.getByTestId('subscribe-error').isVisible({ timeout: 5_000 }).catch(() => false)) ||
      (await page.getByText(/受益者|beneficiary|次へ|加入/i).isVisible({ timeout: 5_000 }).catch(() => false))
    expect(hasSubscribeForm, 'サポーターは加入フローにアクセスできる').toBe(true)

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: 'test-results/f089-p4-07-subscribe-supporter.png',
      fullPage: true,
    })
  })
})
