/**
 * F03.18 予定アクティビティフィード — 実機 E2E（可視性・繰り返し・マージ・ページ送り／API 完結）。
 *
 * 【このファイルの立ち位置】
 *   ウィジェット（WidgetRecentActivity.vue）は「最新20件を1ページ描画する」だけの UI であり、
 *   可視性の縮小・繰り返し予定の更新範囲・5分マージ・組織スコープ・カーソルページ送りは
 *   UI 上に出口が無い（あるいは複数ロールの同時観測が必要で「1ファイル=1ログイン」に反する）。
 *   そこで本ファイルは browser を使わず APIRequestContext のみで、実 BE / 実 DB を相手に
 *   フィードの中身を直接検証する（既存 activity-authz.spec.ts と同じ作法）。
 *   UI 描画側の検証は schedule-activity-feed.spec.ts が担う。
 *
 * 【役者】いずれも seed 済み・パスワード共通
 *   - e2e-admin  : fc-u-18 の ADMIN。すべての操作者（actor）。自分の行動はフィードに出ない。
 *   - e2e-user   : fc-u-18 の MEMBER。可視性を縮めても見え続ける側（陽性対照）。
 *   - e2e-supporter : fc-u-18 の SUPPORTER。min_view_role=MEMBER_PLUS で見えなくなる側。
 *   - e2e-outsider  : どのスコープにも所属しない。何も見えないことの確認に使う。
 *
 * 実行例:
 *   cd frontend && API_BASE_URL=http://localhost:8081 \
 *     npx playwright test --config=playwright-f0318.config.ts \
 *       tests/e2e/real/schedule-activity-feed-visibility.spec.ts
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const API_V1 = `${API}/api/v1`
const TEAM_SLUG = 'fc-u-18'
const PASSWORD = 'TestPass2026!'

// storageState に依存しない（API 完結）。
test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
// 非同期のフィード書き込み・繰り返し予定の展開を待つため既定より長めに取る。
test.setTimeout(180_000)

interface FeedItem {
  id: number
  type: string
  scopeType: string
  targetType: string
  targetId: number
  detail: {
    scheduleId?: number
    title?: string
    affectedCount?: number
    fields?: Array<{ field: string; before?: string; after?: string; changed?: boolean }>
  } | null
}

let api: APIRequestContext
let adminToken: string
let memberToken: string
let supporterToken: string
let outsiderToken: string
let sharedOrgSlug: string

const stamp = Date.now()

function h(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function login(email: string): Promise<string> {
  const res = await api.post(`${API_V1}/auth/login`, { data: { email, password: PASSWORD } })
  expect(res.status(), `login(${email}) は 200`).toBe(200)
  return (await res.json() as { data: { accessToken: string } }).data.accessToken
}

async function feed(token: string, params: { limit?: number; cursor?: string } = {}): Promise<{ items: FeedItem[]; nextCursor: string | null }> {
  const query = new URLSearchParams({ limit: String(params.limit ?? 50) })
  if (params.cursor) query.set('cursor', params.cursor)
  const res = await api.get(`${API_V1}/dashboard/activity?${query}`, { headers: h(token) })
  expect(res.status(), 'GET /dashboard/activity は 200').toBe(200)
  return (await res.json() as { data: { items: FeedItem[]; nextCursor: string | null } }).data
}

async function createTeamSchedule(title: string, extra: Record<string, unknown> = {}): Promise<number> {
  const start = Date.now() + 7 * 24 * 60 * 60 * 1000
  const res = await api.post(`${API_V1}/teams/${TEAM_SLUG}/schedules`, {
    headers: h(adminToken),
    data: {
      title,
      startAt: new Date(start).toISOString(),
      endAt: new Date(start + 3600_000).toISOString(),
      allDay: false,
      eventType: 'PRACTICE',
      visibility: 'MEMBERS_ONLY',
      minViewRole: 'ANYONE',
      attendanceRequired: false,
      ...extra,
    },
  })
  expect(res.status(), `チーム予定作成は 201: ${title}`).toBe(201)
  return (await res.json() as { data: { id: number } }).data.id
}

async function patchSchedule(id: number, body: Record<string, unknown>, updateScope = 'THIS_ONLY'): Promise<void> {
  const res = await api.patch(`${API_V1}/teams/${TEAM_SLUG}/schedules/${id}?updateScope=${updateScope}`, {
    headers: h(adminToken),
    data: body,
  })
  expect(res.status(), `予定更新は 200: id=${id} scope=${updateScope}`).toBe(200)
}

/** 条件に合う行が現れるまでポーリングする（フィード書き込みは AFTER_COMMIT の @Async）。 */
async function waitForRow(
  token: string,
  predicate: (item: FeedItem) => boolean,
  label: string,
  timeoutMs = 60_000,
): Promise<FeedItem> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const page = await feed(token)
    const row = page.items.find(predicate)
    if (row) return row
    if (Date.now() > deadline) throw new Error(`フィード行が現れませんでした: ${label}`)
    await new Promise(resolve => setTimeout(resolve, 1_000))
  }
}

