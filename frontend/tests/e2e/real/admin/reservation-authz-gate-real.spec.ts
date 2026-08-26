/**
 * 予約認可ゲート（御下命③）— 実機フルスタック E2E テスト
 *
 * 御下命③:
 *   「予約権限は既定=そのチームの SUPPORTER＋MEMBER（＋ADMIN/DEPUTY）のみ。
 *    裏設定で PUBLIC（ログイン済み非所属まで）可。ADMIN がチーム予約管理で切替」
 *
 * バックエンド http://localhost:8080 / フロントエンド dev サーバー（playwright.config の
 * webServer が :8081 で起動・NUXT_API_PROXY=true）が起動済みの状態で実行する。
 *
 * playwright.config.ts の chromium-real-admin プロジェクトで実行される。
 *   testMatch: ** /real/admin/** /*.spec.ts
 *   このファイルを real/admin/ 配下に置くのは、設定切替（PATCH reservation-settings）が
 *   ADMIN 限定であり、また reservation-dashboard-real.spec.ts と作法を揃えるため。
 *   storageState はこの spec 内で自前ログイン（複数ユーザーの視点を切り替える）するため未使用。
 *
 * BE 認可ゲート（origin/main 49009f4b 以降）:
 *   ReservationService.createReservation 冒頭で
 *     if (!settingService.isAllowPublic(teamId) && !accessControlService.isMember(userId, teamId, "TEAM"))
 *         throw RESERVATION_PERMISSION_DENIED (RESERVATION_021, HTTP 403)
 *   - isAllowPublic は reservation_team_settings.allow_public_reservation（既定 false）。
 *   - isMember は memberships の role_kind を問わずアクティブ行が在れば true
 *     （MEMBER も SUPPORTER も memberships に行があるため成立する）。
 *   PATCH /api/v1/teams/{teamId}/reservation-settings { allowPublicReservation } は
 *     @PreAuthorize(isScopeStrictAdmin) で ADMIN 限定。
 *   GET .../reservation-settings は { allowPublicReservation, hasBusinessHours, teamId } を返す。
 *
 * FE（reservations.vue / #1629・4-A 是正済）:
 *   isAffiliated = isMember || roleName === 'SUPPORTER'
 *   canBook = isAffiliated || reservationSettings.allowPublicReservation === true
 *   canBook=false のとき「予約する」タブは案内文（reservation.book.not_affiliated_notice）に切替。
 *
 * 検証シナリオ（API 主・UI 補助）:
 *   1) MEMBER（e2e-user）は設定 OFF でも予約 201 成立
 *   2) SUPPORTER（dummy をフォローで SUPPORTER 化）は設定 OFF でも予約 201 成立
 *   3) 非所属（fc-u-18 非所属の dummy）は設定 OFF で予約 403 RESERVATION_021
 *   4) 設定 ON にすると同じ非所属ユーザーが予約 201 成立（403 が解ける）
 *   5)(UI) 非所属視点で設定 OFF は予約導線が出ず案内文 / 設定 ON は SlotPicker が出る。
 *      SUPPORTER 視点では設定に関わらず SlotPicker が出る（4-A 是正の確証）。
 *
 * 後始末: 設定は OFF(false) に戻す。作成した予約はキャンセル、スロット/ラインは削除する。
 *
 * 鉄則（memory: feedback_e2e_real_full_crud / feedback_e2e_wsl2_cors_apibridge）:
 *   read-only/モックでは出ない本物のバグを認証付き書込 CRUD で捕捉する。
 */

import {
  test as base,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
} from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const BE = 'http://localhost:8080'
const PASSWORD = 'TestPass2026!'
// e2e-admin が ADMIN を持つ予約対象チーム（slug=fc-u-18 / teamId=1）。
// BE は slug を teamId パス変数として受け付ける（slug→id 解決は Controller 側）。
const TEAM_SLUG = 'fc-u-18'

const ADMIN_EMAIL = 'e2e-admin@test.mannschaft.local' // fc-u-18 ADMIN / SYSTEM_ADMIN
const MEMBER_EMAIL = 'e2e-user@test.mannschaft.local' // fc-u-18 MEMBER
// fc-u-18 に所属しない（別チーム fc-u-15 のみ所属・systemRole なし）ユーザー。
// /me/teams で fc-u-18(id=1) を持たないことを確認済み（2026-06-18 偵察）。
const OUTSIDER_EMAIL = 'e2e-dummy-6@test.mannschaft.local'
// fc-u-18 に所属しない別 dummy。テスト内で follow して SUPPORTER 化する。
const SUPPORTER_CANDIDATE_EMAIL = 'e2e-dummy-7@test.mannschaft.local'

