/**
 * F08.10 多競技ライブ記録 実機 E2E ― ターン制（囲碁・個人戦）。
 *
 * モックなし・実バックエンド（http://localhost:8080）接続。BE は再起動せず稼働中の最新 main をそのまま使う。
 *
 * 【検証対象】sports/06_go.md のターン制フローを実運用 API で一気通貫実証する:
 *   試合作成（sport=GO）→ 対局結果記録（PUT /result：勝者side・win_method=POINTS_WIN・目数差 margin は detail に）
 *   → COMPLETED 遷移（勝敗確定）
 *   → 中押し（RESIGNATION）・不戦勝（DEFAULT_WIN）・引分（持碁=0-0）のケース
 *   → 将棋 win_method（REPETITION 等）を囲碁に付与 → MATCH_028（競技間の流用を弾く）。
 *
 * 【前提データ】backend/scripts/seed-f0810-turn-team-e2e.js を実行済みであること。
 *   - 固有名前空間: org slug=f0810-multisport-club / team slug=f0810-go-team。
 *   - e2e-admin が当該チームの ADMIN（記録権限の前提）。
 *
 * 【構成】API 完結（APIRequestContext のみ）。FE dev サーバーに依存しない。
 *
 * 設計: docs/features/F08.10_match_record_analytics/sports/06_go.md §4 / 01 §D.7
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

const TEAM_SLUG = 'f0810-go-team'

let api: APIRequestContext
let adminToken: string
let orgId: number
let teamId: number

async function login(apiCtx: APIRequestContext): Promise<string> {
  const res = await apiCtx.post(`${BE_API}/auth/login`, {
    data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
  })
  expect(res.status(), 'login は 200').toBe(200)
  return (await res.json() as { data: { accessToken: string } }).data.accessToken
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function resolveTeam(token: string, slug: string): Promise<{ teamId: number; orgId: number }> {
  const res = await api.get(`${BE_API}/me/teams`, { headers: authHeaders(token) })
  expect(res.status(), '/me/teams は 200').toBe(200)
  const json = await res.json() as {
    data: Array<{ id: number; slug: string; organizationId: number; role: string }>
  }
  const team = json.data.find((t) => t.slug === slug)
  expect(
    team,
    `seed-f0810-turn-team-e2e.js のチーム(${slug})が /me/teams に存在する`,
  ).toBeTruthy()
  expect(team!.role, 'e2e-admin は当該チームの ADMIN').toBe('ADMIN')
  return { teamId: team!.id, orgId: team!.organizationId }
}

/** 囲碁試合を作成して ID を返す。テスト後は呼び出し側が削除する。 */
async function createGoMatch(name: string): Promise<string> {
  const res = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: { sport: 'GO', kind: 'FRIENDLY', homeAway: 'HOME', opponentName: name },
  })
  expect(res.status(), `囲碁試合作成は 201（${name}）。応答: ${await res.text()}`).toBe(201)
  const json = await res.json() as { data: { id: string; sport: string } }
  expect(json.data.sport, 'sport=GO').toBe('GO')
  return json.data.id
}

test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await login(api)
  const resolved = await resolveTeam(adminToken, TEAM_SLUG)
  teamId = resolved.teamId
  orgId = resolved.orgId
})

test.afterAll(async () => {
  await api.dispose()
})

// ===========================================================================
// GO-000: 認証 + チーム解決
// ===========================================================================
test('GO-000: ADMIN ログイン + 囲碁チームの org/team ID を slug 解決', async () => {
  expect(adminToken.length, 'トークンは 50 文字以上').toBeGreaterThanOrEqual(50)
  expect(teamId, 'teamId が解決される').toBeGreaterThan(0)
  expect(orgId, 'orgId が解決される').toBeGreaterThan(0)
})

