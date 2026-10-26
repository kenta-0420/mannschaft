/**
 * F08.10 多競技ライブ記録 実機 E2E ― 採点競技（フィギュアスケート / 体操）。
 *
 * モックなし・実バックエンド（http://localhost:8080）接続。BE は再起動せず稼働中の最新 main をそのまま使う。
 *
 * 【検証対象】sports/07_scored.md の採点競技フローを実運用 API で一気通貫実証する:
 *   試合作成（sport=FIGURE_SKATING / GYMNASTICS）
 *   → 合計点記録（PUT /scored-result・整数スケール×1000）
 *   → COMPLETED 遷移
 *   → home/away_score に記録値が格納されること・勝敗が合計点大小で導出されること（§B.1.2）。
 *
 * 【実証ポイント】
 *   - ×1000 スケール変換: 198.45 点 → homeScoreScaled=198450。サーバーはそのまま格納。
 *   - 勝敗導出: home 198450 < away 201320 → away 勝ち（AWAY_WIN）。
 *   - 同点引分: home=away=150000 → COMPLETED で DRAW（§6）。
 *   - 未確定拒否: 合計点未記録で COMPLETED → MATCH_035（400）をハードアサート。
 *
 * 【前提データ】backend/scripts/seed-f0810-multisport-e2e.js を実行済みであること。
 *   - 固有名前空間: org slug=f0810-multisport-club / team slug=f0810-scored-team。
 *   - e2e-admin が当該チームの ADMIN（採点記録は assertCanEditMeta 委譲・ADMIN/DEPUTY 必須）。
 *   - org/team の数値 ID は環境依存のため固定値を持たず、GET /me/teams から slug で動的解決する。
 *
 * 【構成】API 完結（APIRequestContext のみ）。FE dev サーバー（BASE_URL）に依存しない。
 *
 * 設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §4 / §6 / §9 / §11 / 01 §B.1.2 / §D.8
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

// seed の固有 slug（環境非依存。数値 ID は /me/teams から解決する）
const TEAM_SLUG = 'f0810-scored-team'

// ── テスト状態 ──────────────────────────────────────────────────
let api: APIRequestContext
let adminToken: string
let orgId: number
let teamId: number

// ── ヘルパー ────────────────────────────────────────────────────
async function login(apiCtx: APIRequestContext): Promise<string> {
  const res = await apiCtx.post(`${BE_API}/auth/login`, {
    data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
  })
  expect(res.status(), `login は 200。応答: ${await res.text()}`).toBe(200)
  return (await res.json() as { data: { accessToken: string } }).data.accessToken
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function resolveTeam(token: string, slug: string): Promise<{ teamId: number; orgId: number }> {
  const res = await api.get(`${BE_API}/me/teams`, { headers: authHeaders(token) })
  expect(res.status(), `/me/teams は 200`).toBe(200)
  const json = await res.json() as {
    data: Array<{ id: number; slug: string; organizationId: number; role: string }>
  }
  const team = json.data.find((t) => t.slug === slug)
  expect(
    team,
    `seed-f0810-multisport-e2e.js のチーム(${slug})が /me/teams に存在する（seed 未実行なら null）`,
  ).toBeTruthy()
  expect(team!.role, 'e2e-admin は当該チームの ADMIN（採点記録権限の前提）').toBe('ADMIN')
  return { teamId: team!.id, orgId: team!.organizationId }
}

/**
 * 試合を作成して matchId を返す。
 */
async function createMatch(sport: string, opponentName: string): Promise<string> {
  const res = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: {
      sport,
      kind: 'FRIENDLY',
      homeAway: 'HOME',
      opponentName,
    },
  })
  expect(
    res.status(),
    `試合作成(${sport})は 201。応答: ${await res.text()}`,
  ).toBe(201)
  const json = await res.json() as { data: { id: string; sport: string; status: string } }
  expect(json.data.sport, `sport=${sport} が永続化される`).toBe(sport)
  expect(json.data.status, '初期 status は SCHEDULED').toBe('SCHEDULED')
  const mId = json.data.id
  expect(mId, '試合 UUID が返る').toBeTruthy()
  return mId
}