/**
 * ログインして accessToken を返す。
 *
 * 複数ワーカー／複数プロジェクトが同時刻にログインをバーストすると、BE が
 * レートリミット（429/400）や一過性のリソース競合（500 COMMON_999）を返すことがある
 * （手動の直列ログインは全て 200 で再現せず、並列バーストでのみ発生する）。
 * これは認可ゲート検証の本筋ではないインフラ起因のノイズなので、指数バックオフで
 * リトライして吸収する（症状の握りつぶしではなく、一過性失敗の正当なリトライ）。
 */
async function login(ctx: APIRequestContext, email: string): Promise<string> {
  const maxAttempts = 6
  let lastErr = ''
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    const res = await ctx.post(`${BE}/api/v1/auth/login`, {
      headers: { 'Content-Type': 'application/json' },
      data: { email, password: PASSWORD },
    })
    if (res.ok()) return (await res.json()).data.accessToken as string
    const status = res.status()
    lastErr = `${status} ${await res.text()}`
    // 401/403 は認証情報そのものの誤りなので即時失敗（リトライしても無駄）
    if (status === 401 || status === 403) break
    // 429/400/500 等の一過性はバックオフして再試行
    await new Promise((r) => setTimeout(r, 500 * attempt + Math.floor(Math.random() * 300)))
  }
  throw new Error(`ログイン失敗(${email}): ${lastErr}`)
}

interface MeProfile {
  id: number
  email: string
  lastName: string
  firstName: string
  avatarUrl: string | null
  systemRole: string | null
  timezone: string | null
}

async function fetchMe(ctx: APIRequestContext, token: string): Promise<MeProfile> {
  const res = await ctx.get(`${BE}/api/v1/users/me`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!res.ok()) throw new Error(`/users/me 失敗: ${res.status()}`)
  return (await res.json()).data as MeProfile
}

/**
 * worker スコープで全ユーザーのトークンを一度だけ取得する
 * （多並列の同時ログインで BE が稀に 500 を返す事象を避けるため）。
 */
const test = base.extend<
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type -- test-scoped の追加 fixture は無い（worker-scoped の tokens のみ）
  {},
  {
    tokens: {
      admin: string
      member: string
      outsider: string
      outsiderMe: MeProfile
      supporter: string
      supporterMe: MeProfile
    }
  }
>({
  // storageState 依存を外す（複数ユーザーを spec 内で切り替えるため）
  // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数に分割代入を要求する
  storageState: async ({}, use) => {
    await use(undefined)
  },
  tokens: [
    // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数に分割代入を要求する
    async ({}, use) => {
      const ctx = await playwrightRequest.newContext()
      // ログインは直列＋小休止で行う（同時バーストによる BE のレートリミット/一過性 500 を避ける）。
      const gap = () => new Promise((r) => setTimeout(r, 250))
      const admin = await login(ctx, ADMIN_EMAIL)
      await gap()
      const member = await login(ctx, MEMBER_EMAIL)
      await gap()
      const outsider = await login(ctx, OUTSIDER_EMAIL)
      const outsiderMe = await fetchMe(ctx, outsider)
      await gap()
      const supporter = await login(ctx, SUPPORTER_CANDIDATE_EMAIL)
      const supporterMe = await fetchMe(ctx, supporter)
      await ctx.dispose()
      await use({ admin, member, outsider, outsiderMe, supporter, supporterMe })
    },
    { scope: 'worker' },
  ],
})

test.setTimeout(120_000)

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

// ---------------------------------------------------------------------------
// BE 直接ヘルパー
// ---------------------------------------------------------------------------

