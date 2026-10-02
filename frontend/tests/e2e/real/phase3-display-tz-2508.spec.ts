/**
 * Issue #2508 Phase 3 — 実機 E2E。
 *
 * ## 背景（このファイルが検証する PR）
 *
 * - #2642(FE): 表示側のTZをユーザープロフィール基準に統一する。
 *   Phase 1/2 が「送信側」（FE→BE）のオフセット付与を根治したのに対し、Phase 3 は
 *   「表示側」（BE→FE の描画）を根治する。BE は `LocalDateTimeTimezoneSerializer` に
 *   より **ユーザーのプロフィールTZ** のオフセット付き ISO 文字列を返すが、
 *   一部の FE コンポーネントは `new Date(iso).toLocaleString()` 等でそれを受け、
 *   ブラウザTZで描き直してしまっていた（プロフィールTZを無視する事故）。
 *
 * ## 本 spec が区別する2種類の不具合（取り違え厳禁）
 *
 * ### 種別A: 瞬間（OffsetDateTime）の描画 — P3-01/P3-02/P3-04
 *   再現条件: ブラウザTZ ≠ **プロフィールTZ**。
 *   FE ヘルパー（`useDatetime().formatTime` 等）はブラウザTZではなく
 *   `authStore.user.timezone`（localStorage の `currentUser`）を読むため、
 *   `test.use({ timezoneId })` だけでは再現しない。localStorage の
 *   `currentUser.timezone` と DB の `users.timezone` の **両方** を設定する。
 *   JSTどうしでは絶対に再現しない（回帰確認の P3-04 はあえてJST/JSTで踏む）。
 *
 * ### 種別B: 暦日（LocalDate）の描画 — P3-03
 *   再現条件: **ブラウザTZが UTC より西**（America/Los_Angeles 等）。プロフィールTZは無関係。
 *   `new Date("2026-08-20")` は UTC 午前0時と解釈されるため、西のTZでは前日にずれる。
 *   `formatLocalDateOnly` はこれを踏まない（暦日の壁時計成分から Date を構築する）。
 *
 * ## 実行前提
 *   - BE / FE / MySQL が起動済み・`backend/scripts/seed-e2e-data.js` 実行済み
 *   - `BASE_URL` / `API_BASE_URL` を実行時に指定する
 */

import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const BASE_URL = process.env.BASE_URL ?? 'http://127.0.0.1:3005'
const API_BASE = process.env.API_BASE_URL ?? 'http://127.0.0.1:8085'

const E2E_ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }

const TEAM_SLUG = 'fc-u-18'

const JST_TZ = 'Asia/Tokyo'
const LA_TZ = 'America/Los_Angeles'

// ---------------------------------------------------------------------------
// 汎用ヘルパー（datetime-offset-2508.spec.ts / phase2-contract-and-picker-2508.spec.ts の実績パターンを踏襲）
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
 * ⚠️ FE の useDatetime() はブラウザTZではなく authStore.user?.timezone（localStorage）を読むため、
 *    DB 側の users.timezone も揃えないと、リロードを挟む経路で localStorage と DB がズレて
 *    偽陰性/偽陽性を生む。両方を必ず設定する。
 */
