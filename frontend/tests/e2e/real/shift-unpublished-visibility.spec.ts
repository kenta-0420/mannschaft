/**
 * 実機E2E: 未公開シフト表の遮断（CMP-260826-2127 / F03.5 §10）
 *
 * 正本設計: docs/features/F03.5_shift/05_unpublished_visibility.md（AC は §7）
 *
 * 何を固定するか:
 *   - DRAFT と「ARCHIVED かつ publishedAt が NULL」は非管理者に存在ごと秘匿される（AC-1 / AC-2 / AC-7）
 *   - COLLECTING / ADJUSTING は非管理者にも 200 で返るが、割当は伏せられる（AC-4）。
 *     画面では赤緑の充足バッジ（0/2 等）が出ず「調整中」の中立表示に置き換わる（AC-4(4)）
 *   - PUBLISHED は非管理者にも全量（実際の割当人数バッジが出る）
 *   - 管理者は全ステータスで全量を取得でき、割当が伏せられない（AC-10）
 *   - 一般メンバーの希望提出フロー（/my/shift-request）が壊れていない（AC-8 / AC-4(3)）
 *
 * ロール横断 3 視点（スキル /実機 の必須要件）:
 *   正         … A1〜A3（ADMIN で DRAFT / COLLECTING / ADJUSTING が見え、操作できる）
 *   負         … B1〜B3（MEMBER の画面で充足バッジが出ず割当が伏せられている）
 *   URL 直打ち … C1〜C3（MEMBER が未公開シフトの詳細 URL を直接叩いても弾かれる）
 *
 * 前提データは ADMIN の API で作る（スキル /実機 が API 利用を許す範囲＝ログイン・前提作成・後始末）。
 * 検証対象の操作（一覧表示・詳細表示・バッジ確認・希望提出）はすべて実 UI で踏む。
 *
 * チームは既存の共有チーム `fc-u-18` を使う（役者は実測で揃っている: ADMIN=id24 / MEMBER=id23）。
 *
 * 【使い捨てチームを作らない理由（2026-09-03 実機で実測）】
 *   当初は reservation-member-role.spec.ts を写経して「チーム作成 → 招待トークン → MEMBER 参加」で
 *   隔離した前提を作っていたが、招待トークン発行が 403（COMMON_002）で必ず落ちる。
 *   原因は製品側の欠陥で、`AccessControlService` の ADMIN_ROLES に SYSTEM_ADMIN が含まれておらず、
 *   有効ロール解決が最強ロール（SYSTEM_ADMIN）を採るため、プラットフォーム SYSTEM_ADMIN 兼
 *   チーム ADMIN のユーザーは `isAdminOrAbove` が false になる。`InviteService` は SYSTEM_ADMIN を
 *   短絡しないため、自分で作ったチームの招待トークンすら発行できない。
 *   本 spec の ADMIN（e2e-admin, id=24）はプラットフォーム SYSTEM_ADMIN を持つのでこの経路を必ず踏む。
 *   欠陥自体は別件として起票済みであり、ここで迂回するのは「シフトの遮断を確かめる」という
 *   本 spec の目的が招待経路の欠陥と無関係だからである（症状隠しではない）。
 *
 * 共有チームを使うため、本 spec が作ったシフト表・ポジションは後始末で必ず削除して原状復帰する。
 *
 * ============================================================================
 * 【この spec の射程】検証していること・していないこと（Codex 検分 2026-09-05 を受けて明記）
 * ============================================================================
 *
 * 検証している AC:
 *   AC-1  非管理者の一覧から未公開シフト表が除外される
 *         （メンバーが listSchedules の結果を直接見る画面＝希望提出のシフト表選択で確認。C3）
 *   AC-2  非管理者の単体取得が 404（HTTP ステータスを実応答で確認。C1 / C2）
 *   AC-3  非管理者の枠一覧が 404（同上。C1 / C2）
 *   AC-4  COLLECTING / ADJUSTING は 200 で返り、assignmentMasked=true・assignedUserIds=[] で
 *         割当が伏せられる。枠の骨格（時刻・ポジション・必要人数）は伏せない。
 *         画面では充足バッジが出ず中立表示になる（B1 / B2。API 応答と画面の両方を見る）
 *   AC-7  ARCHIVED かつ publishedAt が NULL は DRAFT と同一に扱われる（C2）
 *   AC-8  一般メンバーの希望提出フローが通る（D1）
 *   AC-9  非管理者が ADJUSTING を一覧・単体で取得できる（B2）
 *   AC-10 管理者は全ステータスで一覧・単体・枠を全量取得でき、割当が伏せられない
 *         （A1〜A3。ただし PDF と検索は未検証）
 *   AC-17 PUBLISHED かつ publishedAt が NULL は公開済みとして扱われる（B4）
 *
 * 検証していない AC（本 spec の射程外。別 spec で足す必要がある）:
 *   AC-5  PDF（layout=team / personal）の 404
 *   AC-6  グローバル検索から未公開シフト表が除外されること
 *   AC-10 のうち PDF・検索の経路
 *   AC-11 非メンバーの SYSTEM_ADMIN が全ステータスを取得できること（PDF 経路含む）
 *   AC-12 別 scope の ADMIN / SUPPORTER / 無所属が常に 403 であること（存在オラクルの不在）
 *   AC-13 既存 IT の緑化（実装側のテストであり実機の射程外）
 *   AC-14 COLLECTING 希望提出リマインド通知がシフト表名を含んで送られること
 *   AC-15 ShiftScheduleList.vue の非管理者向けフィルタ撤去（チーム配下のシフト表タブ）。
 *         当該画面は teamId に slug を渡しており BE の Long 変換で 400 になる既存の契約ずれが
 *         あるため、本 spec では踏んでいない
 *
 * 管理者側（A1〜A3）は、実機の ADMIN アカウントがプラットフォーム SYSTEM_ADMIN を併せ持つため
 * 「SYSTEM_ADMIN 短絡」経路を通る。AC-10（チーム管理者）を純粋な形では踏んでいない。
 */
import {
  test as base,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type BrowserContext,
  type Page,
} from '@playwright/test'
import { execSync } from 'child_process'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://127.0.0.1:8081'
const BE_API = `${API_BASE_URL}/api/v1`

/** チーム作成者＝常に ADMIN になる実機E2E固定ユーザー。 */
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

/** 一般メンバー視点の検証に使う永続ユーザー。 */
const MEMBER_EMAIL = process.env.TEST_MEMBER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const MEMBER_PASSWORD = process.env.TEST_MEMBER_PASSWORD ?? 'TestPass2026!'

