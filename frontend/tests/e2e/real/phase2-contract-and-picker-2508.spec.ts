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
 *   2027-08-04T00:00:00+09:00（瞬間） = 2027-08-03T08:00:00-07:00（LA 壁時計）
 * となり、**送信される暦日が "2027-08-03" にずれていた**。
 * 修正後は `Date` のブラウザ壁時計成分（getFullYear/getMonth/getDate）をそのまま暦日として使い、
 * オフセットだけプロフィールTZから決めるため、送信される暦日は常に "2027-08-04" になる。
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

/** PrimeVue Select（ドロップダウン）でオプションを選ぶ。`.p-select-option` を使うこと（`.p-select-list li` はロード中の「該当なし」にも一致する）。 */
async function selectPrimeVueOption(page: Page, root: Locator, optionText: string): Promise<void> {
  await root.click()
  const option = page.locator('.p-select-option').filter({ hasText: optionText }).first()
  await expect(option, `Select オプション「${optionText}」が見えること`).toBeVisible({ timeout: 10_000 })
  await option.click()
}

// ===========================================================================
// 軸1【最重要】ピッカーのTZ 3パターン（村の祭り FestivalCreateRequest.startsAt/endsAt 経由）
//
// ⚠️ 当初は DigestGenerateDialog を使う設計だったが実機で `timeline_digest` i18n 名前空間が
//    キー生文字列のまま描画される（本 PR の範囲外の別バグ・後述の「発見した既存不具合」参照）ことが
//    判明したため、既存 spec（datetime-offset-2508.spec.ts）で実績のある「村の祭り」経路
//    （<input type="datetime-local"> のためロケール崩れの影響を受けず fill() が確実に効く）へ差し替えた。
// ===========================================================================

const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
/** e2e-user が HEADMAN を務める seed 村（datetime-offset-2508.spec.ts と同一）。 */
const VILLAGE_ID = '6e87b493-512a-11f1-95e3-2ec96fe3ea06'

const FESTIVAL_TXT = {
  festivalCreate: 'お祭りを企画',
  festivalSave: '保存',
  festivalSaveSuccess: 'お祭りを保存しました',
  festivalFilterAll: 'すべて',
} as const

/** 瞬間（Date）を指定 IANA タイムゾーンの壁時計 `YYYY-MM-DD HH:mm:ss` に整形する。 */
function wallClockIn(instant: Date, timeZone: string): string {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(instant)
}

interface FestivalCreateOutcome {
  sent: { startsAt?: string; endsAt?: string }
  status: number
  bodyText: string
  festivalId: string | null
}

/** 「お祭りを企画」ダイアログを実UIで操作して祭を作成し、送信内容とレスポンスを返す。 */
async function createFestivalViaUi(
  page: Page,
  title: string,
  startsWall: string,
  endsWall: string,
): Promise<FestivalCreateOutcome> {
  const createBtn = page.getByRole('button', { name: FESTIVAL_TXT.festivalCreate }).first()
  await expect(createBtn, '村ADMIN（HEADMAN/ELDER）なら「お祭りを企画」ボタンが出る').toBeVisible({ timeout: 25_000 })
  await createBtn.click()

  const dialog = page.getByRole('dialog').filter({ hasText: FESTIVAL_TXT.festivalCreate }).first()
  await expect(dialog).toBeVisible({ timeout: 10_000 })

  await dialog.locator('input.p-inputtext:not([type="datetime-local"]):not([type="color"])').first().fill(title)

  const dtInputs = dialog.locator('input[type="datetime-local"]')
  await expect(dtInputs, '開始・終了の datetime-local が 2 つあること').toHaveCount(2)
  await dtInputs.nth(0).fill(startsWall)
  await dtInputs.nth(1).fill(endsWall)

  const isFestivalPost = (url: string, method: string) =>
    method === 'POST' && new URL(url).pathname.endsWith(`/villages/${VILLAGE_ID}/festivals`)

  const [req, res] = await Promise.all([
    page.waitForRequest((r) => isFestivalPost(r.url(), r.method()), { timeout: 20_000 }),
    page.waitForResponse((r) => isFestivalPost(r.url(), r.request().method()), { timeout: 20_000 }),
    dialog.getByRole('button', { name: FESTIVAL_TXT.festivalSave }).click(),
  ])

  const sent = JSON.parse(req.postData() ?? '{}') as { startsAt?: string; endsAt?: string }
  expect(sent.startsAt, 'startsAt が送られていること').toBeTruthy()
  expect(sent.endsAt, 'endsAt が送られていること').toBeTruthy()

  const status = res.status()
  const bodyText = await res.text().catch(() => '(body read failed)')
  let festivalId: string | null = null
  if (status === 201) {
    try { festivalId = (JSON.parse(bodyText).data as { id: string }).id } catch { festivalId = null }
  }
  return { sent, status, bodyText, festivalId }
}

