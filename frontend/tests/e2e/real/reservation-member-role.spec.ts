/**
 * 実機E2E: MEMBER ロール視点の予約機能（高粒度）
 *
 * 背景:
 *   既存の実機E2E固定ユーザー（id=90209）は自分で作ったチームでは常に ADMIN になるため、
 *   会員（MEMBER）視点でしか到達できない導線が構造的に未検証だった。
 *   とりわけ「キャンセル2択」（cancel-scope-this-only / cancel-scope-this-and-following）は
 *   ReservationList.vue の cancelMine()（mode="mine"）経由でしか発火せず、
 *   ADMIN は cancel()（mode="team"）に入るため到達不能。
 *   reservation-v2-d-group.spec.ts シナリオB-2 が「MEMBERロールの別ユーザーが必要」として
 *   明示スキップしていた欠落を、本 spec が引き取って踏み切る。
 *
 * MEMBER の作り方（プロダクト実装の参加フローを使用）:
 *   1. ADMIN（固定ユーザー）が使い捨てチームを作成 → 作成者は ADMIN
 *   2. ADMIN が POST /teams/{slug}/invite-tokens { roleId: 4 } で MEMBER 招待トークンを発行
 *      （roleId=4 = MEMBER。InviteTokenList.vue の既定値と同一）
 *   3. 会員ユーザーが POST /invite/{token}/join で参加
 *   4. GET /teams/{slug}/me/permissions の roleName が "MEMBER" であることを実レスポンスで裏取り
 *
 * 会員ユーザー本体（MEMBER_EMAIL）は事前プロビジョニング済みの永続ユーザーを使う
 * （固定ユーザー 90209 と同じ扱い）。登録直後は PENDING_VERIFICATION で、
 * メール認証トークンは SHA-256 ハッシュでしか DB に無く平文を復元できないため、
 * 有効化のみ DB 直更新（users.status='ACTIVE'）で行った。参加フロー自体は上記のとおり実プロダクト経路。
 *
 * カバーする PR:
 *   #2574 旧表示2種の撤去（表示切替UIが存在しないこと）
 *   #2576 reservation_slots 未使用カラム除去（予約の作成・一覧・キャンセルが通ること）
 *   #2579 管理者のドラッグ枠作成（週グリッド）
 *   #2587 マトリックスのドラッグ複数選択 ＋ 枠ゼロの空状態
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

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://127.0.0.1:8081'
const BE_API = `${API_BASE_URL}/api/v1`

/** チーム作成者＝常に ADMIN になる既存の実機E2E固定ユーザー。 */
const ADMIN_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-pwui-1782136885@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'Passw0rd!2026'

/** 会員視点検証用の永続ユーザー（本 spec 用に新規プロビジョニング）。 */
const MEMBER_EMAIL = process.env.TEST_MEMBER_EMAIL ?? 'e2e-member-1785848177@test.mannschaft.local'
const MEMBER_PASSWORD = process.env.TEST_MEMBER_PASSWORD ?? 'Passw0rd!2026'

/** 招待トークンの roleId。InviteTokenList.vue L32 の既定値と同一（MEMBER）。 */
const ROLE_ID_MEMBER = 4

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

/**
 * API 用セッション（コンテキスト＋アクセストークン）をユーザー単位でキャッシュする。
 *
 * 【実測 2026-08-04】呼び出しのたびにログインすると BE のログインレートリミット
 * （AUTH_044「リクエストが集中しています」429）に必ず引っかかる。ブラウザ側の
 * loginViaApi は Cookie ベースで別勘定のため、API 側は使い回しで足りる。
 */
const apiSessions = new Map<string, { ctx: APIRequestContext; token: string }>()

async function withApi<T>(
  email: string,
  password: string,
  fn: (ctx: APIRequestContext, token: string) => Promise<T>,
): Promise<T> {
  let session = apiSessions.get(email)
  if (!session) {
    const ctx = await playwrightRequest.newContext()
    session = { ctx, token: await login(ctx, email, password) }
    apiSessions.set(email, session)
  }
  return fn(session.ctx, session.token)
}

/** 本 spec が作った使い捨てチーム（後始末で削除する）。 */
const createdTeamSlugs: string[] = []