/**
 * 役者がそろっている既存の共有チーム（URL 識別子 slug）。
 * ADMIN=e2e-admin(id24) / MEMBER=e2e-user(id23) がこのチームに所属している。
 */
const TEAM_SLUG = process.env.TEST_TEAM_SLUG ?? 'fc-u-18'

/** 枠の必要人数。1 名だけ割り当てるので、伏せなければ「1/2」が出る。 */
const SLOT_REQUIRED_COUNT = 2

/** i18n（ja）の実文言。UI 直書きではなくロケールの値と一致させる。 */
const LABEL_MASKED = '調整中' // shift.slot.assignmentMasked（shift.status.adjusting と同綴りのため識別は title 属性で行う）
const LABEL_MASKED_HINT = '公開前のため割当は表示されません' // shift.slot.assignmentMaskedHint
const LABEL_START_COLLECTING = '希望収集を開始' // shift.detail.startCollecting
const LABEL_CALENDAR_VIEW = 'カレンダー表示' // shift.detail.calendarView
/**
 * 本 spec が作るポジション名・シフト表タイトルの接頭辞。
 *
 * 共有チーム fc-u-18 を使うため、同名ポジションは 409（SHIFT_014）で弾かれる。
 * 実行ごとに一意な名前を付け、さらに setup で同じ接頭辞の残骸を掃除する
 * （テストが中断されると afterAll が走らず、次の実行が前回の残骸で落ちるため）。
 */
const POSITION_PREFIX = '受付_E2E'
const TITLE_PREFIX = '未公開遮断_'

/**
 * 実行ごとの一意な識別子。作るデータの名前に必ず含める。
 *
 * 共有チームを使うため、接頭辞一致だけで掃除すると並行して走る別実行のデータまで消してしまう
 * （実際に殿の実行と相互干渉して赤が出た）。名前に実行 ID を持たせ、掃除は
 * 「本 spec の接頭辞」かつ「十分に古い（= 生きている実行のものではない）」ものだけに限る。
 */
const RUN_TAG = `R${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`

/** これより古い残骸だけを掃除の対象にする（生きている並行実行のデータを消さないため）。 */
const STALE_LEFTOVER_MS = 2 * 60 * 60 * 1000
const LABEL_PREVIEW = '提出前確認' // shift.preview.title
const LABEL_SUBMIT = '提出' // shift.action.submit
const LABEL_TOTAL = '合計' // shift.preview.totalLabel
const LABEL_ERROR_TOAST = 'エラーが発生しました' // dialog.error

/** 充足バッジ（n/2）の検出パターン。伏せているときは出てはならない。 */
const STAFFING_BADGE_PATTERN = new RegExp(String.raw`\d+/${SLOT_REQUIRED_COUNT}`)

// ============================================================================
// API ヘルパー（前提データ作成・後始末専用）
// ============================================================================

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

async function fetchMyUserId(ctx: APIRequestContext, token: string): Promise<number> {
  const res = await ctx.get(`${BE_API}/users/me`, { headers: { Authorization: `Bearer ${token}` } })
  if (!res.ok()) throw new Error(`/users/me 失敗: ${res.status()} ${await res.text()}`)
  return ((await res.json()).data as { id: number }).id
}

/**
 * API 用セッションをユーザー単位でキャッシュする。
 * 呼び出しのたびにログインするとログインレートリミット（AUTH_044 / 429）に当たる。
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

/** 本 spec が作った前提データ（共有チームを汚さないよう後始末で必ず消す）。 */
const createdScheduleIds: number[] = []
let createdPositionId: number | null = null

interface TargetTeam {
  slug: string
  numericId: number
  name: string
}

/**
 * 対象チームを slug から解決する。
 *
 * 数値 teamId を要求する API（シフト系は @RequestParam Long teamId）へ渡すため numericId を、
 * 画面のチーム選択（Select の選択肢・希望提出のチームカード）を踏むため name を取る。
 */
async function fetchTargetTeam(ctx: APIRequestContext, token: string): Promise<TargetTeam> {
  const res = await ctx.get(`${BE_API}/teams/${TEAM_SLUG}`, { headers: authHeaders(token) })
  if (!res.ok()) throw new Error(`チーム取得失敗(${TEAM_SLUG}): ${res.status()} ${await res.text()}`)
  const data = (await res.json()).data as {
    numericId: number
    basicInfo?: { name?: string }
    name?: string
  }
  const name = data.basicInfo?.name ?? data.name
  if (!name) throw new Error(`チーム名が取得できない(${TEAM_SLUG})`)
  if (typeof data.numericId !== 'number') throw new Error(`numericId が取得できない(${TEAM_SLUG})`)
  return { slug: TEAM_SLUG, numericId: data.numericId, name }
}

/**
 * 当該チームでの実効ロールを取得する。
 *
 * 期待どおりの役者かを実レスポンスで裏取りするために使う。ここを省くと、
 * シードの揺れで MEMBER が ADMIN になっていても「伏せられていない」ことに気づけず、
 * 負の視点が静かに無効化される。
 */
async function fetchRoleName(email: string, password: string): Promise<string> {
  return withApi(email, password, async (ctx, token) => {
    const res = await ctx.get(`${BE_API}/teams/${TEAM_SLUG}/me/permissions`, {
      headers: authHeaders(token),
    })
    if (!res.ok()) throw new Error(`権限取得失敗(${email}): ${res.status()} ${await res.text()}`)
    return ((await res.json()).data as { roleName: string }).roleName
  })
}

/**
 * 前回の中断で残った本 spec 由来のデータを消してから始める。
 *
 * 共有チームを使うため、残骸があると次の実行が 409（同名ポジション）や重複表示で落ちる。
 * 対象は「本 spec の接頭辞を持ち」かつ「STALE_LEFTOVER_MS より古い」ものだけに限る
 * （接頭辞一致だけで消すと、並行して走る別実行のデータを道連れにする）。
 * 削除の成否は assert する。403/409/500 を黙って見逃すと残骸が残り、次の実行が落ちる。
 */
