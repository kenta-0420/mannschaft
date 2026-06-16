/**
 * F08.10 採点競技 実機 E2E ― 審判別/種目別採点内訳（match_scored_components）。
 *
 * モックなし・実バックエンド（http://localhost:8080）接続。BE は再起動せず稼働中の最新 main をそのまま使う。
 *
 * 【検証対象】sports/07_scored.md §4B / §9 / §11 の採点内訳経路を実運用 API で一気通貫実証する:
 *   試合作成（sport=FIGURE_SKATING / GYMNASTICS）
 *   → 審判別内訳 PUT /organizations/{orgId}/matches/{matchId}/scored-components（全置換）
 *   → HOME/AWAY ごとの合計点が matches.home_score/away_score（×1000）に正しく集計されること
 *   → COMPLETED 遷移
 *   → 勝敗が合計点大小で導出されること（§B.1.2）。
 *
 * 【実証ポイント】
 *   SCC-001: フィギュア内訳→合計集計
 *     home= TES(80000) + PCS(90000) − DEDUCTION(2000) = 168000
 *     away= TES(75000) + PCS(85000) − DEDUCTION(1500) = 158500
 *     → GET /scored-components で内訳一覧が返る
 *     → home_score=168000 / away_score=158500 → home 勝ち（COMPLETED）
 *   SCC-002: 体操内訳→合計集計（D_SCORE + E_SCORE・種目別）
 *     home= FLOOR D_SCORE(60000) + FLOOR E_SCORE(80000) = 140000
 *     away= FLOOR D_SCORE(55000) + FLOOR E_SCORE(75000) = 130000
 *     → home 勝ち
 *   SCC-003: DEDUCTION 減算・0 クランプ
 *     home= TES(10000) + PCS(20000) − DEDUCTION(99999999) → 合計 < 0 → 0 にクランプ
 *     away= TES(50000) → 50000
 *     → away 勝ち（home=0 < away=50000）
 *   SCC-004: MVP 合計点直接入力との両立
 *     PUT /scored-result（合計直接）が従来どおり動作すること
 *   SCC-005: 冪等（全置換）
 *     内訳を 2 回 PUT → 2 回目で上書き・重複せず合計が正しいこと
 *     GET /scored-components で内訳件数が 2 回目の PUT の件数と一致すること
 *   SCC-006: カタログ検証
 *     フィギュアに D_SCORE（体操専用）を入れる → 400 MATCH_024
 *   SCC-007: 採点競技以外（BASKETBALL）への PUT → 400 MATCH_029
 *
 * 【前提データ】backend/scripts/seed-f0810-multisport-e2e.js を実行済みであること。
 *   - 固有名前空間: org slug=f0810-multisport-club / team slug=f0810-scored-team。
 *   - e2e-admin が当該チームの ADMIN（採点記録は assertCanEditMeta 委譲・ADMIN/DEPUTY 必須）。
 *   - org/team の数値 ID は環境依存のため固定値を持たず、GET /me/teams から slug で動的解決する。
 *
 * 【構成】API 完結（APIRequestContext のみ）。FE dev サーバー（BASE_URL）に依存しない。
 *
 * 設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §4B / §9 / §11 / 01 §B.1.2 / §D.8
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

// 各テストで作成した試合 ID（afterAll でクリーンアップする）
let scc001MatchId: string | null = null
let scc002MatchId: string | null = null
let scc003MatchId: string | null = null
let scc004MatchId: string | null = null
let scc005MatchId: string | null = null
let scc006MatchId: string | null = null
let scc007MatchId: string | null = null

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

/** 採点内訳を全置換で PUT し、応答（更新後の match）を返す。 */
async function putComponents(
  matchId: string,
  components: Array<{
    competitorSide: string
    apparatus?: string
    judgeLabel?: string
    componentType: string
    pointsScaled: number
  }>,
  expectedStatus: number = 200,
): Promise<{ status: number; body: string; data?: { homeScore: number; awayScore: number } }> {
  const res = await api.put(`${BE_API}/organizations/${orgId}/matches/${matchId}/scored-components`, {
    headers: authHeaders(adminToken),
    data: { components },
  })
  const body = await res.text()
  const httpStatus = res.status()
  if (httpStatus === expectedStatus && expectedStatus === 200) {
    const json = JSON.parse(body) as { data: { homeScore: number; awayScore: number } }
    return { status: httpStatus, body, data: json.data }
  }
  return { status: httpStatus, body }
}

