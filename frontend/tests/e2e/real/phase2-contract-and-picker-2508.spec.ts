/**
 * Issue #2508 Phase 2 — 実機 E2E。
 *
 * ## 背景（このファイルが検証する3本のPR）
 *
 * - #2612(BE): 値域超過で 500 になっていた穴を根治。DST gap/overlap を明文化・固定。
 * - #2615(FE): アンケート作成が `distributionMode` 欠落等で 1 件も 201 に到達していなかった
 *   契約不整合5系統（`deadline`→`expiresAt` / `distributionMode` 欠落 /
 *   `resultsVisibility` enum 不一致 / `questionType` の `DATE`/`TEXT` 不整合 /
 *   `sortOrder`→`displayOrder`）を根治。ブログ「今すぐ公開」・ダイジェスト生成の 400 も併せて根治。
 * - #2626(FE): DatePicker が返す `Date` を「瞬間」ではなく「壁時計」として扱うよう
 *   `useDatetime().buildOffsetDateTimeStr` / `~/utils/localDate` を是正。
 *   ブラウザTZとプロフィールTZが食い違うと選んだ日が1日ずれる事故を根治。
 *
 * ## 本 spec の核心（P2-02）
 *
 * `DigestGenerateDialog` の期間ピッカー（`buildDigestPeriod` → `buildOffsetDateTimeStr`）を使い、
 * ブラウザ JST・プロフィール America/Los_Angeles の状態で「8/4」を選んで送信リクエストを実測する。
 *
 * 修正前の実装（`dayjs(date).tz(profileTz)`）だと、ブラウザ JST で 8/4 00:00 に生成される `Date` を
 * 「瞬間」として LA へ投影し直すため、
 *   2026-08-04T00:00:00+09:00（瞬間） = 2026-08-03T08:00:00-07:00（LA 壁時計）
 * となり、**送信される暦日が "2026-08-03" にずれていた**。
 * 修正後は `Date` のブラウザ壁時計成分（getFullYear/getMonth/getDate）をそのまま暦日として使い、
 * オフセットだけプロフィールTZから決めるため、送信される暦日は常に "2026-08-04" になる。
 *
 * ## 実行前提
 *   - BE / FE / MySQL が起動済み・`backend/scripts/seed-e2e-data.js` 実行済み
 *   - `BASE_URL` / `API_BASE_URL` を実行時に指定する
 */

import { test, expect, type Page, type APIRequestContext, type Locator } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const BASE_URL = process.env.BASE_URL ?? 'http://127.0.0.1:3005'
const API_BASE = process.env.API_BASE_URL ?? 'http://127.0.0.1:8085'

const E2E_ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }

const TEAM_SLUG = 'fc-u-18'

const JST_TZ = 'Asia/Tokyo'
const LA_TZ = 'America/Los_Angeles'

// ---------------------------------------------------------------------------
// 汎用ヘルパー（datetime-offset-2508.spec.ts の実績パターンを踏襲）
// ---------------------------------------------------------------------------

/** FE(3005) 起源のブラウザ XHR を Node fetch で BE へ中継し CORS を通す。 */
async function setupApiBridge(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()
    if (req.method() === 'OPTIONS') {
      await route.fulfill({
        status: 204,
        headers: {
          'access-control-allow-origin': BASE_URL,
          'access-control-allow-credentials': 'true',
          'access-control-allow-methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
          'access-control-allow-headers':
            req.headers()['access-control-request-headers'] ?? 'authorization,content-type',
        },
      })
      return
    }
    const url = req.url().replace(/^https?:\/\/[^/]+/, API_BASE)
    const headers: Record<string, string> = {}
    for (const [k, v] of Object.entries(req.headers())) {
      const lk = k.toLowerCase()
      if (lk === 'origin' || lk === 'referer' || lk === 'host') continue
      headers[k] = v
    }
    try {
      const bodyText = req.postData()
      const fetchRes = await fetch(url, { method: req.method(), headers, body: bodyText ?? undefined })
      const resBody = await fetchRes.arrayBuffer()
      const resHeaders: Record<string, string> = {}
      fetchRes.headers.forEach((v, k) => {
        const lk = k.toLowerCase()
        if (lk === 'access-control-allow-origin' || lk === 'access-control-allow-credentials') return
        if (lk === 'content-encoding' || lk === 'content-length' || lk === 'transfer-encoding') return
        resHeaders[k] = v
      })
      resHeaders['access-control-allow-origin'] = BASE_URL
      resHeaders['access-control-allow-credentials'] = 'true'
      await route.fulfill({ status: fetchRes.status, headers: resHeaders, body: Buffer.from(resBody) })
    } catch {
      await route.abort()
    }
  })
}