async function purgeLeftovers(ctx: APIRequestContext, token: string, teamId: number): Promise<void> {
  const staleBefore = Date.now() - STALE_LEFTOVER_MS

  const schedulesRes = await ctx.get(`${BE_API}/shifts/schedules?teamId=${teamId}`, {
    headers: authHeaders(token),
  })
  if (!schedulesRes.ok()) {
    throw new Error(`シフト表一覧取得失敗: ${schedulesRes.status()} ${await schedulesRes.text()}`)
  }
  const schedules = (await schedulesRes.json()).data as Array<{
    id: number
    content: { title: string }
    audit: { createdAt: string }
  }>
  for (const s of schedules) {
    if (!s.content?.title?.startsWith(TITLE_PREFIX)) continue
    if (Date.parse(s.audit?.createdAt ?? '') > staleBefore) continue
    const res = await ctx.delete(`${BE_API}/shifts/schedules/${s.id}`, { headers: authHeaders(token) })
    console.log(`[PURGE] DELETE /shifts/schedules/${s.id} (${s.content.title}) -> ${res.status()}`)
    expect(
      res.status(),
      `残骸のシフト表 ${s.id} を削除できること（消せないと次の実行が落ちる）`,
    ).toBe(204)
  }

  const positionsRes = await ctx.get(`${BE_API}/shifts/positions?teamId=${teamId}`, {
    headers: authHeaders(token),
  })
  if (!positionsRes.ok()) {
    throw new Error(`ポジション一覧取得失敗: ${positionsRes.status()} ${await positionsRes.text()}`)
  }
  const positions = (await positionsRes.json()).data as Array<{
    id: number
    name: string
    createdAt: string
  }>
  for (const pos of positions) {
    if (!pos.name?.startsWith(POSITION_PREFIX)) continue
    if (Date.parse(pos.createdAt ?? '') > staleBefore) continue
    const res = await ctx.delete(`${BE_API}/shifts/positions/${pos.id}`, { headers: authHeaders(token) })
    console.log(`[PURGE] DELETE /shifts/positions/${pos.id} (${pos.name}) -> ${res.status()}`)
    expect(res.status(), `残骸のポジション ${pos.id} を削除できること`).toBe(204)
  }
}

/**
 * MySQL へ直接 SQL を投げる（AC-17 のフィクスチャ作成専用）。
 *
 * wsl.exe 経由で docker のコンテナへ入る形は、既存の実機 spec
 * （2fa-flow.spec.ts / matching-search.spec.ts）と同じ作法。
 */
function runSql(sql: string): string {
  return execSync(
    `wsl.exe -e docker exec mannschaft-mysql mysql -uroot -proot mannschaft -N -B -e "${sql}"`,
    { stdio: 'pipe' },
  ).toString().trim()
}

async function createPosition(
  ctx: APIRequestContext,
  token: string,
  teamId: number,
  name: string,
): Promise<number> {
  const res = await ctx.post(`${BE_API}/shifts/positions?teamId=${teamId}`, {
    headers: authHeaders(token),
    data: { name, displayOrder: 1 },
  })
  if (!res.ok()) throw new Error(`ポジション作成失敗: ${res.status()} ${await res.text()}`)
  return ((await res.json()).data as { id: number }).id
}

async function createSchedule(
  ctx: APIRequestContext,
  token: string,
  teamId: number,
  title: string,
  startDate: string,
  endDate: string,
): Promise<number> {
  const res = await ctx.post(`${BE_API}/shifts/schedules?teamId=${teamId}`, {
    headers: authHeaders(token),
    data: { title, startDate, endDate },
  })
  if (!res.ok()) throw new Error(`シフト表作成失敗(${title}): ${res.status()} ${await res.text()}`)
  return ((await res.json()).data as { id: number }).id
}

async function createSlot(
  ctx: APIRequestContext,
  token: string,
  scheduleId: number,
  slotDate: string,
  positionId: number,
): Promise<number> {
  const res = await ctx.post(`${BE_API}/shifts/schedules/${scheduleId}/slots`, {
    headers: authHeaders(token),
    data: {
      slotDate,
      startTime: '09:00:00',
      endTime: '12:00:00',
      positionId,
      requiredCount: SLOT_REQUIRED_COUNT,
      note: null,
    },
  })
  if (!res.ok()) throw new Error(`シフト枠作成失敗: ${res.status()} ${await res.text()}`)
  return ((await res.json()).data as { id: number }).id
}

/**
 * 枠に 1 名割り当てる。
 * 作成直後の枠は楽観ロック version が 0（ShiftSlotEntity#version の初期値）。
 */
async function assignOneUser(
  ctx: APIRequestContext,
  token: string,
  slotId: number,
  userId: number,
): Promise<void> {
  const res = await ctx.patch(`${BE_API}/shifts/slots/${slotId}/assignments`, {
    headers: authHeaders(token),
    data: { addUserIds: [userId], removeUserIds: [], slotVersion: 0 },
  })
  if (!res.ok()) throw new Error(`割当失敗 slotId=${slotId}: ${res.status()} ${await res.text()}`)
}

async function transition(
  ctx: APIRequestContext,
  token: string,
  scheduleId: number,
  status: string,
): Promise<void> {
  const res = await ctx.post(
    `${BE_API}/shifts/schedules/${scheduleId}/transition?status=${status}`,
    { headers: authHeaders(token) },
  )
  if (!res.ok()) {
    throw new Error(
      `ステータス遷移失敗 id=${scheduleId} → ${status}: ${res.status()} ${await res.text()}`,
    )
  }
}

// ============================================================================
// 日付ユーティリティ
// ============================================================================

function todayIsoJst(): string {
  const jst = new Date(Date.now() + 9 * 60 * 60 * 1000)
  return jst.toISOString().slice(0, 10)
}

function addDaysIso(baseIso: string, days: number): string {
  const [y, m, d] = baseIso.split('-').map(Number)
  const dt = new Date(Date.UTC(y!, m! - 1, d!))
  dt.setUTCDate(dt.getUTCDate() + days)
  return dt.toISOString().slice(0, 10)
}

// ============================================================================
// 前提データ（4 ステータス + 未公開 ARCHIVED を 1 チーム分そろえる）
// ============================================================================

type FixtureKey =
  | 'draft'
  | 'collecting'
  | 'adjusting'
  | 'published'
  | 'publishedNoTimestamp'
  | 'archived'

interface Fixture {
  team: TargetTeam
  /** 本実行で作ったポジション名（実行ごとに一意）。 */
  positionName: string
  adminRoleName: string
  memberRoleName: string
  slotDate: string
  draftId: number
  collectingId: number
  adjustingId: number
  publishedId: number
  /** AC-17: status=PUBLISHED かつ published_at IS NULL の不整合データ。 */
  publishedNoTimestampId: number
  archivedUnpublishedId: number
  /** 枠に割り当てた会員のユーザーID（API 応答の突き合わせに使う）。 */
  memberUserId: number
  /** タイトルは実行ごとに一意化する（他実行の残骸と取り違えないため）。 */
  titles: Record<FixtureKey, string>
}