/** 採点内訳一覧を GET して返す。 */
async function getComponents(matchId: string): Promise<Array<{
  id: string
  competitorSide: string
  apparatus: string | null
  judgeLabel: string | null
  componentType: string
  pointsScaled: number
}>> {
  const res = await api.get(`${BE_API}/organizations/${orgId}/matches/${matchId}/scored-components`, {
    headers: authHeaders(adminToken),
  })
  expect(res.status(), 'GET /scored-components は 200').toBe(200)
  const json = await res.json() as {
    data: Array<{
      id: string
      competitorSide: string
      apparatus: string | null
      judgeLabel: string | null
      componentType: string
      pointsScaled: number
    }>
  }
  return json.data
}

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
// SCC-000: 認証 + チーム解決
// ===========================================================================
test('SCC-000: ADMIN ログイン + 採点競技チームの org/team ID を slug 解決', async () => {
  expect(adminToken.length, 'トークンは 50 文字以上').toBeGreaterThanOrEqual(50)
  expect(teamId, 'teamId が解決される').toBeGreaterThan(0)
  expect(orgId, 'orgId が解決される').toBeGreaterThan(0)
})

// ===========================================================================
// SCC-001: フィギュア内訳 → 合計集計 → home 勝ち
//   PUT /scored-components で HOME/AWAY に TES/PCS/DEDUCTION を投入し
//   matches.home_score/away_score への集計と勝敗導出を実証する。
//
//   home: TES=80000 + PCS=90000 − DEDUCTION=2000 = 168000
//   away: TES=75000 + PCS=85000 − DEDUCTION=1500 = 158500
//   → home 勝ち（168000 > 158500）
// ===========================================================================
test('SCC-001a: フィギュアスケート試合作成 → 201', async () => {
  scc001MatchId = await createMatch('FIGURE_SKATING', 'SCC001フィギュア内訳相手')
})

test('SCC-001b: PUT /scored-components でフィギュア審判別内訳を全置換 → 200 + 合計点が再導出される', async () => {
  expect(scc001MatchId, 'SCC-001a で試合作成済み').toBeTruthy()
  const result = await putComponents(scc001MatchId!, [
    // HOME: TES(ショートプログラム) J1
    { competitorSide: 'HOME', apparatus: 'SP', judgeLabel: 'J1', componentType: 'TES', pointsScaled: 80000 },
    // HOME: PCS(ショートプログラム)
    { competitorSide: 'HOME', apparatus: 'SP', componentType: 'PCS', pointsScaled: 90000 },
    // HOME: DEDUCTION（減点）
    { competitorSide: 'HOME', componentType: 'DEDUCTION', pointsScaled: 2000 },
    // AWAY: TES
    { competitorSide: 'AWAY', apparatus: 'SP', judgeLabel: 'J1', componentType: 'TES', pointsScaled: 75000 },
    // AWAY: PCS
    { competitorSide: 'AWAY', apparatus: 'SP', componentType: 'PCS', pointsScaled: 85000 },
    // AWAY: DEDUCTION
    { competitorSide: 'AWAY', componentType: 'DEDUCTION', pointsScaled: 1500 },
  ])
  expect(result.status, `PUT /scored-components は 200。応答: ${result.body}`).toBe(200)
  // 二層正本の集計検証: TES+PCS−DEDUCTION が home/away_score に反映されること
  expect(
    result.data!.homeScore,
    'home: TES(80000)+PCS(90000)−DEDUCTION(2000) = 168000',
  ).toBe(168000)
  expect(
    result.data!.awayScore,
    'away: TES(75000)+PCS(85000)−DEDUCTION(1500) = 158500',
  ).toBe(158500)
})

test('SCC-001c: GET /scored-components で内訳一覧が 6 件返る（投入した件数と一致）', async () => {
  expect(scc001MatchId, 'SCC-001a で試合作成済み').toBeTruthy()
  const components = await getComponents(scc001MatchId!)
  expect(
    components.length,
    `内訳 6 件が返る（PUT で投入した件数）。実際: ${components.length}`,
  ).toBe(6)
  // HOME 側の件数
  const homeComponents = components.filter((c) => c.competitorSide === 'HOME')
  expect(homeComponents.length, 'HOME 内訳 3 件').toBe(3)
  // AWAY 側の件数
  const awayComponents = components.filter((c) => c.competitorSide === 'AWAY')
  expect(awayComponents.length, 'AWAY 内訳 3 件').toBe(3)
})

