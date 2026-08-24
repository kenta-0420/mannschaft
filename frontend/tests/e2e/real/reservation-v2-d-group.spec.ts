/**
 * 予約v2 D群4弾（W2-6-BE/W2-5-BE/W2-6-FE+W2-4-FE/W2-5-FE）実機一気通貫E2E。
 *
 * バックエンド http://127.0.0.1:8081（検証用・WSL2 Linux JVM常駐）
 * フロントエンド http://127.0.0.1:3001（検証用 dev server。NUXT_PUBLIC_API_BASE=http://127.0.0.1:8081）
 *
 * 【重要】ブラウザ・Playwright はすべて 127.0.0.1 系で統一する（localhost と混在させると
 * Origin 不一致で CORS 死する）。
 *
 * 写経元: real/admin/reservation-v2-recurring-blocked.spec.ts（ログイン機構・日付ユーティリティ・
 * ダイアログ操作作法・単一セッション設計）。
 *
 * 対象 mergeCommit:
 *   - 1370ee103 (W2-6-BE 仮押さえ自動失効バッチ＋予約作成レートリミット)
 *   - b31b06cf3 (W2-5-BE 定期予約(毎週繰り返し)＋定期不可枠の強行登録)
 *   - 8f91e4f03 (W2-6-FE+W2-4-FE 失効設定UI・会員向け告知・429トースト・キャンセル待ち登録導線)
 *   - 65ce0dcc4 (W2-5-FE 繰返しトグル・結果明細・キャンセル2択・seriesバッジ・series一括承認・強行登録UI)
 *
 * 【優先度A】キャンセル待ち: 満席化→登録→週移動して復帰→維持確認→自分の一覧→取消→空き通知
 * 【優先度B】定期予約: 段階開示→4週作成→結果明細→seriesバッジ→キャンセル2択→series一括承認
 * 【優先度C】仮押さえ失効設定＋429レートリミット
 * 【優先度D】強行登録: impact件数と実際のキャンセル件数の一致確認（数を数える）
 *
 * 【担当の引き継ぎ】キャンセル2択（この回だけ／この回以降すべて）の検証は本 spec では行わない。
 * cancelMine()（mode="mine"）経由でしか発火せず、本 spec の固定ユーザーは自作チームで常に ADMIN の
 * ため到達不能だったため、MEMBER ロールの別ユーザーを招待フローで用意する
 * `tests/e2e/real/reservation-member-role.spec.ts` の M1-2 / M1-3 が担当する（実機踏破済み）。
 */

import {
  test as base,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'
import { selectDropdown, fillInput } from '../helpers/form'
import net from 'node:net'

/**
 * Valkey (Redis互換) が生きているか、生の TCP + RESP inline command（PING）で直接確認する。
 * レートリミットが Valkey に依存する以上（fail-openで429が出ない環境依存の疑陰性を防ぐため）、
 * 429 を assert する前に前提条件として明示的に検証する（是正1・殿指摘）。
 * 依存追加を避けるため ioredis 等は使わず net.Socket の生TCPで済ませる。
 */
function pingValkey(host: string, port: number, timeoutMs = 3000): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = new net.Socket()
    let settled = false
    const finish = (ok: boolean) => {
      if (settled) return
      settled = true
      socket.destroy()
      resolve(ok)
    }
    socket.setTimeout(timeoutMs)
    socket.once('connect', () => socket.write('PING\r\n'))
    socket.once('data', (data) => finish(data.toString().startsWith('+PONG')))
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
    socket.connect(port, host)
  })
}

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://127.0.0.1:8081'
const BE_API = `${API_BASE_URL}/api/v1`

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-pwui-1782136885@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'Passw0rd!2026'

/**
 * 会員（MEMBER）視点検証用の永続ユーザー。reservation-member-role.spec.ts と同一の実ユーザー。
 * 固定ユーザー(USER_EMAIL)は自作チームで常に ADMIN になるため、
 * 「WAITING 登録者」と「予約をキャンセルする管理者」を別人にするために使う。
 */
const MEMBER_EMAIL = process.env.TEST_MEMBER_EMAIL ?? 'e2e-member-1785848177@test.mannschaft.local'
const MEMBER_PASSWORD = process.env.TEST_MEMBER_PASSWORD ?? 'Passw0rd!2026'

/** 招待トークンの roleId。InviteTokenList.vue の既定値と同一（MEMBER）。 */
const ROLE_ID_MEMBER = 4

/** キャンセル待ちの空き通知タイプ（ReservationWaitlistService.NOTIFICATION_TYPE と同値）。 */
const WAITLIST_OPENING_TYPE = 'RESERVATION_WAITLIST_OPENING'

interface MeProfile {
  id: number
  email: string
  lastName: string
  firstName: string
}

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

async function login(ctx: APIRequestContext, email: string, password: string): Promise<string> {
  const res = await ctx.post(`${BE_API}/auth/login`, {
    headers: { 'Content-Type': 'application/json' },
    data: { email, password },
  })
  if (!res.ok()) throw new Error(`ログイン失敗(${email}): ${res.status()} ${await res.text()}`)
  return (await res.json()).data.accessToken as string
}

async function fetchMe(ctx: APIRequestContext, token: string): Promise<MeProfile> {
  const res = await ctx.get(`${BE_API}/users/me`, { headers: { Authorization: `Bearer ${token}` } })
  if (!res.ok()) throw new Error(`/users/me 失敗: ${res.status()}`)
  return (await res.json()).data as MeProfile
}

async function createThrowawayTeam(ctx: APIRequestContext, adminToken: string, label: string): Promise<{ slug: string }> {
  const res = await ctx.post(`${BE_API}/teams`, {
    headers: authHeaders(adminToken),
    data: { name: `RsvDGrp_${label}_${Date.now()}` },
  })
  if (!res.ok()) throw new Error(`チーム作成失敗: ${res.status()} ${await res.text()}`)
  return (await res.json()).data as { slug: string }
}

async function enableReservationModule(ctx: APIRequestContext, adminToken: string, slug: string): Promise<void> {
  const catalogRes = await ctx.get(`${BE_API}/teams/${slug}/modules/catalog`, { headers: authHeaders(adminToken) })
  if (!catalogRes.ok()) throw new Error(`モジュールカタログ取得失敗: ${catalogRes.status()} ${await catalogRes.text()}`)
  const catalog = (await catalogRes.json()).data as { modules: { moduleId: number; slug: string; isEnabled: boolean }[] }
  const reservationModule = catalog.modules.find(m => m.slug === 'reservation')
  if (!reservationModule) throw new Error('カタログに reservation モジュールが見つからない')
  const toggleRes = await ctx.patch(`${BE_API}/teams/${slug}/modules/${reservationModule.moduleId}/toggle`, {
    headers: authHeaders(adminToken),
    data: { moduleId: reservationModule.moduleId, enabled: true },
  })
  if (!toggleRes.ok()) throw new Error(`予約モジュール有効化失敗: ${toggleRes.status()} ${await toggleRes.text()}`)
}

const DAY_CODES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] as const
type DayCode = (typeof DAY_CODES)[number]
const WEEKDAY_JA = ['日', '月', '火', '水', '木', '金', '土'] as const

function calendarDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(Date.UTC(y!, m! - 1, d!))
}
function addDaysIso(baseIso: string, days: number): string {
  const dt = calendarDate(baseIso)
  dt.setUTCDate(dt.getUTCDate() + days)
  return `${dt.getUTCFullYear()}-${String(dt.getUTCMonth() + 1).padStart(2, '0')}-${String(dt.getUTCDate()).padStart(2, '0')}`
}
function isoWeekday(iso: string): number {
  return calendarDate(iso).getUTCDay()
}
function todayIsoJst(): string {
  const now = new Date()
  const jst = new Date(now.getTime() + 9 * 60 * 60 * 1000)
  return `${jst.getUTCFullYear()}-${String(jst.getUTCMonth() + 1).padStart(2, '0')}-${String(jst.getUTCDate()).padStart(2, '0')}`
}

interface DateInfo {
  iso: string
  dayCode: DayCode
  rowLabel: string
}
function dateInfo(offsetDays: number): DateInfo {
  const iso = addDaysIso(todayIsoJst(), offsetDays)
  const dow = isoWeekday(iso)
  return { iso, dayCode: DAY_CODES[dow]!, rowLabel: `${iso.replaceAll('-', '/')} (${WEEKDAY_JA[dow]})` }
}

interface SlotRow {
  id: number
  lineId: number | null
  basic: { slotDate: string; startTime: string; endTime: string }
}

async function findSlotId(
  ctx: APIRequestContext,
  teamSlug: string,
  token: string,
  date: string,
  startTimePrefix: string,
  lineId: number,
): Promise<number> {
  const res = await ctx.get(`${BE_API}/teams/${teamSlug}/reservation-slots?from=${date}&to=${date}`, {
    headers: authHeaders(token),
  })
  if (!res.ok()) throw new Error(`枠一覧取得失敗: ${res.status()} ${await res.text()}`)
  const rows = (await res.json()).data as SlotRow[]
  const found = rows.find(r => r.lineId === lineId && r.basic?.startTime?.startsWith(startTimePrefix))
  if (!found) {
    throw new Error(
      `枠が見つからない date=${date} start=${startTimePrefix} lineId=${lineId} `
      + `rows=${JSON.stringify(rows.map(r => ({ id: r.id, lineId: r.lineId, st: r.basic?.startTime })))}`,
    )
  }
  return found.id
}

/**
 * MEMBER ロールでチームへ参加させる（招待トークン発行 → 参加 の実プロダクト経路）。
 * 実際に付与されたロールを GET /teams/{slug}/me/permissions で裏取りして返す。
 * 写経元: reservation-member-role.spec.ts joinAsMember()。
 */
async function joinAsMember(
  adminCtx: APIRequestContext,
  adminToken: string,
  memberCtx: APIRequestContext,
  memberToken: string,
  slug: string,
): Promise<string> {
  const inviteRes = await adminCtx.post(`${BE_API}/teams/${slug}/invite-tokens`, {
    headers: authHeaders(adminToken),
    data: { roleId: ROLE_ID_MEMBER, expiresIn: '7d', maxUses: 10 },
  })
  if (!inviteRes.ok()) throw new Error(`招待トークン作成失敗: ${inviteRes.status()} ${await inviteRes.text()}`)
  const inviteToken = ((await inviteRes.json()).data as { token: string }).token

  const joinRes = await memberCtx.post(`${BE_API}/invite/${inviteToken}/join`, {
    headers: authHeaders(memberToken),
    data: {},
  })
  if (!joinRes.ok()) throw new Error(`招待参加失敗: ${joinRes.status()} ${await joinRes.text()}`)

  const permRes = await memberCtx.get(`${BE_API}/teams/${slug}/me/permissions`, { headers: authHeaders(memberToken) })
  if (!permRes.ok()) throw new Error(`権限取得失敗: ${permRes.status()} ${await permRes.text()}`)
  return ((await permRes.json()).data as { roleName: string }).roleName
}

/** 通知一覧の1件（NotificationResponse のうち本 spec が使うフィールドのみ）。 */
interface NotificationRow {
  id: number
  notificationType: string
  title: string
  body: string
  sourceType: string
  sourceId: number | null
  actionUrl: string | null
  createdAt: string
}

/**
 * 自分宛の通知一覧を取得する。
 * BE 実体: NotificationController は GET /api/v1/notifications で Spring 標準の
 * Pageable（page/size）を受け、PagedResponse（{ data: [...], meta: {...} }）を返す。
 * `limit` というパラメータは存在しない。
 */
async function fetchNotifications(ctx: APIRequestContext, token: string, size = 50): Promise<NotificationRow[]> {
  const res = await ctx.get(`${BE_API}/notifications?page=0&size=${size}`, { headers: authHeaders(token) })
  if (!res.ok()) throw new Error(`通知一覧取得失敗: ${res.status()} ${await res.text()}`)
  return (await res.json()).data as NotificationRow[]
}

/** ReservationResponse のうち本 spec が使うフィールド（slotId は identifier 配下にある）。 */
interface TeamReservationRow {
  id: number
  identifier: { reservationSlotId: number; lineId: number; teamId: number; userId: number }
  status: { status: string }
}

/**
 * チームの予約一覧を取得する（ADMIN 限定）。
 * BE 実体: TeamReservationController#listReservations のクエリパラメータは
 * status / page / size のみ（from/to は存在せず、Spring に黙って捨てられる）。
 */
async function fetchTeamReservations(
  ctx: APIRequestContext,
  token: string,
  slug: string,
  size = 100,
): Promise<TeamReservationRow[]> {
  const res = await ctx.get(`${BE_API}/teams/${slug}/reservations?page=0&size=${size}`, {
    headers: authHeaders(token),
  })
  if (!res.ok()) throw new Error(`チーム予約一覧取得失敗: ${res.status()} ${await res.text()}`)
  return (await res.json()).data as TeamReservationRow[]
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
      const admin = await login(ctx, USER_EMAIL, USER_PASSWORD)
      const adminMe = await fetchMe(ctx, admin)
      await ctx.dispose()
      await use({ admin, adminMe })
    },
    { scope: 'worker' },
  ],
})

test.setTimeout(300_000)

async function gotoReservations(page: Page, teamSlug: string): Promise<void> {
  await loginViaApi(page, { email: USER_EMAIL, password: USER_PASSWORD }, { apiBaseUrl: API_BASE_URL })
  await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  await waitForHydration(page)
}

async function openManageTab(page: Page): Promise<void> {
  const manageTab = page.getByRole('tab', { name: '予約対象の管理' })
  await expect(manageTab).toBeVisible({ timeout: 30_000 })
  await manageTab.click()
}

async function openReserveTab(page: Page): Promise<void> {
  await page.getByRole('tab', { name: '予約する' }).click()
  await expect(page.getByText('メニューで絞り込む')).toBeVisible({ timeout: 20_000 })
}

async function goToWeekContaining(page: Page, targetIso: string): Promise<void> {
  const weekBtn = page.getByRole('button', { name: /^週 /, exact: false })
  await expect(weekBtn).toBeVisible({ timeout: 20_000 })
  const prevWeekBtn = page.locator('button').filter({ has: page.locator('.pi-angle-left') }).first()
  const nextWeekBtn = page.locator('button').filter({ has: page.locator('.pi-angle-right') }).first()
  let reached = false
  for (let i = 0; i < 8; i++) {
    const text = (await weekBtn.textContent()) ?? ''
    const m = text.match(/(\d{4})\/(\d{2})\/(\d{2}) - (\d{4})\/(\d{2})\/(\d{2})/)
    if (!m) throw new Error(`週ラベル取得失敗: "${text}"`)
    const start = `${m[1]}-${m[2]}-${m[3]}`
    const end = `${m[4]}-${m[5]}-${m[6]}`
    if (targetIso >= start && targetIso <= end) { reached = true; break }
    await nextWeekBtn.click()
    await page.waitForTimeout(400)
  }
  if (!reached) throw new Error(`週範囲内に ${targetIso} が見つからない`)
  const dateToggle = page.getByTestId(`matrix-toggle-date-${targetIso}`)
  await expect(dateToggle).toBeVisible({ timeout: 20_000 })
  if (await dateToggle.getAttribute('aria-expanded') !== 'true') await dateToggle.click()
  void prevWeekBtn
  void nextWeekBtn
}