async function loginAndSetTimezone(
  page: Page,
  ctx: APIRequestContext,
  token: string,
  timezone: string,
): Promise<{ userId: number }> {
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

async function restoreUserTimezone(ctx: APIRequestContext, token: string): Promise<void> {
  const res = await api(ctx, token, 'PUT', '/api/v1/users/me', { timezone: JST_TZ })
  expect(res.ok(), `timezone復元に失敗: ${res.status()}`).toBeTruthy()
}

async function goto(page: Page, path: string): Promise<void> {
  await page.goto(`${BASE_URL}${path}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  // eslint-disable-next-line no-restricted-syntax -- スピナー0件観測は「読み込み済み」を意味するため無視
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
  await expect(page).not.toHaveURL(/\/login/)
}

// ===========================================================================
// P3-01【本命】スケジュール一覧（ScheduleListRow）— 種別A
//
// モバイルのリストビュー（`.md:hidden` / `[data-testid="schedule-list-view"]`）は
// 768px 未満でのみ描画されるため、viewport をモバイル幅にする。
// ===========================================================================
test.describe('P3-01 スケジュール一覧の日付・時刻（ScheduleListRow）', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: JST_TZ, viewport: { width: 390, height: 844 } })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let userToken = ''
  let createdScheduleId: number | null = null

  // BE は OffsetDateTime を Asia/Tokyo 壁時計へ正規化して保存し、
  // LocalDateTimeTimezoneSerializer が読み出し時にユーザーTZ（LA）のオフセットへ変換して返す。
  // 2026-08-20 09:00 PDT(-07:00) は Asia/Tokyo では 2026-08-21 01:00 になる（16時間差で誤りが顕著）。
  // ⚠️ スケジュール一覧は既定で「当月」のみ取得する（useCalendarEvents）ため、
  //    月をまたぐ日付にすると一覧に出ず偽陰性になる。実行時点の当月内の日付を使うこと。
  const START_AT = '2026-08-20T09:00:00-07:00'
  const END_AT = '2026-08-20T10:30:00-07:00'
  const TITLE_PREFIX = '#2508P3スケジュール'

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    // ⚠️ スケジュール作成はチーム ADMIN/DEPUTY 限定（ScheduleService.checkCreateScopeAccess）。
    //    e2e-user は fc-u-18 の MEMBER のため 403 になる。e2e-admin（team ADMIN）を使う。
    userToken = await loginToken(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
  })

  test.afterAll(async () => {
    if (createdScheduleId) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, userToken, 'DELETE', `/api/v1/teams/${TEAM_SLUG}/schedules/${createdScheduleId}`).catch(() => {})
    }
    // ⚠️ 必ず JST へ戻す（他 spec / 本セッションの継続利用に影響するため）
    await restoreUserTimezone(ctx, userToken)
    await ctx.dispose()
  })

  test('P3-01: プロフィールTZ=LA・ブラウザTZ=JSTで、一覧の日付・時刻がLA壁時計になる', async ({ page }) => {
    const title = `${TITLE_PREFIX}-${Date.now()}`
    const createRes = await api(ctx, userToken, 'POST', `/api/v1/teams/${TEAM_SLUG}/schedules`, {
      title,
      description: null,
      location: null,
      startAt: START_AT,
      endAt: END_AT,
      allDay: false,
      eventType: 'EVENT',
      visibility: 'MEMBERS_ONLY',
      attendanceRequired: false,
    })
    expect(createRes.ok(), `スケジュール作成に失敗: ${createRes.status()} ${await createRes.text()}`).toBeTruthy()
    createdScheduleId = ((await createRes.json()).data as { id: number }).id

    await loginAndSetTimezone(page, ctx, userToken, LA_TZ)
    await setupApiBridge(page)
    await goto(page, `/teams/${TEAM_SLUG}/schedule`)

    // ⚠️ e2e-admin は178チーム/11組織に所属しており、フレッシュログイン直後は
    //    デイリーログインのゲーミフィケーション処理（GamificationPointListener）が
    //    バックグラウンドで数十秒走り、その間ページの初回データ取得が遅延することがある
    //    （Phase 3 の変更とは無関係の既存の負荷特性）。タイムアウトを長めに取る。
    const listView = page.getByTestId('schedule-list-view')
    await expect(listView, 'モバイルのリストビューが描画されること').toBeVisible({ timeout: 45_000 })

    const row = page.getByTestId('schedule-list-row').filter({ hasText: title }).first()
    await expect(row, `作成したイベント「${title}」の行が一覧に出ること`).toBeVisible({ timeout: 20_000 })
    const rowText = (await row.innerText()).trim()

    // 修正後: LA壁時計（8/20・09:00〜10:30）で表示されること
    expect(rowText, 'LA壁時計の日付(8/20)が表示されること').toContain('8/20')
    expect(rowText, 'LA壁時計の開始時刻(09:00)が表示されること').toContain('09:00')
    expect(rowText, 'LA壁時計の終了時刻(10:30)が表示されること').toContain('10:30')

    // 修正前（ブラウザJSTで描き直し）だった場合の値を明示的に反証する:
    //   2026-08-20 09:00 PDT の瞬間はJSTでは 2026-08-21 01:00 になる。
    expect(rowText, '壊れていた側の日付(8/21)になっていないこと').not.toContain('8/21')
    expect(rowText, '壊れていた側の時刻(01:00)になっていないこと').not.toContain('01:00')
    expect(rowText, '壊れていた側の時刻(02:30)になっていないこと').not.toContain('02:30')
  })
})

// ===========================================================================
// P3-02 大会参加費の支払期限（TournamentFeeResponse.paymentDue）— 種別A
//
// e2e-admin は東京都サッカー協会（JFA org）ADMIN + FC東京U-18（team）ADMIN のため、
// 大会作成→ payment_item 作成 → fee 作成（自 API で完結）→ チーム側 fees ページで表示確認。
// ===========================================================================
test.describe('P3-02 大会参加費の支払期限表示', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: JST_TZ })
  test.setTimeout(120_000)

  let ctx: APIRequestContext
  let adminToken = ''
  let orgId: number | null = null
  let teamId: number | null = null
  let tournamentId: number | null = null
  let paymentItemId: number | null = null

  // BE は LocalDateTime を Asia/Tokyo 壁時計として保持し、ユーザーTZ(LA)へオフセット変換して返す。
  // 2027-09-10 21:00 JST 壁時計で保存 → LAでは 2027-09-10 05:00 PDT になる（日付が変わらない安全側の差だが時刻は別）。
  // 日付跨ぎも検出できるよう、JST 03:00（＝LA前日 11:00）の値を使う。
  const PAYMENT_DUE_JST = '2027-09-10T03:00:00+09:00'

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    adminToken = await loginToken(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
    await api(ctx, adminToken, 'PUT', '/api/v1/users/me', { timezone: JST_TZ })

    // ⚠️ 大会作成・参加費作成は「大会が属する組織」の ADMIN 限定（checkAdminOrAbove）。
    //    fc-u-18（TEAM_SLUG）が実際に所属する組織（org 9）では e2e-admin は MEMBER に過ぎず
    //    403 になる（実測）。fees ページの認可（TournamentFeeService.requireOrganizerAdmin）は
    //    「大会の主催組織 ADMIN か」のみを見て参加チームの所属は問わないため、
    //    e2e-admin が ADMIN 権限を持つ別組織（日本サッカー協会）で大会を作ってもページ表示は成立する。
    //    team は URL 経路（/teams/{slug}/...）用に別途 TEAM_SLUG を使う。
    const orgRes = await api(ctx, adminToken, 'GET', '/api/v1/me/organizations?size=50')
    if (orgRes.ok()) {
      const orgs = ((await orgRes.json()).data ?? []) as Array<{ id: number; name: string; role?: string }>
      const org = orgs.find((o) => o.role === 'ADMIN' || o.role === 'SYSTEM_ADMIN') ?? orgs[0]
      orgId = org?.id ?? null
    }
    teamId = 1 // fc-u-18（TEAM_SLUG）の数値ID。URL経路にのみ使用し、認可には関与しない。

    if (orgId === null) return

    const tourRes = await api(ctx, adminToken, 'POST', `/api/v1/organizations/${orgId}/tournaments`, {
      name: `#2508P3 大会 ${Date.now()}`,
      format: 'LEAGUE',
      season: '2027',
      startDate: '2027-09-01',
      endDate: '2027-11-30',
      winPoints: 3,
      drawPoints: 1,
      lossPoints: 0,
      hasDraw: true,
      hasSets: false,
      visibility: 'MEMBERS_AND_ABOVE',
    })
    if (!tourRes.ok()) return
    tournamentId = ((await tourRes.json()).data as { id: number }).id

    const itemRes = await api(ctx, adminToken, 'POST', `/api/v1/organizations/${orgId}/payment-items`, {
      name: `#2508P3 参加費 ${Date.now()}`,
      description: null,
      type: 'ITEM',
      amount: 5000,
      currency: 'JPY',
      isActive: true,
    })
    if (!itemRes.ok()) return
    paymentItemId = ((await itemRes.json()).data as { id: number }).id
  })

  test.afterAll(async () => {
    if (orgId && tournamentId) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, adminToken, 'DELETE', `/api/v1/organizations/${orgId}/tournaments/${tournamentId}`).catch(() => {})
    }
    await ctx.dispose()
  })

  test('P3-02: プロフィールTZ=LA・ブラウザTZ=JSTで、参加費の期限がLA壁時計になる', async ({ page }) => {
    test.skip(
      orgId === null || teamId === null || tournamentId === null || paymentItemId === null,
      '前提（org/team/tournament/payment-item のいずれか）の解決・作成に失敗したためスキップ',
    )

    const feeRes = await api(
      ctx,
      adminToken,
      'POST',
      `/api/v1/organizations/${orgId}/tournaments/${tournamentId}/fees`,
      {
        paymentItemId,
        title: '#2508P3 参加費',
        divisionId: null,
        targetScope: 'ALL_TEAMS',
        paymentDue: PAYMENT_DUE_JST,
      },
    )
    expect(feeRes.ok(), `参加費作成に失敗: ${feeRes.status()} ${await feeRes.text()}`).toBeTruthy()

    await loginAndSetTimezone(page, ctx, adminToken, LA_TZ)
    await setupApiBridge(page)
    await goto(page, `/teams/${TEAM_SLUG}/tournaments/${tournamentId}/fees?orgId=${orgId}`)

    const card = page.locator('h3', { hasText: '#2508P3 参加費' }).first()
    await expect(card, '作成した参加費カードが一覧に出ること').toBeVisible({ timeout: 20_000 })
    // h3 の親 div（div.min-w-0.flex-1）が金額・期限も内包する（fees.vue のカード構造）。
    const cardText = (await card.locator('..').innerText()).trim()

    // 修正後: LA壁時計（2027/09/09）で表示されること（JST 9/10 03:00 = LA 9/9 11:00）
    expect(cardText, 'LA壁時計の日付(2027/09/09)が表示されること').toContain('2027/09/09')
    // 修正前（ブラウザJSTで描き直し）だった場合の値(2027/09/10)にはなっていないこと
    expect(cardText, '壊れていた側の日付(2027/09/10)になっていないこと').not.toContain('2027/09/10')

    await restoreUserTimezone(ctx, adminToken)
  })
})