async function createThrowawayTeam(ctx: APIRequestContext, adminToken: string, label: string): Promise<string> {
  const res = await ctx.post(`${BE_API}/teams`, {
    headers: authHeaders(adminToken),
    data: { name: `RsvMbr_${label}_${Date.now()}` },
  })
  if (!res.ok()) throw new Error(`チーム作成失敗: ${res.status()} ${await res.text()}`)
  const slug = ((await res.json()).data as { slug: string }).slug
  createdTeamSlugs.push(slug)
  return slug
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
function todayIsoJst(): string {
  const now = new Date()
  const jst = new Date(now.getTime() + 9 * 60 * 60 * 1000)
  return `${jst.getUTCFullYear()}-${String(jst.getUTCMonth() + 1).padStart(2, '0')}-${String(jst.getUTCDate()).padStart(2, '0')}`
}

interface DateInfo {
  iso: string
  dayCode: DayCode
  /** マトリックス行の aria-label 先頭（例: "2026/08/14 (金)"）。 */
  rowLabel: string
}
function dateInfo(offsetDays: number): DateInfo {
  const iso = addDaysIso(todayIsoJst(), offsetDays)
  const dow = calendarDate(iso).getUTCDay()
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
 * 【実測 2026-08-04】枠テンプレート（reservation-slot-templates）は 60 分で登録しても
 * BE が 30 分単位に分割して枠を生成する（cellCount=2 → 11:00-11:30 / 11:30-12:00 の2枠）。
 * そのためテンプレ経由では span>1 の長尺セルは作れず、定期予約UI（ReservationForm の
 * recurring-toggle）へは到達できない。長尺セルは手動枠（POST /reservation-slots）でのみ作れる。
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

async function createTemplate(
  ctx: APIRequestContext,
  token: string,
  slug: string,
  data: { lineId: number; dayOfWeek: DayCode; startTime: string; endTime: string; capacity: number },
): Promise<void> {
  const res = await ctx.post(`${BE_API}/teams/${slug}/reservation-slot-templates`, {
    headers: authHeaders(token),
    data,
  })
  if (!res.ok()) throw new Error(`テンプレ作成失敗: ${res.status()} ${await res.text()}`)
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

interface SlotTemplate {
  dayOfWeek: string
  startTime: string
  endTime: string
}

/** 週間スケジュール（枠テンプレ）一覧。レスポンスは { templates: [...], meta: {...} }。 */
async function listTemplates(ctx: APIRequestContext, token: string, slug: string): Promise<SlotTemplate[]> {
  const res = await ctx.get(`${BE_API}/teams/${slug}/reservation-slot-templates`, { headers: authHeaders(token) })
  if (!res.ok()) throw new Error(`テンプレ一覧取得失敗: ${res.status()} ${await res.text()}`)
  const body = (await res.json()).data as { templates?: SlotTemplate[] }
  return body.templates ?? []
}

interface MyReservation {
  id: number
  /** シリーズID（UUID文字列）。定期予約で作られた予約にのみ入る。 */
  recurringSeriesId: string | null
  identifier?: { lineId?: number; teamId?: number }
  group?: { groupId?: string } | null
  status?: { status?: string }
  slot?: { slotDate?: string; startTime?: string }
}

/**
 * 会員のマイ予約のうち、対象の予約対象(lineId)・生存ステータスのものだけを返す。
 *
 * GET /reservations/my はチームを跨いで全件返し、レスポンスに teamSlug は含まれない
 * （identifier.teamId / identifier.lineId のみ）。lineId は使い捨てチームごとに一意なので
 * これで対象チーム分だけを確実に切り出せる。
 */
async function listMyLiveReservations(
  ctx: APIRequestContext,
  token: string,
  lineId: number,
): Promise<MyReservation[]> {
  const res = await ctx.get(`${BE_API}/reservations/my`, { headers: authHeaders(token) })
  if (!res.ok()) throw new Error(`マイ予約取得失敗: ${res.status()} ${await res.text()}`)
  const all = (await res.json()).data as MyReservation[]
  return all.filter((r) => {
    const st = r.status?.status
    return r.identifier?.lineId === lineId && (st === 'CONFIRMED' || st === 'PENDING')
  })
}

/**
 * 会員に残っている生存予約をすべてキャンセルして白紙に戻す。
 *
 * 「自分の予約」タブ（mode="mine"）はチームを跨いで全予約を出すため、過去の実行や
 * 他の describe が残した予約があると行の特定が曖昧になる。前提を固定するために使う。
 */
async function purgeMyReservations(): Promise<void> {
  await withApi(MEMBER_EMAIL, MEMBER_PASSWORD, async (ctx, token) => {
    const res = await ctx.get(`${BE_API}/reservations/my`, { headers: authHeaders(token) })
    if (!res.ok()) throw new Error(`マイ予約取得失敗: ${res.status()} ${await res.text()}`)
    const all = (await res.json()).data as MyReservation[]
    for (const r of all) {
      const st = r.status?.status
      if (st !== 'CONFIRMED' && st !== 'PENDING') continue
      // グループ予約は単票キャンセルが 400(RESERVATION_042) で拒否されるため、グループ単位で畳む
      const groupId = r.group?.groupId
      const cancelRes = groupId
        ? await ctx.post(`${BE_API}/teams/${r.identifier!.teamId}/reservation-groups/${groupId}/cancel`, {
            headers: authHeaders(token),
            data: { cancelReason: 'E2E事前クリーン' },
          })
        : await ctx.post(`${BE_API}/reservations/${r.id}/cancel`, {
            headers: authHeaders(token),
            data: {},
          })
      if (!cancelRes.ok()) {
        throw new Error(`事前クリーンのキャンセル失敗 id=${r.id}: ${cancelRes.status()} ${await cancelRes.text()}`)
      }
    }
  })
}

/**
 * MEMBER ロールでチームへ参加させる（招待トークン → 参加 の実プロダクト経路）。
 * 実際に付与されたロールを GET /teams/{slug}/me/permissions で裏取りして返す。
 */
async function joinAsMember(adminToken: string, slug: string): Promise<string> {
  const inviteToken = await withApi(ADMIN_EMAIL, ADMIN_PASSWORD, async (ctx) => {
    const res = await ctx.post(`${BE_API}/teams/${slug}/invite-tokens`, {
      headers: authHeaders(adminToken),
      data: { roleId: ROLE_ID_MEMBER, expiresIn: '7d', maxUses: 10 },
    })
    if (!res.ok()) throw new Error(`招待トークン作成失敗: ${res.status()} ${await res.text()}`)
    return ((await res.json()).data as { token: string; roleName: string }).token
  })

  return withApi(MEMBER_EMAIL, MEMBER_PASSWORD, async (ctx, memberToken) => {
    const joinRes = await ctx.post(`${BE_API}/invite/${inviteToken}/join`, {
      headers: authHeaders(memberToken),
      data: {},
    })
    if (!joinRes.ok()) throw new Error(`招待参加失敗: ${joinRes.status()} ${await joinRes.text()}`)
    const permRes = await ctx.get(`${BE_API}/teams/${slug}/me/permissions`, { headers: authHeaders(memberToken) })
    if (!permRes.ok()) throw new Error(`権限取得失敗: ${permRes.status()} ${await permRes.text()}`)
    return ((await permRes.json()).data as { roleName: string }).roleName
  })
}

const test = base.extend<
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type -- test スコープの追加 fixture は無い
  {},
  { tokens: { admin: string; adminMe: MeProfile; memberMe: MeProfile } }
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
      const memberToken = await login(ctx, MEMBER_EMAIL, MEMBER_PASSWORD)
      const memberMe = await fetchMe(ctx, memberToken)
      await ctx.dispose()
      await use({ admin, adminMe, memberMe })
    },
    { scope: 'worker' },
  ],
})

test.setTimeout(300_000)

async function gotoReservationsAs(
  page: Page,
  email: string,
  password: string,
  teamSlug: string,
): Promise<void> {
  await loginViaApi(page, { email, password }, { apiBaseUrl: API_BASE_URL })
  await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  await waitForHydration(page)
}

async function openReserveTab(page: Page): Promise<void> {
  await page.getByRole('tab', { name: '予約する' }).click()
  await expect(page.getByText('メニューで絞り込む')).toBeVisible({ timeout: 30_000 })
}

