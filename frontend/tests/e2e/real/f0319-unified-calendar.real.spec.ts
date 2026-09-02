/**
 * F03.19 統合カレンダービュー — 実機 E2E（設計書 `docs/features/F03.19_unified_calendar_view.md`）。
 *
 * 検証層が E2E の受け入れ条件 25 件を実 BE・実 DB・実ブラウザで確認する。
 * `page.route` 等のモックは一切使わない。
 *
 * ## テストユーザーに e2e-outsider を使う理由（重要）
 *
 * `/calendar` のレイヤー凡例は `FILTER_OVERFLOW = 5` 件を超えると MultiSelect へ畳まれ、
 * `layer-chip-*` が DOM から消える（`CalendarLayerChips.vue`）。共有開発 DB の
 * `e2e-user` は所属レイヤーが **124 件**、`e2e-admin` は **177 件**あり、
 * チップ列に依存する AC（AC-02 / AC-03 / AC-10c / AC-11 / AC-11b / AC-12c / AC-23）は
 * これらのユーザーでは**原理的に検証できない**。
 * `e2e-outsider` は所属ゼロ（レイヤーは PERSONAL の 1 件のみ）であり、
 * 本 spec が API で作るチーム 2 件を足しても 3〜4 チップに収まる。
 *
 * ## 表示対象を「翌月の、月内に完全に収まる週」に置く理由（重要）
 *
 * `DELETE /api/v1/teams/{slug}` はチームを消しても配下の予定行を消さない。残った予定は
 * 元メンバーの `/my/calendar` から返り続けるが、チームが引けないため **API では二度と
 * 削除できない**（`/teams/{slug}/schedules/{id}` も `/teams/{slug}/restore` も 404）。
 * F03.19 のフォールバックチップ（§5.2.1）はこの予定を可視化するため、孤児が 1 スコープ
 * 増えるごとにチップが 1 つ増え、5 件を超えるとチップ列自体が畳まれてしまう。
 * 今月には過去の実行が残した孤児が既に存在するため、本 spec は**フィクスチャと表示対象を
 * 翌月の週へ寄せる**（そこには孤児が無い）。フィクスチャチームを消すときは
 * **必ず配下の予定を先に消す**（{@link deleteTeamWithSchedules}）ことで孤児を増やさない。
 *
 * ## 1ファイル = 1ログイン
 *
 * 複数ファイルで同一ユーザーのセッションを取り合うと干渉して大量に落ちるため、
 * 本 spec はファイル内で 1 度だけログインし、以降は同一 BrowserContext を使い回す。
 */
import {
  test,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type BrowserContext,
  type CDPSession,
  type Locator,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const OUTSIDER = { email: 'e2e-outsider@test.mannschaft.local', password: 'TestPass2026!' }

/** フィクスチャ識別用の接頭辞（掃除の対象判定に使う）。 */
const PREFIX = 'F0319E2E'
const RUN = `${PREFIX}-${Date.now()}`

/** レイヤー選択・ビューの永続化キー（`useMyCalendarData.ts` の LAYER_STATE_KEY と同じ）。 */
const LAYER_STATE_KEY = 'mannschaft:calendar:layerState'
/** 色変更で選ぶパレット色（赤）。`CALENDAR_LAYER_PALETTE` に含まれる値。 */
const RED = '#DC2626'
const RED_RGB = 'rgb(220, 38, 38)'

type ScopeType = 'PERSONAL' | 'TEAM' | 'ORGANIZATION'

interface Layer {
  scopeType: ScopeType
  scopeId: number
  scopeName: string
  color: string
  colorSource: string
  hidden: boolean
}

interface CalendarEntry {
  id: number | null
  content: { title: string; color: string | null; colorSource: string | null }
  time: { startAt: string; endAt: string; allDay: boolean }
  scope: { scopeType: string; scopeId: number | null; scopeName: string | null; scopeSlug: string | null }
}

// ---------------------------------------------------------------------------
// 日付ユーティリティ（すべて Asia/Tokyo の壁時計で扱う。端末 TZ は参照しない）
// ---------------------------------------------------------------------------

function jstToday(): { y: number; m: number; d: number } {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date())
  const pick = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find(p => p.type === type)?.value)
  return { y: pick('year'), m: pick('month'), d: pick('day') }
}

function iso(date: Date): string {
  return date.toISOString().slice(0, 10)
}

function utc(y: number, m: number, d: number): Date {
  return new Date(Date.UTC(y, m - 1, d))
}

/** 日曜起点の週の起点日（`CalendarWeekGrid` と同じ規約）。 */
function sundayOf(date: Date): Date {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate() - date.getUTCDay()))
}

function addDays(date: Date, days: number): Date {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate() + days))
}

const TODAY_PARTS = jstToday()
const TODAY = utc(TODAY_PARTS.y, TODAY_PARTS.m, TODAY_PARTS.d)
const CURRENT_MONTH_PREFIX = iso(TODAY).slice(0, 7)

/**
 * 表示対象の週 — **翌月の 8〜14 日を含む週**。
 * この週の日曜は翌月の 2〜8 日、土曜は 8〜14 日なので、**必ず翌月の中に完全に収まる**。
 * 月ビュー（翌月）と週ビューの双方で同じ予定が見えることが保証される。
 */
const NEXT_MONTH_8TH = utc(TODAY.getUTCFullYear(), TODAY.getUTCMonth() + 2, 8)
const TARGET_SUNDAY = sundayOf(NEXT_MONTH_8TH)
const TARGET_WEEK = Array.from({ length: 7 }, (_, i) => iso(addDays(TARGET_SUNDAY, i)))
const TARGET_MONTH_PREFIX = TARGET_WEEK[0]!.slice(0, 7)

function monthRange(prefix: string): { from: string; to: string } {
  const y = Number(prefix.slice(0, 4))
  const m = Number(prefix.slice(5, 7))
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate()
  return { from: `${prefix}-01T00:00:00`, to: `${prefix}-${String(last).padStart(2, '0')}T23:59:59` }
}

const TARGET_RANGE = monthRange(TARGET_MONTH_PREFIX)
const CURRENT_RANGE = monthRange(CURRENT_MONTH_PREFIX)
/** 掃除用の広い範囲（今月〜3か月先）。 */
const WIDE_FROM = CURRENT_RANGE.from
const WIDE_TO = monthRange(iso(utc(TODAY.getUTCFullYear(), TODAY.getUTCMonth() + 4, 1)).slice(0, 7)).to

// フィクスチャを置く日（すべて表示対象の週＝翌月の中）
const EVENTS_DATE = TARGET_WEEK[1]! // 月曜: 時刻付き・終日・複数日・個人
const OVERFLOW_DATE = TARGET_WEEK[2]! // 火曜: 同日 5 件（月ビューの「+N件」用）
/** 木曜（週の 4 番目）: フィクスチャを1件も置かない列。ドラッグ・キーボード操作の対象。 */
const DRAG_DAY_INDEX = 4

// ---------------------------------------------------------------------------
// API ヘルパー
// ---------------------------------------------------------------------------

let api: APIRequestContext
let token = ''
/** アクセストークンを取得した時刻(ms)。有効期限の管理に使う。 */
let tokenIssuedAt = 0

/**
 * アクセストークンの寿命(ms)に対する再取得の閾値。
 *
 * BE のアクセストークンは `expiresIn: 900`（15分）である。本 spec のフルランは実測で
 * **16分を超える**ため、beforeAll で1度取ったきりにすると**終盤のテストだけが 401 で落ちる**
 * （実際に AC-25 がこれで落ちた。実装の欠陥でも #3051 の回帰でもなく、テストが実行時間に
 * 依存していた）。API を叩く直前に期限が近ければ取り直し、時間依存を断つ。
 * 余裕を持って 10 分で切り替える。
 */
const TOKEN_REFRESH_AFTER_MS = 10 * 60 * 1000

function authHeaders(): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function login(): Promise<void> {
  const res = await api.post(`${API}/api/v1/auth/login`, { data: OUTSIDER })
  expect(res.status(), `${OUTSIDER.email} の実ログイン`).toBe(200)
  token = ((await res.json()) as { data: { accessToken: string } }).data.accessToken
  tokenIssuedAt = Date.now()
}

/** トークンが古ければ取り直す。すべての API ヘルパーの先頭で呼ぶ。 */
async function freshAuthHeaders(): Promise<Record<string, string>> {
  if (Date.now() - tokenIssuedAt >= TOKEN_REFRESH_AFTER_MS) await login()
  return authHeaders()
}

async function getLayers(): Promise<Layer[]> {
  const res = await api.get(`${API}/api/v1/me/calendar-layers`, { headers: await freshAuthHeaders() })
  expect(res.status(), 'レイヤー一覧の取得').toBe(200)
  return ((await res.json()) as { data: Layer[] }).data
}