let fx: Fixture

const test = base.extend<
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type -- test スコープの追加 fixture は無い
  {},
  { tokens: { admin: string; memberUserId: number } }
>({
  // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
  storageState: async ({}, use) => {
    await use(undefined)
  },
  tokens: [
    // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
    async ({}, use) => {
      // ログインは withApi のキャッシュ経由に一本化し、ユーザーごとに 1 回しか行わない。
      // await で直列化しているのは、同一ユーザーのログインが近接すると BE が
      // refresh_tokens の更新でデッドロックし、リトライ無しに 500（COMMON_999）を返す
      // 堅牢性の欠陥があるため（2026-09-05 実機で BE ログにより確認・別途起票済み）。
      // 500 を握りつぶしてリトライするのではなく、ログイン回数そのものを減らして避ける。
      const admin = await withApi(ADMIN_EMAIL, ADMIN_PASSWORD, async (_ctx, token) => token)
      const memberUserId = await withApi(
        MEMBER_EMAIL, MEMBER_PASSWORD, (ctx, token) => fetchMyUserId(ctx, token),
      )
      await use({ admin, memberUserId })
    },
    { scope: 'worker' },
  ],
})

test.setTimeout(300_000)

/**
 * 直列実行にしている理由（1 件落ちると後続がスキップされる構造は承知のうえ）:
 *
 * 1. 前提データを共有チーム fc-u-18 に作るため、並列に走らせると同じチームのシフト表を
 *    互いの掃除で消し合う（実際に並行実行で赤が出た）。
 * 2. 同一ユーザーのログインが近接すると BE が refresh_tokens の更新でデッドロックし
 *    500（COMMON_999）を返す製品側の欠陥があり、並列にすると必ず踏む。
 *
 * どちらも「使い捨てチーム＋ユーザーごとの隔離」で解けるが、その前提となる招待経路に
 * 別の欠陥（AccessControlService の ADMIN_ROLES に SYSTEM_ADMIN が無い）があり今は使えない。
 */
test.describe.configure({ mode: 'serial' })

test.beforeAll(async ({ tokens }) => {
  const ctx = await playwrightRequest.newContext()
  try {
    const team = await fetchTargetTeam(ctx, tokens.admin)

    // 役者の裏取り。ロールが期待と違うまま進むと、負の視点（伏せられていること）が
    // 静かに無効化されるため、ここで落とす。
    const adminRoleName = await fetchRoleName(ADMIN_EMAIL, ADMIN_PASSWORD)
    const memberRoleName = await fetchRoleName(MEMBER_EMAIL, MEMBER_PASSWORD)
    if (adminRoleName !== 'ADMIN' && adminRoleName !== 'DEPUTY_ADMIN' && adminRoleName !== 'SYSTEM_ADMIN') {
      throw new Error(`管理者ユーザーの ${TEAM_SLUG} でのロールが管理者ではない: ${adminRoleName}`)
    }
    if (memberRoleName !== 'MEMBER') {
      throw new Error(`会員ユーザーの ${TEAM_SLUG} でのロールが MEMBER ではない: ${memberRoleName}`)
    }

    await purgeLeftovers(ctx, tokens.admin, team.numericId)

    const positionName = `${POSITION_PREFIX}_${RUN_TAG}`
    const positionId = await createPosition(ctx, tokens.admin, team.numericId, positionName)
    createdPositionId = positionId
    const startDate = addDaysIso(todayIsoJst(), 7)
    const endDate = addDaysIso(startDate, 2)
    const slotDate = startDate

    const titles: Record<FixtureKey, string> = {
      draft: `${TITLE_PREFIX}下書き_${RUN_TAG}`,
      collecting: `${TITLE_PREFIX}希望収集_${RUN_TAG}`,
      adjusting: `${TITLE_PREFIX}調整_${RUN_TAG}`,
      published: `${TITLE_PREFIX}確定_${RUN_TAG}`,
      publishedNoTimestamp: `${TITLE_PREFIX}確定日時なし_${RUN_TAG}`,
      archived: `${TITLE_PREFIX}未公開のままアーカイブ_${RUN_TAG}`,
    }

    /**
     * シフト表 1 件と、その枠 1 件（1 名割当済み）を作る。
     *
     * 割当は必ずステータス遷移より先に入れる。伏せる・伏せないの差を作るのは
     * 「閲覧者とステータス」であって「割当データの有無」ではない、という設計を
     * 固定するため、全ステータスで同じ 1 名割当を持たせる。
     */
    async function seedOne(title: string, statuses: string[]): Promise<number> {
      const scheduleId = await createSchedule(
        ctx, tokens.admin, team.numericId, title, startDate, endDate,
      )
      createdScheduleIds.push(scheduleId)
      const slotId = await createSlot(ctx, tokens.admin, scheduleId, slotDate, positionId)
      await assignOneUser(ctx, tokens.admin, slotId, tokens.memberUserId)
      for (const s of statuses) {
        await transition(ctx, tokens.admin, scheduleId, s)
      }
      return scheduleId
    }

    const draftId = await seedOne(titles.draft, [])
    const collectingId = await seedOne(titles.collecting, ['COLLECTING'])
    const adjustingId = await seedOne(titles.adjusting, ['COLLECTING', 'ADJUSTING'])
    const publishedId = await seedOne(titles.published, ['COLLECTING', 'ADJUSTING', 'PUBLISHED'])

    // AC-17 のフィクスチャ: status=PUBLISHED かつ published_at IS NULL。
    // この形は API では作れない（transitionStatus は publish() で必ず published_at を打刻し、
    // updateSchedule は status を受け付けず、duplicateSchedule は必ず DRAFT にする。
    // いずれも origin/main の実装で確認済み）。一方 DB 側には status と published_at の
    // 整合制約が無く（V3.070・設計書 §8 B-9）本番データにも存在しうる形なので、
    // 自分が作った 1 行だけを id 指定で書き換えて再現する。
    const publishedNoTimestampId = await seedOne(
      titles.publishedNoTimestamp, ['COLLECTING', 'ADJUSTING', 'PUBLISHED'],
    )
    runSql(`UPDATE shift_schedules SET published_at = NULL WHERE id = ${publishedNoTimestampId};`)
    const fixtureRow = runSql(
      `SELECT CONCAT(status, ':', IFNULL(published_at, 'NULL')) FROM shift_schedules WHERE id = ${publishedNoTimestampId};`,
    )
    if (fixtureRow !== 'PUBLISHED:NULL') {
      throw new Error(`AC-17 のフィクスチャを作れなかった: ${JSON.stringify(fixtureRow)}`)
    }
    // DRAFT から直接 ARCHIVED へ落とす（published_at が NULL のまま＝未公開アーカイブ / AC-7）
    const archivedUnpublishedId = await seedOne(titles.archived, ['ARCHIVED'])

    fx = {
      team,
      positionName,
      adminRoleName,
      memberRoleName,
      slotDate,
      draftId,
      collectingId,
      adjustingId,
      publishedId,
      publishedNoTimestampId,
      archivedUnpublishedId,
      memberUserId: tokens.memberUserId,
      titles,
    }
    console.log(
      `[SETUP] team=${team.slug}(#${team.numericId}) `
      + `adminRole=${adminRoleName} memberRole=${memberRoleName} `
      + `slotDate=${slotDate} draft=${draftId} collecting=${collectingId} `
      + `adjusting=${adjustingId} published=${publishedId} `
      + `publishedNoTimestamp=${publishedNoTimestampId} archived=${archivedUnpublishedId}`,
    )
  } finally {
    await ctx.dispose()
  }
})