async function loginToken(ctx: APIRequestContext, email: string, password: string): Promise<string> {
  const res = await ctx.post(`${API_BASE}/api/v1/auth/login`, { data: { email, password } })
  expect(res.ok(), `login ${email}: ${res.status()}`).toBeTruthy()
  return (await res.json()).data.accessToken as string
}

async function api(
  ctx: APIRequestContext,
  token: string,
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
) {
  const opt: { headers: Record<string, string>; data?: unknown } = {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
  }
  if (body !== undefined) opt.data = body
  return ctx.fetch(`${API_BASE}${path}`, { method, ...opt })
}

/**
 * BE へ直接ログインし、Cookie + Bearer + localStorage.currentUser（timezone 明示）を整える。
 * ⚠️ FE の useDatetime().userTimezone は authStore.user?.timezone（localStorage）を読むため、
 *    DB 側の users.timezone も揃えないと、リロードを挟む経路（一覧の formatDate 等）で
 *    localStorage と DB がズレて偽陰性/偽陽性を生む。両方を必ず設定する。
 */
async function loginAndSetTimezone(
  page: Page,
  ctx: APIRequestContext,
  token: string,
  timezone: string,
): Promise<{ userId: number }> {
  // DB 側（PUT /users/me）
  const putRes = await api(ctx, token, 'PUT', '/api/v1/users/me', { timezone })
  expect(putRes.ok(), `users/me timezone更新失敗: ${putRes.status()} ${await putRes.text()}`).toBeTruthy()

  const meRes = await api(ctx, token, 'GET', '/api/v1/users/me')
  expect(meRes.ok()).toBeTruthy()
  const me = (await meRes.json()).data as {
    id: number
    email: string
    lastName: string
    firstName: string
    avatarUrl: string | null
    systemRole: string | null
  }

  await page.setExtraHTTPHeaders({ Authorization: `Bearer ${token}` })
  await page.goto(`${BASE_URL}/`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(
    (user) => localStorage.setItem('currentUser', JSON.stringify(user)),
    {
      id: me.id,
      email: me.email,
      fullName: `${me.lastName} ${me.firstName}`,
      profileImageUrl: me.avatarUrl,
      systemRole: me.systemRole ?? undefined,
      timezone,
    },
  )
  return { userId: me.id }
}

async function goto(page: Page, path: string): Promise<void> {
  await page.goto(`${BASE_URL}${path}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  // eslint-disable-next-line no-restricted-syntax -- スピナー0件観測は「読み込み済み」を意味するため無視
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
  await expect(page).not.toHaveURL(/\/login/)
}

/** date-only（show-time 無し）PrimeVue DatePicker への手入力。 */
async function typeIntoDatePicker(root: Locator, text: string): Promise<void> {
  const input = root.locator('input').first()
  await input.click()
  await input.press('ControlOrMeta+a')
  await input.pressSequentially(text, { delay: 15 })
  await input.press('Escape')
}

/** ラベル文字列から DigestGenerateDialog の DatePicker ルートを引く。 */
function datePickerByLabel(page: Page, dialog: Locator, labelText: string): Locator {
  return dialog
    .locator('div.flex.flex-col.gap-1')
    .filter({ has: page.locator('label', { hasText: labelText }) })
    .first()
}

/** `yyyy-MM-dd` を `yy/mm/dd`（DigestGenerateDialog の date-format）へ変換する。 */
function toSlashYmd(date: Date): string {
  const y = String(date.getFullYear())
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}/${m}/${d}`
}

/** PrimeVue Select（ドロップダウン）でオプションを選ぶ。`.p-select-option` を使うこと（`.p-select-list li` はロード中の「該当なし」にも一致する）。 */
async function selectPrimeVueOption(page: Page, root: Locator, optionText: string): Promise<void> {
  await root.click()
  const option = page.locator('.p-select-option').filter({ hasText: optionText }).first()
  await expect(option, `Select オプション「${optionText}」が見えること`).toBeVisible({ timeout: 10_000 })
  await option.click()
}

// ===========================================================================
// 軸1【最重要】ピッカーのTZ 3パターン（ダイジェスト生成期間 = buildDigestPeriod 経由）
//
// DigestGenerateDialog の期間ピッカーは date-only（show-time 無し）のため typeIntoDatePicker が効く。
// buildDigestPeriod → buildOffsetDateTimeStr(date, '00:00').slice(0,10) → buildDayStartStr/buildDayEndStr
// という経路で #2626 の修正がそのまま検証できる。
// ===========================================================================

async function openDigestDialog(page: Page): Promise<Locator> {
  await goto(page, `/teams/${TEAM_SLUG}/timeline-digest`)
  await page.getByRole('button', { name: '生成' }).click()
  const dialog = page.getByRole('dialog').filter({ hasText: 'ダイジェスト生成' })
  await expect(dialog).toBeVisible({ timeout: 10_000 })
  return dialog
}

/** ダイジェスト生成フォームに日付を入力して送信し、生成 POST の req/res を返す。 */
async function submitDigestGenerate(
  page: Page,
  dialog: Locator,
  periodStart: Date,
  periodEnd: Date,
): Promise<{ sentStart: string; sentEnd: string; status: number; digestId: number | null }> {
  const startPicker = datePickerByLabel(page, dialog, '期間開始')
  const endPicker = datePickerByLabel(page, dialog, '期間終了')
  await typeIntoDatePicker(startPicker, toSlashYmd(periodStart))
  await typeIntoDatePicker(endPicker, toSlashYmd(periodEnd))

  const [req, res] = await Promise.all([
    page.waitForRequest((r) => r.url().includes('/timeline-digest/generate') && r.method() === 'POST', { timeout: 20_000 }),
    page.waitForResponse((r) => r.url().includes('/timeline-digest/generate') && r.request().method() === 'POST', { timeout: 20_000 }),
    dialog.getByRole('button', { name: '生成', exact: true }).click(),
  ])

  const sent = JSON.parse(req.postData() ?? '{}') as { periodStart?: string; periodEnd?: string }
  expect(sent.periodStart, 'periodStart が送られていること').toBeTruthy()
  expect(sent.periodEnd, 'periodEnd が送られていること').toBeTruthy()

  let digestId: number | null = null
  if (res.status() === 201 || res.status() === 200) {
    try {
      digestId = ((await res.json()).data as { id?: number })?.id ?? null
    } catch { /* 本文がない/JSONでない場合は無視 */ }
  }

  return { sentStart: sent.periodStart!, sentEnd: sent.periodEnd!, status: res.status(), digestId }
}

test.describe('P2-01 ピッカー回帰なし（ブラウザ=プロフィール=JST）', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: JST_TZ })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let adminToken = ''
  const createdDigestIds: number[] = []

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    adminToken = await loginToken(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
    // 元の timezone（Asia/Tokyo）に戻す（他 describe の後始末と独立に、常に JST で開始する）
    await api(ctx, adminToken, 'PUT', '/api/v1/users/me', { timezone: JST_TZ })
  })

  test.afterAll(async () => {
    for (const id of createdDigestIds) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, adminToken, 'DELETE', `/api/v1/timeline-digest/${id}`).catch(() => {})
    }
    await ctx.dispose()
  })

  test('P2-01: JST/JST では選んだ日がそのまま送られる（回帰なし）', async ({ page }) => {
    await loginAndSetTimezone(page, ctx, adminToken, JST_TZ)
    await setupApiBridge(page)
    const dialog = await openDigestDialog(page)

    const target = new Date(2026, 7, 4) // 2026-08-04（ローカル=JST）
    const { sentStart, status, digestId } = await submitDigestGenerate(page, dialog, target, target)

    expect(sentStart.slice(0, 10), 'JST/JST では暦日がそのまま送られること').toBe('2026-08-04')
    expect(status, `ダイジェスト生成（軸2/P2-07 相当）が失敗した: ${status}`).toBe(201)
    if (digestId) createdDigestIds.push(digestId)

    // 軸3: 一覧に生成結果が反映され、期間表示が入力値と一致すること（往復）
    await expect(
      page.locator('td').filter({ hasText: '2026/08/04 〜 2026/08/04' }).first(),
      '一覧の期間表示が入力した暦日と一致すること',
    ).toBeVisible({ timeout: 15_000 })
  })

  test('P2-08 境界: 月初00:00:00/月末23:59:59が取りこぼされない', async ({ page }) => {
    await loginAndSetTimezone(page, ctx, adminToken, JST_TZ)
    await setupApiBridge(page)
    const dialog = await openDigestDialog(page)

    const monthStart = new Date(2026, 8, 1) // 2026-09-01
    const monthEnd = new Date(2026, 8, 30) // 2026-09-30
    const { sentStart, sentEnd, status, digestId } = await submitDigestGenerate(page, dialog, monthStart, monthEnd)

    expect(sentStart, '月初は 00:00:00 であること').toMatch(/^2026-09-01T00:00:00/)
    expect(sentEnd, '月末は 23:59:59（両端inclusive）であること').toMatch(/^2026-09-30T23:59:59/)
    expect(status).toBe(201)
    if (digestId) createdDigestIds.push(digestId)
  })
})