async function getCalendar(from = TARGET_RANGE.from, to = TARGET_RANGE.to): Promise<CalendarEntry[]> {
  const res = await api.get(`${API}/api/v1/my/calendar?from=${from}&to=${to}`, { headers: await freshAuthHeaders() })
  expect(res.status(), `横断カレンダーの取得 (${from}〜${to})`).toBe(200)
  return ((await res.json()) as { data: CalendarEntry[] }).data
}

async function createTeam(name: string): Promise<{ slug: string; numericId: number }> {
  const res = await api.post(`${API}/api/v1/teams`, {
    headers: await freshAuthHeaders(),
    data: { name, sportType: 'SOCCER', description: `${RUN} fixture` },
  })
  expect(res.status(), `チーム作成: ${name}`).toBe(201)
  const body = (await res.json()) as { data: { slug: string; numericId: number } }
  return { slug: body.data.slug, numericId: body.data.numericId }
}

async function createTeamSchedule(
  slug: string, title: string, startAt: string, endAt: string, allDay = false,
): Promise<number> {
  const res = await api.post(`${API}/api/v1/teams/${slug}/schedules`, {
    headers: await freshAuthHeaders(),
    data: {
      title, startAt, endAt, allDay,
      eventType: 'OTHER', targetMode: 'ALL_MEMBERS', targetUserIds: [], attendanceRequired: false,
    },
  })
  expect(res.status(), `チーム予定作成: ${title}`).toBe(201)
  return ((await res.json()) as { data: { id: number } }).data.id
}

async function createPersonalSchedule(title: string, startAt: string, endAt: string): Promise<number> {
  const res = await api.post(`${API}/api/v1/me/schedules`, {
    headers: await freshAuthHeaders(),
    data: { title, startAt, endAt, allDay: false },
  })
  expect(res.status(), `個人予定作成: ${title}`).toBe(201)
  return ((await res.json()) as { data: { id: number } }).data.id
}

/**
 * チームを、配下の予定を**先に**消してから削除する。
 *
 * 順序を逆にすると予定行が孤児になり、API では二度と消せなくなる（本ファイル冒頭参照）。
 */
async function deleteTeamWithSchedules(slug: string): Promise<void> {
  for (const entry of await getCalendar(WIDE_FROM, WIDE_TO)) {
    if (entry.id && entry.scope.scopeSlug === slug) {
      const del = await api.delete(`${API}/api/v1/teams/${slug}/schedules/${entry.id}`, {
        headers: await freshAuthHeaders(),
      })
      expect([204, 404], `チーム ${slug} の予定 ${entry.id} の削除`).toContain(del.status())
    }
  }
  const del = await api.delete(`${API}/api/v1/teams/${slug}`, { headers: await freshAuthHeaders() })
  expect([204, 404], `チーム ${slug} の削除`).toContain(del.status())
}

/** JST 壁時計での ISO 8601（オフセット付き）。BE も FE もこの形で扱う。 */
function at(date: string, time: string): string {
  return `${date}T${time}:00+09:00`
}

// ---------------------------------------------------------------------------
// フィクスチャの実体
// ---------------------------------------------------------------------------

let teamA = { slug: '', numericId: 0, name: '' }
let teamB = { slug: '', numericId: 0, name: '' }
/** レイヤー一覧に載らないスコープ（削除済みチーム）の numericId（AC-23）。 */
let fallbackTeamId = 0
let fallbackTitle = ''

const titles = {
  timed: `${RUN}-TIMED`,
  allDay: `${RUN}-ALLDAY`,
  multiDay: `${RUN}-MULTI`,
  personal: `${RUN}-PERSONAL`,
  widget: `${RUN}-WIDGET`,
  overflow: (i: number) => `${RUN}-OVF${i}`,
  dragCreated: `${RUN}-DRAG-SAVED`,
}

const createdTeamSlugs: string[] = []

// ---------------------------------------------------------------------------
// 前回実行の残骸掃除
// ---------------------------------------------------------------------------

async function cleanupStale(): Promise<void> {
  const res = await api.get(`${API}/api/v1/me/teams?limit=200`, { headers: await freshAuthHeaders() })
  expect(res.status(), '所属チーム一覧').toBe(200)
  const teams = ((await res.json()) as {
    data: Array<{ slug: string; basicInfo?: { name: string }; name?: string }>
  }).data
  for (const team of teams) {
    const name = team.basicInfo?.name ?? team.name ?? ''
    if (name.startsWith(PREFIX)) await deleteTeamWithSchedules(team.slug)
  }

  for (const entry of await getCalendar(WIDE_FROM, WIDE_TO)) {
    if (entry.id && entry.content.title.startsWith(PREFIX) && entry.scope.scopeType === 'PERSONAL') {
      const del = await api.delete(`${API}/api/v1/me/schedules/${entry.id}`, { headers: await freshAuthHeaders() })
      expect([204, 404], `古い個人予定 ${entry.id} の削除`).toContain(del.status())
    }
  }

  // レイヤー設定行を消して「1行も無い」状態から始める（AC-15d の前提）
  for (const layer of await getLayers()) {
    const del = await api.delete(
      `${API}/api/v1/me/calendar-layers/${layer.scopeType}/${layer.scopeId}`,
      { headers: await freshAuthHeaders() },
    )
    expect([204, 404], `レイヤー設定 ${layer.scopeType}:${layer.scopeId} の初期化`).toContain(del.status())
  }
}

/**
 * フォールバックチップ（AC-23）の前提を整える。
 *
 * 「レイヤー一覧に無いスコープの予定」は、チームに予定を作ってからチームを削除すると生じる。
 * この予定は二度と削除できないため、**表示対象の月に既に孤児があればそれを使い、無いときだけ
 * 1 スコープだけ作る**（月あたり最大 1 スコープに抑える）。
 */
async function ensureFallbackFixture(): Promise<void> {
  const layerKeySet = new Set((await getLayers()).map(l => `${l.scopeType}:${l.scopeId}`))
  const orphan = (await getCalendar()).find(
    e => e.scope.scopeType !== 'PERSONAL'
      && e.scope.scopeId != null
      && !layerKeySet.has(`${e.scope.scopeType}:${e.scope.scopeId}`),
  )
  if (orphan) {
    fallbackTeamId = orphan.scope.scopeId as number
    fallbackTitle = orphan.content.title
    return
  }
  const team = await createTeam(`${PREFIX}-FB-${Date.now()}`)
  fallbackTitle = `${RUN}-FALLBACK`
  await createTeamSchedule(team.slug, fallbackTitle, at(EVENTS_DATE, '20:00'), at(EVENTS_DATE, '21:00'))
  const del = await api.delete(`${API}/api/v1/teams/${team.slug}`, { headers: await freshAuthHeaders() })
  expect(del.status(), 'フォールバック用チームの削除（予定は意図的に残す）').toBe(204)
  fallbackTeamId = team.numericId
}

// ---------------------------------------------------------------------------
// ブラウザ側ヘルパー
// ---------------------------------------------------------------------------

let context: BrowserContext
let page: Page

function layerKeys(): { personal: string; teamA: string; teamB: string; fallback: string } {
  return {
    personal: 'PERSONAL:0',
    teamA: `TEAM:${teamA.numericId}`,
    teamB: `TEAM:${teamB.numericId}`,
    fallback: `TEAM:${fallbackTeamId}`,
  }
}

function spinner(target: Page = page): Locator {
  return target.locator('.absolute.inset-0 .p-progressspinner')
}

// `:visible` を付ける理由: `/calendar` はモバイル用リスト（`md:hidden`）とデスクトップ用
// カレンダー（`hidden md:grid`）の両方を DOM に持つため、幅に関わらず月ナビのボタンが
// 2 組存在する。付けないと画面幅と無関係に「先に現れる方（非表示側）」を掴んで固まる。
function nextMonthButton(target: Page = page): Locator {
  return target.locator('button:visible:has(.pi-chevron-right)').first()
}

function prevMonthButton(target: Page = page): Locator {
  return target.locator('button:visible:has(.pi-chevron-left)').first()
}

/**
 * localStorage のレイヤー状態を明示してから `/calendar` を開き、**表示対象の月／週**へ移動する。
 *
 * 前のテストが残した選択状態への暗黙依存を断つためのもの。永続化そのものが検証対象の
 * テスト（AC-13c）では使わない。
 */