// ============================================================================
// 画面操作ヘルパー
// ============================================================================

/** ブラウザ側のログイン結果（Cookie と、認証状態判定に使う localStorage の値）。 */
interface BrowserSession {
  cookies: Parameters<BrowserContext['addCookies']>[0]
  currentUser: string | null
  tokenExpiresAt: string | null
}

/**
 * ブラウザ側のログイン結果をユーザー単位でキャッシュする。
 *
 * テストごとに新しいコンテキストが作られるため素直に書くとログインが 10 回走るが、
 * 同一ユーザーのログインが近接すると BE が refresh_tokens の更新でデッドロックし、
 * リトライ無しに 500（COMMON_999）を返す（2026-09-05 実機で BE ログにより確認）。
 * これは製品側の堅牢性の欠陥であり別途起票済み。本 spec はシフトの遮断を確かめるのが
 * 目的なので、500 を握りつぶすのではなく **ログインの回数自体を 1 ユーザー 1 回に減らして**
 * その経路を踏まないようにする。
 */
const browserSessions = new Map<string, BrowserSession>()

async function establishSession(page: Page, email: string, password: string): Promise<void> {
  const cached = browserSessions.get(email)
  if (cached) {
    await page.context().addCookies(cached.cookies)
    // localStorage はオリジンに紐づくため、書き込む前にアプリのオリジンへ入る。
    await page.goto('/', { waitUntil: 'domcontentloaded', timeout: 180_000 })
    await page.evaluate(({ currentUser, tokenExpiresAt }) => {
      if (currentUser) localStorage.setItem('currentUser', currentUser)
      if (tokenExpiresAt) localStorage.setItem('tokenExpiresAt', tokenExpiresAt)
    }, cached)
    return
  }

  await loginViaApi(page, { email, password }, { apiBaseUrl: API_BASE_URL })
  const cookies = await page.context().cookies()
  const stored = await page.evaluate(() => ({
    currentUser: localStorage.getItem('currentUser'),
    tokenExpiresAt: localStorage.getItem('tokenExpiresAt'),
  }))
  browserSessions.set(email, { cookies, ...stored })
}