test.describe('P2-02【本命】ブラウザJST・プロフィールLAで選んだ日がずれない', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: JST_TZ })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let adminToken = ''
  const createdDigestIds: number[] = []

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    adminToken = await loginToken(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
  })

  test.afterAll(async () => {
    for (const id of createdDigestIds) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, adminToken, 'DELETE', `/api/v1/timeline-digest/${id}`).catch(() => {})
    }
    // ⚠️ 必ず JST へ戻す（他 spec / 本セッションの継続利用に影響するため）
    await api(ctx, adminToken, 'PUT', '/api/v1/users/me', { timezone: JST_TZ })
    await ctx.dispose()
  })

  test('P2-02: 選んだ8/4がそのまま送られる（修正前は8/3にずれていた）', async ({ page }) => {
    await loginAndSetTimezone(page, ctx, adminToken, LA_TZ)
    await setupApiBridge(page)
    const dialog = await openDigestDialog(page)

    // ブラウザは JST（test.use）。カレンダーは JST の壁時計で 8/4 を描画する。
    const target = new Date(2026, 7, 4)
    const { sentStart, status, digestId } = await submitDigestGenerate(page, dialog, target, target)

    // 修正前（dayjs(date).tz(LA) で瞬間投影）なら:
    //   2026-08-04T00:00:00+09:00（瞬間） = 2026-08-03T08:00:00-07:00（LA壁時計）→ "2026-08-03..." が送られていた。
    // 修正後（ブラウザ壁時計成分をそのまま暦日として使う）は "2026-08-04..." が送られる。
    expect(
      sentStart.slice(0, 10),
      `選んだ8/4がそのまま送られること（修正前なら "2026-08-03" になっていた）: 実測=${sentStart}`,
    ).toBe('2026-08-04')
    // LA の8月はPDT（-07:00）
    expect(sentStart, 'LAプロフィールのオフセットが付与されること').toMatch(/-07:00$/)
    expect(status, 'ダイジェスト生成が400にならないこと').toBe(201)
    if (digestId) createdDigestIds.push(digestId)

    // 軸3: 一覧の期間表示（useDatetime().formatDate はプロフィールTZ=LA基準）も入力値と一致すること
    await expect(
      page.locator('td').filter({ hasText: '2026/08/04 〜 2026/08/04' }).first(),
      '一覧の期間表示（LA基準）が入力した8/4のまま一致すること（1日ずれ再発なし）',
    ).toBeVisible({ timeout: 15_000 })
  })
})