test('SCC-001d: COMPLETED 遷移 → 200 + home 勝ち（合計点大小で導出・§B.1.2）', async () => {
  expect(scc001MatchId, 'SCC-001a で試合作成済み').toBeTruthy()
  const { status, body } = await changeStatus(scc001MatchId!, 'COMPLETED')
  expect(status, `COMPLETED 遷移は 200。応答: ${body}`).toBe(200)
  const match = await getMatch(scc001MatchId!)
  expect(match.status, 'status=COMPLETED').toBe('COMPLETED')
  expect(match.homeScore, 'home_score=168000 が保持される').toBe(168000)
  expect(match.awayScore, 'away_score=158500 が保持される').toBe(158500)
  // home 168000 > away 158500 → home 勝ち（採点競技は win_method 無し・§10）
  expect(match.winMethod, '採点競技の win_method は null（§10）').toBeNull()
})

// ===========================================================================
// SCC-002: 体操内訳 → 合計集計（D_SCORE + E_SCORE・種目別）→ home 勝ち
//   home= FLOOR D_SCORE(60000) + FLOOR E_SCORE(80000) = 140000
//   away= FLOOR D_SCORE(55000) + FLOOR E_SCORE(75000) = 130000
//   → home 勝ち
// ===========================================================================
test('SCC-002a: 体操試合作成 → 201', async () => {
  scc002MatchId = await createMatch('GYMNASTICS', 'SCC002体操内訳相手')
})

test('SCC-002b: PUT /scored-components で体操内訳（D_SCORE/E_SCORE・FLOOR 種目）→ 200 + 合計集計', async () => {
  expect(scc002MatchId, 'SCC-002a で試合作成済み').toBeTruthy()
  const result = await putComponents(scc002MatchId!, [
    // HOME: 床 D スコア
    { competitorSide: 'HOME', apparatus: 'FLOOR', componentType: 'D_SCORE', pointsScaled: 60000 },
    // HOME: 床 E スコア
    { competitorSide: 'HOME', apparatus: 'FLOOR', componentType: 'E_SCORE', pointsScaled: 80000 },
    // AWAY: 床 D スコア
    { competitorSide: 'AWAY', apparatus: 'FLOOR', componentType: 'D_SCORE', pointsScaled: 55000 },
    // AWAY: 床 E スコア
    { competitorSide: 'AWAY', apparatus: 'FLOOR', componentType: 'E_SCORE', pointsScaled: 75000 },
  ])
  expect(result.status, `体操 PUT /scored-components は 200。応答: ${result.body}`).toBe(200)
  expect(
    result.data!.homeScore,
    'home: D_SCORE(60000)+E_SCORE(80000) = 140000',
  ).toBe(140000)
  expect(
    result.data!.awayScore,
    'away: D_SCORE(55000)+E_SCORE(75000) = 130000',
  ).toBe(130000)
})

test('SCC-002c: 体操 COMPLETED → home 勝ち（140000 > 130000）', async () => {
  expect(scc002MatchId, 'SCC-002a で試合作成済み').toBeTruthy()
  const { status, body } = await changeStatus(scc002MatchId!, 'COMPLETED')
  expect(status, `体操 COMPLETED は 200。応答: ${body}`).toBe(200)
  const match = await getMatch(scc002MatchId!)
  expect(match.status, 'status=COMPLETED').toBe('COMPLETED')
  expect(match.homeScore, 'home_score=140000').toBe(140000)
  expect(match.awayScore, 'away_score=130000').toBe(130000)
  expect(match.winMethod, '体操の win_method は null（採点競技・§10）').toBeNull()
})

