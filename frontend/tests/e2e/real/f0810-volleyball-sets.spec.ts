/**
 * F08.10 多競技ライブ記録 実機 E2E ― セット制（バレーボール）。
 *
 * モックなし・実バックエンド（http://localhost:8080）接続。BE は再起動せず稼働中の最新 main をそのまま使う。
 *
 * 【検証対象】sports/04_volleyball.md のセット制フローを実運用 API で一気通貫実証する:
 *   試合作成（sport=VOLLEYBALL・best-of-5）→ セット入力（PUT /sets・25 点制／デュース／最終 15 点制）
 *   → 獲得セット数の自動集計（match_sets → matches.home/away_score・§B.1.2）
 *   → 3 セット先取で COMPLETED 遷移。
 *   FE の MatchEventSheetVolleyball / useMatchSetTracker が叩く production エンドポイント
 *   （MatchRecordSetController の PUT /sets・upsert）の実挙動をハードアサートする。
 *
 * 【セット勝者・デュースの実証】
 *   - 通常セット 25 点制・2 点差必須（24-26 / 25-20）。
 *   - デュース（24-24 → 26-24）でホーム勝ち。
 *   - サーバーが winner_side を導出する（クライアントの勝敗主張は信頼しない・VolleyballSetRules）。
 *   - 獲得セット数（matches.home/away_score）はサーバー集計＝spec はその反映を検証する。
 *
 * 【前提データ】backend/scripts/seed-f0810-multisport-e2e.js を実行済みであること。
 *   - 固有名前空間: org slug=f0810-multisport-club / team slug=f0810-volleyball-team。
 *   - e2e-admin が当該チームの ADMIN（記録は assertCanRecordTimeline 委譲・ADMIN/DEPUTY 必須）。
 *   - 数値 ID は GET /me/teams から slug で動的解決（環境非依存）。
 *
 * 【構成】API 完結（APIRequestContext のみ）。FE dev サーバー（BASE_URL）に依存しない。
 *
 * 設計: docs/features/F08.10_match_record_analytics/sports/04_volleyball.md §4 / §8.1 / 01 §B.1.2 / §B.5 / §D.6
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

const TEAM_SLUG = 'f0810-volleyball-team'

let api: APIRequestContext
let adminToken: string
let orgId: number
let teamId: number
let matchId: string | null = null

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
    `seed-f0810-multisport-e2e.js のチーム(${slug})が /me/teams に存在する（seed 未実行なら null）`,
  ).toBeTruthy()
  expect(team!.role, 'e2e-admin は当該チームの ADMIN（記録権限の前提）').toBe('ADMIN')
  return { teamId: team!.id, orgId: team!.organizationId }
}

/** PUT /sets でセットスコアを upsert し、サーバー導出の winner_side をハードアサートする。 */
async function recordSet(
  setNumber: number, homePoints: number, awayPoints: number,
  expectedWinner: 'HOME' | 'AWAY' | null, expectedFinal: boolean,
): Promise<void> {
  const res = await api.put(`${BE_API}/organizations/${orgId}/matches/${matchId}/sets`, {
    headers: authHeaders(adminToken),
    data: { setNumber, homePoints, awayPoints },
  })
  expect(
    res.status(),
    `セット${setNumber}記録(${homePoints}-${awayPoints})は 200。応答: ${await res.text()}`,
  ).toBe(200)
  const json = await res.json() as {
    data: { setNumber: number; homePoints: number; awayPoints: number; winnerSide: string | null; finalSet: boolean }
  }
  expect(json.data.setNumber, 'setNumber エコー').toBe(setNumber)
  expect(json.data.homePoints, 'homePoints エコー').toBe(homePoints)
  expect(json.data.awayPoints, 'awayPoints エコー').toBe(awayPoints)
  expect(
    json.data.winnerSide,
    `セット${setNumber}の勝者はサーバー導出で ${expectedWinner ?? 'null(未決着)'}（VolleyballSetRules）`,
  ).toBe(expectedWinner)
  expect(json.data.finalSet, `セット${setNumber}の最終セット判定`).toBe(expectedFinal)
}

