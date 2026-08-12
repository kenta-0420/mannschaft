/**
 * 予約v2 D群4弾（W2-6-BE/W2-5-BE/W2-6-FE+W2-4-FE/W2-5-FE）実機E2E・未踏破3シナリオ。
 *
 * バックエンド http://127.0.0.1:8081（検証用・WSL2 Linux JVM常駐）
 * フロントエンド http://127.0.0.1:3001（検証用 dev server。NUXT_PUBLIC_API_BASE=http://127.0.0.1:8081）
 *
 * 【重要】ブラウザ・Playwright はすべて 127.0.0.1 系で統一する（localhost と混在させると
 * Origin 不一致で CORS 死する）。
 *
 * 写経元: reservation-v2-d-group.spec.ts（ログイン機構・日付ユーティリティ・単一セッション設計・
 * Valkey生存確認）／reservation-member-role.spec.ts（招待トークン経路でのMEMBER作成）。
 *
 * 【背景】殿が grep で実証した事実として、以下3経路は既存 spec に1件も存在しなかった
 * （frontend/tests/e2e/ 配下を検索して確認済み）:
 *   1. approve-series（scope=SERIES の series 一括承認）
 *   2. POST /teams/{slug}/reservation-groups 経由のレートリミット（既存 C-3 は単枠経路のみ）
 *   3. forceCancelConflicting でキャンセルされた予約の申込者への通知到達確認
 *      （既存シナリオD はキャンセル件数までしか検証していない）
 * 本 spec はその3本を担当する。
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
import net from 'node:net'

/**
 * Valkey (Redis互換) が生きているか、生の TCP + RESP inline command（PING）で直接確認する。
 * レートリミットが Valkey に依存する以上（fail-openで429が出ない環境依存の疑陰性を防ぐため）、
 * 429 を assert する前に前提条件として明示的に検証する（写経元: reservation-v2-d-group.spec.ts）。
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

const ADMIN_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-pwui-1782136885@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'Passw0rd!2026'

/** 会員（MEMBER）視点検証用の永続ユーザー。既存 spec と同一の実ユーザー。 */
const MEMBER_EMAIL = process.env.TEST_MEMBER_EMAIL ?? 'e2e-member-1785848177@test.mannschaft.local'
const MEMBER_PASSWORD = process.env.TEST_MEMBER_PASSWORD ?? 'Passw0rd!2026'

/** 招待トークンの roleId。InviteTokenList.vue の既定値と同一（MEMBER）。 */
const ROLE_ID_MEMBER = 4

/** 強行登録キャンセルの通知タイプ（ReservationForceCancelNotificationEventListener.NOTIFICATION_TYPE と同値）。 */
const FORCE_CANCEL_NOTIFICATION_TYPE = 'RESERVATION_CANCELLED'

interface MeProfile {
  id: number
  email: string
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
  if (!res.ok()) throw new Error(`/users/me 失敗: ${res.status()} ${await res.text()}`)
  return (await res.json()).data as MeProfile
}

async function createThrowawayTeam(ctx: APIRequestContext, adminToken: string, label: string): Promise<string> {
  const res = await ctx.post(`${BE_API}/teams`, {
    headers: authHeaders(adminToken),
    data: { name: `RsvRem3_${label}_${Date.now()}` },
  })
  if (!res.ok()) throw new Error(`チーム作成失敗: ${res.status()} ${await res.text()}`)
  return ((await res.json()).data as { slug: string }).slug
}