// ===========================================================================
// SCC-003: DEDUCTION 減算・0 クランプ
//   home= TES(10000) + PCS(20000) − DEDUCTION(99999999) → 合計 < 0 → 0 クランプ
//   away= TES(50000) → 50000
//   → away 勝ち（home=0 < away=50000）
// ===========================================================================
test('SCC-003: DEDUCTION 減算・0 クランプ — 減点超過で合計 < 0 → home_score=0 にクランプ', async () => {
  scc003MatchId = await createMatch('FIGURE_SKATING', 'SCC003減点クランプ相手')

  const result = await putComponents(scc003MatchId!, [
    // HOME: 減点が TES+PCS を超える（負になる入力）→ 0 クランプ（§4B.2）
    { competitorSide: 'HOME', componentType: 'TES', pointsScaled: 10000 },
    { competitorSide: 'HOME', componentType: 'PCS', pointsScaled: 20000 },
    { competitorSide: 'HOME', componentType: 'DEDUCTION', pointsScaled: 99999999 },
    // AWAY: 減点なし
    { competitorSide: 'AWAY', componentType: 'TES', pointsScaled: 50000 },
  ])
  expect(result.status, `0 クランプケース PUT は 200。応答: ${result.body}`).toBe(200)
  // home: 10000 + 20000 − 99999999 = 負 → 0 にクランプ
  expect(
    result.data!.homeScore,
    'home: 減点超過で負 → 0 にクランプされる（UNSIGNED 制約・§4B.2）',
  ).toBe(0)
  expect(
    result.data!.awayScore,
    'away: TES(50000) = 50000',
  ).toBe(50000)

  // COMPLETED で away 勝ちを確認
  const { status, body } = await changeStatus(scc003MatchId!, 'COMPLETED')
  expect(status, `COMPLETED は 200。応答: ${body}`).toBe(200)
  const match = await getMatch(scc003MatchId!)
  expect(match.status, 'status=COMPLETED').toBe('COMPLETED')
  expect(match.homeScore, '0 クランプ後の home_score=0').toBe(0)
  expect(match.awayScore, 'away_score=50000').toBe(50000)
  // home 0 < away 50000 → away 勝ち（win_method は null）
  expect(match.winMethod, 'away 勝ちでも win_method は null（採点競技）').toBeNull()
})

// ===========================================================================
// SCC-004: MVP 合計点直接入力との両立
//   PUT /scored-result（合計直接）が内訳エンドポイントとは独立して動作すること
// ===========================================================================
test('SCC-004: MVP 合計点直接入力（PUT /scored-result）が引き続き動作すること', async () => {
  scc004MatchId = await createMatch('FIGURE_SKATING', 'SCC004合計直接相手')

  // 内訳なし→合計直接入力（既存 API・PUT /scored-result）
  const res = await api.put(`${BE_API}/organizations/${orgId}/matches/${scc004MatchId!}/scored-result`, {
    headers: authHeaders(adminToken),
    data: { homeScoreScaled: 123456, awayScoreScaled: 112000 },
  })
  const body = await res.text()
  expect(
    res.status(),
    `PUT /scored-result は 200（内訳エンドポイントとは独立）。応答: ${body}`,
  ).toBe(200)
  const json = JSON.parse(body) as { data: { homeScore: number; awayScore: number } }
  expect(json.data.homeScore, 'homeScore=123456 が格納される').toBe(123456)
  expect(json.data.awayScore, 'awayScore=112000 が格納される').toBe(112000)

  // COMPLETED 遷移（合計確定済み）
  const { status: cStatus, body: cBody } = await changeStatus(scc004MatchId!, 'COMPLETED')
  expect(cStatus, `COMPLETED は 200。応答: ${cBody}`).toBe(200)
  const match = await getMatch(scc004MatchId!)
  expect(match.status, 'status=COMPLETED').toBe('COMPLETED')
  expect(match.homeScore, 'home_score=123456 が保持される').toBe(123456)
  expect(match.awayScore, 'away_score=112000 が保持される').toBe(112000)
})