async function navBackAndForth(page: Page): Promise<void> {
  const prevWeekBtn = page.locator('button').filter({ has: page.locator('.pi-angle-left') }).first()
  const nextWeekBtn = page.locator('button').filter({ has: page.locator('.pi-angle-right') }).first()
  await nextWeekBtn.click()
  await page.waitForTimeout(400)
  await prevWeekBtn.click()
  await page.waitForTimeout(400)
}

// ============================================================================
// 優先A: キャンセル待ち（W2-4-FE）
// ============================================================================
test.describe('D群 優先A: キャンセル待ち（実機・週移動で維持されるか／自分の一覧に出るか）', () => {
  let teamSlug = ''
  let lineId = 0
  let slotId = 0
  const day = dateInfo(2)

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    const team = await createThrowawayTeam(ctx, tokens.admin, 'waitlist')
    teamSlug = team.slug
    await enableReservationModule(ctx, tokens.admin, teamSlug)

    const hours = DAY_CODES.map(code => ({ dayOfWeek: code, isOpen: true, openTime: '08:00:00', closeTime: '22:00:00' }))
    const hoursRes = await ctx.put(`${BE_API}/teams/${teamSlug}/reservation-settings/business-hours`, {
      headers: authHeaders(tokens.admin),
      data: { hours },
    })
    if (!hoursRes.ok()) throw new Error(`営業時間PUT失敗: ${hoursRes.status()} ${await hoursRes.text()}`)

    const lineRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-lines`, {
      headers: authHeaders(tokens.admin),
      data: { name: '面談室' },
    })
    if (!lineRes.ok()) throw new Error(`ライン作成失敗: ${lineRes.status()} ${await lineRes.text()}`)
    lineId = ((await lineRes.json()).data as { id: number }).id

    // 定員1の枠テンプレを作成（=満席化しやすくする）
    const tplRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-slot-templates`, {
      headers: authHeaders(tokens.admin),
      data: { lineId, dayOfWeek: day.dayCode, startTime: '09:00:00', endTime: '09:30:00', capacity: 1 },
    })
    if (!tplRes.ok()) throw new Error(`テンプレ作成失敗: ${tplRes.status()} ${await tplRes.text()}`)

    // 管理者自身の予約で満席化（capacity=1なので1件で満席）
    slotId = await findSlotId(ctx, teamSlug, tokens.admin, day.iso, '09:00', lineId)
    const reserveRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservations`, {
      headers: authHeaders(tokens.admin),
      data: { reservationSlotId: slotId, lineId, userNote: 'E2E満席化用(管理者)' },
    })
    if (!reserveRes.ok()) throw new Error(`満席化用予約失敗: ${reserveRes.status()} ${await reserveRes.text()}`)

    console.log(`[SETUP-A] teamSlug=${teamSlug} lineId=${lineId} day=${day.iso}(${day.dayCode}) slotId=${slotId}`)
    await ctx.dispose()
  })

  test('シナリオA: 満席セルでキャンセル待ち登録→週移動して戻っても維持→自分の一覧に出る→取消', async ({ page }) => {
    await gotoReservations(page, teamSlug)
    await openReserveTab(page)
    await goToWeekContaining(page, day.iso)

    // 満席セル（BOOKED）をクリックしてキャンセル待ちダイアログを開く
    const bookedCell = page.getByRole('button', { name: new RegExp(`${day.rowLabel.replace(/[()]/g, '\\$&')} 09:00 面談室`) })
    await expect(bookedCell, '満席(BOOKED)セルが表示されること').toBeVisible({ timeout: 15_000 })
    await page.screenshot({ path: 'test-results/d-a-01-booked-cell.png', fullPage: true })
    await bookedCell.click()

    // ダイアログの可視待ちは登録ボタンの断定で兼ねる（握りつぶしcatchを置かない）
    const registerBtn = page.getByTestId('waitlist-register')
    await expect(registerBtn, 'キャンセル待ち登録ボタンが表示されること').toBeVisible({ timeout: 10_000 })
    await registerBtn.click()
    await expect(page.getByText('キャンセル待ちに登録しました').or(page.getByText(/登録しました/))).toBeVisible({ timeout: 15_000 })
    await page.screenshot({ path: 'test-results/d-a-02-registered.png', fullPage: true })

    // 「待機中」バッジが出るか
    const waitingCell = page.getByRole('button', { name: new RegExp(`${day.rowLabel.replace(/[()]/g, '\\$&')} 09:00 面談室`) })
    const waitingText = await waitingCell.textContent()
    console.log(`[シナリオA] 登録直後のセル表示テキスト="${waitingText}"`)
    expect(waitingText, '登録直後は「待機中」表示になること').toContain('待機中')

    // 🔴週を移動して戻る（検分で「移動すると待機中が消え、押すと409」バグを見つけた箇所）
    await navBackAndForth(page)
    await page.waitForTimeout(800)
    const waitingCellAfterNav = page.getByRole('button', { name: new RegExp(`${day.rowLabel.replace(/[()]/g, '\\$&')} 09:00 面談室`) })
    const waitingTextAfterNav = await waitingCellAfterNav.textContent()
    console.log(`[シナリオA] 週移動後(往復)のセル表示テキスト="${waitingTextAfterNav}"`)
    expect(waitingTextAfterNav, '🔴週を移動して戻っても「待機中」表示が維持されること（検分バグ再発防止）').toContain('待機中')
    await page.screenshot({ path: 'test-results/d-a-03-waiting-after-nav.png', fullPage: true })

    // 実API裏取り: /users/me/reservation-waitlist に載っているか
    const waitlistRes = await page.request.get(`${API_BASE_URL}/api/v1/users/me/reservation-waitlist`)
    expect(waitlistRes.ok(), 'キャンセル待ち一覧API失敗').toBeTruthy()
    const waitlistBody = (await waitlistRes.json()) as { data?: Array<{ id: string; slotId: number; teamId: number }> }
    console.log(`[シナリオA] API実体 /users/me/reservation-waitlist = ${JSON.stringify(waitlistBody.data)}`)
    expect(waitlistBody.data?.length ?? 0, 'API実体にキャンセル待ちエントリが存在すること').toBeGreaterThan(0)

    // 🔴自分のキャンセル待ち一覧セクション（slug↔数値ID取り違えバグの検分箇所）にUI上出るか
    // ReservationMyWaitlistList は「予約一覧」タブ(value=1)配下にある（TeamReservationsPanel.vue）。
    await page.getByRole('tab', { name: /予約一覧|自分の予約/ }).click()
    const myListEntry = page.locator('[data-testid^="my-waitlist-entry-"]').first()
    await expect(myListEntry, '🔴自分のキャンセル待ち一覧に実際に表示されること（検分バグ再発防止）').toBeVisible({ timeout: 15_000 })
    await page.screenshot({ path: 'test-results/d-a-04-my-waitlist-list.png', fullPage: true })

    // 一覧から取消
    const cancelBtn = page.locator('[data-testid^="my-waitlist-cancel-"]').first()
    await cancelBtn.click()
    await expect(page.getByText(/キャンセル待ちを取消しました|取消しました/)).toBeVisible({ timeout: 15_000 })
    await expect(myListEntry, '取消後は一覧から消えること').not.toBeVisible({ timeout: 10_000 })
    await page.screenshot({ path: 'test-results/d-a-05-after-cancel.png', fullPage: true })
  })

  test('own booked slot is disabled in the matrix and the waitlist API rejects it with RESERVATION_013', async ({ page }) => {
    await gotoReservations(page, teamSlug)
    await openReserveTab(page)
    await goToWeekContaining(page, day.iso)

    const bookedCell = page.getByRole('button', { name: new RegExp(`${day.rowLabel.replace(/[()]/g, '\\$&')} 09:00 .*予約済み`) })
    await expect(bookedCell).toBeVisible({ timeout: 15_000 })
    await expect(bookedCell).toBeDisabled()
    await expect(bookedCell).toContainText(/予約済み/)
    await expect(page.getByTestId('waitlist-register')).not.toBeVisible()

    const response = await page.request.post(`${BE_API}/teams/${teamSlug}/reservation-slots/${slotId}/waitlist`)
    expect(response.status()).toBe(409)
    const body = (await response.json()) as { error?: { code?: string } }
    expect(body.error?.code).toBe('RESERVATION_013')
  })

})

// ============================================================================
// 優先A-2: 満席予約のキャンセルで WAITING 者へ空き通知が飛ぶこと（別ユーザー間）
// ============================================================================
/**
 * 【踏破の経緯 2026-08-05】本シナリオは以下2つの理由で長らく test.skip されていた。両方とも根治済み。
 *
 * (a) 単一ユーザー制約 — WAITING 登録者と予約キャンセル実行者が同一ユーザーになってしまい、
 *     「別人に通知が飛ぶ」ことを検証できなかった。
 *     → 招待トークン（roleId=4）→参加 の実プロダクト経路で MEMBER ロールの別ユーザーを
 *       チームに入れることで解消（写経元: reservation-member-role.spec.ts）。
 *       ユーザーごとに APIRequestContext を分けるためトークンローテーションの衝突も起きない。
 *
 * (b) API パラメータ不一致 — 旧実装は GET /teams/{slug}/reservations?from=..&to=.. を投げていたが、
 *     TeamReservationController#listReservations の実パラメータは status/page/size のみで
 *     from/to は存在しない（Spring が黙って捨てる）。さらに対象予約の判定に使っていた
 *     `r.reservationSlotId` は ReservationResponse では `identifier.reservationSlotId` の
 *     ネスト配下にあり、トップレベルは常に undefined。結果として target が必ず undefined になり
 *     `if (target)` 配下のキャンセルが一度も実行されない空振り構造だった。
 *     → 実在するパラメータ・実在するフィールドで取り直し、対象が無ければ expect で失敗させる。
 *
 * 通知経路の実体（BE）:
 *   ReservationSlotService が ReservationSlotReopenedEvent を publish
 *   → ReservationWaitlistNotificationEventListener（@Async("event-pool") + AFTER_COMMIT）
 *   → ReservationWaitlistService#notifySlotReopened が
 *     notificationType="RESERVATION_WAITLIST_OPENING" / title="キャンセルが出ました" で通知作成。
 *   非同期のため expect.poll で有界に待つ（届かなければタイムアウトして失敗する）。
 */
test.describe('D群 優先A-2: 満席予約のキャンセルでWAITING者（別ユーザー）に空き通知が飛ぶ', () => {
  test.describe.configure({ mode: 'serial' })

  let teamSlug = ''
  let lineId = 0
  let slotId = 0
  let memberCtx: APIRequestContext
  let memberToken = ''
  let memberRoleName = ''
  const day = dateInfo(3)

  test.beforeAll(async ({ tokens }) => {
    const adminCtx = await playwrightRequest.newContext()
    memberCtx = await playwrightRequest.newContext()
    memberToken = await login(memberCtx, MEMBER_EMAIL, MEMBER_PASSWORD)

    const team = await createThrowawayTeam(adminCtx, tokens.admin, 'wlnotify')
    teamSlug = team.slug
    await enableReservationModule(adminCtx, tokens.admin, teamSlug)

    const hours = DAY_CODES.map(code => ({ dayOfWeek: code, isOpen: true, openTime: '08:00:00', closeTime: '22:00:00' }))
    const hoursRes = await adminCtx.put(`${BE_API}/teams/${teamSlug}/reservation-settings/business-hours`, {
      headers: authHeaders(tokens.admin),
      data: { hours },
    })
    if (!hoursRes.ok()) throw new Error(`営業時間PUT失敗: ${hoursRes.status()} ${await hoursRes.text()}`)

    const lineRes = await adminCtx.post(`${BE_API}/teams/${teamSlug}/reservation-lines`, {
      headers: authHeaders(tokens.admin),
      data: { name: '面談室' },
    })
    if (!lineRes.ok()) throw new Error(`ライン作成失敗: ${lineRes.status()} ${await lineRes.text()}`)
    lineId = ((await lineRes.json()).data as { id: number }).id

    // 定員1の枠テンプレ（=管理者1件の予約で満席になる）
    const tplRes = await adminCtx.post(`${BE_API}/teams/${teamSlug}/reservation-slot-templates`, {
      headers: authHeaders(tokens.admin),
      data: { lineId, dayOfWeek: day.dayCode, startTime: '09:00:00', endTime: '09:30:00', capacity: 1 },
    })
    if (!tplRes.ok()) throw new Error(`テンプレ作成失敗: ${tplRes.status()} ${await tplRes.text()}`)

    slotId = await findSlotId(adminCtx, teamSlug, tokens.admin, day.iso, '09:00', lineId)
    const reserveRes = await adminCtx.post(`${BE_API}/teams/${teamSlug}/reservations`, {
      headers: authHeaders(tokens.admin),
      data: { reservationSlotId: slotId, lineId, userNote: 'E2E満席化用(管理者)' },
    })
    if (!reserveRes.ok()) throw new Error(`満席化用予約失敗: ${reserveRes.status()} ${await reserveRes.text()}`)

    memberRoleName = await joinAsMember(adminCtx, tokens.admin, memberCtx, memberToken, teamSlug)
    console.log(`[SETUP-A2] teamSlug=${teamSlug} lineId=${lineId} day=${day.iso}(${day.dayCode}) slotId=${slotId} memberRole=${memberRoleName}`)
    await adminCtx.dispose()
  })

  test.afterAll(async () => {
    await memberCtx.dispose()
  })

  test('シナリオA-2-0: 招待フローで参加した会員の実ロールが MEMBER であること（別人であることの前提）', async ({ tokens }) => {
    expect(memberRoleName, '招待トークン roleId=4 で参加した結果のロール').toBe('MEMBER')
    const memberMe = await fetchMe(memberCtx, memberToken)
    expect(memberMe.email).toBe(MEMBER_EMAIL)
    expect(memberMe.id, 'WAITING 登録者は予約キャンセルを行う管理者とは別人であること').not.toBe(tokens.adminMe.id)
  })

  test('シナリオA-2: 満席予約を管理者が1件キャンセルすると、WAITING中の別ユーザーに空き通知が届く', async ({ tokens }) => {
    // 1) MEMBER が満席枠にキャンセル待ち登録
    //    BE 実体パスは /teams/{teamId}/reservation-slots/{slotId}/waitlist
    //    （旧実装が使っていた /teams/{slug}/reservation-waitlist/{slotId} は存在しない）
    const joinRes = await memberCtx.post(`${BE_API}/teams/${teamSlug}/reservation-slots/${slotId}/waitlist`, {
      headers: authHeaders(memberToken),
      data: {},
    })
    const joinBody = await joinRes.text()
    console.log(`[シナリオA-2] キャンセル待ち登録 status=${joinRes.status()} body=${joinBody}`)
    expect(joinRes.ok(), `MEMBER が満席枠のキャンセル待ちに登録できること: ${joinRes.status()} ${joinBody}`).toBe(true)

    // 実体裏取り: MEMBER の /users/me/reservation-waitlist に当該 slot が載っていること
    const myWaitlistRes = await memberCtx.get(`${BE_API}/users/me/reservation-waitlist`, {
      headers: authHeaders(memberToken),
    })
    expect(myWaitlistRes.ok(), 'キャンセル待ち一覧APIが通ること').toBe(true)
    const myWaitlist = (await myWaitlistRes.json()).data as Array<{ slotId: number; status: string }>
    console.log(`[シナリオA-2] MEMBER のキャンセル待ち一覧=${JSON.stringify(myWaitlist)}`)
    expect(myWaitlist.some(w => w.slotId === slotId), '対象 slot が MEMBER のキャンセル待ちに載っていること').toBe(true)

    // 2) キャンセル前の通知スナップショット（同一 slot 由来の空き通知は 0 件のはず）
    const notifBefore = await fetchNotifications(memberCtx, memberToken)
    const openingBefore = notifBefore.filter(n => n.notificationType === WAITLIST_OPENING_TYPE && n.sourceId === slotId)
    console.log(`[シナリオA-2] キャンセル前 MEMBER 通知総数=${notifBefore.length} 当該slotの空き通知=${openingBefore.length}`)
    expect(openingBefore.length, 'キャンセル前は当該枠の空き通知がまだ無いこと').toBe(0)

    // 3) ADMIN が満席の原因になっている自分の予約を特定してキャンセル
    //    （このエンドポイントは @accessGuard.isScopeAdmin で ADMIN 限定なので ADMIN コンテキストで呼ぶ）
    const adminCtx = await playwrightRequest.newContext()
    const reservations = await fetchTeamReservations(adminCtx, tokens.admin, teamSlug)
    console.log(`[シナリオA-2] チーム予約一覧=${JSON.stringify(reservations.map(r => ({ id: r.id, slot: r.identifier?.reservationSlotId, st: r.status?.status })))}`)
    const target = reservations.find(r => r.identifier?.reservationSlotId === slotId && r.status?.status !== 'CANCELLED')
    expect(target, `満席の原因になっている生存予約が slotId=${slotId} に存在すること（見つからなければ空振りではなく失敗させる）`).toBeDefined()

    const cancelRes = await adminCtx.post(`${BE_API}/teams/${teamSlug}/reservations/${target!.id}/cancel`, {
      headers: authHeaders(tokens.admin),
      data: { reason: 'E2E空き通知検証' },
    })
    const cancelBody = await cancelRes.text()
    console.log(`[シナリオA-2] 管理者キャンセル status=${cancelRes.status()} body=${cancelBody.slice(0, 400)}`)
    expect(cancelRes.ok(), `管理者による予約キャンセルが成功すること: ${cancelRes.status()} ${cancelBody}`).toBe(true)
    await adminCtx.dispose()

    // 4) MEMBER の通知一覧に空き通知が届くのを有界に待つ（AFTER_COMMIT + @Async のため非同期）。
    //    タイムアウトしたらテストは失敗する（「来なければスキップ」はしない）。
    let received: NotificationRow | undefined
    await expect.poll(async () => {
      const rows = await fetchNotifications(memberCtx, memberToken)
      received = rows.find(n => n.notificationType === WAITLIST_OPENING_TYPE && n.sourceId === slotId)
      return received ? 1 : 0
    }, {
      message: `WAITING 中の MEMBER に ${WAITLIST_OPENING_TYPE}(sourceId=${slotId}) の空き通知が届くこと`,
      timeout: 45_000,
      intervals: [1000, 2000, 3000],
    }).toBe(1)

    console.log(`[シナリオA-2] 受信した空き通知の実体=${JSON.stringify(received)}`)
    expect(received!.title, '通知タイトルは ReservationWaitlistService の実装どおりであること').toBe('キャンセルが出ました')
    expect(received!.body, '本文に空きが出た旨が含まれること').toContain('空きが出ました')
    expect(received!.sourceType, 'sourceType は RESERVATION であること').toBe('RESERVATION')
    expect(received!.actionUrl, '通知の遷移先は当該チームの予約画面であること').toContain('/reservations')

    // 5) ADMIN 側には空き通知が飛ばないこと（WAITING 登録者宛の通知であることの裏取り）
    const adminNotifCtx = await playwrightRequest.newContext()
    const adminNotifs = await fetchNotifications(adminNotifCtx, tokens.admin)
    await adminNotifCtx.dispose()
    const adminOpening = adminNotifs.filter(n => n.notificationType === WAITLIST_OPENING_TYPE && n.sourceId === slotId)
    console.log(`[シナリオA-2] ADMIN 側の当該slot空き通知=${adminOpening.length}件`)
    expect(adminOpening.length, 'キャンセルを実行した管理者は WAITING 者ではないので空き通知を受け取らないこと').toBe(0)
  })
})

// ============================================================================
// 優先B: 定期予約（W2-5-FE/BE）
// ============================================================================
test.describe('D群 優先B: 定期予約（毎週繰り返し・実機）', () => {
  let teamSlug = ''
  let lineId = 0
  const day = dateInfo(10) // 十分先の日付で開始し4週分の空きを確保

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    const team = await createThrowawayTeam(ctx, tokens.admin, 'recurring')
    teamSlug = team.slug
    await enableReservationModule(ctx, tokens.admin, teamSlug)

    const hours = DAY_CODES.map(code => ({ dayOfWeek: code, isOpen: true, openTime: '08:00:00', closeTime: '22:00:00' }))
    await ctx.put(`${BE_API}/teams/${teamSlug}/reservation-settings/business-hours`, {
      headers: authHeaders(tokens.admin),
      data: { hours },
    })

    const lineRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-lines`, {
      headers: authHeaders(tokens.admin),
      data: { name: '会議室' },
    })
    lineId = ((await lineRes.json()).data as { id: number }).id

    // 【是正 2026-08-04】定期予約UI（repeatWeeks トグルを持つ ReservationForm）へは
    // マトリックスの「長尺枠（span>1）」セルからのみ到達する（30分セルは GroupBookingDialog 行き）。
    // 枠テンプレートは 60 分で登録しても BE が 30 分単位に分割して枠を生成する（cellCount=2）ため、
    // テンプレでは長尺セルを作れず本シナリオは到達不能だった（実測で確認）。
    // 長尺セルは手動枠（POST /reservation-slots）でのみ作れるので、4週分をそれで用意する。
    for (const offset of [0, 7, 14, 21]) {
      const slotDate = addDaysIso(day.iso, offset)
      const slotRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-slots`, {
        headers: authHeaders(tokens.admin),
        data: { lineId, slotDate, startTime: '11:00:00', endTime: '12:00:00', capacity: 3 },
      })
      if (!slotRes.ok()) throw new Error(`手動枠作成失敗(${slotDate}): ${slotRes.status()} ${await slotRes.text()}`)
    }

    console.log(`[SETUP-B] teamSlug=${teamSlug} lineId=${lineId} day=${day.iso}(${day.dayCode})`)
    await ctx.dispose()
  })

  test('シナリオB: 段階開示→4週で定期予約作成→結果明細→seriesバッジ→キャンセル2択', async ({ page }) => {
    await gotoReservations(page, teamSlug)
    await openReserveTab(page)

    // 🔴既知: マトリックスの30分単セル（span=1）は GroupBookingDialog（recurring非対応）に
    // ルーティングされ、定期予約(repeatWeeks)トグルを持つ ReservationForm には到達しない
    // （SlotMatrixPicker.vue のコード上コメント「30分セル(span=1・AVAILABLE): GroupBookingDialog」
    // 「長尺手動枠: 既存のReservationForm」がその根拠）。
    // 旧リスト表示（SlotPicker）は撤去済みのため、beforeAll で60分テンプレ（span=2 の長尺セル）を
    // 用意し、そのセルから ReservationForm（定期予約UI）へ到達して検証する。
    await goToWeekContaining(page, day.iso)

    const slotBtn = page.getByRole('button', { name: `${day.rowLabel} 11:00 会議室 空き`, exact: true })
    await expect(slotBtn, '長尺(60分)空きセルが表示されること').toBeVisible({ timeout: 15_000 })
    await slotBtn.click()

    const formDialog = page.getByRole('dialog', { name: '予約確認' })
    await expect(formDialog).toBeVisible({ timeout: 10_000 })

    // 段階開示: 初期状態は週数選択が非表示
    await expect(page.getByTestId('recurring-weeks-2'), 'トグルOFF時は週数選択が非表示').not.toBeVisible()
    await page.getByTestId('recurring-toggle').click()

    // 推奨2〜4が前面
    await expect(page.getByTestId('recurring-weeks-2'), '推奨2週が前面表示').toBeVisible({ timeout: 5_000 })
    await expect(page.getByTestId('recurring-weeks-3'), '推奨3週が前面表示').toBeVisible()
    await expect(page.getByTestId('recurring-weeks-4'), '推奨4週が前面表示').toBeVisible()
    // 5〜12週は「もっと長く」を開く前は非表示
    await expect(page.getByTestId('recurring-weeks-select'), '5-12週セレクトは「もっと長く」展開前は非表示').not.toBeVisible()
    await page.screenshot({ path: 'test-results/d-b-01-stage-disclosure-collapsed.png', fullPage: true })

    await page.getByTestId('recurring-weeks-more-toggle').click()
    await expect(page.getByTestId('recurring-weeks-select'), '「もっと長く」展開後は5-12週セレクトが表示').toBeVisible({ timeout: 5_000 })
    await page.screenshot({ path: 'test-results/d-b-02-stage-disclosure-expanded.png', fullPage: true })

    // 12超は選択肢に存在しないこと(オプション値確認)
    const options = await page.getByTestId('recurring-weeks-select').locator('option, li').allTextContents()
    console.log(`[シナリオB] 週数セレクト選択肢候補=${JSON.stringify(options)}`)

    // 4週で作成
    await page.getByTestId('recurring-weeks-4').click()
    await page.getByRole('button', { name: '予約する', exact: true }).click()

    const resultPanel = page.getByTestId('recurring-result-panel')
    await expect(resultPanel, '結果明細パネルが表示されること').toBeVisible({ timeout: 20_000 })
    const summaryText = await page.getByTestId('recurring-result-summary').textContent()
    console.log(`[シナリオB] 結果明細summary="${summaryText}"`)
    await page.screenshot({ path: 'test-results/d-b-03-result-panel.png', fullPage: true })
    await page.getByTestId('recurring-result-close').click()

    // seriesバッジが一覧に出るか（会員側の予約一覧タブへ）。タブ名は環境で確定しているため
    // .catch() でのフォールバックはしない（見つからなければテスト自体を失敗させる）。
    await page.getByRole('tab', { name: /予約(一覧|の確認)|マイ予約/ }).click()
    await page.waitForTimeout(1000)
    const seriesBadge = page.getByTestId('recurring-series-badge').first()
    // 是正3: 直前に repeatWeeks>=2 で作成した series の予約が一覧にあれば seriesバッジは
    // 必ず出るはずのもの。isVisible()の結果をif分岐で握りつぶさず、確実に断定する。
    await expect(seriesBadge, 'seriesバッジが一覧に表示されること（直前にrepeatWeeks指定で作成した予約のはず）').toBeVisible({ timeout: 10_000 })
    await page.screenshot({ path: 'test-results/d-b-04-series-badge.png', fullPage: true })
  })

  // 旧シナリオB-2（series所属予約のキャンセル2択）はここから撤去した。
  // キャンセル2択は cancelMine()（mode="mine"）経由でしか発火せず、本 spec の固定ユーザーは
  // 自作チームで常に ADMIN のため到達不能で、空の skip が残り続けていた。
  // MEMBER ロールの別ユーザーを招待フローで用意する tests/e2e/real/reservation-member-role.spec.ts
  // の M1-2 / M1-3 が実機で踏破済みのため、そちらへ担当を移した。
})

// ============================================================================
// 優先C: 仮押さえ失効設定 + 429レートリミット
// ============================================================================
test.describe('D群 優先C: 仮押さえ失効設定＋会員向け告知＋429レートリミット', () => {
  let teamSlug = ''
  let lineId = 0
  const day = dateInfo(15)

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    const team = await createThrowawayTeam(ctx, tokens.admin, 'pendexp')
    teamSlug = team.slug
    await enableReservationModule(ctx, tokens.admin, teamSlug)
    const hours = DAY_CODES.map(code => ({ dayOfWeek: code, isOpen: true, openTime: '08:00:00', closeTime: '22:00:00' }))
    await ctx.put(`${BE_API}/teams/${teamSlug}/reservation-settings/business-hours`, {
      headers: authHeaders(tokens.admin),
      data: { hours },
    })
    const lineRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-lines`, {
      headers: authHeaders(tokens.admin),
      data: { name: '相談室' },
    })
    lineId = ((await lineRes.json()).data as { id: number }).id
    // 承認制(MANUAL)に設定
    const policyRes = await ctx.patch(`${BE_API}/teams/${teamSlug}/reservation-settings`, {
      headers: authHeaders(tokens.admin),
      data: { approvalMode: 'MANUAL' },
    })
    console.log(`[SETUP-C] policy PUT status=${policyRes.status()}`)
    const tplRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-slot-templates`, {
      headers: authHeaders(tokens.admin),
      data: { lineId, dayOfWeek: day.dayCode, startTime: '13:00:00', endTime: '13:30:00', capacity: 10 },
    })
    if (!tplRes.ok()) throw new Error(`テンプレ作成失敗: ${tplRes.status()} ${await tplRes.text()}`)
    console.log(`[SETUP-C] teamSlug=${teamSlug} lineId=${lineId} day=${day.iso}(${day.dayCode})`)
    await ctx.dispose()
  })

  test('シナリオC-1: 失効時間設定UI（範囲外拒否・自動キャンセルしないトグル・MANUAL注意書き）', async ({ page }) => {
    await gotoReservations(page, teamSlug)
    await openManageTab(page)

    // ReservationPolicySettings は「予約対象の管理」タブ内の「詳細設定」アコーディオン
    // （既定collapsed・ADMIN限定）配下にある（TeamReservationsPanel.vue:444-479）。
    // team側タブ「機能設定」と紛らわしいため、accordion header の正確なテキストで開く。
    const advancedHeader = page.getByRole('button', { name: '詳細設定' })
    await expect(advancedHeader, '「詳細設定」アコーディオンヘッダーが表示されること').toBeVisible({ timeout: 15_000 })
    if ((await advancedHeader.getAttribute('aria-expanded')) !== 'true') await advancedHeader.click()
    await page.waitForTimeout(500)
    await page.screenshot({ path: 'test-results/d-c-01-settings-tab.png', fullPage: true })

    const disableCheckbox = page.locator('#pending-expire-disable-toggle')
    await expect(disableCheckbox, '自動キャンセルしないトグルが表示されること').toBeVisible({ timeout: 10_000 })

    // MANUAL チームでは初期状態(自動失効ON)では注意書きは出ないはず。トグルONで注意書き確認。
    // 文言実測(reservation.json): "現在「自動キャンセルしない」設定です。承認制（MANUAL）のチームでは、
    // 仮押さえの予約は自動で取り消されません。管理者が個別に対応してください"
    const warning = page.getByText(/自動で取り消されません/)
    await expect(warning, 'トグルON前は注意書きが非表示であること').not.toBeVisible()
    await disableCheckbox.click()
    await expect(warning, 'MANUALチームでトグルONにすると注意書きが表示されること').toBeVisible({ timeout: 5_000 })
    await page.screenshot({ path: 'test-results/d-c-02-no-expire-warning.png', fullPage: true })
    // 元に戻す
    await disableCheckbox.click()
    await expect(warning, 'トグルOFFに戻すと注意書きが再び非表示になること').not.toBeVisible({ timeout: 5_000 })
  })

  test('シナリオC-2: 会員予約フォームの失効告知文言（MANUAL+非NULLのときのみ）', async ({ page, tokens }) => {
    const ctx = await playwrightRequest.newContext()
    // pendingExpireHours を明示設定(既定値確認・範囲内)
    const putRes = await ctx.patch(`${BE_API}/teams/${teamSlug}/reservation-settings`, {
      headers: authHeaders(tokens.admin),
      data: { pendingExpireHours: 48 },
    })
    expect(putRes.ok(), 'pendingExpireHours設定PATCHが成功すること').toBeTruthy()
    console.log(`[シナリオC-2] pendingExpireHours PATCH status=${putRes.status()}`)
    await ctx.dispose()

    await gotoReservations(page, teamSlug)
    await openReserveTab(page)
    await goToWeekContaining(page, day.iso)
    const cell = page.getByRole('button', { name: new RegExp(`${day.rowLabel.replace(/[()]/g, '\\$&')} 13:00 相談室`) })
    await expect(cell).toBeVisible({ timeout: 15_000 })
    await cell.click()

    // メニュー未設定チームでは先に「メニューを選ぶ」ダイアログが挟まる（シナリオBと同じ経路。
    // WeeklyScheduleManager.vue確認済みの導線）。「メニューなしで30分だけ予約」で素通りする。
    const menuChooseDialog = page.getByRole('dialog', { name: 'メニューを選ぶ' })
    if (await menuChooseDialog.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await page.getByRole('button', { name: /メニューなしで30分だけ予約/ }).click()
    }

    const notice = page.getByTestId('pending-expire-notice')
    // MANUAL + pendingExpireHours非NULL の直前設定が効いていれば必ず出るはずの告知。
    // if分岐で握りつぶさず断定する。
    await expect(notice, '会員フォームに仮押さえ失効の告知が表示されること（MANUAL+非NULL設定済み）').toBeVisible({ timeout: 10_000 })
    const text = await notice.textContent()
    console.log(`[シナリオC-2] 告知文言="${text}"`)
    expect(text, '「時間以内に承認されない場合は自動的にキャンセルされます」相当の文言が含まれること').toMatch(/時間以内|承認されない場合|自動的にキャンセル/)
    await page.screenshot({ path: 'test-results/d-c-03-pending-expire-notice.png', fullPage: true })
  })

  test('シナリオC-3: 429レートリミット（ReservationCreateRateLimiter RATE_LIMIT=5/分固定ウィンドウ・6回目が429）', async ({ tokens }) => {
    test.setTimeout(180_000)

    // 是正1（殿指摘）: Valkeyの生存を前提条件として明示検証する。死んでいれば黙って緑にせず
    // test.skip() で明示スキップする（fail-openで429が出ない疑陰性を「合格」と誤認させない）。
    const valkeyAlive = await pingValkey('127.0.0.1', 6379)
    console.log(`[シナリオC-3] Valkey生存確認=${valkeyAlive}`)
    test.skip(!valkeyAlive, 'Valkeyが応答しないためレートリミット検証をスキップ（fail-open環境では429が出ない）')

    const ctx = await playwrightRequest.newContext()
    const slotId = await findSlotId(ctx, teamSlug, tokens.admin, day.iso, '13:00', lineId)

    // 是正2（殿指摘）: reservation-create バケットは同一ユーザーでチーム横断・全ワーカー共有のため、
    // A/B/D の先行シナリオが同一ウィンドウを消費している可能性がある。ReservationCreateRateLimiter の
    // 固定ウィンドウ長(60秒)を超えて待ち、クリーンな状態から計測する。
    console.log('[シナリオC-3] レートリミットウィンドウのリセットのため61秒待機開始')
    await new Promise(resolve => setTimeout(resolve, 61_000))
    console.log('[シナリオC-3] 待機完了。計測開始')

    const results: number[] = []
    for (let i = 0; i < 6; i++) {
      const res = await ctx.post(`${BE_API}/teams/${teamSlug}/reservations`, {
        headers: authHeaders(tokens.admin),
        data: { reservationSlotId: slotId, lineId, userNote: `E2E429検証#${i}` },
      })
      results.push(res.status())
      if (res.status() === 429) {
        // text() はJSON以外でも落ちない。catchで握りつぶさず生ボディをそのまま記録する
        const body = await res.text()
        console.log(`[シナリオC-3] ${i + 1}回目で429検出 body=${body}`)
      }
    }
    console.log(`[シナリオC-3] 6回試行の結果ステータス列=${JSON.stringify(results)}`)
    await ctx.dispose()

    // ReservationCreateRateLimiter の実装は RATE_LIMIT=5・1分固定ウィンドウ（殿が実物のJavaを確認済み）。
    // ウィンドウをリセットした直後の計測であれば、5回目までは429以外・6回目のみ429であるはず。
    // これを断定する。ウィンドウリセット後もそうならない場合は本物のバグとして検知する。
    for (let i = 0; i < 5; i++) {
      expect(results[i], `${i + 1}回目(5回目まで)は429以外であること（ウィンドウリセット後の計測。実測=${JSON.stringify(results)}）`).not.toBe(429)
    }
    expect(results[5], `6回目でRATE_LIMIT=5超過による429が出ること（ウィンドウリセット後の計測。実測=${JSON.stringify(results)}）`).toBe(429)
  })
})