test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await login(api)
  const resolved = await resolveTeam(adminToken, TEAM_SLUG)
  teamId = resolved.teamId
  orgId = resolved.orgId
})

test.afterAll(async () => {
  if (adminToken && matchId) {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
  await api.dispose()
})

// ===========================================================================
// VB-000: 認証 + チーム解決
// ===========================================================================
test('VB-000: ADMIN ログイン + バレーチームの org/team ID を slug 解決', async () => {
  expect(adminToken.length, 'トークンは 50 文字以上').toBeGreaterThanOrEqual(50)
  expect(teamId, 'teamId が解決される').toBeGreaterThan(0)
  expect(orgId, 'orgId が解決される').toBeGreaterThan(0)
})

// ===========================================================================
// VB-001: バレー試合を作成（sport=VOLLEYBALL・best-of-5・セット制）
// ===========================================================================
test('VB-001: バレー試合を作成 → 201 + sport=VOLLEYBALL・status=SCHEDULED', async () => {
  const res = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: {
      sport: 'VOLLEYBALL',
      kind: 'FRIENDLY',
      homeAway: 'HOME',
      opponentName: 'E2Eバレー相手',
      periodFormat: 'BEST_OF_5', // 3 セット先取・第 5 セットが 15 点制（VolleyballSetRules）
    },
  })
  expect(res.status(), `バレー試合作成は 201。応答: ${await res.text()}`).toBe(201)
  const json = await res.json() as { data: { id: string; sport: string; status: string } }
  expect(json.data.sport, 'sport=VOLLEYBALL が永続化される').toBe('VOLLEYBALL')
  expect(json.data.status, '初期 status は SCHEDULED').toBe('SCHEDULED')
  matchId = json.data.id
  expect(matchId, '試合 UUID が返る').toBeTruthy()
})

// ===========================================================================
// VB-002: セット入力（25 点制・デュース・最終 15 点制）+ 勝者サーバー導出
// ===========================================================================
test('VB-002: セット 1〜4 を入力（25 点制・デュース）→ 各セット勝者がサーバー導出される', async () => {
  expect(matchId, 'VB-001 で試合作成済み').toBeTruthy()

  // セット1: 25-20（ホーム勝ち・通常 25 点制）
  await recordSet(1, 25, 20, 'HOME', false)
  // セット2: 24-26（アウェイ勝ち・2 点差成立）
  await recordSet(2, 24, 26, 'AWAY', false)
  // セット3: デュース 26-24（ホーム勝ち・24-24 から 2 点差で決着）
  await recordSet(3, 26, 24, 'HOME', false)
  // セット4: 25-18（ホーム勝ち → ホーム 3 セット先取で試合決着）
  await recordSet(4, 25, 18, 'HOME', false)
})

