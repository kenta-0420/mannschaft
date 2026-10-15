/**
 * 予約グリッド #2575（PR #2594）撤去後の実機回帰E2E。
 *
 * 背景:
 *   PR #2594 で GET /teams/{teamId}/reservation-slots/grid から
 *   クエリ `axis`(STAFF/LINE)・`staffUserIds` と、応答の `axis`・列の
 *   `staffUserId`/`staffName`/`lineIds` を撤去した（ライン軸固定）。
 *   BE のユニット/契約テストは緑だが「実ブラウザでマトリックスが描けるか」
 *   「メニューフィルターが生きているか」「撤去済みパラメータを送っても壊れないか」は
 *   実機でしか確かめられない。本 spec はその穴を塞ぐ回帰ガードである。
 *
 * 検証内容:
 *   1. グリッド（レンジ呼び）が列＝ライン軸で返ること（席A・席B の 2 列）
 *   2. menuId 指定で提供可能ライン（席A）だけに列が絞られ、meta が付くこと
 *   3. 撤去済みパラメータ（axis=STAFF・staffUserIds）を送っても 200 で無視され、
 *      応答が指定なしと完全一致すること。応答 JSON に撤去済みキーが現れないこと
 *   4. 実ブラウザでマトリックスが描画され（列ヘッダー＋セル）、
 *      未捕捉例外ゼロ・予約系APIの 4xx/5xx ゼロであること
 *
 * 実行前提（検証用 worktree）:
 *   BE: API_BASE_URL（既定 http://127.0.0.1:8082） / FE: BASE_URL（既定 http://127.0.0.1:3003）
 *   ブラウザ・Playwright は 127.0.0.1 系で統一する（localhost 混在は CORS 死）。
 */

import {
  test as base,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const BE = process.env.API_BASE_URL ?? process.env.BE_ORIGIN ?? 'http://127.0.0.1:8082'
const FE_ORIGIN = process.env.BASE_URL ?? 'http://127.0.0.1:3003'
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

interface MeProfile {
  id: number
  email: string
  lastName: string
  firstName: string
  avatarUrl: string | null
  systemRole: string | null
  timezone: string | null
}

interface GridCell {
  slotId: number | null
  startTime: string
  endTime: string
  state: string
}

interface GridColumn {
  lineId: number | null
  lineName: string | null
  cells: GridCell[]
}

interface GridDay {
  date: string
  columns: GridColumn[]
}

interface GridResponse {
  date: string | null
  columns: GridColumn[] | null
  days: GridDay[] | null
  meta: {
    menuId: string
    menuName: string
    requiredCellCount: number
    cellMinutes: number
  } | null
}

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

async function login(ctx: APIRequestContext, email: string, password: string): Promise<string> {
  const res = await ctx.post(`${BE}/api/v1/auth/login`, {
    headers: { 'Content-Type': 'application/json' },
    data: { email, password },
  })
  if (!res.ok()) throw new Error(`ログイン失敗(${email}): ${res.status()} ${await res.text()}`)
  return (await res.json()).data.accessToken as string
}

async function fetchMe(ctx: APIRequestContext, token: string): Promise<MeProfile> {
  const res = await ctx.get(`${BE}/api/v1/users/me`, { headers: { Authorization: `Bearer ${token}` } })
  if (!res.ok()) throw new Error(`/users/me 失敗: ${res.status()}`)
  return (await res.json()).data as MeProfile
}

async function createThrowawayTeam(ctx: APIRequestContext, token: string): Promise<string> {
  const res = await ctx.post(`${BE}/api/v1/teams`, {
    headers: authHeaders(token),
    data: { name: `RsvGrid2575_${Date.now()}` },
  })
  if (!res.ok()) throw new Error(`チーム作成失敗: ${res.status()} ${await res.text()}`)
  return ((await res.json()).data as { slug: string }).slug
}

async function enableReservationModule(ctx: APIRequestContext, token: string, slug: string): Promise<void> {
  const catalogRes = await ctx.get(`${BE}/api/v1/teams/${slug}/modules/catalog`, {
    headers: authHeaders(token),
  })
  if (!catalogRes.ok()) {
    throw new Error(`モジュールカタログ取得失敗: ${catalogRes.status()} ${await catalogRes.text()}`)
  }
  const catalog = (await catalogRes.json()).data as {
    modules: { moduleId: number; slug: string }[]
  }
  const mod = catalog.modules.find(m => m.slug === 'reservation')
  if (!mod) throw new Error('カタログに reservation モジュールが見つからない')
  const toggleRes = await ctx.patch(`${BE}/api/v1/teams/${slug}/modules/${mod.moduleId}/toggle`, {
    headers: authHeaders(token),
    data: { moduleId: mod.moduleId, enabled: true },
  })
  if (!toggleRes.ok()) {
    throw new Error(`予約モジュール有効化失敗: ${toggleRes.status()} ${await toggleRes.text()}`)
  }
}

async function createLine(
  ctx: APIRequestContext, token: string, slug: string, name: string, displayOrder: number,
): Promise<number> {
  const res = await ctx.post(`${BE}/api/v1/teams/${slug}/reservation-lines`, {
    headers: authHeaders(token),
    data: { name, displayOrder },
  })
  if (!res.ok()) throw new Error(`ライン作成失敗(${name}): ${res.status()} ${await res.text()}`)
  return ((await res.json()).data as { id: number }).id
}

async function createMenu(
  ctx: APIRequestContext, token: string, slug: string, name: string,
  durationMinutes: number, lineIds: number[],
): Promise<string> {
  const res = await ctx.post(`${BE}/api/v1/teams/${slug}/reservation-menus`, {
    headers: authHeaders(token),
    data: { name, durationMinutes, lineIds },
  })
  if (!res.ok()) throw new Error(`メニュー作成失敗(${name}): ${res.status()} ${await res.text()}`)
  return ((await res.json()).data as { id: string }).id
}

async function createSlot(
  ctx: APIRequestContext, token: string, slug: string,
  slotDate: string, startTime: string, endTime: string, lineId: number,
): Promise<number> {
  const res = await ctx.post(`${BE}/api/v1/teams/${slug}/reservation-slots`, {
    headers: authHeaders(token),
    data: { slotDate, startTime, endTime, lineId, capacity: 1 },
  })
  if (!res.ok()) {
    throw new Error(`枠作成失敗(${slotDate} ${startTime} line=${lineId}): ${res.status()} ${await res.text()}`)
  }
  return ((await res.json()).data as { id: number }).id
}

// === 日付ユーティリティ（Asia/Tokyo 前提） ===
const DAY_CODES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] as const

function calendarDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(Date.UTC(y!, m! - 1, d!))
}

function addDaysIso(baseIso: string, days: number): string {
  const dt = calendarDate(baseIso)
  dt.setUTCDate(dt.getUTCDate() + days)
  return `${dt.getUTCFullYear()}-${String(dt.getUTCMonth() + 1).padStart(2, '0')}-${String(dt.getUTCDate()).padStart(2, '0')}`
}

function todayIsoJst(): string {
  const jst = new Date(Date.now() + 9 * 60 * 60 * 1000)
  return `${jst.getUTCFullYear()}-${String(jst.getUTCMonth() + 1).padStart(2, '0')}-${String(jst.getUTCDate()).padStart(2, '0')}`
}

/** APIブリッジ（memory: feedback_e2e_wsl2_cors_apibridge）。ブラウザの API 呼びを検証用 BE へ中継する。 */
async function installApiBridge(page: Page, token: string): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const target = `${BE}${url.pathname}${url.search}`
    const pageOrigin = req.headers()['origin'] || FE_ORIGIN
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
    await route.fulfill({ status: relay.status(), headers: respHeaders, body: await relay.body() })
  })
}

async function seedBrowserAuth(page: Page, me: MeProfile): Promise<void> {
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
    { user: currentUser, expiresAt: Date.now() + 24 * 60 * 60 * 1000 },
  )
}

const test = base.extend<
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type -- test-scoped の追加 fixture は無い
  {},
  { tokens: { admin: string; adminMe: MeProfile } }
>({
  // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
  storageState: async ({}, use) => {
    await use(undefined)
  },
  tokens: [
    // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
    async ({}, use) => {
      const ctx = await playwrightRequest.newContext()
      const admin = await login(ctx, ADMIN_EMAIL, ADMIN_PASSWORD)
      const adminMe = await fetchMe(ctx, admin)
      await ctx.dispose()
      await use({ admin, adminMe })
    },
    { scope: 'worker' },
  ],
})

test.setTimeout(180_000)
test.describe.configure({ mode: 'serial' })