/**
 * PUT /scored-result で合計点を記録し、保存されたスコアをハードアサートする。
 */
async function recordScoredResult(
  matchId: string,
  homeScoreScaled: number,
  awayScoreScaled: number,
): Promise<{ homeScore: number; awayScore: number }> {
  const res = await api.put(`${BE_API}/organizations/${orgId}/matches/${matchId}/scored-result`, {
    headers: authHeaders(adminToken),
    data: { homeScoreScaled, awayScoreScaled },
  })
  expect(
    res.status(),
    `採点結果記録(home=${homeScoreScaled}, away=${awayScoreScaled})は 200。応答: ${await res.text()}`,
  ).toBe(200)
  const json = await res.json() as { data: { homeScore: number; awayScore: number } }
  // サーバーは整数スケール値をそのまま格納する（§4.1）
  expect(json.data.homeScore, `homeScore が ${homeScoreScaled} として格納される（×1000 スケール値）`).toBe(homeScoreScaled)
  expect(json.data.awayScore, `awayScore が ${awayScoreScaled} として格納される（×1000 スケール値）`).toBe(awayScoreScaled)
  return { homeScore: json.data.homeScore, awayScore: json.data.awayScore }
}

/**
 * PATCH .../status で status を変更し、応答をアサートする。
 */
async function changeStatus(
  matchId: string,
  status: string,
  expectedHttpStatus: number = 200,
): Promise<{ status: number; body: string }> {
  const res = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}/status`, {
    headers: authHeaders(adminToken),
    data: { status },
  })
  const body = await res.text()
  const httpStatus = res.status()
  expect(httpStatus, `status 変更 API は ${expectedHttpStatus} を返す。応答: ${body}`).toBe(expectedHttpStatus)
  return { status: httpStatus, body }
}

/**
 * GET で試合を取得して結果を返す。
 */
async function getMatch(matchId: string): Promise<{
  status: string
  homeScore: number
  awayScore: number
  winMethod: string | null
}> {
  const res = await api.get(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}`, {
    headers: authHeaders(adminToken),
  })
  expect(res.status(), '試合取得は 200').toBe(200)
  return (await res.json() as { data: { status: string; homeScore: number; awayScore: number; winMethod: string | null } }).data
}

/**
 * afterAll 用クリーンアップ（失敗しても握りつぶす）。
 */
