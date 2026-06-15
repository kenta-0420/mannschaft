/**
 * F08.10 多競技ライブ記録 実機 E2E ― ターン制（将棋・個人戦）。
 *
 * モックなし・実バックエンド（http://localhost:8080）接続。BE は再起動せず稼働中の最新 main をそのまま使う。
 *
 * 【検証対象】sports/05_shogi.md のターン制フローを実運用 API で一気通貫実証する:
 *   試合作成（sport=SHOGI）→ 対局結果記録（PUT /result：勝者side・win_method=RESIGNATION・total_moves）
 *   → COMPLETED 遷移（勝敗確定・MATCH_027 が出ないこと）
 *   → 引分ケース（winnerSide=null・win_method=null→0-0→COMPLETED）
 *   → 不正入力のエラーケース（MATCH_028: 勝者ありで win_method なし / 引分なのに win_method あり）。
 *
 * 【前提データ】backend/scripts/seed-f0810-turn-team-e2e.js を実行済みであること。
 *   - 固有名前空間: org slug=f0810-multisport-club / team slug=f0810-shogi-team。
 *   - e2e-admin が当該チームの ADMIN（記録は assertCanRecordTimeline 委譲・ADMIN/DEPUTY 必須）。
 *   - 数値 ID は GET /me/teams から slug で動的解決（環境非依存）。
 *
 * 【構成】API 完結（APIRequestContext のみ）。FE dev サーバーに依存しない。
 *
 * 設計: docs/features/F08.10_match_record_analytics/sports/05_shogi.md §4 / 01 §B.1.2 / §D.7
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

const TEAM_SLUG = 'f0810-shogi-team'

let api: APIRequestContext
let adminToken: string
let orgId: number
let teamId: number
let matchId: string | null = null
let drawMatchId: string | null = null

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
    `seed-f0810-turn-team-e2e.js のチーム(${slug})が /me/teams に存在する（seed 未実行なら null）`,
  ).toBeTruthy()
  expect(team!.role, 'e2e-admin は当該チームの ADMIN（記録権限の前提）').toBe('ADMIN')
  return { teamId: team!.id, orgId: team!.organizationId }
}

test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await login(api)
  const resolved = await resolveTeam(adminToken, TEAM_SLUG)
  teamId = resolved.teamId
  orgId = resolved.orgId
})

test.afterAll(async () => {
  // 後始末: 試作試合を論理削除して環境を汚さない。
  for (const id of [matchId, drawMatchId]) {
    if (adminToken && id) {
      await api.delete(
        `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${id}`,
        { headers: authHeaders(adminToken) },
      ).catch(() => {})
    }
  }
  await api.dispose()
})

// ===========================================================================
// SH-000: 認証 + チーム解決
// ===========================================================================
test('SH-000: ADMIN ログイン + 将棋チームの org/team ID を slug 解決', async () => {
  expect(adminToken.length, 'トークンは 50 文字以上').toBeGreaterThanOrEqual(50)
  expect(teamId, 'teamId が解決される').toBeGreaterThan(0)
  expect(orgId, 'orgId が解決される').toBeGreaterThan(0)
})

// ===========================================================================
// SH-001: 将棋試合を作成（sport=SHOGI・ターン制・durationMinutes 不要）
// ===========================================================================
test('SH-001: 将棋試合を作成 → 201 + sport=SHOGI・stateModel=TURN_BASED・status=SCHEDULED', async () => {
  const res = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: {
      sport: 'SHOGI',
      kind: 'FRIENDLY',
      homeAway: 'HOME',
      opponentName: 'E2E Shogi Opponent',
      // ターン制は durationMinutes 不要（COMPLETED に duration 必須なし・MATCH_023 が出ないこと）
    },
  })
  expect(res.status(), `将棋試合作成は 201。応答: ${await res.text()}`).toBe(201)
  const json = await res.json() as { data: { id: string; sport: string; status: string } }
  expect(json.data.sport, 'sport=SHOGI が永続化される').toBe('SHOGI')
  expect(json.data.status, '初期 status は SCHEDULED').toBe('SCHEDULED')
  matchId = json.data.id
  expect(matchId, '試合 UUID が返る').toBeTruthy()
})

// ===========================================================================
// SH-002: 対局結果記録（HOME 投了勝ち・総手数 = 78）
// ===========================================================================
test('SH-002: 対局結果記録（HOME RESIGNATION 勝ち・totalMoves=78）→ 200 + 1-0 確定', async () => {
  expect(matchId, 'SH-001 で試合作成済み').toBeTruthy()
  const res = await api.put(`${BE_API}/organizations/${orgId}/matches/${matchId}/result`, {
    headers: authHeaders(adminToken),
    data: {
      winnerSide: 'HOME',
      winMethod: 'RESIGNATION', // ShogiWinMethod.RESIGNATION（投了）
      totalMoves: 78,
    },
  })
  expect(res.status(), `対局結果記録は 200。応答: ${await res.text()}`).toBe(200)
  const json = await res.json() as { data: { homeScore: number; awayScore: number; winMethod: string; totalMoves: number } }
  expect(json.data.homeScore, 'HOME 勝ち → homeScore=1').toBe(1)
  expect(json.data.awayScore, 'HOME 勝ち → awayScore=0').toBe(0)
  expect(json.data.winMethod, 'winMethod=RESIGNATION が永続化される').toBe('RESIGNATION')
  expect(json.data.totalMoves, 'totalMoves=78 が永続化される').toBe(78)
})

// ===========================================================================
// SH-003: COMPLETED 遷移（ターン制は duration 不要・勝敗確定で成立）
// ===========================================================================
test('SH-003: COMPLETED 遷移 → 200 + status=COMPLETED（MATCH_023/027 が出ないこと）', async () => {
  expect(matchId, 'SH-001 で試合作成済み').toBeTruthy()
  const res = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}/status`, {
    headers: authHeaders(adminToken),
    data: { status: 'COMPLETED' },
  })
  expect(
    res.status(),
    `COMPLETED 遷移は 200（duration 不要・勝敗確定で終了可。MATCH_023/027 が出ないこと）。応答: ${await res.text()}`,
  ).toBe(200)
  const json = await res.json() as { data: { status: string; homeScore: number; awayScore: number; winMethod: string } }
  expect(json.data.status, 'status=COMPLETED').toBe('COMPLETED')
  expect(json.data.homeScore, 'HOME 勝ちが確定（homeScore=1）').toBe(1)
  expect(json.data.awayScore, 'AWAY 敗北が確定（awayScore=0）').toBe(0)
  expect(json.data.winMethod, 'winMethod=RESIGNATION が維持される').toBe('RESIGNATION')
})

// ===========================================================================
// SH-004: 将棋詰み勝ち（CHECKMATE）で別試合を完走確認
// ===========================================================================
test('SH-004: CHECKMATE（詰み）・AWAY 勝ち → 200 + 0-1 確定', async () => {
  // 別試合を新規作成
  const create = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: { sport: 'SHOGI', kind: 'FRIENDLY', homeAway: 'HOME', opponentName: 'E2E Shogi Check' },
  })
  expect(create.status(), '詰み検証用試合作成は 201').toBe(201)
  const checkMatchId = (await create.json() as { data: { id: string } }).data.id

  try {
    // AWAY 詰み勝ち
    const result = await api.put(`${BE_API}/organizations/${orgId}/matches/${checkMatchId}/result`, {
      headers: authHeaders(adminToken),
      data: { winnerSide: 'AWAY', winMethod: 'CHECKMATE', totalMoves: 102 },
    })
    expect(result.status(), 'AWAY 詰み勝ちは 200').toBe(200)
    const rJson = await result.json() as { data: { homeScore: number; awayScore: number; winMethod: string } }
    expect(rJson.data.homeScore, 'AWAY 勝ち → homeScore=0').toBe(0)
    expect(rJson.data.awayScore, 'AWAY 勝ち → awayScore=1').toBe(1)
    expect(rJson.data.winMethod, 'winMethod=CHECKMATE').toBe('CHECKMATE')

    // COMPLETED 遷移
    const status = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${checkMatchId}/status`, {
      headers: authHeaders(adminToken),
      data: { status: 'COMPLETED' },
    })
    expect(status.status(), 'CHECKMATE COMPLETED 遷移は 200').toBe(200)
  } finally {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${checkMatchId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
})

// ===========================================================================
// SH-005: 引分（千日手=winnerSide null・win_method null・0-0）→ COMPLETED
// ===========================================================================
test('SH-005: 引分（千日手・winnerSide=null・winMethod=null）→ 200 + 0-0 確定 → COMPLETED', async () => {
  // 引分用試合を新規作成
  const create = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: { sport: 'SHOGI', kind: 'FRIENDLY', homeAway: 'HOME', opponentName: 'E2E Draw Match' },
  })
  expect(create.status(), '引分用試合作成は 201').toBe(201)
  drawMatchId = (await create.json() as { data: { id: string } }).data.id

  // 引分記録（winnerSide=null）
  const result = await api.put(`${BE_API}/organizations/${orgId}/matches/${drawMatchId}/result`, {
    headers: authHeaders(adminToken),
    data: {
      winnerSide: null,  // 千日手 = 引分
      winMethod: null,   // 引分では win_method は null であること（責務分離・§4.2）
    },
  })
  expect(result.status(), '引分記録は 200。応答: ' + await result.text()).toBe(200)
  const rJson = await result.json() as { data: { homeScore: number; awayScore: number; winMethod: string | null } }
  expect(rJson.data.homeScore, '引分 → homeScore=0').toBe(0)
  expect(rJson.data.awayScore, '引分 → awayScore=0').toBe(0)
  expect(rJson.data.winMethod, '引分 → winMethod=null').toBeNull()

  // 引分状態で COMPLETED 遷移（0-0 は有効な勝敗確定・MATCH_027 が出ないこと）
  const status = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${drawMatchId}/status`, {
    headers: authHeaders(adminToken),
    data: { status: 'COMPLETED' },
  })
  expect(status.status(), '引分 COMPLETED 遷移は 200（MATCH_027 が出ないこと）').toBe(200)
  const sJson = await status.json() as { data: { status: string; homeScore: number; awayScore: number } }
  expect(sJson.data.status, 'status=COMPLETED').toBe('COMPLETED')
  expect(sJson.data.homeScore, '引分スコア homeScore=0').toBe(0)
  expect(sJson.data.awayScore, '引分スコア awayScore=0').toBe(0)
})

// ===========================================================================
// SH-006: バリデーションエラー（MATCH_028）― 勝者ありで win_method なし
// ===========================================================================
test('SH-006: 勝者ありで winMethod=null → 400 + MATCH_028（症状を隠さない）', async () => {
  expect(matchId, 'SH-001 で試合作成済み（COMPLETED 済みでも記録 API は別途検証可）').toBeTruthy()

  // 別試合で検証（COMPLETED 後に result を上書きしてもバリデーションが同じ挙動を返す）
  const create = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: { sport: 'SHOGI', kind: 'FRIENDLY', homeAway: 'HOME', opponentName: 'E2E Validation' },
  })
  const valMatchId = (await create.json() as { data: { id: string } }).data.id

  try {
    const res = await api.put(`${BE_API}/organizations/${orgId}/matches/${valMatchId}/result`, {
      headers: authHeaders(adminToken),
      data: {
        winnerSide: 'HOME',
        winMethod: null, // 勝者ありなのに win_method=null → MATCH_028
      },
    })
    expect(
      res.status(),
      `勝者ありで winMethod=null は 400（MATCH_028）。応答: ${await res.text()}`,
    ).toBe(400)
    const json = await res.json() as { error: { code: string } }
    expect(json.error.code, 'MATCH_028 が返る').toBe('MATCH_028')
  } finally {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${valMatchId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
})

// ===========================================================================
// SH-007: バリデーションエラー（MATCH_028）― 引分なのに win_method あり
// ===========================================================================
test('SH-007: 引分（winnerSide=null）で winMethod=RESIGNATION → 400 + MATCH_028', async () => {
  const create = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: { sport: 'SHOGI', kind: 'FRIENDLY', homeAway: 'HOME', opponentName: 'E2E Val Draw' },
  })
  const valMatchId = (await create.json() as { data: { id: string } }).data.id

  try {
    const res = await api.put(`${BE_API}/organizations/${orgId}/matches/${valMatchId}/result`, {
      headers: authHeaders(adminToken),
      data: {
        winnerSide: null,      // 引分
        winMethod: 'RESIGNATION', // 引分なのに win_method あり → MATCH_028
      },
    })
    expect(
      res.status(),
      `引分で winMethod ありは 400（MATCH_028）。応答: ${await res.text()}`,
    ).toBe(400)
    const json = await res.json() as { error: { code: string } }
    expect(json.error.code, 'MATCH_028 が返る').toBe('MATCH_028')
  } finally {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${valMatchId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
})

// ===========================================================================
// SH-008: COMPLETED 未確定チェック（MATCH_027）― スコアが未設定のまま完了試みる
// ===========================================================================
test('SH-008: 勝敗未確定（home/away_score=null）のまま COMPLETED → 400 + MATCH_027', async () => {
  const create = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: { sport: 'SHOGI', kind: 'FRIENDLY', homeAway: 'HOME', opponentName: 'E2E No Result' },
  })
  const unfinMatchId = (await create.json() as { data: { id: string } }).data.id

  try {
    // result を記録せずにそのまま COMPLETED を試みる
    const res = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${unfinMatchId}/status`, {
      headers: authHeaders(adminToken),
      data: { status: 'COMPLETED' },
    })
    expect(
      res.status(),
      `勝敗未確定で COMPLETED は 400（MATCH_027）。応答: ${await res.text()}`,
    ).toBe(400)
    const json = await res.json() as { error: { code: string } }
    expect(json.error.code, 'MATCH_027 が返る').toBe('MATCH_027')
  } finally {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${unfinMatchId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
})