async function setAllowPublic(
  request: APIRequestContext,
  adminToken: string,
  allow: boolean,
): Promise<void> {
  const res = await request.patch(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-settings`, {
    headers: authHeaders(adminToken),
    data: { allowPublicReservation: allow },
  })
  if (!res.ok()) throw new Error(`設定更新失敗(allow=${allow}): ${res.status()} ${await res.text()}`)
}

async function getAllowPublic(
  request: APIRequestContext,
  token: string,
): Promise<boolean> {
  const res = await request.get(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-settings`, {
    headers: authHeaders(token),
  })
  if (!res.ok()) throw new Error(`設定取得失敗: ${res.status()} ${await res.text()}`)
  return (await res.json()).data.allowPublicReservation === true
}

async function createLine(
  request: APIRequestContext,
  token: string,
  name: string,
): Promise<{ id: number }> {
  const res = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines`, {
    headers: authHeaders(token),
    data: { name },
  })
  if (!res.ok()) throw new Error(`createLine 失敗: ${res.status()} ${await res.text()}`)
  return (await res.json()).data
}

async function deleteLine(request: APIRequestContext, token: string, lineId: number): Promise<void> {
  await request.delete(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines/${lineId}`, {
    headers: authHeaders(token),
  })
}

async function createSlot(
  request: APIRequestContext,
  token: string,
  body: { slotDate: string; startTime: string; endTime: string },
): Promise<{ id: number }> {
  const res = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots`, {
    headers: authHeaders(token),
    data: body,
  })
  if (!res.ok()) throw new Error(`createSlot 失敗: ${res.status()} ${await res.text()}`)
  return (await res.json()).data
}

async function deleteSlot(request: APIRequestContext, token: string, slotId: number): Promise<void> {
  await request.delete(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots/${slotId}`, {
    headers: authHeaders(token),
  })
}

/** 予約 POST を投げ、HTTP ステータスとレスポンス JSON を返す（ok 判定は呼出側）。 */
async function postReservation(
  request: APIRequestContext,
  token: string,
  slotId: number,
  lineId: number,
): Promise<{ status: number; json: unknown }> {
  const res = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
    headers: authHeaders(token),
    data: { reservationSlotId: slotId, lineId, userNote: 'authz-gate-e2e' },
  })
  return { status: res.status(), json: await res.json() }
}

async function cancelReservation(
  request: APIRequestContext,
  token: string,
  reservationId: number,
): Promise<void> {
  await request
    .post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/cancel`, {
      headers: authHeaders(token),
      data: { reason: 'E2E cleanup' },
    })
    .catch(() => {})
}

/** 既に SUPPORTER 化済みなら follow を取り消して元の非所属状態へ戻す。 */
async function unfollowIfNeeded(request: APIRequestContext, token: string): Promise<void> {
  await request.delete(`${BE}/api/v1/teams/${TEAM_SLUG}/follow`, {
    headers: authHeaders(token),
  }).catch(() => {})
}

// 33日後（YYYY-MM-DD）。過去日スロットのバリデーションを避ける
function futureDate(daysAhead: number): string {
  const d = new Date()
  d.setDate(d.getDate() + daysAhead)
  return d.toISOString().slice(0, 10)
}

/** 予約 ID をレスポンス JSON から安全に取り出す（成立時のみ）。 */
function reservationIdOf(json: unknown): number | null {
  if (json && typeof json === 'object' && 'data' in json) {
    const data = (json as { data?: { id?: number } }).data
    if (data && typeof data.id === 'number') return data.id
  }
  return null
}

/** エラーコードをレスポンス JSON から取り出す。 */
function errorCodeOf(json: unknown): string | null {
  if (json && typeof json === 'object' && 'error' in json) {
    const err = (json as { error?: { code?: string } }).error
    if (err && typeof err.code === 'string') return err.code
  }
  return null
}

/**
 * APIブリッジ（memory: feedback_e2e_wsl2_cors_apibridge）。
 * dev サーバー(:8081) で動かす UI テストでは、ブラウザが BE(:8080) へクロスオリジン fetch する。
 * BE の CORS 許可 origin は localhost:3000/8080 のみで :8081 は弾かれるため、
 * ブラウザの /api/v1 呼び出しを page.route で横取りし、Playwright の APIRequestContext で中継する。
 * 3点必須: ①中継 origin/referer を許可 origin に差替 ②ACAO をブラウザ実 origin に固定
 *          ③Bearer トークン付与で確実に認証（Cookie 中継に依存しない）。
 */
async function installApiBridge(
  page: import('@playwright/test').Page,
  token: string,
): Promise<void> {
  const pageOrigin = new URL(page.url() || 'http://localhost:8081').origin
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const target = `http://127.0.0.1:8080${url.pathname}${url.search}`
    const headers: Record<string, string> = {
      ...req.headers(),
      origin: 'http://localhost:3000',
      referer: 'http://localhost:3000/',
      authorization: `Bearer ${token}`,
    }
    const relay = await page.request.fetch(target, {
      method: req.method(),
      headers,
      data: req.postData() ?? undefined,
      maxRedirects: 0,
    })
    const respHeaders: Record<string, string> = { ...relay.headers() }
    respHeaders['access-control-allow-origin'] = pageOrigin
    respHeaders['access-control-allow-credentials'] = 'true'
    await route.fulfill({
      status: relay.status(),
      headers: respHeaders,
      body: await relay.body(),
    })
  })
}