async function openAs(page: Page, email: string, password: string, path: string): Promise<void> {
  await establishSession(page, email, password)
  await page.goto(path, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
}

/**
 * シフト表詳細のカレンダー表に並ぶ枠チップ。
 *
 * 枠チップの href は `/shift/{id}/edit` だが、**同じ href をタブナビの「編集」タブも持つ**
 * （pages/shift/[id]/index.vue の tabs[1] と、カレンダー表のセル内 NuxtLink）。
 * href だけで引くと 1 枠しか無い画面でも 2 件ヒットする。枠チップはカレンダー表
 * （`<table>` の `<td>` 内）にしか無く、タブナビは `<nav>` 配下なので、表の中に限定して引く。
 */
function slotChips(page: Page, scheduleId: number) {
  return page.locator(`table td a[href="/shift/${scheduleId}/edit"]`)
}

/**
 * 割当が伏せられた印。
 *
 * 文言「調整中」は shift.status.adjusting と同綴りでページ内の別要素にも出るため、
 * 伏せた枠だけに付く title 属性（shift.slot.assignmentMaskedHint）で識別する。
 */
function maskedMarks(page: Page) {
  return page.locator(`[title="${LABEL_MASKED_HINT}"]`)
}

/** 枠一覧 API（GET /shifts/schedules/{id}/slots）の応答かどうか。 */
function isSlotsResponse(url: string, scheduleId: number): boolean {
  return url.endsWith(`/api/v1/shifts/schedules/${scheduleId}/slots`)
}

/** 単体取得 API（GET /shifts/schedules/{id}）の応答かどうか（/slots とは別物）。 */
function isScheduleResponse(url: string, scheduleId: number): boolean {
  return url.endsWith(`/api/v1/shifts/schedules/${scheduleId}`)
}

/** 枠一覧 API の応答本体（画面が実際に受け取ったもの）。 */
interface SlotApiRow {
  id: number
  assignmentMasked: boolean
  assignedUserIds: number[]
  position: { positionId: number | null; positionName: string | null; requiredCount: number }
  time: { slotDate: string; startTime: string; endTime: string }
}

/**
 * 画面が受け取った枠一覧 API の応答を待ち受ける。
 *
 * 画面の見た目だけを見ていると、API が誤って割当ユーザーIDを返していても
 * UI が描画しなければ緑になってしまう（AC-4 は API の契約でもある）。
 * 画面の検証に「上乗せ」して、実際に飛んだ応答の中身を突き合わせるために使う。
 * 遷移より前に呼んで Promise を作っておくこと。
 */
function waitForSlotsResponse(page: Page, scheduleId: number) {
  return page.waitForResponse(
    r => r.request().method() === 'GET' && isSlotsResponse(r.url(), scheduleId),
    { timeout: 120_000 },
  )
}

async function readSlotRows(response: Awaited<ReturnType<typeof waitForSlotsResponse>>): Promise<SlotApiRow[]> {
  return ((await response.json()) as { data: SlotApiRow[] }).data
}

/** /shift 一覧でチームを選ぶ（PrimeVue Select）。 */
async function selectTeamOnShiftIndex(page: Page, teamName: string): Promise<void> {
  const select = page.locator('.p-select').first()
  await expect(select, 'チーム選択の Select が出ること').toBeVisible({ timeout: 30_000 })
  await select.click()
  const option = page.getByRole('option', { name: teamName, exact: true })
  await expect(option, `チーム "${teamName}" が選択肢に出ること`).toBeVisible({ timeout: 30_000 })
  await option.click()
  await waitForSpinnerGone(page)
}

/**
 * /my/shift-request のステップ1（チーム選択）で対象チームを選ぶ。
 *
 * チームカードの描画は teamStore.fetchMyTeams の解決後であり、ハイドレーション直後には
 * まだ無い。カードの数を即座に数えると 0 になり、クリックせずステップ1に留まったまま
 * 次の検証へ進んでしまう（実機 run4/run5 の C3 失敗はこれが原因。実 DOM を
 * ヘッドレスブラウザで採取して確認した）。**カードが出るまで待ってからクリックする**。
 *
 * 「戻る」ボタンをステップ2の目印にしてはならない。PageHeader の戻るボタンが
 * ステップ1の時点で既に存在するため、待ちが即座に成立して race を素通りする
 * （実 DOM で確認済み）。
 *
 * 対象チームの会員（e2e-user）は複数チームに所属しているため、ページの
 * 「所属が 1 件なら自動選択」の分岐には入らず、ステップ1は必ず描画される
 * （GET /api/v1/me/teams で実測）。
 */
async function selectTeamOnShiftRequest(page: Page): Promise<void> {
  const teamCard = page.getByText(fx.team.name, { exact: true })
  await expect(
    teamCard.first(),
    'ステップ1のチーム選択に対象チームのカードが出ること',
  ).toBeVisible({ timeout: 60_000 })
  await teamCard.first().click()
  await waitForSpinnerGone(page)
}

// ============================================================================
// 【正】管理者視点 — 全ステータスが見え、操作でき、割当が伏せられない（AC-10）
// ============================================================================
test.describe('A: 管理者は全ステータスを見て操作できる', () => {
  test('A1: /shift の一覧に DRAFT / COLLECTING / ADJUSTING / PUBLISHED / 未公開アーカイブが並ぶ（AC-10）', async ({ page }) => {
    await openAs(page, ADMIN_EMAIL, ADMIN_PASSWORD, '/shift')
    await selectTeamOnShiftIndex(page, fx.team.name)

    for (const key of ['draft', 'collecting', 'adjusting', 'published', 'archived'] as const) {
      await expect(
        page.getByText(fx.titles[key], { exact: true }),
        `管理者の一覧に ${key} のシフト表が出ること`,
      ).toBeVisible({ timeout: 30_000 })
    }
  })

  test('A2: 管理者が DRAFT の詳細をカードから開き、次ステータスへ進める操作ができる（AC-10）', async ({ page }) => {
    await openAs(page, ADMIN_EMAIL, ADMIN_PASSWORD, '/shift')
    await selectTeamOnShiftIndex(page, fx.team.name)

    await page.getByText(fx.titles.draft, { exact: true }).click()
    await page.waitForURL(`**/shift/${fx.draftId}`, { timeout: 60_000 })
    await waitForHydration(page)
    await waitForSpinnerGone(page)

    await expect(
      page.getByText(fx.titles.draft, { exact: true }),
      'DRAFT の詳細が管理者に表示されること',
    ).toBeVisible({ timeout: 30_000 })
    await expect(
      page.getByRole('button', { name: LABEL_START_COLLECTING }),
      '管理者は DRAFT を次ステータスへ進める操作ができること',
    ).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText(LABEL_CALENDAR_VIEW)).toBeVisible()
  })

  test('A3: 管理者の COLLECTING / ADJUSTING 詳細では割当が伏せられず実人数バッジが出る（AC-10）', async ({ page }) => {
    for (const scheduleId of [fx.collectingId, fx.adjustingId]) {
      await openAs(page, ADMIN_EMAIL, ADMIN_PASSWORD, `/shift/${scheduleId}`)

      const chips = slotChips(page, scheduleId)
      await expect(chips, '枠チップが表示されること').toHaveCount(1, { timeout: 30_000 })
      await expect(
        chips.first(),
        `管理者には実割当人数 1/${SLOT_REQUIRED_COUNT} が出ること（伏せない）`,
      ).toContainText(`1/${SLOT_REQUIRED_COUNT}`)
      await expect(maskedMarks(page), '管理者の枠に伏せた印は付かないこと').toHaveCount(0)
    }
  })
})