/** 条件に合う行が消えるまでポーリングする（可視性フィルタは読み取り時に効く）。 */
async function waitForRowGone(
  token: string,
  predicate: (item: FeedItem) => boolean,
  label: string,
  timeoutMs = 30_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const page = await feed(token)
    if (!page.items.some(predicate)) return
    if (Date.now() > deadline) throw new Error(`フィード行が消えませんでした: ${label}`)
    await new Promise(resolve => setTimeout(resolve, 1_000))
  }
}

const byTarget = (id: number) => (item: FeedItem) => item.targetType === 'SCHEDULE' && item.targetId === id

test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await login('e2e-admin@test.mannschaft.local')
  memberToken = await login('e2e-user@test.mannschaft.local')
  supporterToken = await login('e2e-supporter@test.mannschaft.local')
  outsiderToken = await login('e2e-outsider@test.mannschaft.local')

  // 組織スコープ検証用: actor(admin) と viewer(member) の双方が所属する組織を実データから解決する。
  const orgsOf = async (token: string) => {
    const res = await api.get(`${API_V1}/me/organizations?limit=200`, { headers: h(token) })
    expect(res.status()).toBe(200)
    return (await res.json() as { data: Array<{ id: number; slug: string }> }).data
  }
  const adminOrgs = await orgsOf(adminToken)
  const memberOrgs = await orgsOf(memberToken)
  const shared = adminOrgs.find(o => memberOrgs.some(m => m.id === o.id))
  expect(shared, 'e2e-admin と e2e-user が共通で所属する組織が存在する（seed 前提）').toBeTruthy()
  sharedOrgSlug = shared!.slug
})

test.afterAll(async () => {
  await api?.dispose()
})

test('FEED-API-001: 公開範囲を狭めると見えなくなった側から既出の行が消え、見える側には残る', async () => {
  const id = await createTeamSchedule(`F0318API-可視性-${stamp}`)

  // 陰性側（SUPPORTER）・陽性側（MEMBER）とも、まずは見えていることを確認する。
  await waitForRow(supporterToken, byTarget(id), 'supporter に作成行')
  await waitForRow(memberToken, byTarget(id), 'member に作成行')

  // 閲覧下限を MEMBER 以上へ引き上げる（SUPPORTER は閾値を満たさなくなる）。
  await patchSchedule(id, { minViewRole: 'MEMBER_PLUS' })

  await waitForRowGone(supporterToken, byTarget(id), 'supporter から消える')

  // 陽性対照: 引き続き閲覧できる MEMBER のフィードには残っている。
  const memberFeed = await feed(memberToken)
  expect(memberFeed.items.some(byTarget(id)), 'MEMBER には残る（陽性対照）').toBe(true)
})

test('FEED-API-002: 非所属者にはフィード行が一切見えない', async () => {
  const id = await createTeamSchedule(`F0318API-非所属-${stamp}`)
  await waitForRow(memberToken, byTarget(id), 'member に作成行')

  const outsiderFeed = await feed(outsiderToken)
  expect(outsiderFeed.items.some(byTarget(id)), '非所属者には見えない').toBe(false)
})

test('FEED-API-003: 繰り返し予定を THIS_ONLY で更新すると差分がフィードに出る', async () => {
  const recurrence = {
    recurrenceRule: { type: 'WEEKLY', interval: 1, daysOfWeek: ['MONDAY'], endType: 'COUNT', count: 4 },
  }
  const before = `F0318API-繰返TO-${stamp}`
  const after = `${before}-更新後`
  const id = await createTeamSchedule(before, recurrence)
  await waitForRow(memberToken, byTarget(id), '繰返予定の作成行')

  await patchSchedule(id, { title: after }, 'THIS_ONLY')

  const row = await waitForRow(
    memberToken,
    item => byTarget(id)(item) && item.type === 'SCHEDULE_UPDATED',
    'THIS_ONLY の更新行',
  )
  expect(row.detail?.fields).toEqual([
    expect.objectContaining({ field: 'title', before, after }),
  ])
})