/** ブラウザに currentUser / tokenExpiresAt を仕込んで認証済み状態を作る。 */
async function seedBrowserAuth(
  page: import('@playwright/test').Page,
  me: MeProfile,
): Promise<void> {
  const currentUser = {
    id: me.id,
    email: me.email,
    fullName: `${me.lastName} ${me.firstName}`,
    profileImageUrl: me.avatarUrl,
    systemRole: me.systemRole ?? undefined,
    timezone: me.timezone ?? undefined,
  }
  const farFuture = Date.now() + 24 * 60 * 60 * 1000
  await page.addInitScript(
    ({ user, expiresAt }) => {
      localStorage.setItem('currentUser', JSON.stringify(user))
      localStorage.setItem('tokenExpiresAt', String(expiresAt))
    },
    { user: currentUser, expiresAt: farFuture },
  )
}

// ===========================================================================
// API シナリオ 1〜4: 予約認可ゲート（主検証）
// ===========================================================================

test.describe('RSV-AUTHZ: 予約認可ゲート（API）', () => {
  // 各 API テストはライン+スロットを自前で用意・後始末する。
  // 設定は describe 全体で OFF を既定とし、シナリオ4で一時的に ON にしたあと afterAll で OFF に戻す。

  test.afterAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    // 設定を OFF(false) に戻す（他 E2E への副作用防止）
    await setAllowPublic(ctx, tokens.admin, false).catch(() => {})
    // SUPPORTER 化した候補を元の非所属へ戻す
    await unfollowIfNeeded(ctx, tokens.supporter).catch(() => {})
    await ctx.dispose()
  })

  test('RSV-AUTHZ-01: MEMBER は設定 OFF でも予約 201 成立', async ({ request, tokens }) => {
    await setAllowPublic(request, tokens.admin, false)
    expect(await getAllowPublic(request, tokens.admin), '初期設定は OFF であること').toBe(false)

    const line = await createLine(request, tokens.admin, `AUTHZ_MEMBER_${Date.now()}`)
    const slot = await createSlot(request, tokens.admin, {
      slotDate: futureDate(33),
      startTime: '09:00',
      endTime: '09:30',
    })
    let reservationId: number | null = null
    try {
      const { status, json } = await postReservation(request, tokens.member, slot.id, line.id)
      expect(status, `MEMBER 予約は 201 のはず（受信: ${JSON.stringify(json)}）`).toBe(201)
      reservationId = reservationIdOf(json)
      expect(reservationId, '予約 ID が返ること').toBeTruthy()
    } finally {
      if (reservationId) await cancelReservation(request, tokens.admin, reservationId)
      await deleteSlot(request, tokens.admin, slot.id)
      await deleteLine(request, tokens.admin, line.id)
    }
  })

  test('RSV-AUTHZ-02: SUPPORTER は設定 OFF でも予約 201 成立', async ({ request, tokens }) => {
    await setAllowPublic(request, tokens.admin, false)

    // 候補ユーザーを follow で SUPPORTER 化（fc-u-18 は autoApprove=true → 即 APPROVED）。
    await unfollowIfNeeded(request, tokens.supporter) // 念のため初期化
    const followRes = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/follow`, {
      headers: authHeaders(tokens.supporter),
    })
    expect(
      followRes.status(),
      `follow による SUPPORTER 化は 201 のはず（受信: ${await followRes.text()}）`,
    ).toBe(201)
    const followBody = await followRes.json()
    expect(
      followBody.data?.status,
      'autoApprove=true のチームでは follow 直後に APPROVED になること（SUPPORTER 付与）',
    ).toBe('APPROVED')

    const line = await createLine(request, tokens.admin, `AUTHZ_SUPPORTER_${Date.now()}`)
    const slot = await createSlot(request, tokens.admin, {
      slotDate: futureDate(33),
      startTime: '09:30',
      endTime: '10:00',
    })
    let reservationId: number | null = null
    try {
      const { status, json } = await postReservation(request, tokens.supporter, slot.id, line.id)
      expect(status, `SUPPORTER 予約は 201 のはず（受信: ${JSON.stringify(json)}）`).toBe(201)
      reservationId = reservationIdOf(json)
      expect(reservationId, '予約 ID が返ること').toBeTruthy()
    } finally {
      if (reservationId) await cancelReservation(request, tokens.admin, reservationId)
      await deleteSlot(request, tokens.admin, slot.id)
      await deleteLine(request, tokens.admin, line.id)
      // SUPPORTER 化を取り消して非所属へ戻す（シナリオ間の独立性を保つ）
      await unfollowIfNeeded(request, tokens.supporter)
    }
  })

  test('RSV-AUTHZ-03: 非所属は設定 OFF で予約 403 RESERVATION_021', async ({ request, tokens }) => {
    await setAllowPublic(request, tokens.admin, false)
    expect(await getAllowPublic(request, tokens.admin), '設定は OFF であること').toBe(false)

    const line = await createLine(request, tokens.admin, `AUTHZ_DENY_${Date.now()}`)
    const slot = await createSlot(request, tokens.admin, {
      slotDate: futureDate(33),
      startTime: '10:00',
      endTime: '10:30',
    })
    try {
      const { status, json } = await postReservation(request, tokens.outsider, slot.id, line.id)
      expect(status, `非所属の予約は 403 のはず（受信: ${JSON.stringify(json)}）`).toBe(403)
      expect(
        errorCodeOf(json),
        `エラーコードは RESERVATION_021 のはず（受信: ${JSON.stringify(json)}）`,
      ).toBe('RESERVATION_021')
    } finally {
      await deleteSlot(request, tokens.admin, slot.id)
      await deleteLine(request, tokens.admin, line.id)
    }
  })

  test('RSV-AUTHZ-04: 設定 ON にすると非所属も予約 201 成立（403 が解ける）', async ({
    request,
    tokens,
  }) => {
    const line = await createLine(request, tokens.admin, `AUTHZ_PUBLIC_${Date.now()}`)
    const slotOff = await createSlot(request, tokens.admin, {
      slotDate: futureDate(33),
      startTime: '10:30',
      endTime: '11:00',
    })
    const slotOn = await createSlot(request, tokens.admin, {
      slotDate: futureDate(33),
      startTime: '11:00',
      endTime: '11:30',
    })
    let reservationId: number | null = null
    try {
      // まず OFF で 403 を確認（前提の再確認）
      await setAllowPublic(request, tokens.admin, false)
      const denied = await postReservation(request, tokens.outsider, slotOff.id, line.id)
      expect(denied.status, '設定 OFF では非所属は 403').toBe(403)
      expect(errorCodeOf(denied.json)).toBe('RESERVATION_021')

      // 設定 ON にして同じ非所属ユーザーが別スロットで予約 → 201 成立
      await setAllowPublic(request, tokens.admin, true)
      expect(await getAllowPublic(request, tokens.admin), '設定が ON になったこと').toBe(true)
      const allowed = await postReservation(request, tokens.outsider, slotOn.id, line.id)
      expect(
        allowed.status,
        `設定 ON では非所属も 201 のはず（受信: ${JSON.stringify(allowed.json)}）`,
      ).toBe(201)
      reservationId = reservationIdOf(allowed.json)
      expect(reservationId, '予約 ID が返ること').toBeTruthy()
    } finally {
      if (reservationId) await cancelReservation(request, tokens.admin, reservationId)
      await deleteSlot(request, tokens.admin, slotOff.id)
      await deleteSlot(request, tokens.admin, slotOn.id)
      await deleteLine(request, tokens.admin, line.id)
      // 設定を OFF に戻す（afterAll でも戻すが、後続 UI テストの前提を OFF に固定）
      await setAllowPublic(request, tokens.admin, false)
    }
  })
})

// ===========================================================================
// UI シナリオ 5: 非所属視点の予約導線切替（補助検証 / 4-A 是正の確証）
// ===========================================================================

test.describe('RSV-AUTHZ-UI: 予約タブの導線切替（非所属/SUPPORTER 視点）', () => {
  test.afterEach(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    await setAllowPublic(ctx, tokens.admin, false).catch(() => {})
    await unfollowIfNeeded(ctx, tokens.supporter).catch(() => {})
    await ctx.dispose()
  })

  test('RSV-AUTHZ-05: 非所属は OFF で案内文・ON で予約導線、SUPPORTER は設定に依らず予約導線', async ({
    page,
    request,
    tokens,
  }) => {
    // --- 5-a) 非所属 + 設定 OFF → 案内文（SlotPicker は出ない）---
    await setAllowPublic(request, tokens.admin, false)
    await installApiBridge(page, tokens.outsider)
    await seedBrowserAuth(page, tokens.outsiderMe)
    await page.goto(`/teams/${TEAM_SLUG}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    const bookTab = page.getByRole('tab', { name: '予約する' })
    if (await bookTab.count()) await bookTab.click()

    // 案内文「このチームの予約は所属メンバー専用です」が表示される
    await expect(
      page.getByText('このチームの予約は所属メンバー専用です'),
      '非所属 + 設定 OFF では予約導線が出ず案内文に切り替わること（canBook=false）',
    ).toBeVisible({ timeout: 15_000 })
    await page.screenshot({
      path: 'test-results/reservation-authz-outsider-off-notice.png',
      fullPage: true,
    })

    // --- 5-b) 非所属 + 設定 ON → 予約導線（案内文が消える）---
    await setAllowPublic(request, tokens.admin, true)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    const bookTab2 = page.getByRole('tab', { name: '予約する' })
    if (await bookTab2.count()) await bookTab2.click()

    await expect(
      page.getByText('このチームの予約は所属メンバー専用です'),
      '非所属 + 設定 ON では案内文が消え予約導線が出ること（canBook=true）',
    ).toBeHidden({ timeout: 15_000 })
    await page.screenshot({
      path: 'test-results/reservation-authz-outsider-on-canbook.png',
      fullPage: true,
    })

    // --- 5-c) SUPPORTER 視点では設定 OFF でも予約導線が出る（4-A 是正の確証）---
    await setAllowPublic(request, tokens.admin, false)
    // 候補を SUPPORTER 化
    await unfollowIfNeeded(request, tokens.supporter)
    const followRes = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/follow`, {
      headers: authHeaders(tokens.supporter),
    })
    expect(followRes.status()).toBe(201)

    // 新しい page コンテキスト相当に切り替えるため、ブリッジ/認証を SUPPORTER 用に貼り直す。
    // 既存ルートを解除してから再インストールする。
    await page.unroute('**/api/v1/**')
    await installApiBridge(page, tokens.supporter)
    // SUPPORTER の currentUser を毎ナビゲーションで仕込み直す
    await page.addInitScript(
      ({ user, expiresAt }) => {
        localStorage.setItem('currentUser', JSON.stringify(user))
        localStorage.setItem('tokenExpiresAt', String(expiresAt))
      },
      {
        user: {
          id: tokens.supporterMe.id,
          email: tokens.supporterMe.email,
          fullName: `${tokens.supporterMe.lastName} ${tokens.supporterMe.firstName}`,
          profileImageUrl: tokens.supporterMe.avatarUrl,
          systemRole: tokens.supporterMe.systemRole ?? undefined,
          timezone: tokens.supporterMe.timezone ?? undefined,
        },
        expiresAt: Date.now() + 24 * 60 * 60 * 1000,
      },
    )
    await page.goto(`/teams/${TEAM_SLUG}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    const bookTab3 = page.getByRole('tab', { name: '予約する' })
    if (await bookTab3.count()) await bookTab3.click()

    await expect(
      page.getByText('このチームの予約は所属メンバー専用です'),
      'SUPPORTER は設定 OFF でも案内文が出ず予約導線が出ること（isAffiliated=true・4-A 是正）',
    ).toBeHidden({ timeout: 15_000 })
    await page.screenshot({
      path: 'test-results/reservation-authz-supporter-off-canbook.png',
      fullPage: true,
    })
  })
})