/** 作成した祭のカードを一覧から見つける（作成直後は SCHEDULED なので「すべて」タブに切り替える）。 */
async function findFestivalCard(page: Page, title: string): Promise<Locator> {
  await expect(page.getByText(FESTIVAL_TXT.festivalSaveSuccess)).toBeVisible({ timeout: 10_000 })
  await page.getByRole('button', { name: FESTIVAL_TXT.festivalFilterAll }).first().click()
  // eslint-disable-next-line no-restricted-syntax -- スピナー0件観測は「読み込み済み」を意味するため無視
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  const card = page.locator('.village-festival__card', { hasText: title }).first()
  await expect(card, `作成した祭「${title}」のカードが一覧に出ること`).toBeVisible({ timeout: 20_000 })
  return card
}

/** 祭カードのテキストから開始・終了の生 ISO 文字列を取り出す（整形されず生値のまま描画される）。 */
function parseCardPeriod(cardText: string): [string, string] {
  const matches = cardText.match(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:[+-]\d{2}:\d{2}|Z)?/g)
  expect(matches?.length, `祭カードに ISO 日時が 2 つ含まれること（実際: ${cardText}）`).toBeGreaterThanOrEqual(2)
  return [matches![0]!, matches![1]!]
}

async function restoreUserTimezone(ctx: APIRequestContext, token: string): Promise<void> {
  await api(ctx, token, 'PUT', '/api/v1/users/me', { timezone: JST_TZ })
}

test.describe('P2-01 ピッカー回帰なし（ブラウザ=プロフィール=JST）', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: JST_TZ })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let userToken = ''
  const createdFestivalIds: string[] = []

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    userToken = await loginToken(ctx, E2E_USER.email, E2E_USER.password)
    await restoreUserTimezone(ctx, userToken)
  })

  test.afterAll(async () => {
    for (const id of createdFestivalIds) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, userToken, 'POST', `/api/v1/villages/${VILLAGE_ID}/festivals/${id}/cancel`).catch(() => {})
    }
    await ctx.dispose()
  })

  test('P2-01: JST/JST では選んだ壁時計がそのまま送られる（回帰なし）', async ({ page }) => {
    await loginAndSetTimezone(page, ctx, userToken, JST_TZ)
    await setupApiBridge(page)
    await goto(page, `/villages/${VILLAGE_ID}/festivals`)

    const title = `#2508P2祭JST-${Date.now()}`
    const result = await createFestivalViaUi(page, title, '2027-08-04T10:00', '2027-08-04T18:00')

    expect(wallClockIn(new Date(result.sent.startsAt!), JST_TZ)).toBe('2027-08-04 10:00:00')
    expect(result.status, `祭の作成が失敗した: ${result.bodyText}`).toBe(201)
    if (result.festivalId) createdFestivalIds.push(result.festivalId)

    const card = await findFestivalCard(page, title)
    const [returnedStart] = parseCardPeriod((await card.innerText()).trim())
    expect(wallClockIn(new Date(returnedStart), JST_TZ), '往復しても入力どおりであること').toBe('2027-08-04 10:00:00')
  })

  test('P2-08 境界: 月初00:00:00/月末23:59:59が取りこぼされない', async ({ page }) => {
    await loginAndSetTimezone(page, ctx, userToken, JST_TZ)
    await setupApiBridge(page)
    await goto(page, `/villages/${VILLAGE_ID}/festivals`)

    const title = `#2508P2境界-${Date.now()}`
    const result = await createFestivalViaUi(page, title, '2027-09-01T00:00', '2027-09-30T23:59')

    expect(wallClockIn(new Date(result.sent.startsAt!), JST_TZ), '月初00:00:00が保持されること').toBe('2027-09-01 00:00:00')
    expect(wallClockIn(new Date(result.sent.endsAt!), JST_TZ), '月末23:59:00が保持されること').toBe('2027-09-30 23:59:00')
    expect(result.status).toBe(201)
    if (result.festivalId) createdFestivalIds.push(result.festivalId)
  })
})