// ============================================================================
// 【負】一般メンバー視点 — COLLECTING / ADJUSTING で割当が伏せられる（AC-4 / AC-9）
// ============================================================================
test.describe('B: 一般メンバーには調整段階の割当が伏せられる', () => {
  test('B1: COLLECTING の詳細は開けるが、充足バッジが出ず中立表示になる（AC-4(1)(3)(4)）', async ({ page }) => {
    const slotsResponse = waitForSlotsResponse(page, fx.collectingId)
    await openAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, `/shift/${fx.collectingId}`)

    // --- API 契約（AC-4(1)(2)(3)）: 画面の描画に関係なく、応答そのものを突き合わせる ---
    const response = await slotsResponse
    expect(response.status(), '非管理者にも COLLECTING の枠一覧は 200 で返ること').toBe(200)
    const rows = await readSlotRows(response)
    expect(rows.length, '枠が 1 件返ること').toBe(1)
    expect(rows[0]!.assignmentMasked, 'assignmentMasked が true であること（AC-4(1)）').toBe(true)
    expect(rows[0]!.assignedUserIds, 'assignedUserIds が空配列であること（AC-4(2)）').toEqual([])
    expect(rows[0]!.position.positionName, '枠の骨格は伏せないこと（AC-4(3)）').toBe(fx.positionName)
    expect(rows[0]!.position.requiredCount, '必要人数は伏せないこと（AC-4(3)）').toBe(SLOT_REQUIRED_COUNT)

    await expect(
      page.getByText(fx.titles.collecting, { exact: true }),
      'COLLECTING はメンバーにも表示されること（丸ごと閉じない）',
    ).toBeVisible({ timeout: 30_000 })

    const chips = slotChips(page, fx.collectingId)
    await expect(chips, '枠の骨格は見えること（AC-4(3)）').toHaveCount(1, { timeout: 30_000 })
    await expect(chips.first(), '枠の時刻が見えること').toContainText('09:00〜12:00')
    await expect(chips.first(), '枠のポジションが見えること').toContainText(fx.positionName)
    await expect(maskedMarks(page), '割当が伏せられた印が付くこと').toHaveCount(1)
    await expect(chips.first(), '中立表示の文言が出ること').toContainText(LABEL_MASKED)
    await expect(
      chips.first(),
      `充足バッジ（n/${SLOT_REQUIRED_COUNT}）が出ないこと`,
    ).not.toContainText(STAFFING_BADGE_PATTERN)
  })

  test('B2: ADJUSTING もメンバーに見え、割当だけが伏せられる（AC-9 / AC-4）', async ({ page }) => {
    const slotsResponse = waitForSlotsResponse(page, fx.adjustingId)
    await openAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, `/shift/${fx.adjustingId}`)

    const response = await slotsResponse
    expect(response.status(), '非管理者にも ADJUSTING の枠一覧は 200 で返ること').toBe(200)
    const rows = await readSlotRows(response)
    expect(rows.length, '枠が 1 件返ること').toBe(1)
    expect(rows[0]!.assignmentMasked, 'assignmentMasked が true であること（AC-4(1)）').toBe(true)
    expect(rows[0]!.assignedUserIds, 'assignedUserIds が空配列であること（AC-4(2)）').toEqual([])

    await expect(
      page.getByText(fx.titles.adjusting, { exact: true }),
      'ADJUSTING がメンバーの画面から消えないこと（AC-9）',
    ).toBeVisible({ timeout: 30_000 })

    const chips = slotChips(page, fx.adjustingId)
    await expect(chips).toHaveCount(1, { timeout: 30_000 })
    await expect(maskedMarks(page), '割当が伏せられた印が付くこと').toHaveCount(1)
    await expect(
      chips.first(),
      `充足バッジ（n/${SLOT_REQUIRED_COUNT}）が出ないこと`,
    ).not.toContainText(STAFFING_BADGE_PATTERN)
  })

  test('B3: PUBLISHED はメンバーにも全量（実割当人数バッジが出る）', async ({ page }) => {
    const slotsResponse = waitForSlotsResponse(page, fx.publishedId)
    await openAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, `/shift/${fx.publishedId}`)

    const response = await slotsResponse
    expect(response.status(), '公開済みの枠一覧は 200 で返ること').toBe(200)
    const rows = await readSlotRows(response)
    expect(rows[0]!.assignmentMasked, '公開後は伏せないこと').toBe(false)
    expect(rows[0]!.assignedUserIds, '公開後は実際の割当が返ること').toEqual([fx.memberUserId])

    await expect(
      page.getByText(fx.titles.published, { exact: true }),
      '公開済みはメンバーにも表示されること',
    ).toBeVisible({ timeout: 30_000 })

    const chips = slotChips(page, fx.publishedId)
    await expect(chips).toHaveCount(1, { timeout: 30_000 })
    await expect(
      chips.first(),
      `公開後は実割当人数 1/${SLOT_REQUIRED_COUNT} が出ること`,
    ).toContainText(`1/${SLOT_REQUIRED_COUNT}`)
    await expect(maskedMarks(page), '公開後に伏せた印は付かないこと').toHaveCount(0)
  })

  test('B4: PUBLISHED かつ publishedAt が NULL でも公開済みとして扱われる（AC-17）', async ({ page }) => {
    // §2.2(e) の非対称規則の番人。PUBLISHED は publishedAt を見ない、という決めが崩れると
    // ShiftSwapScopeContractIT / ShiftMapperTest が作る形のデータや、published_at に制約の無い
    // 本番データ（V3.070）で、公開済みシフトが非管理者から消える。
    const slotsResponse = waitForSlotsResponse(page, fx.publishedNoTimestampId)
    await openAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, `/shift/${fx.publishedNoTimestampId}`)

    const response = await slotsResponse
    expect(
      response.status(),
      'publishedAt が NULL でも枠一覧は 200（404 にならない）こと',
    ).toBe(200)
    const rows = await readSlotRows(response)
    expect(rows[0]!.assignmentMasked, '公開済み扱いなので伏せないこと').toBe(false)
    expect(rows[0]!.assignedUserIds, '公開済み扱いなので実際の割当が返ること').toEqual([fx.memberUserId])

    await expect(
      page.getByText(fx.titles.publishedNoTimestamp, { exact: true }),
      'publishedAt が NULL でもメンバーの画面に表示されること',
    ).toBeVisible({ timeout: 30_000 })

    const chips = slotChips(page, fx.publishedNoTimestampId)
    await expect(chips).toHaveCount(1, { timeout: 30_000 })
    await expect(
      chips.first(),
      `実割当人数 1/${SLOT_REQUIRED_COUNT} が出ること`,
    ).toContainText(`1/${SLOT_REQUIRED_COUNT}`)
    await expect(maskedMarks(page), '伏せた印は付かないこと').toHaveCount(0)
  })
})