test.describe('P2-03 ピッカー一致（ブラウザ=プロフィール=LA）+ DST境界', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: LA_TZ })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let adminToken = ''
  const createdDigestIds: number[] = []

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    adminToken = await loginToken(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
  })

  test.afterAll(async () => {
    for (const id of createdDigestIds) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, adminToken, 'DELETE', `/api/v1/timeline-digest/${id}`).catch(() => {})
    }
    // ⚠️ 必ず JST へ戻す
    await api(ctx, adminToken, 'PUT', '/api/v1/users/me', { timezone: JST_TZ })
    await ctx.dispose()
  })

  test('P2-03: ブラウザ=プロフィール=LA が一致するとき正常動作する', async ({ page }) => {
    await loginAndSetTimezone(page, ctx, adminToken, LA_TZ)
    await setupApiBridge(page)
    const dialog = await openDigestDialog(page)

    const target = new Date(2026, 7, 4)
    const { sentStart, status, digestId } = await submitDigestGenerate(page, dialog, target, target)

    expect(sentStart.slice(0, 10), 'LA/LA一致時も選んだ日がそのまま送られること').toBe('2026-08-04')
    expect(status).toBe(201)
    if (digestId) createdDigestIds.push(digestId)
  })

  test('P2-09 境界: 夏時間切替日(2026/3/8)をまたぐ範囲でもオフセットが正しく遷移する', async ({ page }) => {
    await loginAndSetTimezone(page, ctx, adminToken, LA_TZ)
    await setupApiBridge(page)
    const dialog = await openDigestDialog(page)

    // US DST 2026: 3/8(日) 2:00 に PST(-08:00) → PDT(-07:00)へ切替
    const before = new Date(2026, 2, 7) // 2026-03-07（PST）
    const after = new Date(2026, 2, 9) // 2026-03-09（PDT）
    const { sentStart, sentEnd, status, digestId } = await submitDigestGenerate(page, dialog, before, after)

    expect(sentStart, '切替前日はPST(-08:00)であること').toMatch(/^2026-03-07T00:00:00-08:00$/)
    expect(sentEnd, '切替後日はPDT(-07:00)であること').toMatch(/^2026-03-09T23:59:59-07:00$/)
    expect(status, 'DST境界をまたいでも生成が失敗しないこと（#2612 の500根治を実測）').toBe(201)
    if (digestId) createdDigestIds.push(digestId)
  })
})