test.describe('P2-02【本命】ブラウザJST・プロフィールLAで選んだ日がずれない', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: JST_TZ })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let userToken = ''
  const createdFestivalIds: string[] = []

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    userToken = await loginToken(ctx, E2E_USER.email, E2E_USER.password)
  })

  test.afterAll(async () => {
    for (const id of createdFestivalIds) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, userToken, 'POST', `/api/v1/villages/${VILLAGE_ID}/festivals/${id}/cancel`).catch(() => {})
    }
    // ⚠️ 必ず JST へ戻す（他 spec / 本セッションの継続利用に影響するため）
    await restoreUserTimezone(ctx, userToken)
    await ctx.dispose()
  })

  test('P2-02: 選んだ8/4 10:00がそのまま送られる（修正前は瞬間投影でずれていた）', async ({ page }) => {
    await loginAndSetTimezone(page, ctx, userToken, LA_TZ)
    await setupApiBridge(page)
    await goto(page, `/villages/${VILLAGE_ID}/festivals`)

    // ブラウザは JST（test.use）。datetime-local へ入れた "2027-08-04T10:00" は
    // ブラウザ壁時計としてパースされ、new Date(...) の getFullYear/getMonth/getDate は常に 2027-08-04 になる。
    const title = `#2508P2祭LA-${Date.now()}`
    const result = await createFestivalViaUi(page, title, '2027-08-04T10:00', '2027-08-04T18:00')

    // 修正後: ブラウザ壁時計成分（2027-08-04 10:00）をそのままプロフィールTZ(LA)のオフセットで送る。
    expect(
      wallClockIn(new Date(result.sent.startsAt!), LA_TZ),
      `LAプロフィールのオフセットを付けて2027-08-04 10:00のまま送られること（実測startsAt=${result.sent.startsAt}）`,
    ).toBe('2027-08-04 10:00:00')
    expect(result.sent.startsAt, 'LAの8月はPDT(-07:00)であること').toMatch(/-07:00$/)

    // 修正前（dayjs(date).tz(LA)で瞬間投影）だった場合の値をここで明示的に反証する:
    //   ブラウザJSTで生成されたDate（2027-08-04 10:00 JSTの瞬間）をLAへ投影すると
    //   2027-08-03 18:00 LA になってしまう（16時間差）。修正後はこれと一致しないことを確認する。
    const brokenWallClock = wallClockIn(new Date(result.sent.startsAt!), LA_TZ)
    expect(brokenWallClock, '修正前の壊れた値(2027-08-03 18:00)になっていないこと').not.toBe('2027-08-03 18:00:00')

    expect(result.status, '祭の作成が400にならないこと').toBe(201)
    if (result.festivalId) createdFestivalIds.push(result.festivalId)

    // 軸3: 往復してもLA壁時計で入力どおりであること（1日ずれ再発なし）
    const card = await findFestivalCard(page, title)
    const [returnedStart] = parseCardPeriod((await card.innerText()).trim())
    expect(
      wallClockIn(new Date(returnedStart), LA_TZ),
      `読み戻したstartsAtがLA壁時計で入力どおりであること（修正前なら2027-08-03になっていた）`,
    ).toBe('2027-08-04 10:00:00')
  })
})