// ============================================================================
// 【URL 直打ち】未公開シフトの詳細 URL を直接叩いても弾かれる（AC-2 / AC-7 / AC-1）
// ============================================================================
test.describe('C: 一般メンバーの URL 直打ちが弾かれる', () => {
  /**
   * 未公開シフトの詳細 URL を直接開いたとき、画面に何も出ないことを確かめる。
   *
   * 「ボタンが出ない」だけでは FE の出し分けを見ているにすぎず、BE が素通りする
   * IDOR を検出できない。ここでは URL を直接叩いて、タイトル・カレンダー表・枠チップの
   * いずれも描画されないこと（＝ BE が 404 を返していること）を画面で見る。
   */
  async function expectBlocked(page: Page, scheduleId: number, title: string): Promise<void> {
    await establishSession(page, MEMBER_EMAIL, MEMBER_PASSWORD)

    // 遷移より前に応答の待ち受けを張る。DOM の不在だけを見ると、403 / 500 / 通信失敗や
    // 「FE が白紙を描いただけ」でも緑になってしまう（実際、契約ずれで画面が何も描画されない
    // 局面が今日あった）。設計書 AC-2 / AC-3 は HTTP 404 を要求しているので、
    // 実際に飛んだ応答のステータスで確かめる。
    const scheduleResponse = page.waitForResponse(
      r => r.request().method() === 'GET' && isScheduleResponse(r.url(), scheduleId),
      { timeout: 120_000 },
    )
    const slotsResponse = page.waitForResponse(
      r => r.request().method() === 'GET' && isSlotsResponse(r.url(), scheduleId),
      { timeout: 120_000 },
    )

    await page.goto(`/shift/${scheduleId}`, { waitUntil: 'domcontentloaded', timeout: 180_000 })
    await waitForHydration(page)

    expect(
      (await scheduleResponse).status(),
      '単体取得が 404 であること（AC-2。403 や 500 では存在オラクルや別の欠陥が残る）',
    ).toBe(404)
    expect(
      (await slotsResponse).status(),
      '枠一覧が 404 であること（AC-3）',
    ).toBe(404)

    await waitForSpinnerGone(page)

    await expect(
      page.locator('.p-toast-message'),
      '取得失敗のエラートーストが出ること',
    ).toBeVisible({ timeout: 30_000 })
    await expect(
      page.getByText(title, { exact: true }),
      'シフト表のタイトルが一切表示されないこと',
    ).toHaveCount(0)
    await expect(
      page.getByText(LABEL_CALENDAR_VIEW),
      'カレンダー表（枠の中身）が描画されないこと',
    ).toHaveCount(0)
    await expect(slotChips(page, scheduleId), '枠チップが 1 件も出ないこと').toHaveCount(0)
  }

  test('C1: DRAFT の詳細 URL を直接叩いても表示されない（AC-2）', async ({ page }) => {
    await expectBlocked(page, fx.draftId, fx.titles.draft)
  })

  test('C2: 未公開のまま ARCHIVED にしたシフトも DRAFT と同様に弾かれる（AC-7）', async ({ page }) => {
    await expectBlocked(page, fx.archivedUnpublishedId, fx.titles.archived)
  })

  test('C3: メンバーが見る一覧に DRAFT / 未公開アーカイブが混ざらない（AC-1）', async ({ page }) => {
    // メンバーが listSchedules の結果を直接見る画面は希望提出フローのシフト表選択。
    // 比較対象（COLLECTING）が出ていることを先に確かめてから不在を主張する
    // （全部出ていないだけの偽 green を避けるため）。
    await openAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, '/my/shift-request')
    await selectTeamOnShiftRequest(page)

    await expect(
      page.getByText(fx.titles.collecting, { exact: true }),
      '希望収集中のシフト表は出ること（比較対象）',
    ).toBeVisible({ timeout: 30_000 })
    await expect(
      page.getByText(fx.titles.draft, { exact: true }),
      'DRAFT のタイトルが 1 件も出ないこと',
    ).toHaveCount(0)
    await expect(
      page.getByText(fx.titles.archived, { exact: true }),
      '未公開アーカイブのタイトルが 1 件も出ないこと',
    ).toHaveCount(0)
  })
})

// ============================================================================
// 【非回帰】希望提出フローが壊れていないこと（AC-8 / AC-4(3)）
// ============================================================================
test.describe('D: 一般メンバーの希望提出フロー（非回帰・本戦役の最重要点）', () => {
  test('D1: メンバーが COLLECTING のシフト表を選び、枠を見て希望を提出できる（AC-8）', async ({ page }) => {
    await openAs(page, MEMBER_EMAIL, MEMBER_PASSWORD, '/my/shift-request')
    await selectTeamOnShiftRequest(page)

    // ステップ2: COLLECTING のシフト表が並ぶ
    // （初回の軍議が起こしかけた「希望提出画面が空になる」機能回帰の番人）
    const scheduleCard = page.getByText(fx.titles.collecting, { exact: true })
    await expect(
      scheduleCard,
      '希望提出画面に COLLECTING のシフト表が出ること（空にならないこと）',
    ).toBeVisible({ timeout: 30_000 })
    await scheduleCard.click()

    // ステップ3: 枠の骨格（時刻・ポジション）が見える（AC-4(3)）
    await expect(
      page.getByText('09:00–12:00'),
      '枠の時刻が希望提出画面に出ること',
    ).toBeVisible({ timeout: 30_000 })
    await expect(
      page.getByText(fx.positionName, { exact: true }).first(),
      '枠のポジションが希望提出画面に出ること',
    ).toBeVisible()

    // 希望を選ぶ → プレビュー → 提出
    await page.getByRole('radio').first().click()
    await page.getByRole('button', { name: LABEL_PREVIEW }).click()
    await expect(page.getByText(LABEL_TOTAL)).toBeVisible({ timeout: 30_000 })
    await page.getByRole('button', { name: LABEL_SUBMIT, exact: true }).click()

    const toast = page.locator('.p-toast-message')
    await expect(toast, '提出結果のトーストが出ること').toBeVisible({ timeout: 30_000 })
    await expect(
      toast,
      '提出が成功すること（エラーで終わらないこと）',
    ).not.toContainText(LABEL_ERROR_TOAST)
  })
})

// ============================================================================
// 後始末: 共有チームを汚さないよう、作ったシフト表とポジションを消して原状復帰する
// （チーム自体は他セッション・他テストが使うので絶対に削除しない）
// ============================================================================
test.afterAll(async () => {
  const failures: string[] = []
  await withApi(ADMIN_EMAIL, ADMIN_PASSWORD, async (ctx, token) => {
    for (const scheduleId of createdScheduleIds) {
      const res = await ctx.delete(`${BE_API}/shifts/schedules/${scheduleId}`, {
        headers: authHeaders(token),
      })
      console.log(`[CLEANUP] DELETE /shifts/schedules/${scheduleId} -> ${res.status()}`)
      // 204 = 削除成功、404 = 既に無い。どちらも「残っていない」状態。それ以外は残骸が残る。
      if (res.status() !== 204 && res.status() !== 404) {
        failures.push(`schedule ${scheduleId} -> ${res.status()} ${await res.text()}`)
      }
    }
    if (createdPositionId !== null) {
      const res = await ctx.delete(`${BE_API}/shifts/positions/${createdPositionId}`, {
        headers: authHeaders(token),
      })
      console.log(`[CLEANUP] DELETE /shifts/positions/${createdPositionId} -> ${res.status()}`)
      if (res.status() !== 204 && res.status() !== 404) {
        failures.push(`position ${createdPositionId} -> ${res.status()} ${await res.text()}`)
      }
    }
  })
  createdScheduleIds.length = 0
  createdPositionId = null
  for (const session of apiSessions.values()) {
    await session.ctx.dispose()
  }
  apiSessions.clear()
  // 共有チームに残骸を残さないことは本 spec の責務。失敗を表示だけして緑にしない。
  expect(failures, '作成した前提データを後始末で全て削除できること').toEqual([])
})