async function goToWeekContaining(page: Page, targetIso: string): Promise<void> {
  const weekBtn = page.getByRole('button', { name: /^週 /, exact: false })
  await expect(weekBtn).toBeVisible({ timeout: 30_000 })
  const nextWeekBtn = page.locator('button').filter({ has: page.locator('.pi-angle-right') }).first()
  for (let i = 0; i < 8; i++) {
    const text = (await weekBtn.textContent()) ?? ''
    const m = text.match(/(\d{4})\/(\d{2})\/(\d{2}) - (\d{4})\/(\d{2})\/(\d{2})/)
    if (!m) throw new Error(`週ラベル取得失敗: "${text}"`)
    const start = `${m[1]}-${m[2]}-${m[3]}`
    const end = `${m[4]}-${m[5]}-${m[6]}`
    if (targetIso >= start && targetIso <= end) return
    await nextWeekBtn.click()
    await page.waitForTimeout(400)
  }
  throw new Error(`週範囲内に ${targetIso} が見つからない`)
}

/**
 * マトリックスのセル（同一行）をドラッグして複数選択する。
 * SlotMatrixPicker は pointer イベント + 8px しきい値で判定するため、
 * mouse.move を細かく刻んでしきい値を確実に超えさせる。
 */
async function dragCells(page: Page, fromLabel: string, toLabel: string): Promise<void> {
  const from = page.getByRole('button', { name: fromLabel, exact: true })
  const to = page.getByRole('button', { name: toLabel, exact: true })
  await expect(from, `ドラッグ開始セルが表示されること: ${fromLabel}`).toBeVisible({ timeout: 20_000 })
  await expect(to, `ドラッグ終了セルが表示されること: ${toLabel}`).toBeVisible({ timeout: 20_000 })
  // page.mouse はビューポート座標で動くため、対象行を可視領域へ入れてから座標を取り直す
  // （対象日が下にスクロールした位置にあると、座標がビューポート外になりドラッグが空振りする）。
  await from.scrollIntoViewIfNeeded()
  await page.waitForTimeout(300)
  const a = await from.boundingBox()
  const b = await to.boundingBox()
  if (!a || !b) throw new Error(`セルの boundingBox が取得できない: ${fromLabel} / ${toLabel}`)
  const ax = a.x + a.width / 2
  const ay = a.y + a.height / 2
  const bx = b.x + b.width / 2
  const by = b.y + b.height / 2
  await page.mouse.move(ax, ay)
  await page.mouse.down()
  const steps = 12
  for (let i = 1; i <= steps; i++) {
    await page.mouse.move(ax + ((bx - ax) * i) / steps, ay + ((by - ay) * i) / steps)
  }
  await page.mouse.up()
}