// ===========================================================================
// 軸2/軸3: アンケート作成（distributionMode欠落・expiresAt欠落の根治）
// ===========================================================================
test.describe('P2-04 アンケート作成: 締切保存+distributionMode', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: JST_TZ })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let adminToken = ''
  let createdSurveyId: number | null = null

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    adminToken = await loginToken(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
    await api(ctx, adminToken, 'PUT', '/api/v1/users/me', { timezone: JST_TZ })
  })

  test.afterAll(async () => {
    if (createdSurveyId) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, adminToken, 'DELETE', `/api/v1/teams/${TEAM_SLUG}/surveys/${createdSurveyId}`).catch(() => {})
    }
    await ctx.dispose()
  })

  test('P2-04: 締切ありでアンケートを作成すると201でexpiresAtが保存・往復する', async ({ page }) => {
    await loginAndSetTimezone(page, ctx, adminToken, JST_TZ)
    await setupApiBridge(page)
    await goto(page, `/teams/${TEAM_SLUG}/surveys`)

    await page.getByTestId('survey-create-button').click()
    const dialog = page.getByTestId('survey-create-dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    const title = `#2508P2 アンケート ${Date.now()}`
    await page.getByTestId('survey-create-title').fill(title)

    // 締切: show-time 付き DatePicker のためカレンダーパネル操作で選ぶ
    const target = new Date(Date.now() + 14 * 24 * 3600_000)
    target.setHours(15, 0, 0, 0)
    await pickDeadlineInPanel(page, page.getByTestId('survey-create-deadline'), target)

    const [req, res] = await Promise.all([
      page.waitForRequest((r) => r.url().includes('/surveys') && r.method() === 'POST', { timeout: 20_000 }),
      page.waitForResponse((r) => r.url().includes('/surveys') && r.request().method() === 'POST', { timeout: 20_000 }),
      page.getByTestId('survey-create-save-draft').click(),
    ])

    const sentBody = JSON.parse(req.postData() ?? '{}') as {
      expiresAt?: string
      distributionMode?: string
      deadline?: string
    }
    expect(sentBody.deadline, 'BEに存在しないdeadlineキーは送られないこと').toBeUndefined()
    expect(sentBody.expiresAt, 'expiresAtが送られていること（修正前は締切が黙って捨てられていた）').toBeTruthy()
    expect(sentBody.distributionMode, 'BE必須のdistributionModeが送られていること').toBe('ALL')

    expect(
      res.status(),
      `アンケート作成が失敗した（修正前はdistributionMode欠落で400だった）: ${await res.text()}`,
    ).toBe(201)
    const created = (await res.json()).data as { id: number }
    createdSurveyId = created.id

    // 軸3: 一覧に反映（round trip 1）
    await expect(
      page.getByTestId(`survey-item-${created.id}`).filter({ hasText: title }),
      '作成したアンケートが一覧に出ること',
    ).toBeVisible({ timeout: 15_000 })

    // 軸3: 詳細画面で締切が保存された値のまま表示される（round trip 2）
    await page.getByTestId(`survey-item-edit-draft-${created.id}`).click()
    await expect(page).toHaveURL(new RegExp(`/surveys/${created.id}`))
    const y = target.getFullYear()
    const m = String(target.getMonth() + 1).padStart(2, '0')
    const d = String(target.getDate()).padStart(2, '0')
    await expect(
      page.getByText(new RegExp(`${y}-${m}-${d}`)),
      '詳細画面の締切表示に入力日が含まれること',
    ).toBeVisible({ timeout: 15_000 })
  })
})