// ===========================================================================
// GO-001: 目数差勝ち（POINTS_WIN）・HOME 勝ち
// ===========================================================================
test('GO-001: 目数差勝ち（POINTS_WIN）→ 200 + 1-0 確定 → COMPLETED', async () => {
  const mId = await createGoMatch('E2E Go PointsWin')
  try {
    // 目数差勝ち（GoWinMethod.POINTS_WIN・margin は detail に保持可・§2.1）
    const result = await api.put(`${BE_API}/organizations/${orgId}/matches/${mId}/result`, {
      headers: authHeaders(adminToken),
      data: {
        winnerSide: 'HOME',
        winMethod: 'POINTS_WIN',  // GoWinMethod.POINTS_WIN
        totalMoves: 240,
      },
    })
    expect(result.status(), `POINTS_WIN 記録は 200。応答: ${await result.text()}`).toBe(200)
    const rJson = await result.json() as { data: { homeScore: number; awayScore: number; winMethod: string } }
    expect(rJson.data.homeScore, 'HOME 勝ち → homeScore=1').toBe(1)
    expect(rJson.data.awayScore, 'HOME 勝ち → awayScore=0').toBe(0)
    expect(rJson.data.winMethod, 'winMethod=POINTS_WIN').toBe('POINTS_WIN')

    // COMPLETED 遷移（ターン制は duration 不要）
    const status = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${mId}/status`, {
      headers: authHeaders(adminToken),
      data: { status: 'COMPLETED' },
    })
    expect(status.status(), 'COMPLETED 遷移は 200（MATCH_027 なし）').toBe(200)
    const sJson = await status.json() as { data: { status: string } }
    expect(sJson.data.status, 'status=COMPLETED').toBe('COMPLETED')
  } finally {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${mId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
})

// ===========================================================================
// GO-002: 中押し勝ち（RESIGNATION）・AWAY 勝ち
// ===========================================================================
test('GO-002: 中押し（RESIGNATION）AWAY 勝ち → 200 + 0-1 確定', async () => {
  const mId = await createGoMatch('E2E Go Resignation')
  try {
    const result = await api.put(`${BE_API}/organizations/${orgId}/matches/${mId}/result`, {
      headers: authHeaders(adminToken),
      data: { winnerSide: 'AWAY', winMethod: 'RESIGNATION' },
    })
    expect(result.status(), 'RESIGNATION AWAY 勝ちは 200').toBe(200)
    const rJson = await result.json() as { data: { homeScore: number; awayScore: number } }
    expect(rJson.data.homeScore, 'AWAY 勝ち → homeScore=0').toBe(0)
    expect(rJson.data.awayScore, 'AWAY 勝ち → awayScore=1').toBe(1)

    // COMPLETED
    const status = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${mId}/status`, {
      headers: authHeaders(adminToken),
      data: { status: 'COMPLETED' },
    })
    expect(status.status(), 'COMPLETED 遷移は 200').toBe(200)
  } finally {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${mId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
})

// ===========================================================================
// GO-003: 引分（持碁・winnerSide=null・0-0）→ COMPLETED
// ===========================================================================
test('GO-003: 持碁（引分・winnerSide=null）→ 200 + 0-0 → COMPLETED', async () => {
  const mId = await createGoMatch('E2E Go Draw')
  try {
    const result = await api.put(`${BE_API}/organizations/${orgId}/matches/${mId}/result`, {
      headers: authHeaders(adminToken),
      data: { winnerSide: null, winMethod: null },
    })
    expect(result.status(), '持碁引分記録は 200').toBe(200)
    const rJson = await result.json() as { data: { homeScore: number; awayScore: number; winMethod: string | null } }
    expect(rJson.data.homeScore, '持碁 → homeScore=0').toBe(0)
    expect(rJson.data.awayScore, '持碁 → awayScore=0').toBe(0)
    expect(rJson.data.winMethod, '持碁 → winMethod=null').toBeNull()

    const status = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${mId}/status`, {
      headers: authHeaders(adminToken),
      data: { status: 'COMPLETED' },
    })
    expect(status.status(), '持碁 COMPLETED 遷移は 200').toBe(200)
  } finally {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${mId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
})

// ===========================================================================
// GO-004: 競技間流用エラー（MATCH_028）― 将棋の REPETITION（千日手）を囲碁に付与
// ===========================================================================
test('GO-004: 将棋専用 win_method（REPETITION）を囲碁に付与 → 400 + MATCH_028（競技カタログ外）', async () => {
  const mId = await createGoMatch('E2E Go CrossSport')
  try {
    const res = await api.put(`${BE_API}/organizations/${orgId}/matches/${mId}/result`, {
      headers: authHeaders(adminToken),
      data: {
        winnerSide: 'HOME',
        winMethod: 'REPETITION', // ShogiWinMethod.REPETITION（将棋専用）を囲碁に誤適用
      },
    })
    expect(
      res.status(),
      `競技間 win_method 流用は 400（MATCH_028）。応答: ${await res.text()}`,
    ).toBe(400)
    const json = await res.json() as { error: { code: string } }
    expect(json.error.code, '競技カタログ外で MATCH_028').toBe('MATCH_028')
  } finally {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${mId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
})

// ===========================================================================
// GO-005: 不戦勝（DEFAULT_WIN）・HOME 勝ち → COMPLETED
// ===========================================================================
test('GO-005: 不戦勝（DEFAULT_WIN）→ 200 + 1-0 → COMPLETED', async () => {
  const mId = await createGoMatch('E2E Go DefaultWin')
  try {
    const result = await api.put(`${BE_API}/organizations/${orgId}/matches/${mId}/result`, {
      headers: authHeaders(adminToken),
      data: { winnerSide: 'HOME', winMethod: 'DEFAULT_WIN' },
    })
    expect(result.status(), 'DEFAULT_WIN 記録は 200').toBe(200)
    const rJson = await result.json() as { data: { homeScore: number; awayScore: number; winMethod: string } }
    expect(rJson.data.homeScore, 'homeScore=1').toBe(1)
    expect(rJson.data.winMethod, 'winMethod=DEFAULT_WIN').toBe('DEFAULT_WIN')

    const status = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${mId}/status`, {
      headers: authHeaders(adminToken),
      data: { status: 'COMPLETED' },
    })
    expect(status.status(), 'DEFAULT_WIN COMPLETED 遷移は 200').toBe(200)
  } finally {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${mId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
})