// ===========================================================================
// SCC-005: 冪等（全置換）
//   内訳を 2 回 PUT → 2 回目で上書き・重複せず合計が 2 回目の内訳の値に一致すること。
//   GET /scored-components で内訳件数が 2 回目の PUT の件数（2件）と一致すること。
// ===========================================================================
test('SCC-005: 冪等（全置換） — 2 回目の PUT で上書き・内訳件数が置き換わること', async () => {
  scc005MatchId = await createMatch('FIGURE_SKATING', 'SCC005冪等相手')

  // 1 回目: 3 件投入
  const result1 = await putComponents(scc005MatchId!, [
    { competitorSide: 'HOME', componentType: 'TES', pointsScaled: 50000 },
    { competitorSide: 'HOME', componentType: 'PCS', pointsScaled: 60000 },
    { competitorSide: 'AWAY', componentType: 'TES', pointsScaled: 40000 },
  ])
  expect(result1.status, `1 回目 PUT は 200。応答: ${result1.body}`).toBe(200)
  expect(result1.data!.homeScore, '1 回目: home=110000').toBe(110000)
  expect(result1.data!.awayScore, '1 回目: away=40000').toBe(40000)

  const components1 = await getComponents(scc005MatchId!)
  expect(components1.length, '1 回目 PUT 後の内訳件数は 3 件').toBe(3)

  // 2 回目: 2 件投入（全置換）→ 1 回目の 3 件が削除されて 2 件になること
  const result2 = await putComponents(scc005MatchId!, [
    { competitorSide: 'HOME', componentType: 'TES', pointsScaled: 99000 },
    { competitorSide: 'AWAY', componentType: 'TES', pointsScaled: 88000 },
  ])
  expect(result2.status, `2 回目 PUT は 200（全置換・冪等）。応答: ${result2.body}`).toBe(200)
  // 合計は 2 回目の内訳のみ（重複しない）
  expect(result2.data!.homeScore, '2 回目: home=99000（1 回目の 110000 ではない）').toBe(99000)
  expect(result2.data!.awayScore, '2 回目: away=88000（1 回目の 40000 ではない）').toBe(88000)

  // GET で内訳件数が 2 件になっていること（全置換で 1 回目の 3 件が消えている）
  const components2 = await getComponents(scc005MatchId!)
  expect(
    components2.length,
    '2 回目 PUT 後の内訳件数は 2 件（全置換で 1 回目の 3 件が上書きされた）',
  ).toBe(2)
})

// ===========================================================================
// SCC-006: カタログ検証 — フィギュアに体操専用の D_SCORE を入れる → 400 MATCH_024
// ===========================================================================
test('SCC-006: カタログ検証 — フィギュアに体操専用 D_SCORE → 400 MATCH_024', async () => {
  scc006MatchId = await createMatch('FIGURE_SKATING', 'SCC006カタログ違反相手')

  const result = await putComponents(scc006MatchId!, [
    // D_SCORE はフィギュアのカタログにない（体操専用）→ 400 MATCH_024
    { competitorSide: 'HOME', componentType: 'D_SCORE', pointsScaled: 60000 },
    { competitorSide: 'AWAY', componentType: 'TES', pointsScaled: 50000 },
  ], 400)

  expect(
    result.status,
    `フィギュアに D_SCORE は 400（カタログ外・MATCH_024・症状を隠さない）。応答: ${result.body}`,
  ).toBe(400)
  expect(
    result.body,
    '応答本文に MATCH_024 が含まれる（競技カタログ外の項目は弾く）',
  ).toContain('MATCH_024')
})

// ===========================================================================
// SCC-007: 採点競技以外（BASKETBALL）への PUT → 400 MATCH_029
// ===========================================================================
test('SCC-007: BASKETBALL 試合に PUT /scored-components → 400 MATCH_029', async () => {
  // BASKETBALL 試合を scored-team で作成（sport は matches 側で決まる）
  scc007MatchId = await createMatch('BASKETBALL', 'SCC007バスケ誤操作相手')

  const result = await putComponents(scc007MatchId!, [
    { competitorSide: 'HOME', componentType: 'TES', pointsScaled: 50000 },
  ], 400)

  expect(
    result.status,
    `BASKETBALL 試合への scored-components PUT は 400（MATCH_029）。応答: ${result.body}`,
  ).toBe(400)
  expect(
    result.body,
    '応答本文に MATCH_029 が含まれる（採点競技以外への操作を弾く）',
  ).toContain('MATCH_029')
})

// ===========================================================================
// クリーンアップ
// ===========================================================================
test.afterAll(async () => {
  for (const mId of [
    scc001MatchId,
    scc002MatchId,
    scc003MatchId,
    scc004MatchId,
    scc005MatchId,
    scc006MatchId,
    scc007MatchId,
  ]) {
    if (mId) await deleteMatch(mId)
  }
  await api.dispose()
})