// ===========================================================================
// 軸2/軸3: アンケート設問追加（questionType/DisplayOrder不整合の根治）
// ===========================================================================
test.describe('P2-05 アンケート設問追加: enum翻訳+displayOrder', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: JST_TZ })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let adminToken = ''
  let draftSurveyId: number | null = null

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    adminToken = await loginToken(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
    // DRAFT の下書きアンケートをAPIで用意（設問追加UIの検証が本題のため、前提はAPIで作る）
    const res = await api(ctx, adminToken, 'POST', `/api/v1/teams/${TEAM_SLUG}/surveys`, {
      title: `#2508P2 設問追加下書き ${Date.now()}`,
      isAnonymous: false,
      allowMultipleSubmissions: false,
      distributionMode: 'ALL',
      questions: [],
    })
    expect(res.ok(), `下書きアンケート作成に失敗: ${res.status()}`).toBeTruthy()
    draftSurveyId = ((await res.json()).data as { id: number }).id
  })

  test.afterAll(async () => {
    if (draftSurveyId) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, adminToken, 'DELETE', `/api/v1/teams/${TEAM_SLUG}/surveys/${draftSurveyId}`).catch(() => {})
    }
    await ctx.dispose()
  })

  test('P2-05: TEXT型設問がFREE_TEXTへ翻訳され、displayOrderが0に潰れない', async ({ page }) => {
    test.skip(draftSurveyId === null, '前提の下書きアンケート作成に失敗したためスキップ')
    await loginAndSetTimezone(page, ctx, adminToken, JST_TZ)
    await setupApiBridge(page)
    await goto(page, `/surveys/${draftSurveyId}?scope=team&scopeId=${TEAM_SLUG}`)

    const editor = page.getByTestId('survey-question-editor')
    await expect(editor).toBeVisible({ timeout: 15_000 })

    // 設問1: TEXT型（BE enum FREE_TEXT への翻訳を検証）
    await page.getByTestId('question-add').click()
    await page.getByTestId('question-text-0').fill('#2508P2 設問1（TEXT）')
    await selectPrimeVueOption(page, page.getByTestId('question-type-0'), '自由記述')

    // 設問2: SINGLE_CHOICE（既定型のまま）。displayOrderが2件とも0にならないことの確認用。
    await page.getByTestId('question-add').click()
    await page.getByTestId('question-text-1').fill('#2508P2 設問2（単一選択）')
    await page.getByTestId('question-option-1-0').fill('選択肢A')
    await page.getByTestId('question-option-1-1').fill('選択肢B')

    const requests: Array<{ questionText?: string; questionType?: string; displayOrder?: number; sortOrder?: number }> = []
    page.on('request', (r) => {
      if (r.url().includes('/questions') && r.method() === 'POST') {
        try {
          requests.push(JSON.parse(r.postData() ?? '{}'))
        } catch { /* ignore */ }
      }
    })

    const [, res] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/questions') && r.request().method() === 'POST', { timeout: 20_000 }),
      page.waitForResponse((r) => r.url().includes('/publish') && r.request().method() === 'POST', { timeout: 20_000 }),
      page.getByTestId('survey-publish-with-questions-button').click(),
    ])
    void res

    expect(requests.length, '設問追加POSTが2件走ること').toBe(2)
    const q1 = requests.find((r) => r.questionText?.includes('設問1'))
    const q2 = requests.find((r) => r.questionText?.includes('設問2'))
    expect(q1?.sortOrder, 'FEのsortOrderキーはBEへ送らないこと（displayOrderへ翻訳済み）').toBeUndefined()
    expect(q1?.questionType, 'TEXT型はBE enum FREE_TEXTへ翻訳されること').toBe('FREE_TEXT')
    expect(q2?.questionType, 'SINGLE_CHOICEは恒等変換されること').toBe('SINGLE_CHOICE')
    expect(
      [q1?.displayOrder, q2?.displayOrder].sort(),
      'displayOrderが2件とも0に潰れず区別できること（修正前の残存事故）',
    ).toEqual([1, 2])

    // 軸3: 公開後の詳細画面に2設問とも表示される（round trip）
    await expect(page.getByText('#2508P2 設問1（TEXT）')).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText('#2508P2 設問2（単一選択）')).toBeVisible({ timeout: 15_000 })
  })
})