// ===========================================================================
// P3-03 TERM型契約期間表示（payments/subscribe/[itemId].vue）— 種別B
//
// ⚠️ 種別Bはブラウザ TZ のみが条件（プロフィールTZは無関係）。formatLocalDateOnly は
//    LocalDate の壁時計成分から Date を組み立てるため localStorage の設定は不要。
// ===========================================================================
test.describe('P3-03 TERM型契約期間の暦日表示', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: LA_TZ })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let adminToken = ''
  let orgId: number | null = null
  let termItemId: number | null = null
  let blockedReason: string | null = null

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    adminToken = await loginToken(ctx, E2E_ADMIN.email, E2E_ADMIN.password)

    const orgRes = await api(ctx, adminToken, 'GET', '/api/v1/me/organizations?size=50')
    if (!orgRes.ok()) {
      blockedReason = `/me/organizations が ${orgRes.status()}`
      return
    }
    const orgs = ((await orgRes.json()).data ?? []) as Array<{ id: number; name: string; role?: string }>
    const org = orgs.find((o) => o.role === 'ADMIN' || o.role === 'SYSTEM_ADMIN') ?? orgs[0]
    orgId = org?.id ?? null
    if (orgId === null) {
      blockedReason = '所属組織が見つからない'
      return
    }

    // type=TERM は termStartsOn/termEndsOn が必須（P6 契約）。
    const itemRes = await api(ctx, adminToken, 'POST', `/api/v1/organizations/${orgId}/payment-items`, {
      name: `#2508P3 TERM期別 ${Date.now()}`,
      description: null,
      type: 'TERM',
      amount: 3000,
      currency: 'JPY',
      isActive: true,
      termStartsOn: '2027-08-01',
      termEndsOn: '2027-08-20',
    })
    if (!itemRes.ok()) {
      blockedReason = `TERM型 payment-item 作成が ${itemRes.status()}: ${(await itemRes.text()).slice(0, 300)}`
      return
    }
    termItemId = ((await itemRes.json()).data as { id: number }).id
  })

  test.afterAll(async () => {
    if (orgId && termItemId) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, adminToken, 'DELETE', `/api/v1/organizations/${orgId}/payment-items/${termItemId}`).catch(() => {})
    }
    await ctx.dispose()
  })

  test('P3-03: ブラウザTZ=LAでもTERM契約終了日が8/20のまま（8/19にずれない）', async ({ page }) => {
    test.skip(termItemId === null, `TERM型 payment-item を用意できなかったためスキップ: ${blockedReason ?? '(不明)'}`)

    await setupApiBridge(page)
    await page.setExtraHTTPHeaders({ Authorization: `Bearer ${adminToken}` })
    await page.goto(`${BASE_URL}/`, { waitUntil: 'domcontentloaded' })
    await page.evaluate((token) => {
      // subscribe ページは authStore.user を要求する（見た目上のログイン状態合わせ。timezoneは無関係）。
      localStorage.setItem('currentUser', JSON.stringify({
        id: 0, email: 'e2e-admin@test.mannschaft.local', fullName: 'E2E Admin',
        profileImageUrl: null, systemRole: 'SYSTEM_ADMIN', timezone: 'Asia/Tokyo',
      }))
      void token
    }, adminToken)

    await goto(page, `/payments/subscribe/${termItemId}`)

    // GET /api/v1/payment-items/{itemId} の実測。BE未実装なら以降のUI観測は不可能なため、
    // 「i18n崩れ」等に誤って原因帰属せず、正しい理由でskipする（過去に足軽が誤帰属した実績への戒め）。
    const itemRes = await api(ctx, adminToken, 'GET', `/api/v1/organizations/${orgId}/payment-items?size=50`)
    void itemRes
    const directRes = await ctx.fetch(`${API_BASE}/api/v1/payment-items/${termItemId}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    })
    test.skip(
      !directRes.ok(),
      `GET /api/v1/payment-items/{itemId} がBE未実装/失敗のため（実測 ${directRes.status()}）、`
      + 'ページ側 loadPaymentItem() が常に失敗しTERM期間表示が到達不能。UI経路での検証は不可能なためskip'
      + '（FE composable のコメントにも "P6 実装待ち" と明記済み・本PRの範囲外の既知ギャップ）。',
    )

    const periodNote = page.getByRole('note')
    await expect(periodNote, 'TERM契約期間の注記が表示されること').toBeVisible({ timeout: 15_000 })
    const noteText = (await periodNote.innerText()).trim()

    expect(noteText, '契約終了日が2027/08/20のまま表示されること').toContain('2027/08/20')
    expect(noteText, '壊れていた側の値(2027/08/19)になっていないこと').not.toContain('2027/08/19')
  })
})

// ===========================================================================
// P3-04 回帰確認: JST/JSTでは従来どおり正しいこと（種別A・壊していないことの確認）
// ===========================================================================
test.describe('P3-04 回帰確認（ブラウザ=プロフィール=JST）', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: JST_TZ, viewport: { width: 390, height: 844 } })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let userToken = ''
  let createdScheduleId: number | null = null

  // ⚠️ P3-01 と同じ理由で当月内の日付を使う（月をまたぐと一覧に出ず偽陰性になる）。
  const START_AT = '2026-08-20T09:00:00+09:00'
  const END_AT = '2026-08-20T10:30:00+09:00'
  const TITLE_PREFIX = '#2508P3回帰JST'

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    // P3-01 と同じ理由（スケジュール作成はチーム ADMIN 限定）で e2e-admin を使う。
    userToken = await loginToken(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
    await restoreUserTimezone(ctx, userToken)
  })

  test.afterAll(async () => {
    if (createdScheduleId) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, userToken, 'DELETE', `/api/v1/teams/${TEAM_SLUG}/schedules/${createdScheduleId}`).catch(() => {})
    }
    await ctx.dispose()
  })

  test('P3-04: ブラウザ=プロフィール=JSTでは従来どおり正しい壁時計で表示される（回帰なし）', async ({ page }) => {
    const title = `${TITLE_PREFIX}-${Date.now()}`
    const createRes = await api(ctx, userToken, 'POST', `/api/v1/teams/${TEAM_SLUG}/schedules`, {
      title,
      description: null,
      location: null,
      startAt: START_AT,
      endAt: END_AT,
      allDay: false,
      eventType: 'EVENT',
      visibility: 'MEMBERS_ONLY',
      attendanceRequired: false,
    })
    expect(createRes.ok(), `スケジュール作成に失敗: ${createRes.status()} ${await createRes.text()}`).toBeTruthy()
    createdScheduleId = ((await createRes.json()).data as { id: number }).id

    await loginAndSetTimezone(page, ctx, userToken, JST_TZ)
    await setupApiBridge(page)
    await goto(page, `/teams/${TEAM_SLUG}/schedule`)

    // ⚠️ P3-01 と同じ理由（e2e-adminの178チーム所属によるデイリーログイン処理の遅延）でタイムアウトを長めに取る。
    const listView = page.getByTestId('schedule-list-view')
    await expect(listView).toBeVisible({ timeout: 45_000 })

    const row = page.getByTestId('schedule-list-row').filter({ hasText: title }).first()
    await expect(row, `作成したイベント「${title}」の行が一覧に出ること`).toBeVisible({ timeout: 20_000 })
    const rowText = (await row.innerText()).trim()

    expect(rowText, 'JST壁時計の日付(8/20)がそのまま表示されること').toContain('8/20')
    expect(rowText, 'JST壁時計の開始時刻(09:00)がそのまま表示されること').toContain('09:00')
    expect(rowText, 'JST壁時計の終了時刻(10:30)がそのまま表示されること').toContain('10:30')
  })
})