async function enableReservationModule(ctx: APIRequestContext, adminToken: string, slug: string): Promise<void> {
  const catalogRes = await ctx.get(`${BE_API}/teams/${slug}/modules/catalog`, { headers: authHeaders(adminToken) })
  if (!catalogRes.ok()) throw new Error(`モジュールカタログ取得失敗: ${catalogRes.status()} ${await catalogRes.text()}`)
  const catalog = (await catalogRes.json()).data as { modules: { moduleId: number; slug: string }[] }
  const mod = catalog.modules.find(m => m.slug === 'reservation')
  if (!mod) throw new Error('カタログに reservation モジュールが見つからない')
  const toggleRes = await ctx.patch(`${BE_API}/teams/${slug}/modules/${mod.moduleId}/toggle`, {
    headers: authHeaders(adminToken),
    data: { moduleId: mod.moduleId, enabled: true },
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

async function setBusinessHours(ctx: APIRequestContext, token: string, slug: string): Promise<void> {
  const hours = DAY_CODES.map(code => ({ dayOfWeek: code, isOpen: true, openTime: '08:00:00', closeTime: '22:00:00' }))
  const res = await ctx.put(`${BE_API}/teams/${slug}/reservation-settings/business-hours`, {
    headers: authHeaders(token),
    data: { hours },
  })
  if (!res.ok()) throw new Error(`営業時間PUT失敗: ${res.status()} ${await res.text()}`)
}

async function createLine(ctx: APIRequestContext, token: string, slug: string, name: string): Promise<number> {
  const res = await ctx.post(`${BE_API}/teams/${slug}/reservation-lines`, {
    headers: authHeaders(token),
    data: { name },
  })
  if (!res.ok()) throw new Error(`ライン作成失敗: ${res.status()} ${await res.text()}`)
  return ((await res.json()).data as { id: number }).id
}

/**
 * 手動枠（長尺枠）を1件作成する。
 *
 * 【実測（写経元 spec で確認済み）】枠テンプレート（reservation-slot-templates）は 60 分で
 * 登録しても BE が 30 分単位に分割して枠を生成する（cellCount=2）ため、テンプレでは長尺セルは
 * 作れず定期予約UI（recurring-toggle を持つ ReservationForm）へは到達できない。
 * 長尺セルは手動枠（POST /reservation-slots）でのみ作れる。
 */
async function createManualSlot(
  ctx: APIRequestContext,
  token: string,
  slug: string,
  data: { lineId: number; slotDate: string; startTime: string; endTime: string; capacity: number },
): Promise<number> {
  const res = await ctx.post(`${BE_API}/teams/${slug}/reservation-slots`, {
    headers: authHeaders(token),
    data,
  })
  if (!res.ok()) throw new Error(`手動枠作成失敗: ${res.status()} ${await res.text()}`)
  return ((await res.json()).data as { id: number }).id
}

interface SlotRow {
  id: number
  lineId: number | null
  basic: { slotDate: string; startTime: string; endTime: string }
}

async function findSlotId(
  ctx: APIRequestContext,
  slug: string,
  token: string,
  date: string,
  startTimePrefix: string,
  lineId: number,
): Promise<number> {
  const res = await ctx.get(`${BE_API}/teams/${slug}/reservation-slots?from=${date}&to=${date}`, {
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

/** 通知一覧の1件（本 spec が使うフィールドのみ）。 */
interface NotificationRow {
  id: number
  notificationType: string
  title: string
  body: string
  sourceType: string
  sourceId: number | null
  actionUrl: string | null
}

/** GET /notifications は page/size のみを受け付ける（limit は存在しない）。 */
async function fetchNotifications(ctx: APIRequestContext, token: string, size = 50): Promise<NotificationRow[]> {
  const res = await ctx.get(`${BE_API}/notifications?page=0&size=${size}`, { headers: authHeaders(token) })
  if (!res.ok()) throw new Error(`通知一覧取得失敗: ${res.status()} ${await res.text()}`)
  return (await res.json()).data as NotificationRow[]
}

/** ReservationResponse のうち本 spec が使うフィールド。 */
interface ReservationRow {
  id: number
  identifier: { reservationSlotId: number; lineId: number }
  status: { status: string }
  recurringSeriesId: string | null
}

async function fetchTeamReservations(
  ctx: APIRequestContext,
  token: string,
  slug: string,
  size = 100,
): Promise<ReservationRow[]> {
  const res = await ctx.get(`${BE_API}/teams/${slug}/reservations?page=0&size=${size}`, {
    headers: authHeaders(token),
  })
  if (!res.ok()) throw new Error(`チーム予約一覧取得失敗: ${res.status()} ${await res.text()}`)
  return (await res.json()).data as ReservationRow[]
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

test.setTimeout(300_000)

async function gotoReservations(page: Page, teamSlug: string): Promise<void> {
  await loginViaApi(page, { email: ADMIN_EMAIL, password: ADMIN_PASSWORD }, { apiBaseUrl: API_BASE_URL })
  await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  await waitForHydration(page)
}

// ============================================================================
// シナリオE1: series 一括承認（scope=SERIES・ReservationList.vue の approve-series ボタン）
// ============================================================================
/**
 * 検証内容:
 *   - MANUAL 承認チームで repeatWeeks=4 の定期予約を作ると series 内の4回すべてが PENDING になる
 *   - ReservationList.vue の approve-series ボタン（scope=SERIES）をクリックすると、
 *     series 内の PENDING 4件が「全回」CONFIRMED になる（1回だけでなく）
 *   - 対照実験: 別チームで同様の series を作り、scope 未指定（THIS_ONLY 相当）の単票承認を叩くと
 *     1件だけ CONFIRMED になり残り3件は PENDING のまま（＝ SERIES との差が実際に出ることの裏取り）
 */
test.describe('D群 追加E1: series一括承認（scope=SERIES）', () => {
  let teamSeriesSlug = ''
  let lineSeries = 0
  let seriesId = ''
  let firstReservationId = 0
  const day = dateInfo(25)

  let teamControlSlug = ''
  let lineControl = 0
  let controlSeriesId = ''
  let controlFirstReservationId = 0

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()

    // --- SERIES 一括承認を検証するチーム ---
    teamSeriesSlug = await createThrowawayTeam(ctx, tokens.admin, 'seriesapprove')
    await enableReservationModule(ctx, tokens.admin, teamSeriesSlug)
    await setBusinessHours(ctx, tokens.admin, teamSeriesSlug)
    lineSeries = await createLine(ctx, tokens.admin, teamSeriesSlug, '面談室')
    const policyRes = await ctx.patch(`${BE_API}/teams/${teamSeriesSlug}/reservation-settings`, {
      headers: authHeaders(tokens.admin),
      data: { approvalMode: 'MANUAL' },
    })
    if (!policyRes.ok()) throw new Error(`承認モードPATCH失敗: ${policyRes.status()} ${await policyRes.text()}`)
    for (const offset of [0, 7, 14, 21]) {
      await createManualSlot(ctx, tokens.admin, teamSeriesSlug, {
        lineId: lineSeries,
        slotDate: addDaysIso(day.iso, offset),
        startTime: '11:00:00',
        endTime: '12:00:00',
        capacity: 3,
      })
    }
    const firstSlotId = await findSlotId(ctx, teamSeriesSlug, tokens.admin, day.iso, '11:00', lineSeries)
    const createRes = await ctx.post(`${BE_API}/teams/${teamSeriesSlug}/reservations`, {
      headers: authHeaders(tokens.admin),
      data: { reservationSlotId: firstSlotId, lineId: lineSeries, userNote: 'E1 series作成', repeatWeeks: 4 },
    })
    if (!createRes.ok()) throw new Error(`series作成失敗: ${createRes.status()} ${await createRes.text()}`)
    const createBody = (await createRes.json()).data as { id: number; recurringSeriesId: string | null }
    firstReservationId = createBody.id
    seriesId = createBody.recurringSeriesId ?? ''
    expect(seriesId, 'repeatWeeks=4で作成した予約にrecurringSeriesIdが付くこと').not.toBe('')

    const seriesRows = await fetchTeamReservations(ctx, tokens.admin, teamSeriesSlug)
    const seriesPending = seriesRows.filter(r => r.recurringSeriesId === seriesId && r.status.status === 'PENDING')
    console.log(`[SETUP-E1-SERIES] teamSlug=${teamSeriesSlug} seriesId=${seriesId} PENDING件数=${seriesPending.length}`)
    expect(seriesPending.length, 'MANUAL承認チームでは4回すべてPENDINGで作られること').toBe(4)

    // --- 対照実験（THIS_ONLY相当・単票承認）チーム ---
    teamControlSlug = await createThrowawayTeam(ctx, tokens.admin, 'seriescontrol')
    await enableReservationModule(ctx, tokens.admin, teamControlSlug)
    await setBusinessHours(ctx, tokens.admin, teamControlSlug)
    lineControl = await createLine(ctx, tokens.admin, teamControlSlug, '面談室B')
    const policyRes2 = await ctx.patch(`${BE_API}/teams/${teamControlSlug}/reservation-settings`, {
      headers: authHeaders(tokens.admin),
      data: { approvalMode: 'MANUAL' },
    })
    if (!policyRes2.ok()) throw new Error(`承認モードPATCH失敗(対照): ${policyRes2.status()} ${await policyRes2.text()}`)
    for (const offset of [0, 7, 14, 21]) {
      await createManualSlot(ctx, tokens.admin, teamControlSlug, {
        lineId: lineControl,
        slotDate: addDaysIso(day.iso, offset),
        startTime: '11:00:00',
        endTime: '12:00:00',
        capacity: 3,
      })
    }
    const firstSlotIdControl = await findSlotId(ctx, teamControlSlug, tokens.admin, day.iso, '11:00', lineControl)
    const createResControl = await ctx.post(`${BE_API}/teams/${teamControlSlug}/reservations`, {
      headers: authHeaders(tokens.admin),
      data: { reservationSlotId: firstSlotIdControl, lineId: lineControl, userNote: 'E1 対照series作成', repeatWeeks: 4 },
    })
    if (!createResControl.ok()) throw new Error(`対照series作成失敗: ${createResControl.status()} ${await createResControl.text()}`)
    const createBodyControl = (await createResControl.json()).data as { id: number; recurringSeriesId: string | null }
    controlFirstReservationId = createBodyControl.id
    controlSeriesId = createBodyControl.recurringSeriesId ?? ''
    expect(controlSeriesId).not.toBe('')

    console.log(`[SETUP-E1-CONTROL] teamSlug=${teamControlSlug} seriesId=${controlSeriesId} firstReservationId=${controlFirstReservationId}`)
    await ctx.dispose()
  })

  test('E1-対照: scope未指定(単票承認)は1件だけCONFIRMEDになり残り3件はPENDINGのまま', async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    const confirmRes = await ctx.post(
      `${BE_API}/teams/${teamControlSlug}/reservations/${controlFirstReservationId}/confirm`,
      { headers: authHeaders(tokens.admin) },
    )
    const confirmBody = await confirmRes.text()
    console.log(`[E1-対照] 単票承認 status=${confirmRes.status()} body=${confirmBody.slice(0, 300)}`)
    expect(confirmRes.ok(), `単票承認が成功すること: ${confirmRes.status()} ${confirmBody}`).toBe(true)

    const rows = await fetchTeamReservations(ctx, tokens.admin, teamControlSlug)
    const seriesRows = rows.filter(r => r.recurringSeriesId === controlSeriesId)
    const confirmed = seriesRows.filter(r => r.status.status === 'CONFIRMED')
    const pending = seriesRows.filter(r => r.status.status === 'PENDING')
    console.log(`[E1-対照] series内訳 CONFIRMED=${confirmed.length} PENDING=${pending.length}`)
    expect(confirmed.length, '単票承認はscope=SERIESと異なり対象の1件だけCONFIRMEDになること').toBe(1)
    expect(pending.length, '残り3件はPENDINGのまま変化しないこと（SERIESとの対照）').toBe(3)
    await ctx.dispose()
  })

  test('E1: ReservationList.vueのapprove-seriesボタン(scope=SERIES)でseries内の4回全てがCONFIRMEDになる', async ({ page, tokens }) => {
    void firstReservationId
    await gotoReservations(page, teamSeriesSlug)
    await page.getByRole('tab', { name: '予約一覧' }).click()

    // series所属のPENDING行にのみ approve-series ボタンが出る（ReservationList.vue L358-369）。
    const approveSeriesBtn = page.getByTestId('approve-series').first()
    await expect(approveSeriesBtn, 'series所属PENDING行にapprove-seriesボタンが表示されること').toBeVisible({ timeout: 20_000 })
    await page.screenshot({ path: 'test-results/e1-01-approve-series-visible.png', fullPage: true })
    await approveSeriesBtn.click()

    // confirmDialog（PrimeVue useConfirm）の承諾ボタン
    const confirmDialog = page.locator('.p-confirmdialog')
    await expect(confirmDialog, 'series一括承認の確認ダイアログが出ること').toBeVisible({ timeout: 10_000 })
    await confirmDialog.getByRole('button', { name: 'まとめて承認する（定期予約）' }).click()

    // approveSeries() 成功トースト（reservation.recurring.confirm_series.success）
    await expect(page.getByText(/件承認|承認しました|まとめて承認/)).toBeVisible({ timeout: 20_000 })
    await page.screenshot({ path: 'test-results/e1-02-approve-series-done.png', fullPage: true })

    // 実データ裏取り: series内の4件すべてがCONFIRMEDになっていること（1回だけでなく全回）
    await expect(async () => {
      const rows = await fetchTeamReservations(page.request, tokens.admin, teamSeriesSlug)
      const seriesRows = rows.filter(r => r.recurringSeriesId === seriesId)
      const confirmed = seriesRows.filter(r => r.status.status === 'CONFIRMED')
      const stillPending = seriesRows.filter(r => r.status.status === 'PENDING')
      console.log(`[E1] series内訳 CONFIRMED=${confirmed.length} PENDING=${stillPending.length} 総数=${seriesRows.length}`)
      expect(seriesRows.length, 'series内は4件のまま').toBe(4)
      expect(confirmed.length, '🔴scope=SERIESなのでseries内の4回すべてがCONFIRMEDになること（1回だけでなく）').toBe(4)
      expect(stillPending.length, 'PENDINGとして残る回が無いこと').toBe(0)
    }).toPass({ timeout: 30_000 })
  })
})

// ============================================================================
// シナリオE2: 予約作成レートリミット（429）— グループ経路も単枠と同一zoneを消費するか
// ============================================================================
/**
 * 検証内容:
 *   BE 実装（ReservationCreateRateLimiter の Javadoc）は「単枠予約とグループ予約が同一の
 *   Valkey zone(reservation-create・1ユーザー1分5回固定ウィンドウ)を共有する」と明記している。
 *   これを実機で実証する: 単枠作成を5回叩いて枠を使い切った直後に、6回目としてグループ作成
 *   （POST /teams/{slug}/reservation-groups）を叩くと、単枠と同じ429で弾かれるはずである。
 *   既存 reservation-v2-d-group.spec.ts のシナリオC-3は単枠経路のみを検証しており、
 *   グループ経路が同一バケットを消費するかは未踏破だった。
 */
test.describe('D群 追加E2: 予約作成レートリミット — グループ経路も単枠と同一zoneを消費する', () => {
  let teamSlug = ''
  let lineId = 0
  const day = dateInfo(30)

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    teamSlug = await createThrowawayTeam(ctx, tokens.admin, 'ratelimitgroup')
    await enableReservationModule(ctx, tokens.admin, teamSlug)
    await setBusinessHours(ctx, tokens.admin, teamSlug)
    lineId = await createLine(ctx, tokens.admin, teamSlug, '多目的室')

    // 単枠作成5回分 + グループ作成用の予備2枠、余裕をもって8枠用意する（capacity大きめ）。
    // 実体の枠として作る（テンプレートは週次パターンの定義であり、findSlotIdが照会する
    // GET /reservation-slots には出てこないため、テンプレートでは前提データにならない）。
    for (const start of ['09:00:00', '09:30:00', '10:00:00', '10:30:00', '11:00:00', '11:30:00', '12:00:00', '12:30:00']) {
      const end = `${String(Number(start.slice(0, 2)) + (start.slice(3, 5) === '30' ? 1 : 0)).padStart(2, '0')}:${start.slice(3, 5) === '30' ? '00' : '30'}:00`
      await createManualSlot(ctx, tokens.admin, teamSlug, {
        lineId, slotDate: day.iso, startTime: start, endTime: end, capacity: 5,
      })
    }
    console.log(`[SETUP-E2] teamSlug=${teamSlug} lineId=${lineId} day=${day.iso}(${day.dayCode})`)
    await ctx.dispose()
  })

  test('E2: 単枠5回で枠を使い切った直後、グループ作成(6回目)も429で弾かれる（同一zone実証）', async ({ tokens }) => {
    test.setTimeout(180_000)

    // Valkey生存確認（写経元と同じ理由: fail-openで429が出ない疑陰性を「合格」と誤認させない）
    const valkeyAlive = await pingValkey('127.0.0.1', 6379)
    console.log(`[E2] Valkey生存確認=${valkeyAlive}`)
    test.skip(!valkeyAlive, 'Valkeyが応答しないためレートリミット検証をスキップ（fail-open環境では429が出ない）')

    const ctx = await playwrightRequest.newContext()

    // 先行シナリオ(E1等)が同一ユーザーのreservation-createウィンドウを消費している可能性があるため、
    // 固定ウィンドウ長(60秒)を超えて待ち、クリーンな状態から計測する（写経元C-3と同じ作法）。
    console.log('[E2] レートリミットウィンドウのリセットのため61秒待機開始')
    await new Promise(resolve => setTimeout(resolve, 61_000))
    console.log('[E2] 待機完了。計測開始')

    // 1〜5回目: 単枠作成（別々の枠を使い重複予約エラーを避ける）
    const singleSlotStarts = ['09:00', '09:30', '10:00', '10:30', '11:00']
    const singleResults: number[] = []
    for (const start of singleSlotStarts) {
      const slotId = await findSlotId(ctx, teamSlug, tokens.admin, day.iso, start, lineId)
      const res = await ctx.post(`${BE_API}/teams/${teamSlug}/reservations`, {
        headers: authHeaders(tokens.admin),
        data: { reservationSlotId: slotId, lineId, userNote: `E2単枠#${start}` },
      })
      singleResults.push(res.status())
      if (res.status() === 429) console.log(`[E2] 単枠(${start})で429検出: ${await res.text()}`)
    }
    console.log(`[E2] 単枠5回のステータス列=${JSON.stringify(singleResults)}`)
    for (let i = 0; i < 5; i++) {
      expect(singleResults[i], `単枠${i + 1}回目(5回目まで)は429以外であること（ウィンドウリセット直後）`).not.toBe(429)
    }

    // 6回目: グループ作成（POST /teams/{slug}/reservation-groups）。
    // slotIdsは実在チェックより先にレートリミットで弾かれる想定のため、
    // 実在する2枠（11:30/12:00）を指定して正当なリクエスト形状にする。
    const groupSlotId1 = await findSlotId(ctx, teamSlug, tokens.admin, day.iso, '11:30', lineId)
    const groupSlotId2 = await findSlotId(ctx, teamSlug, tokens.admin, day.iso, '12:00', lineId)
    const groupRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-groups`, {
      headers: authHeaders(tokens.admin),
      data: { menuId: null, lineId, slotIds: [groupSlotId1, groupSlotId2], userNote: 'E2グループ6回目' },
    })
    const groupBody = await groupRes.text()
    console.log(`[E2] グループ作成(6回目) status=${groupRes.status()} body=${groupBody.slice(0, 400)}`)

    expect(
      groupRes.status(),
      '🔴単枠経路で5回消費した直後のグループ作成は、単枠と同一zoneを共有していれば429になるはず'
      + `（実装のJavadoc主張の実機実証。実測status=${groupRes.status()} body=${groupBody.slice(0, 200)}）`,
    ).toBe(429)

    await ctx.dispose()
  })
})

// ============================================================================
// シナリオE3: 強行登録でキャンセルされた予約の申込者への通知
// ============================================================================
/**
 * 検証内容:
 *   forceCancelConflicting:true で定期予約不可枠を登録すると、衝突する予約がCANCELLEDになる
 *   （既存シナリオDはここまでを検証済み）。本シナリオは一歩進めて、
 *   キャンセルされた予約の申込者本人（MEMBER・ADMIN本人ではない）が
 *   GET /notifications で当該通知を実際に受け取ること・本文にキャンセルされた枠の情報が
 *   含まれることまでを検証する。
 */
test.describe('D群 追加E3: 強行登録でキャンセルされた予約の申込者への通知', () => {
  let teamSlug = ''
  let lineId = 0
  let memberCtx: APIRequestContext
  let memberToken = ''
  let memberRoleName = ''
  let memberReservationId = 0
  const day = dateInfo(35)
  const blockReason = 'E3強行登録検証(申込者通知)'

  test.beforeAll(async ({ tokens }) => {
    const adminCtx = await playwrightRequest.newContext()
    memberCtx = await playwrightRequest.newContext()
    memberToken = await login(memberCtx, MEMBER_EMAIL, MEMBER_PASSWORD)

    teamSlug = await createThrowawayTeam(adminCtx, tokens.admin, 'forcenotify')
    await enableReservationModule(adminCtx, tokens.admin, teamSlug)
    await setBusinessHours(adminCtx, tokens.admin, teamSlug)
    lineId = await createLine(adminCtx, tokens.admin, teamSlug, '相談室')

    // 09:00-10:00の実体枠を用意（テンプレートでは findSlotId が照会する実体枠一覧に出てこないため）
    await createManualSlot(adminCtx, tokens.admin, teamSlug, {
      lineId, slotDate: day.iso, startTime: '09:00:00', endTime: '10:00:00', capacity: 3,
    })

    memberRoleName = await joinAsMember(adminCtx, tokens.admin, memberCtx, memberToken, teamSlug)
    expect(memberRoleName, '招待トークンroleId=4で参加した結果のロール').toBe('MEMBER')

    // MEMBER本人（ADMINではない）が09:00枠を予約する。この予約がのちほど強行登録で巻き込まれる。
    const slotId = await findSlotId(adminCtx, teamSlug, tokens.admin, day.iso, '09:00', lineId)
    const reserveRes = await memberCtx.post(`${BE_API}/teams/${teamSlug}/reservations`, {
      headers: authHeaders(memberToken),
      data: { reservationSlotId: slotId, lineId, userNote: 'E3 MEMBER申込(強行キャンセル対象)' },
    })
    if (!reserveRes.ok()) throw new Error(`MEMBER予約失敗: ${reserveRes.status()} ${await reserveRes.text()}`)
    memberReservationId = ((await reserveRes.json()).data as { id: number }).id

    console.log(`[SETUP-E3] teamSlug=${teamSlug} lineId=${lineId} day=${day.iso}(${day.dayCode}) memberRole=${memberRoleName} memberReservationId=${memberReservationId}`)
    await adminCtx.dispose()
  })

  test.afterAll(async () => {
    await memberCtx.dispose()
  })

  test('E3-0: 申込者はADMIN本人ではなく別ユーザー(MEMBER)であること（前提の裏取り）', async ({ tokens }) => {
    const memberMe = await fetchMe(memberCtx, memberToken)
    expect(memberMe.email).toBe(MEMBER_EMAIL)
    expect(memberMe.id, '強行キャンセルされる予約の申込者はADMINとは別人であること').not.toBe(tokens.adminMe.id)
  })

  test('E3: forceCancelConflicting:trueで衝突予約がCANCELLEDになり、申込者(MEMBER)に通知が届く', async ({ tokens }) => {
    // キャンセル前の通知スナップショット（同一予約由来の強行キャンセル通知はまだ無いはず）
    const notifBefore = await fetchNotifications(memberCtx, memberToken)
    const cancelledBefore = notifBefore.filter(
      n => n.notificationType === FORCE_CANCEL_NOTIFICATION_TYPE && n.sourceId === memberReservationId,
    )
    console.log(`[E3] 強行登録前 MEMBER通知総数=${notifBefore.length} 当該予約の強行キャンセル通知=${cancelledBefore.length}`)
    expect(cancelledBefore.length, '強行登録前は当該予約の強行キャンセル通知がまだ無いこと').toBe(0)

    // ADMINが09:00-10:00に定期予約不可枠をforceCancelConflicting:trueで登録する。
    // MEMBERの09:00予約と衝突するため強行キャンセルされる想定。
    const ctx = await playwrightRequest.newContext()
    const blockRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-recurring-blocked-times`, {
      headers: authHeaders(tokens.admin),
      data: {
        lineId,
        dayOfWeek: day.dayCode,
        startTime: '09:00:00',
        endTime: '10:00:00',
        reason: blockReason,
        isPublic: false,
        forceCancelConflicting: true,
      },
    })
    const blockBody = await blockRes.text()
    console.log(`[E3] 定期予約不可枠(強行登録) status=${blockRes.status()} body=${blockBody.slice(0, 400)}`)
    expect(blockRes.ok(), `強行登録が成功すること: ${blockRes.status()} ${blockBody}`).toBe(true)

    // 実DB裏取り: MEMBERの予約がCANCELLEDになっていること
    const rowsAfter = await fetchTeamReservations(ctx, tokens.admin, teamSlug)
    console.log(`[E3] 強行登録後のチーム予約一覧=${JSON.stringify(rowsAfter.map(r => ({ id: r.id, status: r.status?.status })))}`)
    const memberReservationAfter = rowsAfter.find(r => r.id === memberReservationId)
    expect(memberReservationAfter, '強行キャンセル対象のMEMBER予約が一覧に存在すること').toBeDefined()
    expect(memberReservationAfter!.status.status, '🔴MEMBERの予約がCANCELLEDになっていること').toBe('CANCELLED')
    await ctx.dispose()

    // 非同期（AFTER_COMMIT + @Async）のため expect.poll で有界に待つ。届かなければタイムアウトして失敗する。
    let received: NotificationRow | undefined
    await expect.poll(async () => {
      const rows = await fetchNotifications(memberCtx, memberToken)
      received = rows.find(
        n => n.notificationType === FORCE_CANCEL_NOTIFICATION_TYPE && n.sourceId === memberReservationId,
      )
      return received ? 1 : 0
    }, {
      message: `強行キャンセルされた申込者(MEMBER)に${FORCE_CANCEL_NOTIFICATION_TYPE}(sourceId=${memberReservationId})の通知が届くこと`,
      timeout: 45_000,
      intervals: [1000, 2000, 3000],
    }).toBe(1)

    console.log(`[E3] 受信した強行キャンセル通知の実体=${JSON.stringify(received)}`)
    expect(received!.title, '通知タイトルはReservationForceCancelNotificationEventListenerの実装どおりであること').toBe('ご予約がキャンセルされました')
    expect(received!.body, '本文に強行キャンセルされた枠の情報(理由込み)が含まれること').toContain(blockReason)
    expect(received!.body, '本文に予約不可時間に設定された旨が含まれること').toContain('予約不可時間')
    expect(received!.sourceType, 'sourceTypeはRESERVATIONであること').toBe('RESERVATION')
    expect(received!.actionUrl, '通知の遷移先は当該チームの予約画面であること').toContain('/reservations')
  })
})