// ===========================================================================
// 軸2: ブログ「今すぐ公開」（予約公開の導線撤去を含む）
// ===========================================================================
test.describe('P2-06 ブログ「今すぐ公開」', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: JST_TZ })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let adminToken = ''
  let postId: number | null = null

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    adminToken = await loginToken(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
    const res = await api(ctx, adminToken, 'POST', '/api/v1/users/me/blog/posts', {
      title: `#2508P2 公開テスト記事 ${Date.now()}`,
      body: '本文（実機E2E）',
      status: 'DRAFT',
      scopeType: 'PERSONAL',
      scopeId: null,
    })
    expect(res.ok(), `下書き記事作成に失敗: ${res.status()}`).toBeTruthy()
    postId = ((await res.json()).data as { id: number }).id
  })

  test.afterAll(async () => {
    if (postId) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, adminToken, 'DELETE', `/api/v1/users/me/blog/posts/${postId}`).catch(() => {})
    }
    await ctx.dispose()
  })

  test('P2-06: 「今すぐ公開」が200で公開され、予約公開の導線は存在しない', async ({ page }) => {
    test.skip(postId === null, '前提の下書き記事作成に失敗したためスキップ')
    await loginAndSetTimezone(page, ctx, adminToken, JST_TZ)
    await setupApiBridge(page)
    await goto(page, `/blog/posts/${postId}/edit`)

    // 予約公開の導線が撤去されていること（検分指摘で撤去済み）
    await expect(
      page.getByText('予約公開'),
      '予約公開UIが存在しないこと（即時公開になる事故を防ぐため撤去済み）',
    ).toHaveCount(0)

    const [req, res] = await Promise.all([
      page.waitForRequest((r) => r.url().includes(`/blog/posts/${postId}/publish`) && r.method() === 'PATCH', { timeout: 20_000 }),
      page.waitForResponse((r) => r.url().includes(`/blog/posts/${postId}/publish`) && r.request().method() === 'PATCH', { timeout: 20_000 }),
      page.getByRole('button', { name: '今すぐ公開' }).click(),
    ])

    const sent = JSON.parse(req.postData() ?? '{}') as { status?: string; published_at?: string }
    expect(sent.status, 'statusがBE必須のためボディに含まれること').toBe('PUBLISHED')
    expect(sent.published_at, 'snake_caseキーは送らないこと（修正前の事故）').toBeUndefined()
    expect(res.status(), `公開が失敗した（修正前はボディ無しで400だった）: ${await res.text()}`).toBe(200)

    // 軸3: 一覧（/blog）で公開済みとして往復すること
    await goto(page, '/blog')
    const getRes = await api(ctx, adminToken, 'GET', `/api/v1/users/me/blog/posts/${postId}`)
    expect(getRes.ok()).toBeTruthy()
    const status = ((await getRes.json()).data as { meta?: { status?: string } }).meta?.status
    expect(status, 'DB上のstatusがPUBLISHEDへ往復していること').toBe('PUBLISHED')
  })
})