// ===========================================================================
// VB-003: 獲得セット数が matches.home/away_score に自動集計される（§B.1.2）
// ===========================================================================
test('VB-003: 獲得セット数（home=3, away=1）が matches スコアに自動集計される', async () => {
  expect(matchId, 'VB-001 で試合作成済み').toBeTruthy()

  // セット一覧（PUT のたびに集計されている）
  const setsRes = await api.get(`${BE_API}/organizations/${orgId}/matches/${matchId}/sets`, {
    headers: authHeaders(adminToken),
  })
  expect(setsRes.status(), 'sets 一覧は 200').toBe(200)
  const setsJson = await setsRes.json() as { data: Array<{ setNumber: number; winnerSide: string | null }> }
  expect(setsJson.data.length, '4 セット記録済み').toBe(4)
  const homeSets = setsJson.data.filter((s) => s.winnerSide === 'HOME').length
  const awaySets = setsJson.data.filter((s) => s.winnerSide === 'AWAY').length
  expect(homeSets, 'ホーム獲得セット=3').toBe(3)
  expect(awaySets, 'アウェイ獲得セット=1').toBe(1)

  // matches.home/away_score（獲得セット数の正本反映）
  const matchRes = await api.get(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}`, {
    headers: authHeaders(adminToken),
  })
  expect(matchRes.status(), '試合取得は 200').toBe(200)
  const matchJson = await matchRes.json() as { data: { homeScore: number; awayScore: number } }
  expect(matchJson.data.homeScore, 'matches.home_score=3（獲得セット数の自動集計）').toBe(3)
  expect(matchJson.data.awayScore, 'matches.away_score=1（獲得セット数の自動集計）').toBe(1)
})

// ===========================================================================
// VB-004: 3 セット先取で COMPLETED 遷移できる（試合決着＝勝敗確定）
// ===========================================================================
test('VB-004: COMPLETED 遷移 → 200 + status=COMPLETED（3 セット先取で決着・MATCH_026 が出ないこと）', async () => {
  expect(matchId, 'VB-001 で試合作成済み').toBeTruthy()
  const res = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}/status`, {
    headers: authHeaders(adminToken),
    data: { status: 'COMPLETED' },
  })
  expect(
    res.status(),
    `COMPLETED 遷移は 200（獲得セット 3-1 で決着・MATCH_026 が出ないこと）。応答: ${await res.text()}`,
  ).toBe(200)
  const json = await res.json() as { data: { status: string; homeScore: number; awayScore: number } }
  expect(json.data.status, 'status=COMPLETED').toBe('COMPLETED')
  expect(json.data.homeScore, '勝者=ホーム（獲得 3 セット）').toBe(3)
  expect(json.data.awayScore, '敗者=アウェイ（獲得 1 セット）').toBe(1)
})

// ===========================================================================
// VB-005: 未決着（同点 24-24）はセット勝者 null（デュース未成立を握りつぶさない）
// ===========================================================================
test('VB-005: デュース未決着（24-24）は winner_side=null（upsert で勝者再評価）', async () => {
  expect(matchId, 'VB-001 で試合作成済み').toBeTruthy()
  // 別試合を汚さないため、本検証用の試合を新規作成する。
  const create = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: { sport: 'VOLLEYBALL', kind: 'FRIENDLY', homeAway: 'HOME', opponentName: 'E2Eデュース検証', periodFormat: 'BEST_OF_5' },
  })
  expect(create.status(), 'デュース検証用試合作成は 201').toBe(201)
  const deuceMatchId = (await create.json() as { data: { id: string } }).data.id

  try {
    // 24-24 は目標点未到達 or 2 点差未成立 → 未決着（winner=null）
    const res1 = await api.put(`${BE_API}/organizations/${orgId}/matches/${deuceMatchId}/sets`, {
      headers: authHeaders(adminToken),
      data: { setNumber: 1, homePoints: 24, awayPoints: 24 },
    })
    expect(res1.status(), 'セット記録は 200').toBe(200)
    const j1 = await res1.json() as { data: { winnerSide: string | null } }
    expect(j1.data.winnerSide, '24-24 は未決着 → 勝者 null（症状を隠さない）').toBeNull()

    // 同一セットを 26-24 に更新（upsert）→ 勝者がホームに確定する
    const res2 = await api.put(`${BE_API}/organizations/${orgId}/matches/${deuceMatchId}/sets`, {
      headers: authHeaders(adminToken),
      data: { setNumber: 1, homePoints: 26, awayPoints: 24 },
    })
    expect(res2.status(), 'セット upsert は 200').toBe(200)
    const j2 = await res2.json() as { data: { winnerSide: string | null } }
    expect(j2.data.winnerSide, '26-24 へ更新 → 勝者 HOME に再評価される（upsert）').toBe('HOME')
  } finally {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${deuceMatchId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
})