test.describe('P2-03 ピッカー一致（ブラウザ=プロフィール=LA）+ DST境界', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: LA_TZ })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let userToken = ''
  const createdFestivalIds: string[] = []

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    userToken = await loginToken(ctx, E2E_USER.email, E2E_USER.password)
  })

  test.afterAll(async () => {
    for (const id of createdFestivalIds) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, userToken, 'POST', `/api/v1/villages/${VILLAGE_ID}/festivals/${id}/cancel`).catch(() => {})
    }
    await restoreUserTimezone(ctx, userToken)
    await ctx.dispose()
  })

  test('P2-03: ブラウザ=プロフィール=LA が一致するとき正常動作する', async ({ page }) => {
    await loginAndSetTimezone(page, ctx, userToken, LA_TZ)
    await setupApiBridge(page)
    await goto(page, `/villages/${VILLAGE_ID}/festivals`)

    const title = `#2508P2祭LALA-${Date.now()}`
    const result = await createFestivalViaUi(page, title, '2027-08-04T10:00', '2027-08-04T18:00')

    expect(wallClockIn(new Date(result.sent.startsAt!), LA_TZ), 'LA/LA一致時も入力どおりであること').toBe('2027-08-04 10:00:00')
    expect(result.status).toBe(201)
    if (result.festivalId) createdFestivalIds.push(result.festivalId)
  })

  test('P2-09 境界: 夏時間切替日(2027/3/14)をまたぐ範囲でもオフセットが正しく遷移する', async ({ page }) => {
    await loginAndSetTimezone(page, ctx, userToken, LA_TZ)
    await setupApiBridge(page)
    await goto(page, `/villages/${VILLAGE_ID}/festivals`)

    // US DST 2026: 3/8(日) 2:00 に PST(-08:00) → PDT(-07:00) へ切替
    const title = `#2508P2祭DST-${Date.now()}`
    const result = await createFestivalViaUi(page, title, '2027-03-13T10:00', '2027-03-15T10:00')

    expect(result.sent.startsAt, '切替前(3/7)はPST(-08:00)であること').toMatch(/^2027-03-13T10:00:00-08:00$/)
    expect(result.sent.endsAt, '切替後(3/9)はPDT(-07:00)であること').toMatch(/^2027-03-15T10:00:00-07:00$/)
    expect(result.status, 'DST境界をまたいでも作成が失敗しないこと（#2612の500根治を実測）').toBe(201)
    if (result.festivalId) createdFestivalIds.push(result.festivalId)
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
    // NOTE: ここで報告していた応答契約の非対称（作成 POST だけが {data:{survey:{...}}} の入れ子で、
    //    一覧 GET は {data:[{id,...}]} のフラット）は Issue #2635 で解消され、
    //    作成 POST も {data:{id,...,questions:[]}} のフラット形になった。
    //    本テストは引き続き一覧から実 ID を引く（経路をもう一段検証できるため）。
    const listRes = await api(ctx, adminToken, 'GET', `/api/v1/teams/${TEAM_SLUG}/surveys`)
    expect(listRes.ok()).toBeTruthy()
    const list = ((await listRes.json()).data ?? []) as Array<{ id: number; content?: { title?: string } }>
    const createdSurvey = list.find((s) => s.content?.title === title)
    expect(createdSurvey, `一覧に作成したアンケート「${title}」が見つかること`).toBeTruthy()
    createdSurveyId = createdSurvey!.id

    // 軸3: 一覧に反映（round trip 1）
    await expect(
      page.getByTestId(`survey-item-${createdSurveyId}`).filter({ hasText: title }),
      '作成したアンケートが一覧に出ること',
    ).toBeVisible({ timeout: 15_000 })

    // 軸3: 詳細画面で締切が保存された値のまま表示される（round trip 2）
    await page.getByTestId(`survey-item-edit-draft-${createdSurveyId}`).click()
    await expect(page).toHaveURL(new RegExp(`/surveys/${createdSurveyId}`), { timeout: 15_000 })
    const y = target.getFullYear()
    const m = String(target.getMonth() + 1).padStart(2, '0')
    const d = String(target.getDate()).padStart(2, '0')
    await expect(
      page.getByTestId('survey-detail-page').getByText(new RegExp(`${y}-${m}-${d}`)),
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
      resultsVisibility: 'AFTER_RESPONSE',
      unrespondedVisibility: 'CREATOR_AND_ADMIN',
      questions: [],
    })
    expect(res.ok(), `下書きアンケート作成に失敗: ${res.status()} ${await res.text()}`).toBeTruthy()
    // Issue #2635 で POST /surveys のレスポンスはフラット化され、{data:{id,...,questions:[]}} になった
    // （かつては {data:{survey:{id,...}}} の入れ子だった。P2-04 参照）。
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

    // 軸3: 公開後もBE側に2設問がdisplayOrder順で往復していること（round trip）。
    // ⚠️ 公開後の詳細画面は作成者=creatorのため「集計結果」パネル（設問文は展開しないと出ない）に
    // 遷移し、getByText では設問文が見えない。BEへ読み戻して確認する。
    const detailRes = await api(ctx, adminToken, 'GET', `/api/v1/teams/${TEAM_SLUG}/surveys/${draftSurveyId}`)
    expect(detailRes.ok(), `詳細取得に失敗: ${detailRes.status()}`).toBeTruthy()
    const detail = (await detailRes.json()).data as {
      questions: Array<{ questionType: string; content: { questionText: string; displayOrder: number } }>
    }
    expect(detail.questions.map((q) => q.content.questionText).sort()).toEqual(
      ['#2508P2 設問1（TEXT）', '#2508P2 設問2（単一選択）'].sort(),
    )
    const q1Readback = detail.questions.find((q) => q.content.questionText.includes('設問1'))
    const q2Readback = detail.questions.find((q) => q.content.questionText.includes('設問2'))
    expect(q1Readback?.questionType, '往復後もBE enum FREE_TEXTのまま保存されていること').toBe('FREE_TEXT')
    expect(
      [q1Readback?.content.displayOrder, q2Readback?.content.displayOrder].sort(),
      '往復後もdisplayOrderが0に潰れず区別できること',
    ).toEqual([1, 2])
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