// ============================================================================
// M1: キャンセル2択（本丸）— MEMBER でしか到達できない導線
// ============================================================================
test.describe('M1: MEMBER のキャンセル2択（この回だけ / この回以降すべて）', () => {
  test.describe.configure({ mode: 'serial' })

  let teamSlug = ''
  let lineId = 0
  let memberRoleName = ''
  const day = dateInfo(10)

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    teamSlug = await createThrowawayTeam(ctx, tokens.admin, 'cancelscope')
    await enableReservationModule(ctx, tokens.admin, teamSlug)
    await setBusinessHours(ctx, tokens.admin, teamSlug)
    lineId = await createLine(ctx, tokens.admin, teamSlug, '会議室')
    // 定期予約UI（recurring-toggle を持つ ReservationForm）へはマトリックスの
    // 長尺枠（span>1）セルからのみ到達する。テンプレは 30 分に分割されてしまうため
    // 手動枠で 60 分枠を作る。さらに 4 週指定に耐えるよう +7/+14/+21 日にも同じ枠を用意する。
    for (const offset of [0, 7, 14, 21]) {
      await createManualSlot(ctx, tokens.admin, teamSlug, {
        lineId,
        slotDate: addDaysIso(day.iso, offset),
        startTime: '11:00:00',
        endTime: '12:00:00',
        capacity: 3,
      })
    }
    await ctx.dispose()

    memberRoleName = await joinAsMember(tokens.admin, teamSlug)
    // 「自分の予約」はチーム横断のため、行の特定が曖昧にならないよう白紙から始める
    await purgeMyReservations()
    console.log(`[SETUP-M1] teamSlug=${teamSlug} lineId=${lineId} day=${day.iso}(${day.dayCode}) memberRole=${memberRoleName}`)
  })

  test('M1-0: 招待フローで参加した会員の実ロールが MEMBER であること（API 実レスポンス裏取り）', async ({ tokens }) => {
    expect(memberRoleName, '招待トークン roleId=4 で参加した結果のロール').toBe('MEMBER')
    expect(tokens.memberMe.email).toBe(MEMBER_EMAIL)
    expect(tokens.adminMe.email).toBe(ADMIN_EMAIL)
    expect(tokens.memberMe.id, '会員は固定ADMINユーザーとは別人であること').not.toBe(tokens.adminMe.id)
  })

  test('M1-1: MEMBER の画面には管理タブが無く、タブ名が「自分の予約」であること（認可境界・画面）', async ({ page }) => {
    await gotoReservationsAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, teamSlug)

    await expect(page.getByRole('tab', { name: '自分の予約' }), 'MEMBER のタブ名は「自分の予約」').toBeVisible({ timeout: 30_000 })
    await expect(page.getByRole('tab', { name: '予約一覧' }), 'MEMBER に管理者用の「予約一覧」タブは出ない').toHaveCount(0)
    await expect(page.getByRole('tab', { name: '予約対象の管理' }), 'MEMBER に「予約対象の管理」タブは出ない').toHaveCount(0)
    await expect(page.getByRole('tab', { name: '緊急休業' }), 'MEMBER に「緊急休業」タブは出ない').toHaveCount(0)
    await page.screenshot({ path: 'test-results/m1-1-member-tabs.png', fullPage: true })
  })

  test('M1-2: MEMBER が定期予約(4週)を作成し、キャンセル2択の「この回だけ」で1件だけ消えること', async ({ page }) => {
    await gotoReservationsAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, teamSlug)
    await openReserveTab(page)
    await goToWeekContaining(page, day.iso)

    const slotBtn = page.getByRole('button', { name: `${day.rowLabel} 11:00 会議室 空き`, exact: true })
    await expect(slotBtn, '長尺(60分)空きセルが表示されること').toBeVisible({ timeout: 20_000 })
    await slotBtn.click()

    await expect(page.getByRole('dialog', { name: '予約確認' })).toBeVisible({ timeout: 15_000 })
    await page.getByTestId('recurring-toggle').click()
    await expect(page.getByTestId('recurring-weeks-4')).toBeVisible({ timeout: 10_000 })
    await page.getByTestId('recurring-weeks-4').click()
    await page.getByRole('button', { name: '予約する', exact: true }).click()

    await expect(page.getByTestId('recurring-result-panel'), '定期予約の結果明細が出ること').toBeVisible({ timeout: 30_000 })
    const summary = await page.getByTestId('recurring-result-summary').textContent()
    console.log(`[M1-2] 定期予約 結果明細="${summary}"`)
    await page.getByTestId('recurring-result-close').click()

    // 実データで件数を数える（画面表示だけを信じない）
    const before = await withApi(MEMBER_EMAIL, MEMBER_PASSWORD, (ctx, t) => listMyLiveReservations(ctx, t, lineId))
    console.log(`[M1-2] 作成直後のマイ予約件数=${before.length} recurringSeriesId=${JSON.stringify([...new Set(before.map(r => r.recurringSeriesId))])}`)
    expect(before.length, '4週指定なので4件の定期予約が作られていること').toBe(4)
    expect(before.every(r => r.recurringSeriesId != null), '全件が同一シリーズに属すこと').toBe(true)

    // 「自分の予約」タブへ
    await page.getByRole('tab', { name: '自分の予約' }).click()
    await expect(page.getByTestId('recurring-series-badge').first(), '定期バッジが一覧に出ること').toBeVisible({ timeout: 20_000 })

    // ★本丸: キャンセル2択が実際に画面へ出ること
    await page.getByTestId('my-reservation-cancel').first().click()
    const scopeDialog = page.getByRole('dialog', { name: 'キャンセル範囲の選択' })
    await expect(scopeDialog, 'キャンセル範囲の選択ダイアログが出ること').toBeVisible({ timeout: 15_000 })
    await expect(page.getByTestId('cancel-scope-this-only'), '「この回だけ」が表示されること').toBeVisible()
    await expect(page.getByTestId('cancel-scope-this-and-following'), '「この回以降すべて」が表示されること').toBeVisible()
    await expect(page.getByTestId('cancel-scope-this-only')).toHaveText('この回だけ')
    await expect(page.getByTestId('cancel-scope-this-and-following')).toHaveText('この回以降すべて')
    await page.screenshot({ path: 'test-results/m1-2-cancel-scope-dialog.png', fullPage: true })

    await page.getByTestId('cancel-scope-this-only').click()
    await expect(scopeDialog, '2択ダイアログが閉じること').toBeHidden({ timeout: 15_000 })

    const after = await withApi(MEMBER_EMAIL, MEMBER_PASSWORD, (ctx, t) => listMyLiveReservations(ctx, t, lineId))
    console.log(`[M1-2] THIS_ONLY 後のマイ予約件数=${after.length}`)
    expect(after.length, 'THIS_ONLY なので1件だけ減ること').toBe(before.length - 1)
  })

  test('M1-3: 「この回以降すべて」で残り全件が消え、結果明細が出ること', async ({ page }) => {
    const before = await withApi(MEMBER_EMAIL, MEMBER_PASSWORD, (ctx, t) => listMyLiveReservations(ctx, t, lineId))
    expect(before.length, 'M1-2 の続きで3件残っていること').toBe(3)

    await gotoReservationsAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, teamSlug)
    // 「この回以降すべて」は起点の回より後だけを畳むため、残っている最も早い回を起点にする
    // （一覧の並び順に依存して件数が変わるのを避ける。日付はISO文字列でそのまま描画される）。
    const earliestDate = before.map(r => r.slot?.slotDate ?? '').sort()[0]!
    console.log(`[M1-3] 起点にする最古の回=${earliestDate}`)

    await page.getByRole('tab', { name: '自分の予約' }).click()
    await expect(page.getByTestId('recurring-series-badge').first()).toBeVisible({ timeout: 20_000 })

    const targetRow = page.getByRole('row').filter({ hasText: earliestDate })
    await expect(targetRow, '最古の回の行が一覧にあること').toHaveCount(1)
    await targetRow.getByTestId('my-reservation-cancel').click()
    await expect(page.getByRole('dialog', { name: 'キャンセル範囲の選択' })).toBeVisible({ timeout: 15_000 })
    await page.getByTestId('cancel-scope-this-and-following').click()

    const resultSummary = page.getByTestId('cancel-scope-result-summary')
    await expect(resultSummary, 'THIS_AND_FOLLOWING は結果明細を出すこと').toBeVisible({ timeout: 20_000 })
    const text = (await resultSummary.textContent()) ?? ''
    console.log(`[M1-3] キャンセル結果明細="${text}"`)
    expect(text, '3件キャンセルした旨が出ること').toContain('3件')

    // ダイアログのヘッダ×ボタンも aria-label="閉じる" のため、フッターの実ボタン（最後）を指す。
    await page.getByRole('button', { name: '閉じる', exact: true }).last().click()

    const after = await withApi(MEMBER_EMAIL, MEMBER_PASSWORD, (ctx, t) => listMyLiveReservations(ctx, t, lineId))
    console.log(`[M1-3] THIS_AND_FOLLOWING 後のマイ予約件数=${after.length}`)
    expect(after.length, '残り3件すべてがキャンセルされること').toBe(0)
  })

  test('M1-4: MEMBER は管理系APIに到達できないこと（認可境界・API）', async ({ tokens }) => {
    void tokens
    await withApi(MEMBER_EMAIL, MEMBER_PASSWORD, async (ctx, memberToken) => {
      const lineRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-lines`, {
        headers: authHeaders(memberToken),
        data: { name: 'MEMBERが作ってはいけないライン' },
      })
      expect(lineRes.status(), 'MEMBER は予約対象を作成できない').toBe(403)

      const tplRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-slot-templates`, {
        headers: authHeaders(memberToken),
        data: { lineId, dayOfWeek: day.dayCode, startTime: '15:00:00', endTime: '15:30:00', capacity: 1 },
      })
      expect(tplRes.status(), 'MEMBER は週間スケジュール(枠テンプレ)を作成できない').toBe(403)

      const hoursRes = await ctx.put(`${BE_API}/teams/${teamSlug}/reservation-settings/business-hours`, {
        headers: authHeaders(memberToken),
        data: { hours: DAY_CODES.map(c => ({ dayOfWeek: c, isOpen: false, openTime: '08:00:00', closeTime: '22:00:00' })) },
      })
      expect(hoursRes.status(), 'MEMBER は営業時間を変更できない').toBe(403)
    })
  })
})