test.describe('RSV-GRID-2575: スタッフ軸撤去後のグリッド実機回帰', () => {
  let teamSlug = ''
  let lineAId = 0
  let lineBId = 0
  let menuId = ''
  const day = addDaysIso(todayIsoJst(), 1)

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    teamSlug = await createThrowawayTeam(ctx, tokens.admin)
    await enableReservationModule(ctx, tokens.admin, teamSlug)

    const hours = DAY_CODES.map(code => ({
      dayOfWeek: code, isOpen: true, openTime: '09:00:00', closeTime: '18:00:00',
    }))
    const bhRes = await ctx.put(`${BE}/api/v1/teams/${teamSlug}/reservation-settings/business-hours`, {
      headers: authHeaders(tokens.admin), data: { hours },
    })
    if (!bhRes.ok()) throw new Error(`営業時間PUT失敗: ${bhRes.status()} ${await bhRes.text()}`)

    lineAId = await createLine(ctx, tokens.admin, teamSlug, '席A', 1)
    lineBId = await createLine(ctx, tokens.admin, teamSlug, '席B', 2)
    // 「カット」は席Aのみで提供可能 → menuId 指定時に列が席Aだけへ絞られるはず
    menuId = await createMenu(ctx, tokens.admin, teamSlug, 'カット', 60, [lineAId])

    for (const [start, end] of [['10:00:00', '10:30:00'], ['10:30:00', '11:00:00']]) {
      await createSlot(ctx, tokens.admin, teamSlug, day, start!, end!, lineAId)
      await createSlot(ctx, tokens.admin, teamSlug, day, start!, end!, lineBId)
    }
    await ctx.dispose()
    console.log(`[SETUP] team=${teamSlug} day=${day} lineA=${lineAId} lineB=${lineBId} menu=${menuId}`)
  })

  test('AC-1: レンジ呼びのグリッドがライン軸2列（席A/席B）で返る', async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    const res = await ctx.get(
      `${BE}/api/v1/teams/${teamSlug}/reservation-slots/grid?from=${day}&to=${day}`,
      { headers: authHeaders(tokens.admin) },
    )
    expect(res.status(), 'グリッド取得は200').toBe(200)
    const grid = (await res.json()).data as GridResponse
    await ctx.dispose()

    expect(grid.days, 'レンジ呼びは days[] を返す').not.toBeNull()
    expect(grid.days!).toHaveLength(1)
    const columns = grid.days![0]!.columns
    const names = columns.map(c => c.lineName)
    console.log(`[AC-1] columns=${JSON.stringify(names)} cells=${columns.map(c => c.cells.length).join(',')}`)
    // 列 = ライン列（席A・席B）＋末尾の共通列（lineId=null・共通枠を集約する仕様列）。
    // 共通枠は作っていないのでセル0件で末尾に付く（ReservationGridResponse.GridColumnDto の契約）。
    expect(names, '席A・席B＋共通列（lineId=null）の順で返ること').toEqual(['席A', '席B', null])
    const lineColumns = columns.filter(c => c.lineId !== null)
    expect(lineColumns, 'ライン列は2本').toHaveLength(2)
    for (const col of lineColumns) {
      expect(col.cells.length, '各ライン列に枠セルが並ぶこと').toBeGreaterThanOrEqual(2)
    }
  })

  test('AC-2: menuId 指定で提供可能ライン（席A）だけに列が絞られ meta が付く', async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    const res = await ctx.get(
      `${BE}/api/v1/teams/${teamSlug}/reservation-slots/grid?from=${day}&to=${day}&menuId=${menuId}`,
      { headers: authHeaders(tokens.admin) },
    )
    expect(res.status(), 'menuId 付きグリッドは200').toBe(200)
    const grid = (await res.json()).data as GridResponse
    await ctx.dispose()

    const names = grid.days![0]!.columns.map(c => c.lineName)
    console.log(`[AC-2] columns=${JSON.stringify(names)} meta=${JSON.stringify(grid.meta)}`)
    // 席B は「カット」を提供しないため列から落ちる（共通列は仕様上そのまま残る）。
    expect(names.filter(n => n !== null), 'カットを提供する席Aのみに絞られること').toEqual(['席A'])
    expect(names, '席Bの列は絞り込みで消えること').not.toContain('席B')
    expect(grid.meta, 'menuId 指定時は meta が付く').not.toBeNull()
    expect(grid.meta!.menuName).toBe('カット')
    // 60分メニュー = 30分セル×2
    expect(grid.meta!.requiredCellCount).toBe(2)
    expect(grid.meta!.cellMinutes).toBe(30)
  })

  test('AC-3: 撤去済みパラメータ(axis=STAFF / staffUserIds)は400にならず無視される', async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    const baseUrl = `${BE}/api/v1/teams/${teamSlug}/reservation-slots/grid?from=${day}&to=${day}`
    const plain = await ctx.get(baseUrl, { headers: authHeaders(tokens.admin) })
    const withRemoved = await ctx.get(
      `${baseUrl}&axis=STAFF&staffUserIds=${tokens.adminMe.id},999999`,
      { headers: authHeaders(tokens.admin) },
    )
    const plainBody = await plain.text()
    const removedBody = await withRemoved.text()
    await ctx.dispose()

    console.log(`[AC-3] plain=${plain.status()} withRemoved=${withRemoved.status()}`)
    expect(plain.status()).toBe(200)
    expect(withRemoved.status(), '撤去済みパラメータでも400にならず200であること').toBe(200)
    expect(removedBody, '撤去済みパラメータは応答に一切影響しない（完全無視）').toBe(plainBody)
    // 応答スキーマから撤去済みフィールドが消えていること（BE 側の構造的担保）
    expect(removedBody).not.toContain('"axis"')
    expect(removedBody).not.toContain('"staffUserId"')
    expect(removedBody).not.toContain('"staffName"')
    expect(removedBody).not.toContain('"lineIds"')

    // axis=LINE（旧FEが送っていた値）も同様に無害であること
    const ctx2 = await playwrightRequest.newContext()
    const withLine = await ctx2.get(`${baseUrl}&axis=LINE`, { headers: authHeaders(tokens.admin) })
    const lineBody = await withLine.text()
    await ctx2.dispose()
    expect(withLine.status(), 'axis=LINE でも200').toBe(200)
    expect(lineBody).toBe(plainBody)
  })

  test('AC-4: 実ブラウザでマトリックスが描画され未捕捉例外・予約API 4xx/5xx がゼロ', async ({
    page, tokens,
  }) => {
    const pageErrors: string[] = []
    const consoleErrors: string[] = []
    const badResponses: string[] = []
    page.on('pageerror', err => pageErrors.push(err.message))
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })
    page.on('response', (res) => {
      const url = res.url()
      if (url.includes('/api/v1/teams/') && res.status() >= 400) {
        badResponses.push(`${res.status()} ${url}`)
      }
    })

    await installApiBridge(page, tokens.admin)
    await seedBrowserAuth(page, tokens.adminMe)
    await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    // マトリックス（SlotMatrixPicker）本体の存在確認
    await expect(page.getByText('メニューで絞り込む'), 'メニューフィルターが描画される').toBeVisible({
      timeout: 30_000,
    })
    await expect(
      page.getByRole('button', { name: /^週 /, exact: false }),
      '週ナビが描画される',
    ).toBeVisible({ timeout: 15_000 })

    // 列ヘッダー（ライン名）が両方出ること = 列軸がライン固定で生きている
    await expect(page.getByText('席A', { exact: true }).first()).toBeVisible({ timeout: 20_000 })
    await expect(page.getByText('席B', { exact: true }).first()).toBeVisible({ timeout: 20_000 })

    // セル（空き）が並ぶこと。白画面・列ゼロの検出。
    const availableCells = page.getByRole('button', { name: /空き$/ })
    await expect(availableCells.first(), '空きセルが1つ以上描画される').toBeVisible({ timeout: 20_000 })
    const cellCount = await availableCells.count()
    console.log(`[AC-4] 空きセル数=${cellCount}`)
    expect(cellCount, '席A・席Bの2列×2枠=4セル以上が描画されること').toBeGreaterThanOrEqual(4)

    // 「枠ゼロ」空状態が出ていないこと（列ゼロの静かな退行を検出）
    await expect(page.getByTestId('matrix-no-slots-empty')).toHaveCount(0)

    await page.screenshot({ path: 'test-results/rsv-grid-2575-matrix.png', fullPage: true })

    console.log(`[AC-4] pageerror=${JSON.stringify(pageErrors)}`)
    console.log(`[AC-4] console.error=${JSON.stringify(consoleErrors)}`)
    console.log(`[AC-4] 4xx/5xx=${JSON.stringify(badResponses)}`)
    expect(pageErrors, '未捕捉例外はゼロ').toEqual([])
    expect(badResponses, 'チーム配下APIの4xx/5xxはゼロ').toEqual([])

    // テスト終了時に飛行中の route コールバックが context 破棄と競合して落ちるため、
    // 先に route を解除する（Playwright 公式の推奨手順）。
    await page.unrouteAll({ behavior: 'ignoreErrors' })
  })
})