async function openCalendar(options: {
  view?: 'month' | 'week' | 'agenda'
  selected?: string[]
} = {}): Promise<void> {
  const keys = layerKeys()
  const selected = options.selected ?? [keys.personal, keys.teamA, keys.teamB, keys.fallback]
  const view = options.view ?? 'month'
  // 永続化する view は常に 'month'。週ビューへは「表示月を対象月へ動かしてから」切り替える
  // 必要があるため（下記 setViewTo のコメント参照）、開いた瞬間に週ビューにはしない。
  //
  // `page.addInitScript` を使わない理由: 追加した初期化スクリプトはページに永続的に積まれ、
  // **以後すべての遷移とリロードで再実行される**。永続化そのものを検証するテスト（AC-13c）で
  // リロードのたびに状態が上書きされ、「週にしてリロードしても月に戻る」という嘘の失敗を生む。
  // 現在のページ（同一オリジン）で 1 度だけ書いてから遷移する。
  if (!page.url().startsWith('http')) await page.goto('/')
  await page.evaluate(
    ({ key, state }) => window.localStorage.setItem(key, JSON.stringify(state)),
    {
      key: LAYER_STATE_KEY,
      // knownFallbackKeys には既知のキーを常に全部入れる。ここを `selected` と同じにすると、
      // 「全レイヤー非選択」を作りたい場面（AC-11b）でフォールバックチップが
      // 「初めて見たキー」と判定されて自動選択され、前提が崩れる（§5.2.1）。
      state: {
        version: 2,
        selected,
        view: 'month',
        knownFallbackKeys: [keys.personal, keys.teamA, keys.teamB, keys.fallback],
      },
    },
  )
  await page.goto('/calendar')
  await waitForHydration(page)
  await gotoTargetMonth()
  if (view !== 'month') await setViewTo(view)
}

/** 月ビューのまま表示月を対象月（翌月）へ 1 つ進める。 */
async function gotoTargetMonth(): Promise<void> {
  await nextMonthButton().click()
  // 月ラベルもモバイル用リストと二重に存在するため、可視な方だけを見る。
  await expect(page.getByText(
    `${TARGET_MONTH_PREFIX.slice(0, 4)}年${Number(TARGET_MONTH_PREFIX.slice(5, 7))}月`,
  ).filter({ visible: true }).first()).toBeVisible()
  await expect(spinner()).toHaveCount(0)
  await expect(page.getByTestId('calendar-layer-chips')).toBeVisible()
}

/**
 * ビューを切り替える（週ビューでは対象週まで進める）。
 *
 * **必ず表示月を対象月へ動かしてから呼ぶこと。** `calendar.vue#setView` は、表示月が今月で
 * なければ「表示月の1日を含む週」を週ビューの起点にする。取得範囲は表示月の6週グリッド
 * （＝前月末〜翌月頭を含む）なので、今月のまま週送りすると取得範囲が今月のままになり、
 * 今月に残っている孤児予定（本ファイル冒頭参照）が読み込まれてチップ列が畳まれてしまう。
 */
async function setViewTo(view: 'week' | 'agenda'): Promise<void> {
  await page.getByTestId(`calendar-view-${view}`).click()
  if (view === 'agenda') {
    await expect(page.getByTestId('agenda-list')).toBeVisible()
  }
  else {
    await expect(page.getByTestId('week-grid-columns')).toBeVisible()
    await gotoTargetWeek()
  }
  await expect(spinner()).toHaveCount(0)
  await expect(page.getByTestId('calendar-layer-chips')).toBeVisible()
}

/**
 * `week-day-header-{i}` が示す「日」を数値で読む。
 *
 * ヘッダーは曜日ラベルと日にちだけを描く（`CalendarWeekGrid.vue`）ので、最初に現れる
 * 数字がその日である。**部分一致で判定してはならない** — 例えば目的の日が 6 日のとき、
 * 別の週の月曜が 26 日だと `includes('6')` が真になり、**間違った週で止まる**。
 */
async function weekHeaderDay(dayIndex: number): Promise<number> {
  const text = await page.getByTestId(`week-day-header-${dayIndex}`).innerText()
  const matched = text.match(/\d+/)
  expect(matched, `week-day-header-${dayIndex} に日付が含まれる`).not.toBeNull()
  return Number(matched![0])
}

/**
 * 表示中の週を、フィクスチャを置いた週（`EVENTS_DATE` を含む週）まで `week-next` で進める。
 *
 * 押す回数を日付から計算せず、**実際に描かれたヘッダーを見て収束させる**。週ビューの起点は
 * `calendar.vue#setView` が表示月から決めるため、「今日の週から N 回」という前提が成り立たない。
 * 到達したことは計算ではなく表示で確かめる。
 */
async function gotoTargetWeek(): Promise<void> {
  const targetDay = Number(EVENTS_DATE.slice(8))
  for (let i = 0; i < 6; i++) {
    if (await weekHeaderDay(1) === targetDay) break
    await page.getByTestId('week-next').click()
  }
  expect(await weekHeaderDay(1), '表示対象の週へ移動できた').toBe(targetDay)
}

/** 月ビューの日付セル（当月の日付数字で特定する）。 */
function monthDayCell(day: string): Locator {
  return page.locator('.grid.grid-cols-7:visible > div')
    .filter({ has: page.getByText(day, { exact: true }) }).first()
}

function chip(value: string): Locator {
  return page.getByTestId(`layer-chip-${value}`)
}

async function chipValues(): Promise<string[]> {
  // `layer-chip-dot`（色ドット）と `layer-chip-more-*`（…ボタン）は同じ接頭辞を持つので除外する。
  return page.locator('[data-testid^="layer-chip-"]').evaluateAll(nodes => nodes
    .map(n => n.getAttribute('data-testid') ?? '')
    .filter(id => id !== 'layer-chip-dot' && !id.startsWith('layer-chip-more-'))
    .map(id => id.replace('layer-chip-', '')))
}

async function chipStates(values: string[]): Promise<Array<string | null>> {
  return Promise.all(values.map(v => chip(v).getAttribute('aria-pressed')))
}

/**
 * 週ビューのスロット上端（+1px）の client 座標。
 *
 * 中心ではなく上端を使う理由は AC-21 のコメントを参照。
 * **座標を測る前にスクロールを済ませておくこと**（{@link scrollSlotsIntoView}）。
 * 測ってからスクロールすると、先に測った座標が現在の画面位置とずれる。
 */
async function slotTopPoint(
  dayIndex: number, hour: number, minute: number, target: Page = page,
): Promise<{ x: number; y: number }> {
  const box = await target.getByTestId(`week-slot-${dayIndex}-${hour}-${minute}`).boundingBox()
  expect(box, `week-slot-${dayIndex}-${hour}-${minute} の bounding box`).not.toBeNull()
  return { x: box!.x + box!.width / 2, y: box!.y + 1 }
}

/**
 * ドラッグで使う複数スロットを、**すべて同時に画面へ入れてから**測れるようにする。
 *
 * 終端側を先にスクロールインし、次に始端側を入れる。1時間 = 48px なので数時間ぶんの
 * 範囲は同一ビューポートに収まる。この順序を守らないと、始端を測ったあとに終端の
 * スクロールが走り、始端の座標が画面外を指してドラッグが成立しない（19:00 のように
 * 初期スクロール位置から外れた時間帯で実際に踏んだ）。
 */
async function scrollSlotsIntoView(dayIndex: number, slots: Array<[number, number]>): Promise<void> {
  for (const [hour, minute] of [...slots].reverse()) {
    await page.getByTestId(`week-slot-${dayIndex}-${hour}-${minute}`).scrollIntoViewIfNeeded()
  }
}

/** 週ビューのスロット間を実座標でドラッグする（マウス）。 */
async function dragSlots(
  dayIndex: number, from: [number, number], to: [number, number],
  options: { release?: boolean } = {},
): Promise<void> {
  await scrollSlotsIntoView(dayIndex, [from, to])
  const start = await slotTopPoint(dayIndex, from[0], from[1])
  const end = await slotTopPoint(dayIndex, to[0], to[1])
  await page.mouse.move(start.x, start.y)
  await page.mouse.down()
  // 途中点を挟む。pointermove が 1 回しか飛ばないと、閾値（4px）を超えたことが
  // ハイライト表示へ反映される前に確定してしまう。
  await page.mouse.move(end.x, (start.y + end.y) / 2, { steps: 5 })
  await page.mouse.move(end.x, end.y, { steps: 5 })
  if (options.release !== false) await page.mouse.up()
}

function dialog(target: Page = page): Locator {
  return target.locator('.p-dialog').filter({ hasText: /予定を追加|イベントを作成/ })
}

/** 作成ダイアログの開始/終了時刻（PrimeVue Select のラベル文字列 `HH:mm`）。 */
async function dialogTimes(): Promise<{ start: string; end: string }> {
  const texts = await dialog().locator('.p-select-label').allInnerTexts()
  expect(texts.length, 'ダイアログ内に開始時刻・終了時刻の Select がある').toBeGreaterThanOrEqual(2)
  return { start: texts[0]!.trim(), end: texts[1]!.trim() }
}

async function closeDialog(): Promise<void> {
  await dialog().getByRole('button', { name: 'キャンセル' }).click()
  await expect(dialog()).toBeHidden()
}

/**
 * `/api/v1/**` へのリクエストを記録する。フィルタ操作・ビュー切替が通信を伴わないことの検証用。
 *
 * `stop()` は全件、`stopDataOnly()` は**カレンダーのデータ取得だけ**を返す。認証セッションの
 * 維持（`/auth/*`・`/users/me`）はカレンダーの操作とは無関係に走るため、AC-12c / AC-13 が
 * 問うている「フィルタやビュー切替がデータを取り直さないこと」の判定からは外す
 * （通信そのものを見逃さないよう、除外した分も失敗時のメッセージに出す）。
 */