// ============================================================================
// M2: 枠ゼロの空状態（#2587）— ADMIN / MEMBER で文言が異なること
// ============================================================================
test.describe('M2: 枠ゼロの空状態（予約対象はあるが枠が無い）', () => {
  test.describe.configure({ mode: 'serial' })

  let teamSlug = ''
  let memberRoleName = ''

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    teamSlug = await createThrowawayTeam(ctx, tokens.admin, 'noslots')
    await enableReservationModule(ctx, tokens.admin, teamSlug)
    await setBusinessHours(ctx, tokens.admin, teamSlug)
    // 予約対象（ライン）だけ作り、枠テンプレは一切作らない
    await createLine(ctx, tokens.admin, teamSlug, '相談室')
    await ctx.dispose()
    memberRoleName = await joinAsMember(tokens.admin, teamSlug)
    console.log(`[SETUP-M2] teamSlug=${teamSlug} memberRole=${memberRoleName}`)
  })

  test('M2-1: MEMBER には「別の週を選んでお試しください」が出て、枠管理CTAは出ないこと', async ({ page }) => {
    expect(memberRoleName).toBe('MEMBER')
    await gotoReservationsAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, teamSlug)
    await openReserveTab(page)

    const empty = page.getByTestId('matrix-no-slots-empty')
    await expect(empty, '枠ゼロの空状態が出ること').toBeVisible({ timeout: 30_000 })
    await expect(empty).toContainText('この週は空き枠がありません')
    await expect(empty, 'MEMBER 向けの案内文であること').toContainText('別の週を選んでお試しください')
    await expect(empty, 'MEMBER に管理者向けの案内は出ない').not.toContainText('週間スケジュール')
    await expect(page.getByRole('button', { name: '枠を管理する' }), 'MEMBER に枠管理CTAは出ない').toHaveCount(0)
    await page.screenshot({ path: 'test-results/m2-1-member-no-slots.png', fullPage: true })
  })

  test('M2-2: ADMIN には週間スケジュールへ導く文言とCTAが出て、CTAで週間スケジュールが開くこと', async ({ page }) => {
    await gotoReservationsAs(page, ADMIN_EMAIL, ADMIN_PASSWORD, teamSlug)
    await openReserveTab(page)

    const empty = page.getByTestId('matrix-no-slots-empty')
    await expect(empty, '枠ゼロの空状態が出ること').toBeVisible({ timeout: 30_000 })
    await expect(empty).toContainText('この週は空き枠がありません')
    await expect(empty, 'ADMIN 向けの案内文であること').toContainText('「週間スケジュール」で枠を作成すると、ここに予約枠が表示されます')
    await expect(empty, 'ADMIN に会員向けの案内は出ない').not.toContainText('別の週を選んでお試しください')

    const cta = page.getByRole('button', { name: '枠を管理する' })
    await expect(cta, 'ADMIN には枠管理CTAが出ること').toBeVisible()
    await page.screenshot({ path: 'test-results/m2-2-admin-no-slots.png', fullPage: true })
    await cta.click()

    // CTA は「予約対象の管理」タブへ切り替え、週間スケジュールのアコーディオンを開く
    await expect(page.getByTestId('slot-drag-grid'), 'CTA で週間スケジュール（ドラッグ枠作成グリッド）まで到達すること').toBeVisible({ timeout: 30_000 })
    await page.screenshot({ path: 'test-results/m2-3-admin-cta-weekly.png', fullPage: true })
  })
})