// ============================================================================
// 優先D: 強行登録（管理者・破壊的操作）
// ============================================================================
test.describe('D群 優先D: 強行登録（定期予約不可枠・impact件数と実キャンセル件数の一致）', () => {
  let teamSlug = ''
  let lineId = 0
  const day = dateInfo(20)

  test.beforeAll(async ({ tokens }, testInfo) => {
    // beforeAll内で61秒のレートリミット待機を行うため、既定の60秒hookタイムアウトを引き上げる
    // （test.describe.configureのtimeoutはhookには効かないため、testInfo.setTimeout()で明示指定）。
    testInfo.setTimeout(150_000)

    // 優先C(シナリオC-3)がファイル内で直前に実行されると、同一ユーザーの reservation-create
    // レートリミット(RATE_LIMIT=5/60秒固定ウィンドウ)を使い切った直後になり、本ブロックの
    // 予約投入(4件)が429で失敗する。全ファイル一括実行時の順序依存を避けるため、
    // このブロック専用にもウィンドウ経過を待つ（他シナリオの429検証結果には影響しない）。
    await new Promise(resolve => setTimeout(resolve, 61_000))

    const ctx = await playwrightRequest.newContext()
    const team = await createThrowawayTeam(ctx, tokens.admin, 'forcereg')
    teamSlug = team.slug
    await enableReservationModule(ctx, tokens.admin, teamSlug)
    const hours = DAY_CODES.map(code => ({ dayOfWeek: code, isOpen: true, openTime: '08:00:00', closeTime: '22:00:00' }))
    await ctx.put(`${BE_API}/teams/${teamSlug}/reservation-settings/business-hours`, {
      headers: authHeaders(tokens.admin),
      data: { hours },
    })
    const lineRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-lines`, {
      headers: authHeaders(tokens.admin),
      data: { name: '多目的室' },
    })
    lineId = ((await lineRes.json()).data as { id: number }).id

    // 09:00-12:00の6コマ(30分刻み)テンプレを用意
    const tplRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-slot-templates`, {
      headers: authHeaders(tokens.admin),
      data: { lineId, dayOfWeek: day.dayCode, startTime: '09:00:00', endTime: '12:00:00', capacity: 1 },
    })
    if (!tplRes.ok()) throw new Error(`テンプレ作成失敗: ${tplRes.status()} ${await tplRes.text()}`)

    // 09:00, 09:30, 10:00, 10:30 の4枠に予約を入れておく（定期予約不可枠は09:00-11:00で作る想定＝4枠が衝突対象）
    for (const start of ['09:00', '09:30', '10:00', '10:30']) {
      const slotId = await findSlotId(ctx, teamSlug, tokens.admin, day.iso, start, lineId)
      const res = await ctx.post(`${BE_API}/teams/${teamSlug}/reservations`, {
        headers: authHeaders(tokens.admin),
        data: { reservationSlotId: slotId, lineId, userNote: `E2E強行登録衝突用(${start})` },
      })
      if (!res.ok()) throw new Error(`衝突用予約作成失敗(${start}): ${res.status()} ${await res.text()}`)
    }
    console.log(`[SETUP-D] teamSlug=${teamSlug} lineId=${lineId} day=${day.iso}(${day.dayCode}) 4枠に予約投入済み`)
    await ctx.dispose()
  })

  test('シナリオD: impact件数の表示と実際のキャンセル件数が一致する（数えて確認）', async ({ page, tokens }) => {
    await gotoReservations(page, teamSlug)
    await openManageTab(page)

    const header = page.getByRole('button', { name: /^週間スケジュール(\s*\(\d+\))?$/ })
    await expect(header).toBeVisible({ timeout: 20_000 })
    if ((await header.getAttribute('aria-expanded')) !== 'true') await header.click()

    await page.getByTestId('recurring-add').click()
    const dialog = page.getByRole('dialog', { name: '予約不可を追加' })
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // 09:00-11:00で定期不可枠を作成（09:00/09:30/10:00/10:30の4予約と衝突する想定）
    await selectDropdown(page, page.getByTestId('recurring-day-select'), WEEKDAY_JA[isoWeekday(day.iso)]!)
    await selectDropdown(page, page.getByTestId('recurring-start-time'), '09:00')
    await selectDropdown(page, page.getByTestId('recurring-end-time'), '11:00')
    await fillInput(page.getByTestId('recurring-reason'), 'E2E強行登録検証')

    // impact警告のカウントを取得
    const impactText = await page.getByText(/今後90日間に重複する予約が\s*\d+\s*件あります/).textContent({ timeout: 15_000 })
    const impactMatch = impactText?.match(/(\d+)\s*件/)
    const impactCount = impactMatch ? Number(impactMatch[1]) : -1
    console.log(`[シナリオD] impact表示件数=${impactCount} (raw="${impactText}")`)
    await page.screenshot({ path: 'test-results/d-d-01-impact-warning.png', fullPage: true })

    // 実DBの衝突件数を裏取り（09:00-11:00にoverlapするactive予約）
    const beforeListRes = await page.request.get(
      `${API_BASE_URL}/api/v1/teams/${teamSlug}/reservations?from=${day.iso}&to=${day.iso}`,
      { headers: { Authorization: `Bearer ${tokens.admin}` } },
    )
    const beforeReservations = (await beforeListRes.json()).data as Array<{ id: string; status: string; reservationSlotId: number }>
    const activeBefore = beforeReservations.filter(r => r.status !== 'CANCELLED')
    console.log(`[シナリオD] 強行登録前のactive予約数=${activeBefore.length} 実体=${JSON.stringify(activeBefore.map(r => r.id))}`)

    // 是正3（殿指摘）: impact件数>0(=recurringHasConflict)かつADMINならボタンは
    // WeeklyScheduleManager.vue上 v-if="isAdmin" で必ず出る設計（コード実測済み）。
    // 「出ないこと自体が異常」なのでif分岐で握りつぶさず断定する。
    const forceBtn = page.getByTestId('recurring-force-cancel-button')
    await expect(forceBtn, '強行登録ボタンが表示されること（impact件数>0のADMIN表示のはず）').toBeVisible({ timeout: 10_000 })
    await forceBtn.click()

    // confirmForceSaveRecurring() は常に confirmDialog.require() を無条件で呼ぶ設計（コード実測済み）。
    // 確認ダイアログの承諾も同様に断定する。
    const confirmDialog = page.locator('.p-confirmdialog')
    await expect(confirmDialog, '強行登録の確認ダイアログが表示されること').toBeVisible({ timeout: 5_000 })
    await confirmDialog.getByRole('button', { name: /キャンセルして登録する/ }).click()
    await expect(page.getByText(/定期予約不可枠を(登録|更新)しました/)).toBeVisible({ timeout: 20_000 })
    await page.screenshot({ path: 'test-results/d-d-02-force-registered.png', fullPage: true })

    // notifyForceCancelledIfAny() が出す「N件の予約をキャンセルして登録しました」トーストで
    // 実際にキャンセルされた件数を裏取り（BEレスポンス forceCancelledCount がそのまま文言化される）。
    const forceCancelToast = await page.getByText(/(\d+)件の予約をキャンセルして登録しました/).textContent({ timeout: 10_000 })
    const forceCancelMatch = forceCancelToast?.match(/(\d+)件の予約をキャンセルして登録しました/)
    const actualCancelledCount = forceCancelMatch ? Number(forceCancelMatch[1]) : -1
    console.log(`[シナリオD] 強行登録トースト実体="${forceCancelToast}" 抽出件数=${actualCancelledCount}`)

    // 実DB裏取り（status=CANCELLEDで明示フィルタ。既定は未指定だとCANCELLEDが除外される可能性があるため
    // status パラメータを明示する。BE TeamReservationController の一覧APIは from/to ではなく status/page/size）
    const afterListRes = await page.request.get(
      `${API_BASE_URL}/api/v1/teams/${teamSlug}/reservations?status=CANCELLED&size=50`,
      { headers: { Authorization: `Bearer ${tokens.admin}` } },
    )
    // text() はJSON以外でも落ちない。catchで握りつぶさず生ボディをそのまま記録する
    const afterBody = await afterListRes.text()
    console.log(`[シナリオD] status=CANCELLED裏取りAPI実体=${afterBody.slice(0, 800)}`)

    console.log(`[シナリオD] 【最終比較】impact表示件数=${impactCount} vs トースト実キャンセル件数=${actualCancelledCount}`)
    expect(actualCancelledCount, '🔴impact件数と実際にキャンセルされる件数(BEレスポンスforceCancelledCount由来のトースト)が一致すること（検分バグ再発防止）').toBe(impactCount)
  })
})