function countApiRequests(): { stop: () => string[]; stopDataOnly: () => string[] } {
  const urls: string[] = []
  const handler = (req: { url: () => string }) => {
    if (req.url().includes('/api/v1/')) urls.push(req.url())
  }
  page.on('request', handler)
  const stop = () => { page.off('request', handler); return urls }
  return {
    stop,
    stopDataOnly: () => stop().filter(u => !/\/api\/v1\/(auth\/|users\/me)/.test(u)),
  }
}

async function selectCreateScope(name: string): Promise<void> {
  const select = page.locator('.p-select').first()
  await select.click()
  await page.locator('.p-select-overlay li', { hasText: name }).first().click()
  await expect(select).toContainText(name)
}

// ---------------------------------------------------------------------------
// セットアップ
// ---------------------------------------------------------------------------

// serial にはしない。1 件でも落ちると後続が丸ごと skip され、25 件の受け入れ条件のうち
// どれが通りどれが落ちたのかが分からなくなるため（skip の山は「検証した」とは言えない）。
// 実行順の安定と相互干渉の回避は playwright-f0319.config.ts の `fullyParallel: false` /
// `workers: 1` と、各テスト冒頭の openCalendar による状態リセットで担保する。

test.beforeAll(async ({ browser }) => {
  api = await playwrightRequest.newContext()
  await login()
  await cleanupStale()

  teamA = { ...(await createTeam(`${PREFIX}-A-${Date.now()}`)), name: `${PREFIX}-A` }
  createdTeamSlugs.push(teamA.slug)
  teamB = { ...(await createTeam(`${PREFIX}-B-${Date.now()}`)), name: `${PREFIX}-B` }
  createdTeamSlugs.push(teamB.slug)

  // チーム A の予定（チーム B には 1 件も作らない = AC-02 の前提）
  await createTeamSchedule(teamA.slug, titles.timed, at(EVENTS_DATE, '13:00'), at(EVENTS_DATE, '14:00'))
  await createTeamSchedule(teamA.slug, titles.allDay, at(EVENTS_DATE, '00:00'), at(EVENTS_DATE, '23:59'), true)
  await createTeamSchedule(
    teamA.slug, titles.multiDay, at(EVENTS_DATE, '00:00'), at(OVERFLOW_DATE, '23:59'), true,
  )
  for (let i = 1; i <= 5; i++) {
    const hour = String(7 + i).padStart(2, '0')
    await createTeamSchedule(
      teamA.slug, titles.overflow(i), at(OVERFLOW_DATE, `${hour}:00`), at(OVERFLOW_DATE, `${hour}:30`),
    )
  }
  // ダッシュボードのウィジェットは常に「今月」を描くため、今月にも 1 件置く（AC-15）
  await createTeamSchedule(
    teamA.slug, titles.widget,
    at(`${CURRENT_MONTH_PREFIX}-15`, '10:00'), at(`${CURRENT_MONTH_PREFIX}-15`, '11:00'),
  )
  await createPersonalSchedule(titles.personal, at(EVENTS_DATE, '16:00'), at(EVENTS_DATE, '17:00'))

  await ensureFallbackFixture()

  // ブラウザ側は 1 ファイル 1 ログイン。以降すべてのテストがこの context を共有する。
  context = await browser.newContext({
    viewport: { width: 1440, height: 1000 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo',
  })
  page = await context.newPage()
  await loginViaApi(page, OUTSIDER, { apiBaseUrl: API })
})

test.afterAll(async () => {
  if (token) {
    for (const slug of createdTeamSlugs) await deleteTeamWithSchedules(slug)
    for (const entry of await getCalendar(WIDE_FROM, WIDE_TO)) {
      if (entry.id && entry.scope.scopeType === 'PERSONAL' && entry.content.title.startsWith(PREFIX)) {
        await api.delete(`${API}/api/v1/me/schedules/${entry.id}`, { headers: await freshAuthHeaders() })
      }
    }
    for (const layer of await getLayers()) {
      await api.delete(
        `${API}/api/v1/me/calendar-layers/${layer.scopeType}/${layer.scopeId}`,
        { headers: await freshAuthHeaders() },
      )
    }
  }
  await context?.close()
  await api?.dispose()
})

// ---------------------------------------------------------------------------
// 環境の裏取り — これが落ちたら以降の緑は信用してはならない
// ---------------------------------------------------------------------------

test('環境確認: 検証用 BE(8081) を向いており、色変更UIを含む最新版が動いている', async () => {
  const seen = countApiRequests()
  await openCalendar()
  const urls = seen.stop()
  expect(urls.length, 'カレンダー表示で API を呼んでいる').toBeGreaterThan(0)

  // 禁止したいのは**本陣（8080 / 3000）を向くこと**。FE の dev サーバー自身（3001）を
  // 経由する呼び出しは構成上ありうるので、host の否定形で判定する。
  const hosts = [...new Set(urls.map(u => new URL(u).host))]
  const forbidden = hosts.filter(h => h.endsWith(':8080') || h.endsWith(':3000'))
  expect(forbidden, `本陣（8080/3000）へは向いていない（実際の host: ${hosts.join(',')}）`).toEqual([])
  expect(hosts, `検証用 BE ${new URL(API).host} を直接叩いている`).toContain(new URL(API).host)

  // 色変更 UI（F03.19 の当該コミットで入った要素）が存在すること。
  // 古い版には layer-chip-more-* が存在しないため、これが最新版であることの裏取りになる。
  await expect(page.getByTestId(`layer-chip-more-${layerKeys().teamA}`)).toBeVisible()
})

// ---------------------------------------------------------------------------
// (a) レイヤーの実体化・色
// ---------------------------------------------------------------------------

test('AC-15d: レイヤー設定が1行も無くても /calendar が描画され、全レイヤーが自動色になる', async () => {
  // beforeAll の cleanupStale が user_calendar_layer_settings を全削除している。
  const layers = await getLayers()
  expect(layers.length, 'レイヤーが返る').toBeGreaterThan(0)
  for (const l of layers) {
    expect(l.colorSource, `${l.scopeType}:${l.scopeId} は自動色`).toBe('LAYER_AUTO')
    expect(l.color, `${l.scopeType}:${l.scopeId} の色が #RRGGBB`).toMatch(/^#[0-9A-F]{6}$/)
  }

  await openCalendar()
  const keys = layerKeys()
  for (const value of [keys.personal, keys.teamA, keys.teamB]) {
    await expect(chip(value)).toBeVisible()
    const bg = await chip(value).getByTestId('layer-chip-dot')
      .evaluate(el => getComputedStyle(el).backgroundColor)
    expect(bg, `${value} の色ドットが塗られている`).not.toBe('rgba(0, 0, 0, 0)')
  }
})

test('AC-02: 予定が1件も無いチームのレイヤーチップが、チーム名つきで画面に出る', async () => {
  await openCalendar()

  const entries = await getCalendar(WIDE_FROM, WIDE_TO)
  expect(
    entries.filter(e => e.scope.scopeType === 'TEAM' && e.scope.scopeId === teamB.numericId),
    'チーム B には予定が無い',
  ).toHaveLength(0)

  await expect(chip(layerKeys().teamB)).toBeVisible()
  await expect(chip(layerKeys().teamB)).toContainText(teamB.name)
})

test('AC-03: 月を「翌月→翌月→前月→前月」と往復してもチップの個数と並び順が変わらない', async () => {
  await openCalendar({ view: 'month' })
  const before = await chipValues()
  expect(before.length, 'チップが並んでいる').toBeGreaterThan(0)

  for (const button of [nextMonthButton(), nextMonthButton(), prevMonthButton(), prevMonthButton()]) {
    await button.click()
    await expect(spinner()).toHaveCount(0, { timeout: 20_000 })
    await expect(page.getByTestId('calendar-layer-chips')).toBeVisible()
  }

  expect(await chipValues(), '往復後もチップの個数・並び順が同一').toEqual(before)
})

test('AC-10c: レイヤー色を赤に変更すると、リロード後もその予定が赤で描かれる（サーバー保存）', async () => {
  await openCalendar({ view: 'week' })
  const key = layerKeys().teamA

  await page.getByTestId(`layer-chip-more-${key}`).click()
  await expect(page.getByTestId(`layer-color-popover-${key}`)).toBeVisible()
  await page.getByTestId(`layer-color-${RED}`).click()
  await expect(page.getByTestId(`layer-color-popover-${key}`)).toBeHidden()

  // サーバーに保存されたことを API でも裏取りする（FE のローカル状態だけの変化ではない）
  await expect.poll(async () => {
    const layer = (await getLayers()).find(l => l.scopeType === 'TEAM' && l.scopeId === teamA.numericId)
    return `${layer?.color}/${layer?.colorSource}`
  }, { message: 'レイヤー色がサーバーへ保存される' }).toBe(`${RED}/LAYER_USER`)

  // リロード後も赤で描かれること（週ビューの予定バーは border-left に素の色を持つ）
  // 画面を作り直して読み込み直す（色は localStorage に持たないので、赤く出るなら
  // サーバー保存が効いている証拠になる）。`page.reload()` ではなく openCalendar を使うのは、
  // 表示月・ビューを対象週へ確実に戻すため。
  await openCalendar({ view: 'week' })
  const bar = page.locator('[data-testid^="week-event-"]').filter({ hasText: titles.timed }).first()
  await bar.scrollIntoViewIfNeeded()
  await expect(bar).toBeVisible()
  expect(
    await bar.evaluate(el => getComputedStyle(el).borderLeftColor),
    'リロード後も赤で描かれる',
  ).toBe(RED_RGB)

  // 後続テストのために自動色へ戻す
  await page.getByTestId(`layer-chip-more-${key}`).click()
  await page.getByTestId(`layer-color-auto-${key}`).click()
  await expect.poll(async () => {
    const layer = (await getLayers()).find(l => l.scopeType === 'TEAM' && l.scopeId === teamA.numericId)
    return layer?.colorSource
  }).toBe('LAYER_AUTO')
})

// ---------------------------------------------------------------------------
// (c)(d) 表示欠落・操作性・結合切り
// ---------------------------------------------------------------------------

test('AC-11: 作成スコープ Select を変更しても、表示中のレイヤーチップの選択状態が変わらない', async () => {
  await openCalendar({ view: 'month' })
  const values = await chipValues()
  const before = await chipStates(values)

  await selectCreateScope(teamA.name)

  expect(await chipValues(), 'チップの並びが変わらない').toEqual(values)
  expect(await chipStates(values), 'チップの選択状態が一切変わらない').toEqual(before)
})

test('AC-11b: 全レイヤー非選択で予定を作成すると「非表示」案内と「表示する」ボタンが出る', async () => {
  await openCalendar({ view: 'month', selected: [] })
  for (const value of await chipValues()) {
    await expect(chip(value)).toHaveAttribute('aria-pressed', 'false')
  }

  // 上部の「予定を追加」ボタンは日付が未選択のままダイアログを開くため、開始日が空で
  // バリデーションに掛かって保存できない。実際の利用と同じく日付セルから作成する。
  await monthDayCell('15').click()
  await expect(dialog()).toBeVisible()
  await dialog().locator('input.p-inputtext').first().fill(`${RUN}-HIDDEN`)
  await dialog().getByRole('button', { name: '作成', exact: true }).click()
  await expect(dialog()).toBeHidden()

  const notice = page.getByTestId('hidden-layer-notice')
  await expect(notice, '作成先のレイヤーが非表示である案内が出る').toBeVisible()
  await expect(notice).toContainText('作成先のレイヤーが非表示です')

  await page.getByTestId('hidden-layer-show-button').click()
  await expect(chip(layerKeys().personal), '押したレイヤーだけが選択状態になる')
    .toHaveAttribute('aria-pressed', 'true')
  await expect(chip(layerKeys().teamA)).toHaveAttribute('aria-pressed', 'false')
  await expect(notice).toBeHidden()
})

test('AC-12c: レイヤーチップの切替で全画面スピナーが出ず、ネットワークリクエストも発生しない', async () => {
  await openCalendar({ view: 'month' })
  const key = layerKeys().teamA

  const seen = countApiRequests()
  await chip(key).click()
  await expect(chip(key)).toHaveAttribute('aria-pressed', 'false')
  await chip(key).click()
  await expect(chip(key)).toHaveAttribute('aria-pressed', 'true')
  // 「一度も出なかった」ことを見るための待機。遅れて飛ぶリクエストを取りこぼさないためであり、
  // 失敗を隠すための待機ではない。
  await page.waitForTimeout(1_000)

  expect(seen.stopDataOnly(), 'フィルタ操作でカレンダーデータの取得が発生しない').toEqual([])
  await expect(spinner(), '全画面スピナーが出ない').toHaveCount(0)
})

// ---------------------------------------------------------------------------
// (e)(f) ビュー・モバイル
// ---------------------------------------------------------------------------

test('AC-13: 週ビューへ切り替えてもレイヤー選択と予定集合が維持され、通信が発生しない', async () => {
  await openCalendar({ view: 'month' })
  const values = await chipValues()
  const before = await chipStates(values)
  const storedBefore = await page.evaluate(k => window.localStorage.getItem(k), LAYER_STATE_KEY)

  const seen = countApiRequests()
  await page.getByTestId('calendar-view-week').click()
  await expect(page.getByTestId('week-grid-columns')).toBeVisible()
  await page.waitForTimeout(1_000)
  expect(seen.stopDataOnly(), 'ビュー切替でカレンダーデータの取得が発生しない').toEqual([])

  // 切替直後の選択状態（永続化された `selected`）が同一であること。
  // 切替後は「今日の週」に戻るため、表示範囲に依存するフォールバックチップの数は変わりうる。
  // 選択そのものは表示範囲に依存しないので、ここでは永続化状態で突き合わせる。
  expect(
    JSON.parse(await page.evaluate(k => window.localStorage.getItem(k), LAYER_STATE_KEY) ?? '{}').selected,
    'レイヤー選択が維持される',
  ).toEqual(JSON.parse(storedBefore ?? '{}').selected)

  // 表示対象の週へ移動すると、月ビューと同じチップ・同じ予定集合が 7 日分描かれる
  await gotoTargetWeek()
  expect(await chipValues()).toEqual(values)
  expect(await chipStates(values), 'チップの選択状態も同一').toEqual(before)
  await expect(page.locator('[data-testid^="week-event-"]').filter({ hasText: titles.timed })).toHaveCount(1)
  await expect(page.locator('[data-testid^="week-event-"]').filter({ hasText: titles.personal })).toHaveCount(1)
  await expect(page.getByTestId('week-allday-lane').getByText(titles.allDay, { exact: false })).toBeVisible()
  await expect(page.getByTestId('week-day-header-6'), '7 日分の列がある').toBeVisible()
})

test('AC-13c: ビューを「週」にしてリロードすると週ビューで復帰する（localStorage 永続化）', async () => {
  // 永続化そのものが検証対象なので、addInitScript で view を注入しない
  await page.goto('/calendar')
  await waitForHydration(page)
  await page.getByTestId('calendar-view-week').click()
  await expect(page.getByTestId('week-grid-columns')).toBeVisible()

  const stored = await page.evaluate(k => window.localStorage.getItem(k), LAYER_STATE_KEY)
  expect(JSON.parse(stored ?? '{}').view, 'view が localStorage に保存される').toBe('week')

  await page.reload()
  await waitForHydration(page)
  await expect(page.getByTestId('week-grid-columns'), 'リロード後も週ビュー').toBeVisible()
  await expect(page.getByTestId('calendar-view-week')).toHaveAttribute('aria-pressed', 'true')
})

test('AC-13b: アジェンダビューには「+N件」が一切現れない（月ビューには現れる）', async () => {
  // まず月ビューで「+N件」が実際に出る状況であることを確かめる（対照）
  await openCalendar({ view: 'month' })
  await expect(
    page.getByTestId(`day-overflow-${OVERFLOW_DATE}`).first(),
    '同日 5 件の日には月ビューで「+N件」が出る',
  ).toBeVisible()

  await page.getByTestId('calendar-view-agenda').click()
  await expect(page.getByTestId('agenda-list')).toBeVisible()
  await expect(
    page.locator('[data-testid^="day-overflow-"]'),
    'アジェンダには「+N件」が1つも無い',
  ).toHaveCount(0)

  const day = page.getByTestId(`agenda-day-${OVERFLOW_DATE}`)
  await day.scrollIntoViewIfNeeded()
  for (let i = 1; i <= 5; i++) {
    await expect(day.getByText(titles.overflow(i), { exact: false }), `${i} 件目が行として並ぶ`).toBeVisible()
  }
})

test('AC-13d: 終日・複数日の予定は終日帯にのみ現れ、時間グリッドをスクロールしても固定される', async () => {
  await openCalendar({ view: 'week' })
  const lane = page.getByTestId('week-allday-lane')
  await expect(lane).toBeVisible()

  for (const title of [titles.allDay, titles.multiDay]) {
    await expect(lane.getByText(title, { exact: false }), `${title} は終日帯にある`).toBeVisible()
    await expect(
      page.locator('[data-testid^="week-event-"]').filter({ hasText: title }),
      `${title} は時間グリッドに出ない`,
    ).toHaveCount(0)
  }

  const scroller = page.getByTestId('week-scroll-container')
  const before = await lane.boundingBox()
  await scroller.evaluate(el => { el.scrollTop = el.scrollHeight })
  await expect.poll(async () => scroller.evaluate(el => el.scrollTop)).toBeGreaterThan(100)
  await expect(lane, 'スクロール後も終日帯が見えている').toBeVisible()
  const after = await lane.boundingBox()
  expect(Math.abs((after?.y ?? 0) - (before?.y ?? 0)), '終日帯の画面上の位置が固定されている').toBeLessThan(4)
})

test('AC-14: ビューポート 375px では /calendar がリスト表示になり、各行に時刻・タイトル・色バーが出る', async () => {
  const mobile = await context.newPage()
  try {
    await mobile.setViewportSize({ width: 375, height: 812 })
    // 「既定表示」の検証なので、永続化済みの表示設定が無い状態（初回訪問）から始める。
    await mobile.goto('/')
    await mobile.evaluate(key => window.localStorage.removeItem(key), LAYER_STATE_KEY)
    await mobile.goto('/calendar')
    await waitForHydration(mobile)

    await expect(mobile.getByTestId('schedule-list-view'), '狭幅ではリストが既定表示').toBeVisible()
    // デスクトップ用ブロックは `hidden md:grid`（CSS で display:none）なので DOM には
    // 残りうる。見えていないことを見る。
    await expect(mobile.getByTestId('week-grid-columns'), '狭幅で週グリッドは見えない').not.toBeVisible()

    // フィクスチャは翌月にあるので月ナビで移動する
    await mobile.getByRole('button', { name: '次の月' }).click()
    const row = mobile.getByTestId('schedule-list-row-wrap').filter({ hasText: titles.timed }).first()
    await row.scrollIntoViewIfNeeded()
    await expect(row).toBeVisible()
    await expect(row, '時刻が出る').toContainText('13:00')
    await expect(row, 'タイトルが出る').toContainText(titles.timed)
    const bar = row.getByTestId('schedule-list-row-color-bar')
    await expect(bar, 'レイヤー色の縦バーが出る').toBeVisible()
    expect(
      await bar.evaluate(el => getComputedStyle(el).backgroundColor),
      '色バーが塗られている',
    ).not.toBe('rgba(0, 0, 0, 0)')
  }
  finally {
    await mobile.close()
  }
})

test('AC-14b: チームのスケジュール画面も 375px でリスト・月ナビ・空状態が動く（共通化後の回帰）', async () => {
  const mobile = await context.newPage()
  try {
    await mobile.setViewportSize({ width: 375, height: 812 })
    await mobile.goto(`/teams/${teamA.slug}/schedule`)
    await waitForHydration(mobile)

    const list = mobile.getByTestId('schedule-list-view')
    await expect(list).toBeVisible()
    // 今月にはウィジェット用の 1 件だけがある
    await expect(
      mobile.getByTestId('schedule-list-row-wrap').filter({ hasText: titles.widget }).first(),
    ).toBeVisible()

    await mobile.getByRole('button', { name: '次の月' }).click()
    await expect(
      mobile.getByTestId('schedule-list-row-wrap').filter({ hasText: titles.timed }).first(),
      '次の月へ移動できる',
    ).toBeVisible()

    // 予定を作っていない先々月へ動かすと空状態の文言が出る
    await mobile.getByRole('button', { name: '前の月' }).click()
    await mobile.getByRole('button', { name: '前の月' }).click()
    await mobile.getByRole('button', { name: '前の月' }).click()
    await expect(list.getByText('この期間の予定はありません'), '空状態の文言（チーム画面）').toBeVisible()
  }
  finally {
    await mobile.close()
  }
})

// ---------------------------------------------------------------------------
// (h) グリッド選択による予定作成
// ---------------------------------------------------------------------------

test('AC-21b/AC-21c: ドラッグ中にハイライトと時刻が出て、保存せず閉じれば何も作られず状態も戻る', async () => {
  await openCalendar({ view: 'week' })
  const scroller = page.getByTestId('week-scroll-container')
  const scrollBefore = await scroller.evaluate(el => el.scrollTop)
  const values = await chipValues()
  const chipsBefore = await chipStates(values)
  const weekHeaderBefore = await page.getByTestId('week-day-header-0').innerText()

  // 離さずにドラッグし、ハイライトとリアルタイムの時刻ラベルを確認する（AC-21b）
  await dragSlots(DRAG_DAY_INDEX, [9, 0], [10, 30], { release: false })
  const highlight = page.getByTestId('week-selection-highlight')
  await expect(highlight).toBeVisible()
  await expect(highlight, 'ハイライト内に選択中の時刻が出る').toContainText('9:00')
  await expect(highlight).toContainText('10:30')

  // さらに動かすとラベルがリアルタイムに更新される
  // ドラッグ中なのでスクロールはしない（9:45 は 9:00-10:30 の範囲内で既に見えている）
  const shorter = await slotTopPoint(DRAG_DAY_INDEX, 9, 45)
  await page.mouse.move(shorter.x, shorter.y, { steps: 5 })
  await expect(highlight, '時刻ラベルがリアルタイムで更新される').toContainText('9:45')
  await page.mouse.up()

  // AC-21c: 保存せずに閉じる
  await expect(dialog()).toBeVisible()
  await closeDialog()
  await expect(highlight, 'ハイライトが消える').toHaveCount(0)

  expect(
    (await getCalendar()).filter(e => e.content.title.startsWith(`${RUN}-DRAG`)),
    '予定は1件も作成されない',
  ).toHaveLength(0)
  expect(await chipStates(values), 'レイヤー選択が変わらない').toEqual(chipsBefore)
  expect(await page.getByTestId('week-day-header-0').innerText(), '表示週が変わらない').toBe(weekHeaderBefore)
  expect(await scroller.evaluate(el => el.scrollTop), 'スクロール位置が変わらない').toBe(scrollBefore)
})

test('AC-21: 9:00→10:30 をなぞると時刻がプリセットされ、保存で即座になぞった位置にバーが出る', async () => {
  await openCalendar({ view: 'week' })

  // スロットの「上端」を掴む理由（設計書 §6.6.3）:
  // スナップは Math.round（最も近い 15 分境界）である。スロットの中心は境界から 7.5 分ずれており、
  // JS の Math.round(0.5) は切り上げなので、AC 本文が例示する「bounding box 中心どうしのドラッグ」は
  // 実装上 9:15–10:45 になる。ここでは AC が意図する 9:00–10:30 を得るために上端を使う
  // （待ち時間やしきい値をいじって症状を隠しているのではなく、狙う座標を正しく計算している）。
  await dragSlots(DRAG_DAY_INDEX, [9, 0], [10, 30])

  await expect(dialog()).toBeVisible()
  const times = await dialogTimes()
  expect(times.start, '開始 9:00 がプリセットされる').toBe('09:00')
  expect(times.end, '終了 10:30 がプリセットされる').toBe('10:30')

  await dialog().locator('input.p-inputtext').first().fill(titles.dragCreated)
  await dialog().getByRole('button', { name: '作成', exact: true }).click()
  await expect(dialog()).toBeHidden()

  // リロードせずに、なぞった位置（＝その日の列）へバーが現れる
  const bar = page.locator('[data-testid^="week-event-"]').filter({ hasText: titles.dragCreated }).first()
  await expect(bar, 'ダイアログを閉じた直後に予定バーが現れる').toBeVisible({ timeout: 20_000 })
  await expect(bar).toHaveAttribute('data-day-index', String(DRAG_DAY_INDEX))
})

test('AC-21g: 時間グリッドを単クリックすると既定 60 分の範囲でダイアログが開く', async () => {
  await openCalendar({ view: 'week' })
  await scrollSlotsIntoView(DRAG_DAY_INDEX, [[14, 0]])
  const point = await slotTopPoint(DRAG_DAY_INDEX, 14, 0)
  await page.mouse.move(point.x, point.y)
  await page.mouse.down()
  await page.mouse.up()

  await expect(dialog()).toBeVisible()
  const times = await dialogTimes()
  expect(times.start).toBe('14:00')
  expect(times.end, '単クリックは既定 60 分').toBe('15:00')
  await closeDialog()
})

test('AC-22b: 月ビュー・アジェンダビューではドラッグしてもハイライトもダイアログも出ない', async () => {
  for (const view of ['month', 'agenda'] as const) {
    await openCalendar({ view })
    const target = view === 'month'
      ? page.locator('.grid.grid-cols-7 > div').nth(10)
      : page.getByTestId('agenda-list')
    const box = await target.boundingBox()
    expect(box, `${view} のドラッグ対象`).not.toBeNull()

    await page.mouse.move(box!.x + 10, box!.y + 10)
    await page.mouse.down()
    await page.mouse.move(box!.x + 10, box!.y + 80, { steps: 8 })
    await expect(
      page.getByTestId('week-selection-highlight'), `${view} でハイライトが出ない`,
    ).toHaveCount(0)
    await page.mouse.up()
    await expect(dialog(), `${view} でドラッグでは作成ダイアログが開かない`).toHaveCount(0)
  }
})

test('AC-22c: 直前に週ビューでドラッグしても、月ビューの日付セルクリックに時刻が漏れない', async () => {
  await openCalendar({ view: 'week' })
  // 9:00-10:30 は AC-21 が作成した予定で埋まっている（予定バーの上ではドラッグが
  // 始まらないのが正しい挙動＝AC-22d）ため、空いている時間帯を使う。
  await dragSlots(DRAG_DAY_INDEX, [16, 0], [17, 30])
  await expect(dialog()).toBeVisible()
  expect((await dialogTimes()).start).toBe('16:00')
  await closeDialog()

  await page.getByTestId('calendar-view-month').click()
  await expect(page.locator('.grid.grid-cols-7').first()).toBeVisible()
  await monthDayCell('15').click()

  await expect(dialog()).toBeVisible()
  const times = await dialogTimes()
  expect(
    `${times.start}-${times.end}`,
    '日付クリック経路には週ドラッグの時刻が残らない',
  ).not.toBe('16:00-17:30')
  await closeDialog()
})

test('AC-22d: 既存の予定バーの上でドラッグを開始しても選択は始まらず、予定詳細が開く', async () => {
  await openCalendar({ view: 'week' })
  const bar = page.locator('[data-testid^="week-event-"]').filter({ hasText: titles.timed }).first()
  await bar.scrollIntoViewIfNeeded()
  const box = await bar.boundingBox()
  expect(box, '予定バーの bounding box').not.toBeNull()

  await page.mouse.move(box!.x + box!.width / 2, box!.y + 2)
  await page.mouse.down()
  await page.mouse.move(box!.x + box!.width / 2, box!.y + 60, { steps: 8 })
  await expect(page.getByTestId('week-selection-highlight'), '選択が始まらない').toHaveCount(0)
  await page.mouse.up()
  await expect(dialog(), '作成ダイアログは開かない').toHaveCount(0)

  // 予定バーの操作は従来どおり `@click` で詳細を開く。上のドラッグはバーの外へ抜けるため
  // click イベント自体が発生しない（同一要素上で押して離した場合にのみ click が出るという
  // ブラウザの仕様）。よって「詳細が開く」ことはクリックで改めて確かめる。
  // AC-22d は「ドラッグで選択が始まらない」と「従来どおり詳細が開く」の両方が内容である。
  await bar.click()
  // 詳細パネル自体は開く（コメント欄・対象者が描画される）。
  await expect(
    page.locator('.lg\\:col-span-1').getByText('予定の対象者', { exact: false }).first(),
    '右カラムに予定詳細パネルが開く',
  ).toBeVisible({ timeout: 20_000 })
  // ここは現在**赤になる**。実装の欠陥を掴んでいる（下記コメント参照）。
  await expect(
    page.locator('.lg\\:col-span-1').getByText(titles.timed, { exact: false }).first(),
    '予定詳細にタイトルが表示される'
    + '（【実装の欠陥】現状ここは失敗する。`pages/calendar.vue#onEventClick` の共有予定側は'
    + ' 詳細 API の応答をフラットに `...d` で展開しているが、応答は `content.title` /'
    + ' `time.startAt` のネスト構造である。個人予定側は `d.content?.title` と明示的に'
    + ' 詰め替えているのに、共有（チーム／組織）予定側だけ詰め替えが無いため、'
    + ' タイトルと日時が undefined になりパネルが「タイトル空・日時が〜だけ」で開く。'
    + ' 同関数は catch を握りつぶしているため画面にもエラーが出ず、静かに壊れている）',
  ).toBeVisible({ timeout: 20_000 })
})

test('AC-22e: 作成スコープがチームAのとき、週ドラッグのダイアログの作成先もチームA（フィルタは不変）', async () => {
  await openCalendar({ view: 'week' })
  const values = await chipValues()
  const before = await chipStates(values)

  await selectCreateScope(teamA.name)
  await dragSlots(DRAG_DAY_INDEX, [11, 0], [12, 0])
  await expect(dialog()).toBeVisible()

  // ダイアログの見出しは作成先が個人かどうかで変わる（個人=「予定を追加」／共有=「イベントを作成」）。
  await expect(
    dialog().locator('.p-dialog-header'), '共有スコープの作成ダイアログとして開く',
  ).toContainText('イベントを作成')

  // 「作成先がチームAである」ことは、実際に保存して**どのスコープに入ったか**で確かめる
  // （見た目の選択状態は下の別テストが扱う）。
  const title = `${RUN}-SCOPED`
  await dialog().locator('input.p-inputtext').first().fill(title)
  await dialog().getByRole('button', { name: '作成', exact: true }).click()
  await expect(dialog()).toBeHidden()

  await expect.poll(async () => {
    const entry = (await getCalendar()).find(e => e.content.title === title)
    return `${entry?.scope.scopeType}:${entry?.scope.scopeId}`
  }, { message: '作成された予定のスコープがチームAである' }).toBe(`TEAM:${teamA.numericId}`)

  expect(await chipStates(values), '表示フィルタは一切変化しない').toEqual(before)
})

test('AC-22e（付随）: 作成ダイアログの「作成先」が現在のスコープを選択状態で表示する', async () => {
  // 【実装の欠陥】現状ここは失敗する。
  // `pages/calendar.vue` の `createScopeOptions[].value` は `TEAM:<slug>` 形式だが、
  // `ScheduleEventForm` は `selectedScopeKey` を `` `${scopeType}_${scopeId}` ``（例
  // `team_<slug>`）で初期化する。両者が一致しないため `scopeOptions.find(...)` が外れ、
  // `ScheduleEventScopeSelector` はどのボタンにも `border-primary` を付けない。
  // 実効スコープ自体は props フォールバックで正しいので**保存先は正しい**（上のテストが示す）が、
  // 画面上は「作成先が何も選ばれていない」ように見える。ダイアログ内で選び直すと一致するため、
  // 壊れているのは**初期表示だけ**である。
  await openCalendar({ view: 'week' })
  await selectCreateScope(teamA.name)
  // 直前の AC-22e が 11:00-12:00 に予定を保存するため、そこをなぞると予定バーの上での
  // ドラッグになり選択が始まらない（＝AC-22d の正しい挙動）。空いている時間帯を使う。
  await dragSlots(DRAG_DAY_INDEX, [19, 0], [20, 0])
  await expect(dialog()).toBeVisible()
  await expect(
    dialog().locator('button').filter({ hasText: teamA.name }).first(),
    '作成先としてチームAが選択状態で表示される',
  ).toHaveClass(/border-primary/)
  await closeDialog()
})

test('AC-22: タッチでは素早いスワイプでスクロールし、長押ししてからなぞると選択が始まる', async () => {
  // 注記: 設計書 AC-22 は「モバイル幅（375px）の週ビュー」と書いているが、実装は
  // `calendar.vue` で週ビューを `hidden md:grid` の中に置いており、**768px 未満では
  // 週ビューが CSS で display:none になり操作できない**（狭幅は ScheduleMobileListView に
  // 置換される。§6.8）。よって 375px では本 AC を実行できない。ジェスチャ弁別（useGridRangeSelect の
  // touch 経路）そのものを検証するため、タッチ可能なタブレット幅の文脈で実施する。
  const touch = await context.newPage()
  let cdp: CDPSession | undefined
  try {
    await touch.setViewportSize({ width: 820, height: 900 })
    await touch.addInitScript(
      ({ key, state }) => window.localStorage.setItem(key, JSON.stringify(state)),
      {
        key: LAYER_STATE_KEY,
        state: { version: 2, selected: [layerKeys().personal], view: 'week', knownFallbackKeys: [] },
      },
    )
    await touch.goto('/calendar')
    await waitForHydration(touch)
    await expect(touch.getByTestId('week-grid-columns')).toBeVisible()

    cdp = await context.newCDPSession(touch)
    const dispatch = (type: 'touchStart' | 'touchMove' | 'touchEnd', px: number, py: number) =>
      cdp!.send('Input.dispatchTouchEvent', {
        type,
        touchPoints: type === 'touchEnd' ? [] : [{ x: px, y: py, radiusX: 5, radiusY: 5, force: 1 }],
      })

    const scroller = touch.getByTestId('week-scroll-container')
    const slot = touch.getByTestId(`week-slot-${DRAG_DAY_INDEX}-12-0`)
    await slot.scrollIntoViewIfNeeded()
    const box = await slot.boundingBox()
    expect(box, 'タッチ対象スロット').not.toBeNull()
    const x = box!.x + box!.width / 2
    const y = box!.y + 4
    const scrollBefore = await scroller.evaluate(el => el.scrollTop)

    // (1) 素早いスワイプ: 長押し成立前（500ms 未満）に 10px 以上動かす
    await dispatch('touchStart', x, y)
    for (let i = 1; i <= 6; i++) await dispatch('touchMove', x, y - i * 25)
    await dispatch('touchEnd', x, y - 150)
    await expect(
      touch.getByTestId('week-selection-highlight'), 'スワイプでは選択ハイライトが出ない',
    ).toHaveCount(0)
    await expect(touch.locator('.p-dialog'), 'スワイプでは作成ダイアログが開かない').toHaveCount(0)
    expect(
      await scroller.evaluate(el => el.scrollTop), 'スワイプで縦スクロールする',
    ).not.toBe(scrollBefore)

    // (2) 長押ししてからなぞる: 500ms 静止 → 移動 → 離す
    const box2 = await touch.getByTestId(`week-slot-${DRAG_DAY_INDEX}-12-0`).boundingBox()
    const x2 = box2!.x + box2!.width / 2
    const y2 = box2!.y + 4
    await dispatch('touchStart', x2, y2)
    // 長押し成立（LONG_PRESS_MS = 500ms）を待つ。実装が 500ms のタイマーで長押しを
    // 判定しているためであり、失敗を誤魔化す待機ではない。
    await touch.waitForTimeout(700)
    await expect(
      touch.getByTestId('week-selection-highlight'), '長押し成立で選択ハイライトが出る',
    ).toBeVisible()
    await dispatch('touchMove', x2, y2 + 36)
    await dispatch('touchEnd', x2, y2 + 36)
    await expect(dialog(touch), '離すと作成ダイアログが開く').toBeVisible()
  }
  finally {
    // 後始末の失敗はテスト結果を覆さないが、握りつぶさずログには残す。
    // finally の中で throw すると本来の失敗理由が上書きされて消えるため再 throw はしない。
    try {
      await cdp?.detach()
    }
    catch (error) {
      console.warn('CDP セッションの切断に失敗した（テストの合否には影響させない）:', error)
    }
    await touch.close()
  }
})

// ---------------------------------------------------------------------------
// (g) 状態共通化・横断
// ---------------------------------------------------------------------------

test('AC-15: /calendar のレイヤー選択がダッシュボードの WidgetMyCalendar にも反映される', async () => {
  const keys = layerKeys()
  // WidgetMyCalendar には data-testid が無いため、**見出し「マイカレンダー」と月グリッド
  // （7列）の両方を含む最も内側の要素**をウィジェット本体とみなす。
  // ページ全体から予定タイトルを探してはならない — `/dashboard` には
  // WidgetScheduleCalendar / WidgetAttendanceResults など**予定タイトルを描く別ウィジェット**が
  // 同居しており、それらはレイヤー選択の影響を受けない。ページ全体で探すと、
  // 別ウィジェットの表示を掴んで「非表示になっていない」と誤判定する（実際に一度誤検知した）。
  const widgetHeading = page.getByRole('heading', { name: 'マイカレンダー' })
  const widget = () => page.locator('div')
    .filter({ has: page.getByRole('heading', { name: 'マイカレンダー' }) })
    .filter({ has: page.locator('.grid.grid-cols-7') })
    .last()
  const widgetEvent = () => widget().getByText(titles.widget, { exact: false }).filter({ visible: true })

  await openCalendar({ view: 'month' })
  await chip(keys.teamA).click()
  await expect(chip(keys.teamA)).toHaveAttribute('aria-pressed', 'false')
  await expect.poll(async () => {
    const raw = await page.evaluate(k => window.localStorage.getItem(k), LAYER_STATE_KEY)
    return (JSON.parse(raw ?? '{}').selected ?? []) as string[]
  }, { message: 'レイヤー選択が永続化される' }).not.toContain(keys.teamA)

  await page.goto('/dashboard')
  await waitForHydration(page)
  await expect(widgetHeading, 'ダッシュボードに WidgetMyCalendar が出ている').toBeVisible()
  await expect(widgetEvent(), '非選択にしたチームAの予定はウィジェットにも出ない').toHaveCount(0)

  // 選択に戻すとウィジェットにも現れる（同じ出所を見ていることの往復確認）
  await openCalendar({ view: 'month' })
  await expect(chip(keys.teamA)).toHaveAttribute('aria-pressed', 'true')
  await page.goto('/dashboard')
  await waitForHydration(page)
  await expect(widgetHeading).toBeVisible()
  await expect(
    widgetEvent().first(), '選択に戻すとウィジェットにも現れる',
  ).toBeVisible({ timeout: 20_000 })
})

test('AC-23: レイヤー一覧に無いスコープの予定にフォールバックチップが出て、外すと消える', async () => {
  const keys = layerKeys()
  const layerKeySet = new Set((await getLayers()).map(l => `${l.scopeType}:${l.scopeId}`))
  expect(layerKeySet.has(keys.fallback), 'フォールバック対象はレイヤー一覧に含まれない').toBe(false)

  await openCalendar({ view: 'month' })
  const fallbackChip = chip(keys.fallback)
  await expect(fallbackChip, 'フォールバックチップが1つ現れる').toBeVisible()
  await expect(fallbackChip).toHaveAttribute('aria-pressed', 'true')
  await expect(
    page.getByTestId(`layer-chip-more-${keys.fallback}`),
    'フォールバックチップには色変更 UI を出さない',
  ).toHaveCount(0)

  // モバイル用リスト（`md:hidden`）にも同じタイトルが描かれており、そちらは CSS で
  // 非表示のまま DOM に残る。可視な方＝月グリッド上の要素だけを見る。
  const fallbackEvent = page.getByText(fallbackTitle, { exact: false }).filter({ visible: true })
  await expect(fallbackEvent.first(), 'その予定がグリッド上に表示される').toBeVisible()

  await fallbackChip.click()
  await expect(fallbackChip).toHaveAttribute('aria-pressed', 'false')
  await expect(fallbackEvent, 'チップを外すと消える').toHaveCount(0)

  await fallbackChip.click()
  await expect(fallbackChip).toHaveAttribute('aria-pressed', 'true')
  await expect(fallbackEvent.first(), '再度選ぶと戻る').toBeVisible()
})

test('AC-25: キーボードのみで予定を作成できる（グリッド全体で1タブストップ）', async () => {
  await openCalendar({ view: 'week' })
  const grid = page.getByTestId('week-grid-columns')

  // グリッド全体で 1 タブストップであること: グリッド自身が tabindex=0 を持ち、
  // 配下のセルはタブ順に入らない（672 個のセルが個別のタブストップだと Tab で抜けられない）。
  await expect(grid).toHaveAttribute('tabindex', '0')
  expect(
    await grid.evaluate(el => el.querySelectorAll('[tabindex]:not([tabindex="-1"])').length),
    'グリッド配下にタブストップは無い',
  ).toBe(0)

  // Tab でグリッドへフォーカスが入る（直前のフォーカス可能要素からの 1 回の Tab で到達する）
  await grid.evaluate((el) => {
    const focusable = [...document.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex="-1"])',
    )]
    focusable[focusable.indexOf(el as HTMLElement) - 1]?.focus()
  })
  await page.keyboard.press('Tab')
  await expect(grid, 'Tab でグリッドへフォーカスが入る').toBeFocused()

  // さらに Tab を押すとグリッドから抜ける（セルがタブストップではないことの実証）
  await page.keyboard.press('Tab')
  await expect(grid, 'Tab 1 回でグリッドを抜ける').not.toBeFocused()

  await grid.focus()
  const activeBefore = await grid.getAttribute('aria-activedescendant')
  await page.keyboard.press('ArrowDown')
  await expect
    .poll(async () => grid.getAttribute('aria-activedescendant'), { message: '↓ でフォーカスセルが移動する' })
    .not.toBe(activeBefore)

  // Shift+↓ で選択範囲が延長される
  const highlight = page.getByTestId('week-selection-highlight')
  await page.keyboard.press('Shift+ArrowDown')
  await expect(highlight, 'Shift+↓ で選択範囲が出る').toBeVisible()
  await page.keyboard.press('Shift+ArrowDown')
  await expect(page.getByTestId('week-selection-announcement')).toContainText('選択中')

  // Escape で選択が破棄される
  await page.keyboard.press('Escape')
  await expect(highlight, 'Escape で選択が破棄される').toHaveCount(0)

  // 改めて選択して Enter で、その時刻がプリセットされたダイアログが開く
  await page.keyboard.press('Shift+ArrowDown')
  await page.keyboard.press('Shift+ArrowDown')
  await expect(highlight).toBeVisible()
  const [start, end] = (await highlight.innerText()).trim().split('–').map(s => s.trim())
  await page.keyboard.press('Enter')
  await expect(dialog()).toBeVisible()

  const times = await dialogTimes()
  const normalize = (v: string) => v.replace(/^0/, '')
  expect(normalize(times.start), `ハイライトの開始 ${start} がプリセットされる`).toBe(start)
  expect(normalize(times.end), `ハイライトの終了 ${end} がプリセットされる`).toBe(end)

  await dialog().locator('input.p-inputtext').first().fill(`${RUN}-KEYBOARD`)
  await dialog().getByRole('button', { name: '作成', exact: true }).click()
  await expect(dialog()).toBeHidden()
  await expect.poll(
    async () => (await getCalendar(WIDE_FROM, WIDE_TO)).some(e => e.content.title === `${RUN}-KEYBOARD`),
    { message: 'キーボードのみで予定が作成される' },
  ).toBe(true)
})