// ============================================================================
// M3: マトリックスのドラッグ複数選択（#2587）
// ============================================================================
test.describe('M3: マトリックスのドラッグ複数選択', () => {
  test.describe.configure({ mode: 'serial' })

  let teamSlug = ''
  let lineId = 0
  const day = dateInfo(11)

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    teamSlug = await createThrowawayTeam(ctx, tokens.admin, 'drag')
    await enableReservationModule(ctx, tokens.admin, teamSlug)
    await setBusinessHours(ctx, tokens.admin, teamSlug)
    lineId = await createLine(ctx, tokens.admin, teamSlug, '練習場')

    // 09:00-09:30 / 09:30-10:00 / 10:00-10:30 の連続3枠（capacity 2）
    for (const [s, e] of [['09:00:00', '09:30:00'], ['09:30:00', '10:00:00'], ['10:00:00', '10:30:00']] as const) {
      await createTemplate(ctx, tokens.admin, teamSlug, { lineId, dayOfWeek: day.dayCode, startTime: s, endTime: e, capacity: 2 })
    }
    // 13:00-13:30（capacity 1・後で満席にして BOOKED にする） / 13:30-14:00 / 14:00-14:30
    await createTemplate(ctx, tokens.admin, teamSlug, { lineId, dayOfWeek: day.dayCode, startTime: '13:00:00', endTime: '13:30:00', capacity: 1 })
    await createTemplate(ctx, tokens.admin, teamSlug, { lineId, dayOfWeek: day.dayCode, startTime: '13:30:00', endTime: '14:00:00', capacity: 2 })
    await createTemplate(ctx, tokens.admin, teamSlug, { lineId, dayOfWeek: day.dayCode, startTime: '14:00:00', endTime: '14:30:00', capacity: 2 })
    // 16:00-17:00 の長尺枠（span=2）＋その手前の 15:30-16:00
    await createTemplate(ctx, tokens.admin, teamSlug, { lineId, dayOfWeek: day.dayCode, startTime: '15:30:00', endTime: '16:00:00', capacity: 2 })
    await createTemplate(ctx, tokens.admin, teamSlug, { lineId, dayOfWeek: day.dayCode, startTime: '16:00:00', endTime: '17:00:00', capacity: 2 })

    // 13:00 を ADMIN の予約で満席化 → MEMBER 視点では BOOKED になる
    const blockedSlotId = await findSlotId(ctx, teamSlug, tokens.admin, day.iso, '13:00', lineId)
    const res = await ctx.post(`${BE_API}/teams/${teamSlug}/reservations`, {
      headers: authHeaders(tokens.admin),
      data: { reservationSlotId: blockedSlotId, lineId, userNote: 'E2E満席化(管理者)' },
    })
    if (!res.ok()) throw new Error(`満席化用予約失敗: ${res.status()} ${await res.text()}`)
    await ctx.dispose()

    const role = await joinAsMember(tokens.admin, teamSlug)
    expect(role).toBe('MEMBER')
    console.log(`[SETUP-M3] teamSlug=${teamSlug} lineId=${lineId} day=${day.iso}(${day.dayCode})`)
  })

  test('M3-1: 連続する空き枠3つをドラッグ選択し、まとめて予約できること', async ({ page }) => {
    await gotoReservationsAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, teamSlug)
    await openReserveTab(page)
    await goToWeekContaining(page, day.iso)

    await dragCells(page, `${day.rowLabel} 09:00 練習場 空き`, `${day.rowLabel} 10:00 練習場 空き`)

    const dialog = page.getByRole('dialog')
    await expect(dialog, 'ドラッグ確定でプレビューダイアログが開くこと').toBeVisible({ timeout: 15_000 })
    await expect(dialog, '3枠が選択されていること').toContainText('3')
    await page.screenshot({ path: 'test-results/m3-1-drag-preview.png', fullPage: true })

    const before = await withApi(MEMBER_EMAIL, MEMBER_PASSWORD, (ctx, t) => listMyLiveReservations(ctx, t, lineId))
    await page.getByTestId('group-confirm').click()
    await expect(dialog).toBeHidden({ timeout: 30_000 })

    const after = await withApi(MEMBER_EMAIL, MEMBER_PASSWORD, (ctx, t) => listMyLiveReservations(ctx, t, lineId))
    console.log(`[M3-1] マイ予約件数 before=${before.length} after=${after.length}`)
    expect(after.length, 'グループ予約は代表1件としてマイ予約に出ること').toBe(before.length + 1)
  })

  test('M3-2: BOOKED をまたぐドラッグは BOOKED の手前で打ち切られること', async ({ page }) => {
    await gotoReservationsAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, teamSlug)
    await openReserveTab(page)
    await goToWeekContaining(page, day.iso)

    // 13:00 は満席（BOOKED）。12:30 は枠が無いので、13:30 から 14:00 へ向けて右方向、
    // かつ 13:00(BOOKED) を含む範囲＝ 14:00 → 13:00 の左方向ドラッグで打ち切りを確認する。
    const booked = page.getByRole('button', { name: new RegExp(`^${day.rowLabel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} 13:00 練習場 埋まっている`) })
    await expect(booked, '13:00 が BOOKED 表示であること').toBeVisible({ timeout: 20_000 })

    await dragCells(page, `${day.rowLabel} 14:00 練習場 空き`, `${day.rowLabel} 13:30 練習場 空き`)

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 15_000 })
    // 14:00 と 13:30 の 2 枠まで。13:00(BOOKED) は含まれない
    await expect(dialog, 'BOOKED の手前で打ち切られ2枠になること').toContainText('2')
    await page.screenshot({ path: 'test-results/m3-2-drag-blocked-by-booked.png', fullPage: true })
    await page.getByRole('button', { name: 'キャンセル', exact: true }).click()
    await expect(dialog).toBeHidden({ timeout: 15_000 })
  })

  test('M3-3: 長尺枠(span>1)を含む範囲へのドラッグは長尺枠の手前で打ち切られること', async ({ page }) => {
    await gotoReservationsAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, teamSlug)
    await openReserveTab(page)
    await goToWeekContaining(page, day.iso)

    // 15:30(span=1) から 16:00(span=2 の長尺枠) へドラッグしても、長尺枠は
    // ドラッグ選択の対象外（isDraggableCell が span===1 のみ）なので 15:30 の1枠だけ。
    await dragCells(page, `${day.rowLabel} 15:30 練習場 空き`, `${day.rowLabel} 16:00 練習場 空き`)

    const dialog = page.getByRole('dialog')
    await expect(dialog, '1枠のみの確定でもダイアログは開くこと').toBeVisible({ timeout: 15_000 })
    await expect(dialog, '長尺枠は取り込まれず1枠に留まること').toContainText('1')
    await page.screenshot({ path: 'test-results/m3-3-drag-longslot.png', fullPage: true })
    await page.getByRole('button', { name: 'キャンセル', exact: true }).click()
  })

  test('M3-4: 満席枠は予約できずキャンセル待ち導線になること（エラー系・満席）', async ({ page }) => {
    await gotoReservationsAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, teamSlug)
    await openReserveTab(page)
    await goToWeekContaining(page, day.iso)

    const booked = page.getByRole('button', { name: new RegExp(`^${day.rowLabel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} 13:00 練習場 埋まっている`) })
    await expect(booked).toBeVisible({ timeout: 20_000 })
    await booked.click()
    await expect(page.getByTestId('waitlist-register'), '満席セルはキャンセル待ち登録の導線になること').toBeVisible({ timeout: 15_000 })
    await page.screenshot({ path: 'test-results/m3-4-waitlist.png', fullPage: true })
  })

  test('M3-5: 満席枠への予約はAPIでも拒否されること（エラー系・満席／BE側）', async () => {
    await withApi(MEMBER_EMAIL, MEMBER_PASSWORD, async (ctx, memberToken) => {
      const slotId = await findSlotId(ctx, teamSlug, memberToken, day.iso, '13:00', lineId)
      const res = await ctx.post(`${BE_API}/teams/${teamSlug}/reservations`, {
        headers: authHeaders(memberToken),
        data: { reservationSlotId: slotId, lineId, userNote: '満席枠への予約(拒否されるべき)' },
      })
      console.log(`[M3-5] 満席枠への予約 status=${res.status()} body=${await res.text()}`)
      expect(res.ok(), '満席の枠には予約できないこと').toBe(false)
      expect(res.status()).toBeGreaterThanOrEqual(400)
    })
  })

  test('M3-6: 同一枠への重複予約は拒否されること（エラー系・重複）', async () => {
    await withApi(MEMBER_EMAIL, MEMBER_PASSWORD, async (ctx, memberToken) => {
      const slotId = await findSlotId(ctx, teamSlug, memberToken, day.iso, '13:30', lineId)
      const first = await ctx.post(`${BE_API}/teams/${teamSlug}/reservations`, {
        headers: authHeaders(memberToken),
        data: { reservationSlotId: slotId, lineId, userNote: '重複検証1件目' },
      })
      expect(first.ok(), '1件目の予約は成功すること').toBe(true)

      const second = await ctx.post(`${BE_API}/teams/${teamSlug}/reservations`, {
        headers: authHeaders(memberToken),
        data: { reservationSlotId: slotId, lineId, userNote: '重複検証2件目' },
      })
      console.log(`[M3-6] 重複予約 status=${second.status()} body=${await second.text()}`)
      expect(second.ok(), '同一枠への重複予約は拒否されること').toBe(false)
    })
  })
})