/**
 * `show-time` 付き PrimeVue DatePicker をカレンダーパネル操作で日付＋時刻選択する
 * （手入力は populateTime のバグで v-model が更新されない。既存 datetime-offset-2508.spec.ts と同一の作法）。
 */
async function pickDeadlineInPanel(page: Page, root: Locator, target: Date): Promise<void> {
  const input = root.locator('input').first()
  await input.click()
  const panel = page.locator('.p-datepicker-panel').last()
  await expect(panel, 'カレンダーパネルが開くこと').toBeVisible({ timeout: 10_000 })

  const targetYear = target.getFullYear()
  const targetMonth = target.getMonth() + 1
  const targetIndex = targetYear * 12 + targetMonth
  let reached = false
  for (let i = 0; i < 200; i++) {
    const yearText = await panel.locator('.p-datepicker-select-year').first().innerText()
    const monthText = await panel.locator('.p-datepicker-select-month').first().innerText()
    const year = Number(yearText.replace(/\D/g, ''))
    const month = Number(monthText.replace(/\D/g, ''))
    const currentIndex = year * 12 + month
    if (currentIndex === targetIndex) { reached = true; break }
    const button = currentIndex < targetIndex ? '.p-datepicker-next-button' : '.p-datepicker-prev-button'
    await panel.locator(button).first().click()
  }
  expect(reached, `カレンダーを ${targetYear}年${targetMonth}月 まで送れること`).toBe(true)

  const dayCell = panel
    .locator('td.p-datepicker-day-cell:not(.p-datepicker-other-month)')
    .filter({ hasText: new RegExp(`^${target.getDate()}$`) })
    .first()
  await expect(dayCell).toBeVisible({ timeout: 10_000 })
  await dayCell.click()

  const hourPicker = panel.locator('.p-datepicker-hour-picker').first()
  const minutePicker = panel.locator('.p-datepicker-minute-picker').first()
  await spinTo(hourPicker, target.getHours(), 24)
  await spinTo(minutePicker, target.getMinutes(), 60)

  await input.press('Escape')
  await expect(input, '日付選択がv-modelに反映されていること').not.toHaveValue('', { timeout: 10_000 })
}

async function spinTo(picker: Locator, goal: number, modulo: number): Promise<void> {
  const readout = picker.locator('span').filter({ hasText: /^\d{2}$/ }).first()
  const current = Number((await readout.innerText()).trim())
  const clicks = ((goal - current) % modulo + modulo) % modulo
  const increment = picker.locator('.p-datepicker-increment-button').first()
  for (let i = 0; i < clicks; i++) await increment.click()
  await expect(readout).toHaveText(String(goal).padStart(2, '0'))
}