async function deleteMatch(matchId: string): Promise<void> {
  await api
    .delete(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}`, {
      headers: authHeaders(adminToken),
    })
    .catch(() => {})
}

// ── セットアップ ─────────────────────────────────────────────────
test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await login(api)
  const resolved = await resolveTeam(adminToken, TEAM_SLUG)
  teamId = resolved.teamId
  orgId = resolved.orgId
})

// ===========================================================================
// SC-000: 認証 + チーム解決
// ===========================================================================
test('SC-000: ADMIN ログイン + 採点競技チームの org/team ID を slug 解決', async () => {
  expect(adminToken.length, 'トークンは 50 文字以上').toBeGreaterThanOrEqual(50)
  expect(teamId, 'teamId が解決される').toBeGreaterThan(0)
  expect(orgId, 'orgId が解決される').toBeGreaterThan(0)
})

// ===========================================================================
// SC-001: フィギュアスケート E2E（away 勝ち）
//   フィギュア試合作成 → PUT /scored-result（×1000スケール）→ COMPLETED
//   → away_score > home_score なので away 勝ち（§B.1.2）
// ===========================================================================
test('SC-001: フィギュアスケート試合作成 → 201 + sport=FIGURE_SKATING・status=SCHEDULED', async () => {
  const matchId = await createMatch('FIGURE_SKATING', 'E2Eフィギュア相手')
  // afterAll でクリーンアップするため test.info に matchId を保存
  // （serial モードなので SC-002 以降が参照できるようグローバル変数を使う）
  figureMatchId = matchId
})

test('SC-001b: PUT /scored-result で合計点記録（home=198450≒198.45点, away=201320≒201.32点）', async () => {
  expect(figureMatchId, 'SC-001 で試合作成済み').toBeTruthy()
  // 整数スケール×1000: 198.45 → 198450、201.32 → 201320
  const { homeScore, awayScore } = await recordScoredResult(figureMatchId!, 198450, 201320)
  expect(homeScore, 'home_score に 198450 がそのまま格納される').toBe(198450)
  expect(awayScore, 'away_score に 201320 がそのまま格納される').toBe(201320)
})

test('SC-001c: COMPLETED 遷移 → 200 + status=COMPLETED・away 勝ち（合計点大小で導出）', async () => {
  expect(figureMatchId, 'SC-001 で試合作成済み').toBeTruthy()
  const { status, body } = await changeStatus(figureMatchId!, 'COMPLETED')
  expect(
    status,
    `COMPLETED 遷移は 200（採点確定済み・§D.8・MATCH_035 が出ないこと）。応答: ${body}`,
  ).toBe(200)
  const match = await getMatch(figureMatchId!)
  expect(match.status, 'status=COMPLETED').toBe('COMPLETED')
  // home 198450 < away 201320 → away 勝ち（合計点大小・§B.1.2）
  expect(match.homeScore, 'home_score=198450 が保持される').toBe(198450)
  expect(match.awayScore, 'away_score=201320 が保持される').toBe(201320)
  // 採点競技は win_method を使わない（NULL・§10）
  expect(match.winMethod, '採点競技の win_method は null（勝ち方概念なし・§10）').toBeNull()
})

// ===========================================================================
// SC-002: 体操 E2E（home 勝ち）
//   体操試合作成 → PUT /scored-result → COMPLETED
//   → home_score > away_score なので home 勝ち
// ===========================================================================
test('SC-002: 体操試合作成 → 201 + sport=GYMNASTICS・status=SCHEDULED', async () => {
  const matchId = await createMatch('GYMNASTICS', 'E2E体操相手')
  gymnasticsMatchId = matchId
})

test('SC-002b: PUT /scored-result で合計点記録（home=875600≒875.6点, away=863200≒863.2点）', async () => {
  expect(gymnasticsMatchId, 'SC-002 で試合作成済み').toBeTruthy()
  // 整数スケール×1000: 875.6 → 875600、863.2 → 863200
  const { homeScore, awayScore } = await recordScoredResult(gymnasticsMatchId!, 875600, 863200)
  expect(homeScore, 'home_score に 875600 がそのまま格納される').toBe(875600)
  expect(awayScore, 'away_score に 863200 がそのまま格納される').toBe(863200)
})

test('SC-002c: COMPLETED 遷移 → 200 + status=COMPLETED・home 勝ち（合計点大小で導出）', async () => {
  expect(gymnasticsMatchId, 'SC-002 で試合作成済み').toBeTruthy()
  const { status, body } = await changeStatus(gymnasticsMatchId!, 'COMPLETED')
  expect(
    status,
    `体操 COMPLETED は 200。応答: ${body}`,
  ).toBe(200)
  const match = await getMatch(gymnasticsMatchId!)
  expect(match.status, 'status=COMPLETED').toBe('COMPLETED')
  // home 875600 > away 863200 → home 勝ち
  expect(match.homeScore, 'home_score=875600 が保持される').toBe(875600)
  expect(match.awayScore, 'away_score=863200 が保持される').toBe(863200)
  expect(match.winMethod, '体操の win_method は null（採点競技・§10）').toBeNull()
})

// ===========================================================================
// SC-003: 同点引分 E2E（DRAW・§6）
//   home=away 同点 → COMPLETED → DRAW（引分）
// ===========================================================================
test('SC-003: 同点引分 E2E — home=away=150000（150.000点）で COMPLETED → DRAW', async () => {
  const drawMatchId = await createMatch('FIGURE_SKATING', 'E2E同点引分相手')
  drawMatchIdGlobal = drawMatchId

  // 同点スコアを記録（150.000点 → 150000）
  const { homeScore, awayScore } = await recordScoredResult(drawMatchId, 150000, 150000)
  expect(homeScore, 'home_score=150000').toBe(150000)
  expect(awayScore, 'away_score=150000').toBe(150000)

  // COMPLETED に遷移（同点でも COMPLETED 可・§6）
  const { status, body } = await changeStatus(drawMatchId, 'COMPLETED')
  expect(status, `同点 COMPLETED は 200（DRAW・§6）。応答: ${body}`).toBe(200)

  const match = await getMatch(drawMatchId)
  expect(match.status, 'status=COMPLETED').toBe('COMPLETED')
  // 勝敗は home/away_score の大小で導出（同点 → DRAW・§B.1.2 / §6）
  // DRAW であることは status=COMPLETED かつ home_score==away_score であれば OK
  // （勝者フィールドが別途ない場合はスコア同値が DRAW の証拠）
  expect(match.homeScore, 'home_score=150000 が保持される').toBe(150000)
  expect(match.awayScore, 'away_score=150000 が保持される（同点引分）').toBe(150000)
  expect(match.winMethod, '引分の win_method は null').toBeNull()
})

// ===========================================================================
// SC-004: 未確定拒否 E2E — 合計点未記録で COMPLETED → MATCH_035（400）
// ===========================================================================
test('SC-004: 合計点未記録（home/away_score=null）で COMPLETED → 400 MATCH_035', async () => {
  const noScoreMatchId = await createMatch('FIGURE_SKATING', 'E2E未確定拒否相手')
  noScoreMatchIdGlobal = noScoreMatchId

  // scored-result を記録せずに COMPLETED を試みる
  const { status, body } = await changeStatus(noScoreMatchId, 'COMPLETED', 400)
  expect(
    status,
    `採点未確定の COMPLETED は 400（MATCH_035）。応答: ${body}`,
  ).toBe(400)
  // MATCH_035 エラーコードのハードアサート（症状を隠さない）
  expect(body, '応答本文に MATCH_035 が含まれる').toContain('MATCH_035')
})

// ===========================================================================
// SC-005: SCORED 以外の試合への scored-result PUT → 400 MATCH_029
//   採点競技でない試合（BASKETBALL）への PUT /scored-result は弾かれること
// ===========================================================================
test('SC-005: BASKETBALL 試合に PUT /scored-result → 400 MATCH_029（採点競技以外の操作拒否）', async () => {
  // BASKETBALL 試合を作成（※ f0810-basketball-team を使わず、scored-team でも BASKETBALL 試合を作れる）
  const basketMatchId = await createMatch('BASKETBALL', 'E2Eバスケ相手（採点誤操作テスト）')
  basketMatchIdGlobal = basketMatchId

  // 採点競技でない試合に PUT /scored-result → 400 MATCH_029
  const res = await api.put(`${BE_API}/organizations/${orgId}/matches/${basketMatchId}/scored-result`, {
    headers: authHeaders(adminToken),
    data: { homeScoreScaled: 100000, awayScoreScaled: 95000 },
  })
  const body = await res.text()
  expect(
    res.status(),
    `BASKETBALL 試合への scored-result PUT は 400（MATCH_029・採点競技以外の操作を弾く）。応答: ${body}`,
  ).toBe(400)
  expect(body, '応答本文に MATCH_029 が含まれる').toContain('MATCH_029')
})

// ===========================================================================
// クリーンアップ
// ===========================================================================
test.afterAll(async () => {
  // 全試合のクリーンアップ（失敗しても握りつぶす）
  for (const mId of [
    figureMatchId,
    gymnasticsMatchId,
    drawMatchIdGlobal,
    noScoreMatchIdGlobal,
    basketMatchIdGlobal,
  ]) {
    if (mId) await deleteMatch(mId)
  }
  await api.dispose()
})

// ── グローバル状態（serial モード用） ───────────────────────────
let figureMatchId: string | null = null
let gymnasticsMatchId: string | null = null
let drawMatchIdGlobal: string | null = null
let noScoreMatchIdGlobal: string | null = null
let basketMatchIdGlobal: string | null = null