// ============================================================================
// M4: 管理者のドラッグ枠作成（#2579）＋ 旧表示撤去（#2574）
// ============================================================================
test.describe('M4: 管理者の週グリッド ドラッグ枠作成 / 旧表示の撤去', () => {
  test.describe.configure({ mode: 'serial' })

  let teamSlug = ''
  let lineId = 0

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    teamSlug = await createThrowawayTeam(ctx, tokens.admin, 'draggrid')
    await enableReservationModule(ctx, tokens.admin, teamSlug)
    await setBusinessHours(ctx, tokens.admin, teamSlug)
    lineId = await createLine(ctx, tokens.admin, teamSlug, 'コートA')
    await ctx.dispose()
    console.log(`[SETUP-M4] teamSlug=${teamSlug} lineId=${lineId}`)
  })

  async function openWeeklySchedule(page: Page): Promise<void> {
    await page.getByRole('tab', { name: '予約対象の管理' }).click()
    // アコーディオンの見出しボタンを指す（枠ゼロ空状態の案内文にも「週間スケジュール」の
    // 文字列が含まれるため、getByText では取り違える）。
    await page.getByRole('button', { name: /週間スケジュール/ }).first().click()
    await expect(page.getByTestId('slot-drag-grid'), '週グリッドが表示されること').toBeVisible({ timeout: 30_000 })
  }

  /** 週グリッドの (曜日, 行) セルをドラッグする。row は 6:00 起点の30分刻み。 */
  async function dragGridCells(page: Page, fromTestId: string, toTestId: string): Promise<void> {
    const from = page.getByTestId(fromTestId)
    const to = page.getByTestId(toTestId)
    await expect(from).toBeVisible({ timeout: 20_000 })
    await from.scrollIntoViewIfNeeded()
    const a = await from.boundingBox()
    const b = await to.boundingBox()
    if (!a || !b) throw new Error(`グリッドセルの boundingBox 取得失敗: ${fromTestId} / ${toTestId}`)
    const ax = a.x + a.width / 2
    const ay = a.y + a.height / 2
    const bx = b.x + b.width / 2
    const by = b.y + b.height / 2
    await page.mouse.move(ax, ay)
    await page.mouse.down()
    for (let i = 1; i <= 12; i++) {
      await page.mouse.move(ax + ((bx - ax) * i) / 12, ay + ((by - ay) * i) / 12)
    }
    await page.mouse.up()
  }

  test('M4-1: 旧表示の切替UI（マトリックス/リスト/スタッフ別グリッド）が存在しないこと（#2574 回帰）', async ({ page }) => {
    await gotoReservationsAs(page, ADMIN_EMAIL, ADMIN_PASSWORD, teamSlug)
    await openReserveTab(page)

    await expect(page.getByText('マトリックス表示', { exact: true }), '旧表示切替UIは撤去済み').toHaveCount(0)
    await expect(page.getByText('リスト表示', { exact: true }), '旧表示切替UIは撤去済み').toHaveCount(0)
    await expect(page.getByText('スタッフ別グリッド', { exact: true }), '旧表示切替UIは撤去済み').toHaveCount(0)

    const stored = await page.evaluate(() => localStorage.getItem('mannschaft.reservation.bookDisplayMode'))
    expect(stored, '表示選好の localStorage キーも書かれないこと').toBeNull()
    await page.screenshot({ path: 'test-results/m4-1-no-view-toggle.png', fullPage: true })
  })

  test('M4-2: 週グリッドをドラッグして枠が作成されること', async ({ page, tokens }) => {
    await gotoReservationsAs(page, ADMIN_EMAIL, ADMIN_PASSWORD, teamSlug)
    await openWeeklySchedule(page)

    // 月曜 10:00 (row=(10-6)*2=8) から 月曜 11:00 (row=10) まで＝3枠
    await dragGridCells(page, 'slot-cell-MON-8', 'slot-cell-MON-10')

    const rangeLabel = page.getByTestId('drag-range-label')
    await expect(rangeLabel, '確定ダイアログに範囲が表示されること').toBeVisible({ timeout: 15_000 })
    const labelText = (await rangeLabel.textContent()) ?? ''
    console.log(`[M4-2] ドラッグ範囲="${labelText}"`)
    expect(labelText).toContain('10:00')
    await page.screenshot({ path: 'test-results/m4-2-drag-range.png', fullPage: true })

    await page.getByTestId('drag-create-confirm').click()

    // 実データで裏取り
    await expect(async () => {
      const templates = await withApi(ADMIN_EMAIL, ADMIN_PASSWORD, (ctx, t) => listTemplates(ctx, t, teamSlug))
      const mon = templates.filter(t => t.dayOfWeek === 'MON')
      console.log(`[M4-2] MON テンプレ=${JSON.stringify(mon.map(t => `${t.startTime}-${t.endTime}`))}`)
      // ドラッグ範囲は「曜日ごとに1件のテンプレ」として作られる（createFromDragRange →
      // save() が selectedDays をループする実装）。3セル分＝10:00-11:30 の1件になる。
      expect(mon.length, '月曜にドラッグ範囲のテンプレが1件作られること').toBe(1)
      expect(mon[0]!.startTime, '開始は 10:00').toContain('10:00')
      expect(mon[0]!.endTime, '3セル分なので終了は 11:30').toContain('11:30')
    }).toPass({ timeout: 30_000 })
    void tokens
  })

  test('M4-3: 既存枠を含む範囲のドラッグは弾かれ、枠が増えないこと', async ({ page }) => {
    const beforeCount = await withApi(ADMIN_EMAIL, ADMIN_PASSWORD, async (ctx, t) => {
      return (await listTemplates(ctx, t, teamSlug)).length
    })

    await gotoReservationsAs(page, ADMIN_EMAIL, ADMIN_PASSWORD, teamSlug)
    await openWeeklySchedule(page)

    // M4-2 で作った月曜 10:00-11:30 を含む範囲（09:30 row=7 → 10:30 row=9）をドラッグ
    await dragGridCells(page, 'slot-cell-MON-7', 'slot-cell-MON-9')

    await expect(
      page.getByText('選択した範囲に登録済みの枠が含まれています。空いている範囲を選んでください'),
      '既存枠を含む範囲は警告で弾かれること',
    ).toBeVisible({ timeout: 15_000 })
    await expect(page.getByTestId('drag-create-confirm'), '確定ダイアログは開かないこと').toHaveCount(0)
    await page.screenshot({ path: 'test-results/m4-3-drag-blocked.png', fullPage: true })

    const afterCount = await withApi(ADMIN_EMAIL, ADMIN_PASSWORD, async (ctx, t) => {
      return (await listTemplates(ctx, t, teamSlug)).length
    })
    expect(afterCount, '弾かれたので枠数は変わらないこと').toBe(beforeCount)
  })

  test('M4-4: 複数曜日にまたがるドラッグで各曜日に枠が作られること', async ({ page }) => {
    await gotoReservationsAs(page, ADMIN_EMAIL, ADMIN_PASSWORD, teamSlug)
    await openWeeklySchedule(page)

    // 火曜 19:00 (row=26) から 木曜 19:30 (row=27) までドラッグ（火・水・木の3曜日 × 2行）
    await dragGridCells(page, 'slot-cell-TUE-26', 'slot-cell-THU-27')

    await expect(page.getByTestId('drag-range-label')).toBeVisible({ timeout: 15_000 })
    console.log(`[M4-4] 複数曜日ドラッグ範囲="${await page.getByTestId('drag-range-label').textContent()}"`)
    await page.screenshot({ path: 'test-results/m4-4-multiday-range.png', fullPage: true })
    await page.getByTestId('drag-create-confirm').click()

    await expect(async () => {
      const templates = await withApi(ADMIN_EMAIL, ADMIN_PASSWORD, (ctx, t) => listTemplates(ctx, t, teamSlug))
      for (const d of ['TUE', 'WED', 'THU']) {
        const rows = templates.filter(t => t.dayOfWeek === d && t.startTime.startsWith('19:'))
        console.log(`[M4-4] ${d} 19時台テンプレ=${JSON.stringify(rows.map(r => `${r.startTime}-${r.endTime}`))}`)
        expect(rows.length, `${d} にドラッグ範囲のテンプレが1件作られること`).toBe(1)
        expect(rows[0]!.endTime, `${d} は 2セル分なので 20:00 終了`).toContain('20:00')
      }
    }).toPass({ timeout: 30_000 })
  })

  test('M4-5: 予約の作成・一覧・キャンセルが通常どおり動くこと（#2576 未使用カラム除去の影響なし）', async ({ page }) => {
    const day = dateInfo(12)
    await withApi(ADMIN_EMAIL, ADMIN_PASSWORD, async (ctx, t) => {
      await createTemplate(ctx, t, teamSlug, {
        lineId, dayOfWeek: day.dayCode, startTime: '20:00:00', endTime: '20:30:00', capacity: 2,
      })
    })

    await gotoReservationsAs(page, ADMIN_EMAIL, ADMIN_PASSWORD, teamSlug)
    await openReserveTab(page)
    await goToWeekContaining(page, day.iso)

    const cell = page.getByRole('button', { name: `${day.rowLabel} 20:00 コートA 空き`, exact: true })
    await expect(cell, '作成した枠がマトリックスに出ること').toBeVisible({ timeout: 20_000 })
    await cell.click()
    await page.getByTestId('group-no-menu').click()
    await page.getByTestId('group-confirm').click()

    // 一覧（管理者は mode="team"）に出ること
    await page.getByRole('tab', { name: '予約一覧' }).click()
    await expect(page.getByRole('cell', { name: /20:00/ }).first(), '作成した予約が一覧に出ること').toBeVisible({ timeout: 20_000 })
    await page.screenshot({ path: 'test-results/m4-5-reservation-list.png', fullPage: true })

    // API でキャンセルまで通ること
    await withApi(ADMIN_EMAIL, ADMIN_PASSWORD, async (ctx, t) => {
      const res = await ctx.get(`${BE_API}/teams/${teamSlug}/reservations`, { headers: authHeaders(t) })
      expect(res.ok(), '予約一覧APIが通ること').toBe(true)
      const body = (await res.json()).data as { content?: { id: number }[] } | { id: number }[]
      const rows = Array.isArray(body) ? body : (body.content ?? [])
      expect(rows.length, '予約が1件以上あること').toBeGreaterThan(0)
      const cancelRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservations/${rows[0]!.id}/cancel`, {
        headers: authHeaders(t),
        data: { cancelReason: 'E2E後始末' },
      })
      expect(cancelRes.ok(), `キャンセルAPIが通ること: ${cancelRes.status()} ${await cancelRes.text()}`).toBe(true)
    })
  })
})

// ============================================================================
// M5: エラー系（レートリミット）
// ============================================================================
test.describe('M5: エラー系 — ログインレートリミット', () => {
  test('M5-1: 短時間に連続ログインすると 429 (AUTH_044) で弾かれること', async () => {
    // 実在ユーザーを使うと以降のテストのセッションまで巻き添えで縛られるため、
    // 存在しないアカウントに対して連打する（レートリミットは認証成否より手前で効く）。
    const ctx = await playwrightRequest.newContext()
    try {
      const bogus = `e2e-ratelimit-${Date.now()}@test.mannschaft.local`
      let sawTooManyRequests = false
      let attempts = 0
      for (let i = 0; i < 40; i++) {
        attempts++
        const res = await ctx.post(`${BE_API}/auth/login`, {
          headers: { 'Content-Type': 'application/json' },
          data: { email: bogus, password: 'WrongPassw0rd!2026' },
        })
        if (res.status() === 429) {
          const body = await res.text()
          console.log(`[M5-1] ${attempts}回目で429: ${body}`)
          expect(body, 'レートリミットの専用エラーコードが返ること').toContain('AUTH_044')
          sawTooManyRequests = true
          break
        }
        expect(res.status(), 'レートリミット到達前は認証失敗(400番台)であること').toBeGreaterThanOrEqual(400)
      }
      expect(sawTooManyRequests, `${attempts}回のログイン連打でレートリミット(429)が作動すること`).toBe(true)
    }
    finally {
      await ctx.dispose()
    }
  })
})

// ============================================================================
// 後始末: 作った使い捨てチームを削除し、API セッションを閉じる
// ============================================================================
test.afterAll(async () => {
  await withApi(ADMIN_EMAIL, ADMIN_PASSWORD, async (ctx, token) => {
    for (const slug of createdTeamSlugs) {
      const res = await ctx.delete(`${BE_API}/teams/${slug}`, { headers: authHeaders(token) })
      console.log(`[CLEANUP] DELETE /teams/${slug} -> ${res.status()}`)
    }
  })
  createdTeamSlugs.length = 0
  for (const session of apiSessions.values()) {
    await session.ctx.dispose()
  }
  apiSessions.clear()
})
