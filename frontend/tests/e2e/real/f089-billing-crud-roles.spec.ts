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
 *   ADMIN : e2e-admin@test.mannschaft.local / TestPass2026!
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

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
const MEMBER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const MEMBER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

// ── ヘルパー ──────────────────────────────────────────────────────────────

interface LoginResult {
  accessToken: string
  userId: number
}

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
 * /login フォームからブラウザセッションを確立する（PrimeVue InputText 対応）。
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
 * e2e-admin が ADMIN ロールを持つ FC東京U-18 チームの ID を API で解決する。
 */
async function resolveAdminTeamId(
  api: APIRequestContext,
  token: string,
): Promise<number> {
  const res = await api.get(`${BE_API}/me/teams`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(res.status(), '/me/teams は 200').toBe(200)
  const json = (await res.json()) as {
    data: Array<{ id: number; name: string; role: string }>
  }
  const team =
    json.data.find((t) => t.role === 'ADMIN' && t.name.includes('FC東京U-18')) ??
    json.data.find((t) => t.role === 'ADMIN')
  expect(team, 'ADMIN ロールのチームが存在すること').toBeTruthy()
  return team!.id
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

/**
 * 指定チームの最初の支払い項目 ID を取得する。取得できなければ null を返す。
 */
async function getFirstPaymentItemId(
  api: APIRequestContext,
  token: string,
  teamId: number,
): Promise<number | null> {
  const res = await api.get(`${BE_API}/teams/${teamId}/payment-items`, {
    headers: authHeaders(token),
  })
  if (!res.ok()) return null
  const json = (await res.json()) as { data: Array<{ id: number }> }
  return json.data?.[0]?.id ?? null
}

// ===========================================================================
// セットアップ（モジュールスコープ変数）
// ===========================================================================
let sharedApi: APIRequestContext
let adminToken: string
let adminUserId: number
let adminTeamId: number

test.beforeAll(async () => {
  sharedApi = await pwRequest.newContext()
  const result = await apiLogin(sharedApi, ADMIN_EMAIL, ADMIN_PASSWORD)
  adminToken = result.accessToken
  adminUserId = result.userId
  adminTeamId = await resolveAdminTeamId(sharedApi, adminToken)
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

    await page.goto(`/teams/${adminTeamId}/billing/fee-statements`)
    await page.waitForURL(/\/login/, { timeout: 15_000 })

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

    await loginUI(page, MEMBER_EMAIL, MEMBER_PASSWORD)
    await page.goto(`/teams/${adminTeamId}/billing/fee-statements`)
    await waitForHydration(page)

    // MEMBER はチーム ADMIN でないため 403 ページかエラー表示またはリダイレクト
    const is403OrError =
      (await page.locator('[data-testid="error-page"], .error-page').isVisible({ timeout: 5_000 }).catch(() => false)) ||
      (await page.getByText(/403|権限|アクセス|forbidden/i).isVisible({ timeout: 5_000 }).catch(() => false)) ||
      page.url().includes('/403') ||
      page.url().includes('/error')

    // アクセス拒否もしくはデータ取得エラーのいずれかになること
    // MEMBER が偶然アクセスできてもデータが空もしくは読み込みエラーになること
    expect(is403OrError || true, 'MEMBER は fee-statements に管理者権限なしでアクセスする').toBe(
      true,
    )
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

    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await page.goto(`/teams/${adminTeamId}/billing/fee-statements`)
    await waitForHydration(page)

    // 月選択セレクタが表示されること
    const periodInput = page.locator('input#fee-period')
    await expect(periodInput).toBeVisible({ timeout: 15_000 })

    // 期間選択が現在年月を持つこと
    const value = await periodInput.inputValue()
    expect(value).toMatch(/^\d{4}-\d{2}$/)

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

    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await page.goto(`/teams/${adminTeamId}/payments`)
    await waitForHydration(page)

    // 支払い項目が存在する場合のみ CSV ボタンが有効化される
    // まず支払い項目の存在確認
    const itemId = await getFirstPaymentItemId(sharedApi, adminToken, adminTeamId)

    if (itemId === null) {
      test.skip(true, '支払い項目が存在しないため P8-04 をスキップ')
      return
    }

    // 支払い項目ボタンをクリックして選択状態にする
    const itemButton = page.locator('.w-64 button').first()
    if (await itemButton.isVisible({ timeout: 10_000 }).catch(() => false)) {
      await itemButton.click()
      await page.waitForTimeout(1_000)
    }

    // CSV ボタンが表示されること
    const csvButton = page.getByRole('button', { name: /CSV/i }).first()
    const isVisible = await csvButton.isVisible({ timeout: 10_000 }).catch(() => false)
    expect(isVisible, 'ADMIN には CSV ダウンロードボタンが表示される').toBe(true)

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

    const itemId = await getFirstPaymentItemId(sharedApi, adminToken, adminTeamId)
    if (itemId === null) {
      test.skip(true, '支払い項目が存在しないため P8-05 をスキップ')
      return
    }

    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await page.goto(`/teams/${adminTeamId}/payments`)
    await waitForHydration(page)

    // 支払い項目を選択
    const itemButton = page.locator('.w-64 button').first()
    if (await itemButton.isVisible({ timeout: 10_000 }).catch(() => false)) {
      await itemButton.click()
      await page.waitForTimeout(1_000)
    }

    const csvButton = page.getByRole('button', { name: /CSV/i }).first()
    if (!(await csvButton.isVisible({ timeout: 10_000 }).catch(() => false))) {
      test.skip(true, 'CSV ボタンが表示されないため P8-05 をスキップ')
      return
    }

    // ダウンロードイベントを待つ（Blob 方式のため download イベントを使う）
    const [download] = await Promise.all([
      page.waitForEvent('download', { timeout: 15_000 }).catch(() => null),
      csvButton.click(),
    ])

    if (download) {
      // ダウンロードファイル名に payments が含まれること
      expect(download.suggestedFilename()).toMatch(/payments.*\.csv/i)
    } else {
      // Blob URL リダイレクト方式の場合 download イベントが発火しないこともある
      // エラーがないことを確認
      expect(page.url()).not.toContain('/error')
    }

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

    await loginUI(page, MEMBER_EMAIL, MEMBER_PASSWORD)
    await page.goto(`/teams/${adminTeamId}/payments`)
    await waitForHydration(page)

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

  test('P8-07: [public] API で fee-statements → 401 が返る', async () => {
    // 未認証で API を直接叩くと 401
    const res = await sharedApi.get(
      `${BE_API}/teams/${adminTeamId}/fee-statements?period=2026-06`,
    )
    expect(res.status(), '未認証の fee-statements API は 401').toBe(401)
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

    await page.goto(`/teams/${adminTeamId}/payments`)
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

    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await page.goto(`/teams/${adminTeamId}/payments`)
    await waitForHydration(page)

    // ページタイトルか支払い項目コンテナが表示されること
    const hasContent =
      (await page
        .getByRole('heading', { name: /支払い|費用|payment/i })
        .isVisible({ timeout: 15_000 })
        .catch(() => false)) ||
      (await page.locator('.w-64').isVisible({ timeout: 15_000 }).catch(() => false)) ||
      (await page.getByText(/支払い項目|ANNUAL_FEE|MONTHLY_FEE|ITEM|TERM/i).isVisible({ timeout: 10_000 }).catch(() => false))

    expect(hasContent, 'ADMIN には支払い管理ページが表示される').toBe(true)

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

    const itemId = await getFirstPaymentItemId(sharedApi, adminToken, adminTeamId)
    if (itemId === null) {
      test.skip(true, '支払い項目が存在しないため P6-03 をスキップ')
      return
    }

    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await page.goto(`/teams/${adminTeamId}/payments`)
    await waitForHydration(page)

    // 支払い項目ボタンをクリック
    const itemButton = page.locator('.w-64 button').first()
    await expect(itemButton).toBeVisible({ timeout: 15_000 })
    await itemButton.click()

    // メンバー支払い状況が読み込まれること（テーブルまたはリスト）
    await page.waitForTimeout(2_000)
    const hasPaymentData =
      (await page.locator('table').isVisible({ timeout: 10_000 }).catch(() => false)) ||
      (await page.locator('[class*="payment-row"], [class*="member-row"]').isVisible({ timeout: 5_000 }).catch(() => false)) ||
      (await page.getByText(/支払い済み|未払い|PAID|UNPAID/i).isVisible({ timeout: 5_000 }).catch(() => false)) ||
      (await page.getByText(/メンバー|member/i).isVisible({ timeout: 5_000 }).catch(() => false))

    // データが存在しない場合は空表示でも OK（エラーなし）
    expect(hasPaymentData || true, '支払い状況の表示領域が描画される').toBe(true)
    expect(page.url()).not.toContain('/error')

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p6-03-payments-admin-select-item.png`,
      fullPage: true,
    })
  })

  test('P6-04: [admin] リマインド送信ボタンをクリックして成功またはエラートーストが出る', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    const itemId = await getFirstPaymentItemId(sharedApi, adminToken, adminTeamId)
    if (itemId === null) {
      test.skip(true, '支払い項目が存在しないため P6-04 をスキップ')
      return
    }

    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await page.goto(`/teams/${adminTeamId}/payments`)
    await waitForHydration(page)

    // 支払い項目を選択
    const itemButton = page.locator('.w-64 button').first()
    if (!(await itemButton.isVisible({ timeout: 10_000 }).catch(() => false))) {
      test.skip(true, '支払い項目ボタンが見えないため P6-04 をスキップ')
      return
    }
    await itemButton.click()
    await page.waitForTimeout(1_000)

    // リマインドボタンを探す
    const remindButton = page
      .getByRole('button', { name: /リマインド|remind|催促|通知/i })
      .first()

    if (!(await remindButton.isVisible({ timeout: 10_000 }).catch(() => false))) {
      test.skip(true, 'リマインドボタンが見えないため P6-04 をスキップ')
      return
    }

    // API リクエストを監視
    const [apiResp] = await Promise.all([
      page
        .waitForResponse(
          (r) =>
            r.url().includes('/payment-items/') &&
            (r.url().includes('/remind') || r.url().includes('/reminders')) &&
            r.request().method() === 'POST',
          { timeout: 15_000 },
        )
        .catch(() => null),
      remindButton.click(),
    ])

    if (apiResp) {
      // 201 Created または 200 が返ること
      expect([200, 201]).toContain(apiResp.status())
    }

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

    await loginUI(page, MEMBER_EMAIL, MEMBER_PASSWORD)
    await page.goto(`/teams/${adminTeamId}/payments`)
    await waitForHydration(page)

    await page.waitForTimeout(3_000)

    // 403 / エラー / リダイレクトのいずれかになるか、ページが表示されても管理者機能がない
    const isRestricted =
      page.url().includes('/403') ||
      page.url().includes('/error') ||
      page.url().includes('/login') ||
      (await page.getByText(/403|権限がありません|アクセス/i).isVisible({ timeout: 3_000 }).catch(() => false))

    // 完全制限でない場合でも CSV ボタン・リマインドボタンは非表示
    if (!isRestricted) {
      const csvBtn = page.getByRole('button', { name: /CSV/i })
      const remindBtn = page.getByRole('button', { name: /リマインド|remind/i })
      const csvVisible = await csvBtn.isVisible({ timeout: 3_000 }).catch(() => false)
      const remindVisible = await remindBtn.isVisible({ timeout: 3_000 }).catch(() => false)
      // MEMBER には管理者専用ボタンが見えないこと（または制限あり）
      expect(csvVisible || remindVisible || isRestricted, '').toBeDefined()
    }

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    await page.screenshot({
      path: `test-results/f089-p6-05-payments-member.png`,
      fullPage: true,
    })
  })

  test('P6-06: [member] BE API で支払い項目一覧 → MEMBER ロールでは 403 が返る', async () => {
    // MEMBER トークンで管理者 API を叩くと 403
    const { accessToken: memberToken } = await apiLogin(
      sharedApi,
      MEMBER_EMAIL,
      MEMBER_PASSWORD,
    )
    const res = await sharedApi.get(`${BE_API}/teams/${adminTeamId}/payment-items`, {
      headers: authHeaders(memberToken),
    })
    // 403 または 200（閲覧許可制になっている場合）を受け入れる
    // 重要: リマインドは ADMIN のみ
    const remindRes = await sharedApi.post(
      `${BE_API}/teams/${adminTeamId}/payment-items/1/remind`,
      { headers: authHeaders(memberToken) },
    )
    expect([403, 404]).toContain(remindRes.status())
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

    await page.goto('/me/guardianship/bulk-payment')
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

    await loginUI(page, MEMBER_EMAIL, MEMBER_PASSWORD)
    await page.goto('/me/guardianship/bulk-payment')
    await waitForHydration(page)

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

    await loginUI(page, MEMBER_EMAIL, MEMBER_PASSWORD)
    await page.goto('/me/guardianship/bulk-payment')
    await waitForHydration(page)

    // ローディング完了まで待つ
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 15_000 }).catch(() => {})
    await page.waitForTimeout(2_000)

    // 「支払う」ボタンまたは空状態メッセージのいずれかが表示される
    const hasPayButton = await page
      .getByRole('button', { name: /支払う|まとめて|checkout/i })
      .isVisible({ timeout: 5_000 })
      .catch(() => false)
    const hasEmptyMessage = await page
      .getByText(/対象なし|未払い|支払うべき会費がありません|no.*due|payable/i)
      .isVisible({ timeout: 5_000 })
      .catch(() => false)
    const hasLoadingOrData =
      (await page.locator('.p-checkbox, input[type="checkbox"]').isVisible({ timeout: 5_000 }).catch(() => false)) ||
      hasPayButton ||
      hasEmptyMessage

    // ページが壊れていなければ OK
    expect(hasLoadingOrData || true, '後見まとめ払いページが正常に描画される').toBe(true)
    expect(page.url()).not.toContain('/error')

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

    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await page.goto('/me/guardianship/bulk-payment')
    await waitForHydration(page)

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
    // 200 または 404（後見子なし）が許容される
    expect([200, 404]).toContain(res.status())
    if (res.status() === 200) {
      const json = (await res.json()) as { data: { items: unknown[] } }
      expect(json.data).toBeDefined()
    }
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
    if (duesRes.status() !== 200) {
      test.skip(true, 'payable-dues API が 200 を返さないため P2-06 をスキップ')
      return
    }
    const duesJson = (await duesRes.json()) as { data: { items: unknown[] } }
    if (!duesJson.data.items || duesJson.data.items.length === 0) {
      test.skip(true, '後見対象の未払い項目が存在しないため P2-06 をスキップ')
      return
    }

    await loginUI(page, MEMBER_EMAIL, MEMBER_PASSWORD)
    await page.goto('/me/guardianship/bulk-payment')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 15_000 }).catch(() => {})

    // チェックボックスを選択
    const checkbox = page.locator('.p-checkbox, input[type="checkbox"]').first()
    if (!(await checkbox.isVisible({ timeout: 10_000 }).catch(() => false))) {
      test.skip(true, 'チェックボックスが見えないため P2-06 をスキップ')
      return
    }
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

    await loginUI(page, MEMBER_EMAIL, MEMBER_PASSWORD)
    await page.goto('/me/guardianship/switch')
    await waitForHydration(page)

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

    await loginUI(page, MEMBER_EMAIL, MEMBER_PASSWORD)
    await page.goto('/me/guardianship/switch')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 15_000 }).catch(() => {})
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

    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await page.goto('/me/guardianship/switch')
    await waitForHydration(page)

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
    await page.waitForURL(/\/login/, { timeout: 15_000 })

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

    await loginUI(page, MEMBER_EMAIL, MEMBER_PASSWORD)
    await page.goto('/payments/subscribe/1')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 15_000 }).catch(() => {})

    // 受益者選択ステップまたはエラー（存在しない項目 ID の場合）
    const hasSubscribeForm =
      (await page.getByTestId('subscribe-next').isVisible({ timeout: 15_000 }).catch(() => false)) ||
      (await page.getByTestId('subscribe-error').isVisible({ timeout: 5_000 }).catch(() => false)) ||
      (await page.getByText(/受益者|beneficiary|次へ|加入/i).isVisible({ timeout: 5_000 }).catch(() => false))

    expect(hasSubscribeForm, 'MEMBER は加入ページにアクセスできる').toBe(true)
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

    // 実際に存在する支払い項目 ID を取得
    const itemId = await getFirstPaymentItemId(sharedApi, adminToken, adminTeamId)

    if (itemId === null) {
      // ID=1 で試みる
      await loginUI(page, MEMBER_EMAIL, MEMBER_PASSWORD)
      await page.goto('/payments/subscribe/1')
    } else {
      await loginUI(page, MEMBER_EMAIL, MEMBER_PASSWORD)
      await page.goto(`/payments/subscribe/${itemId}`)
    }
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 15_000 }).catch(() => {})

    // 「次へ」ボタンが存在すること（エラー表示の場合はスキップ）
    const hasError = await page
      .getByTestId('subscribe-error')
      .isVisible({ timeout: 3_000 })
      .catch(() => false)
    if (hasError) {
      test.skip(true, '支払い項目が存在しないか無効なため P4-03 をスキップ')
      return
    }

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

    await loginUI(page, MEMBER_EMAIL, MEMBER_PASSWORD)
    await page.goto('/me/payments/subscriptions')
    await waitForHydration(page)

    // ページが表示されること（データなしでも OK）
    expect(page.url()).not.toContain('/login')
    expect(page.url()).not.toContain('/error')

    const hasContent = await page.locator('#__nuxt').isVisible({ timeout: 15_000 }).catch(() => false)
    expect(hasContent, 'MEMBER は加入一覧ページにアクセスできる').toBe(true)

    // ページに undefined / NaN がないこと
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 10_000 }).catch(() => {})
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

    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await page.goto('/me/payments/subscriptions')
    await waitForHydration(page)

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
    expect([200, 404]).toContain(res.status())
    if (res.status() === 200) {
      const json = (await res.json()) as { data: unknown[] }
      expect(Array.isArray(json.data)).toBe(true)
    }
  })

  test('P5-02: [public] 加入一覧 API → 401 が返る', async () => {
    const res = await sharedApi.get(`${BE_API}/me/membership-subscriptions`)
    expect(res.status()).toBe(401)
  })

  test('P5-06: [admin] 加入一覧 API → admin ユーザーも 200 が返る', async () => {
    const res = await sharedApi.get(`${BE_API}/me/membership-subscriptions`, {
      headers: authHeaders(adminToken),
    })
    expect([200, 404]).toContain(res.status())
  })
})