test('FEED-API-004: 繰り返し予定を THIS_AND_FOLLOWING で更新しても差分がフィードに出る', async () => {
  const recurrence = {
    recurrenceRule: { type: 'WEEKLY', interval: 1, daysOfWeek: ['MONDAY'], endType: 'COUNT', count: 4 },
  }
  const before = `F0318API-繰返TAF-${stamp}`
  const after = `${before}-更新後`
  const id = await createTeamSchedule(before, recurrence)
  await waitForRow(memberToken, byTarget(id), '繰返予定の作成行')

  await patchSchedule(id, { title: after }, 'THIS_AND_FOLLOWING')

  const row = await waitForRow(
    memberToken,
    item => byTarget(id)(item) && item.type === 'SCHEDULE_UPDATED',
    'THIS_AND_FOLLOWING の更新行',
  )
  expect(row.detail?.fields).toEqual([
    expect.objectContaining({ field: 'title', before, after }),
  ])
  expect(row.detail?.title, 'フィードのタイトルは更新後の値').toBe(after)
})

test('FEED-API-005: 5分以内の連続編集はフィード行が増えず1行にまとまる', async () => {
  const original = `F0318API-マージ-${stamp}`
  const id = await createTeamSchedule(original)
  await waitForRow(memberToken, byTarget(id), 'マージ検証の作成行')

  await patchSchedule(id, { title: `${original}-1回目` })
  await waitForRow(
    memberToken,
    item => byTarget(id)(item) && item.type === 'SCHEDULE_UPDATED',
    '1回目の更新行',
  )

  await patchSchedule(id, { title: `${original}-2回目` })
  // 2回目の書き込みが確実に済むまで待ってから行数を数える。
  await waitForRow(
    memberToken,
    item => byTarget(id)(item) && item.detail?.title === `${original}-2回目`,
    '2回目の更新がマージ済み',
  )

  const page = await feed(memberToken)
  const updateRows = page.items.filter(item => byTarget(id)(item) && item.type !== 'SCHEDULE_CREATED')
  expect(updateRows, '連続編集は1行にまとまる').toHaveLength(1)
  // before は初回値、after は最新値へ畳まれている。
  expect(updateRows[0]!.detail?.fields).toEqual([
    expect.objectContaining({ field: 'title', before: original, after: `${original}-2回目` }),
  ])
})

test('FEED-API-006: 組織スコープの活動がフィードに現れる', async () => {
  const title = `F0318API-組織-${stamp}`
  const start = Date.now() + 7 * 24 * 60 * 60 * 1000
  const res = await api.post(`${API_V1}/organizations/${sharedOrgSlug}/schedules`, {
    headers: h(adminToken),
    data: {
      title,
      startAt: new Date(start).toISOString(),
      endAt: new Date(start + 3600_000).toISOString(),
      allDay: false,
      eventType: 'MEETING',
      visibility: 'ORGANIZATION',
      minViewRole: 'ANYONE',
      attendanceRequired: false,
    },
  })
  expect(res.status(), '組織予定作成は 201').toBe(201)
  const id = (await res.json() as { data: { id: number } }).data.id

  const row = await waitForRow(memberToken, byTarget(id), '組織スコープの作成行')
  expect(row.scopeType, 'スコープ種別は ORGANIZATION').toBe('ORGANIZATION')
  expect(row.detail?.title).toBe(title)
})

test('FEED-API-007: 2ページ読み込んでも行の重複・欠落が無い', async () => {
  // 直前までのテストで十分な行数が積まれている前提を明示的に確認する。
  const whole = await feed(memberToken, { limit: 50 })
  expect(whole.items.length, 'ページ送り検証には最低6行必要').toBeGreaterThanOrEqual(6)

  const first = await feed(memberToken, { limit: 3 })
  expect(first.items).toHaveLength(3)
  expect(first.nextCursor, '続きがあるので nextCursor は非 null').not.toBeNull()

  const second = await feed(memberToken, { limit: 3, cursor: first.nextCursor! })
  const firstIds = first.items.map(i => i.id)
  const secondIds = second.items.map(i => i.id)

  // 重複ゼロ
  expect(firstIds.filter(id => secondIds.includes(id)), 'ページ間で行が重複しない').toEqual([])
  // 欠落ゼロ: 通しで取った並びの先頭6件と、2ページ分の連結が一致する
  expect([...firstIds, ...secondIds]).toEqual(whole.items.slice(0, firstIds.length + secondIds.length).map(i => i.id))
  // id 降順であること（カーソル条件 a.id < :cursor と整合）
  const all = [...firstIds, ...secondIds]
  expect(all).toEqual([...all].sort((a, b) => b - a))
})
