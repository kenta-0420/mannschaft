/**
 * F08.10 多競技ライブ記録 実機 E2E ― 団体戦（親子ボード・勝ち星集計）。
 *
 * モックなし・実バックエンド（http://localhost:8080）接続。
 *
 * 【検証対象】01 §B.6 / sports/05_shogi.md §4.3 の団体戦フローを実 API で一気通貫実証する:
 *   親 match 作成（sport=SHOGI）→ 子ボード作成 POST /boards（1〜5 局）→ 各ボードに対局結果記録
 *   → 親の勝ち星が子ボードから自動集計される（整数スケール: 勝ち=2・引分=各1）
 *   → 親 COMPLETED 遷移（win_method=NULL が正常＝§4.3 の win_method 免除が機能すること）
 *   → 引分（勝ち星同数）でも親 COMPLETED 可
 *   → IDOR: 他テナント親の子ボードを操作しようとしたら 404
 *   → 重複 board_number → 400 + MATCH_024。
 *
 * 【勝ち星集計の整数スケール（§B.6）】
 *   - 子ボード勝ち（1-0）= 親 homeStars += 2
 *   - 子ボード負け（0-1）= 親 awayStars += 2
 *   - 子ボード引分（0-0）= 親 homeStars += 1 / awayStars += 1
 *   - 5 局の結果例: HOME 3 勝・DRAW 1・AWAY 1 → homeStars=7・awayStars=3（HOME 勝越）
 *
 * 【前提データ】backend/scripts/seed-f0810-turn-team-e2e.js を実行済みであること。
 *   - 固有名前空間: org slug=f0810-multisport-club / team slug=f0810-shogi-team。
 *   - e2e-admin が当該チームの ADMIN。
 *
 * 【構成】API 完結（APIRequestContext のみ）。
 *
 * 設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.6 / sports/05_shogi.md §4.3
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
let parentMatchId: string | null = null
let boardIds: string[] = []

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
  expect(team, `seed チーム(${slug})が /me/teams に存在する`).toBeTruthy()
  expect(team!.role, 'e2e-admin は ADMIN').toBe('ADMIN')
  return { teamId: team!.id, orgId: team!.organizationId }
}

/** 子ボードに対局結果を記録する（winnerSide=null で引分）。 */
async function recordBoardResult(
  boardMatchId: string,
  winnerSide: 'HOME' | 'AWAY' | null,
  winMethod: string | null,
): Promise<void> {
  const res = await api.put(`${BE_API}/organizations/${orgId}/matches/${boardMatchId}/result`, {
    headers: authHeaders(adminToken),
    data: { winnerSide, winMethod },
  })
  expect(
    res.status(),
    `ボード ${boardMatchId} 結果記録は 200（winner=${String(winnerSide)}）。応答: ${await res.text()}`,
  ).toBe(200)
}

test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await login(api)
  const resolved = await resolveTeam(adminToken, TEAM_SLUG)
  teamId = resolved.teamId
  orgId = resolved.orgId
})

test.afterAll(async () => {
  // 後始末: 子ボードを削除してから親を削除する（FK 逆順）。
  for (const bid of boardIds) {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${bid}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
  if (parentMatchId) {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${parentMatchId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
  await api.dispose()
})

// ===========================================================================
// TM-000: 認証 + チーム解決
// ===========================================================================
test('TM-000: ADMIN ログイン + 将棋チームの org/team ID を slug 解決', async () => {
  expect(adminToken.length, 'トークンは 50 文字以上').toBeGreaterThanOrEqual(50)
  expect(teamId, 'teamId が解決される').toBeGreaterThan(0)
  expect(orgId, 'orgId が解決される').toBeGreaterThan(0)
})

// ===========================================================================
// TM-001: 団体戦の親 match を作成（sport=SHOGI・durationMinutes 不要）
// ===========================================================================
test('TM-001: 団体戦の親 match 作成 → 201 + sport=SHOGI・status=SCHEDULED', async () => {
  const res = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: {
      sport: 'SHOGI',
      kind: 'FRIENDLY',
      homeAway: 'HOME',
      opponentName: 'E2E Team Shogi',
    },
  })
  expect(res.status(), `団体戦親作成は 201。応答: ${await res.text()}`).toBe(201)
  const json = await res.json() as { data: { id: string; sport: string; status: string; parentMatchId: string | null } }
  expect(json.data.sport, 'sport=SHOGI').toBe('SHOGI')
  expect(json.data.status, 'status=SCHEDULED').toBe('SCHEDULED')
  expect(json.data.parentMatchId, '親 match は parent_match_id=NULL（団体戦の親）').toBeNull()
  parentMatchId = json.data.id
  expect(parentMatchId, '親 match UUID が返る').toBeTruthy()
})

// ===========================================================================
// TM-002: 子ボードを 5 局作成（board_number=1〜5）
// ===========================================================================
test('TM-002: 子ボード 5 局作成（board_number=1〜5）→ 各 201 + parentMatchId が親 ID', async () => {
  expect(parentMatchId, 'TM-001 で親作成済み').toBeTruthy()
  boardIds = []

  for (let i = 1; i <= 5; i++) {
    const res = await api.post(`${BE_API}/organizations/${orgId}/matches/${parentMatchId}/boards`, {
      headers: authHeaders(adminToken),
      data: { boardNumber: i, opponentName: `E2E Board ${i}` },
    })
    expect(res.status(), `ボード${i}作成は 201。応答: ${await res.text()}`).toBe(201)
    const json = await res.json() as { data: { id: string; parentMatchId: string; boardNumber: number; sport: string } }
    expect(json.data.parentMatchId, `ボード${i}の parentMatchId が親 ID と一致`).toBe(parentMatchId)
    expect(json.data.boardNumber, `boardNumber=${i}`).toBe(i)
    expect(json.data.sport, `sport は親から継承（SHOGI）`).toBe('SHOGI')
    boardIds.push(json.data.id)
  }
  expect(boardIds.length, '5 局のボード ID が揃う').toBe(5)
})

// ===========================================================================
// TM-003: 子ボード一覧（GET /boards）→ 5 局が board_number 昇順で返る
// ===========================================================================
test('TM-003: 子ボード一覧（GET /boards）→ 5 局・board_number 昇順', async () => {
  expect(parentMatchId, 'TM-001 で親作成済み').toBeTruthy()

  const res = await api.get(`${BE_API}/organizations/${orgId}/matches/${parentMatchId}/boards`, {
    headers: authHeaders(adminToken),
  })
  expect(res.status(), 'boards 一覧は 200').toBe(200)
  const json = await res.json() as { data: Array<{ boardNumber: number; parentMatchId: string }> }
  expect(json.data.length, '5 局が返る').toBe(5)
  // board_number 昇順であること
  const nums = json.data.map((b) => b.boardNumber)
  expect(nums, 'board_number 昇順（[1,2,3,4,5]）').toEqual([1, 2, 3, 4, 5])
  // 各ボードの parentMatchId が親 ID と一致
  for (const b of json.data) {
    expect(b.parentMatchId, '各ボードの parentMatchId が親 ID と一致').toBe(parentMatchId)
  }
})

// ===========================================================================
// TM-004: 各ボードに対局結果を記録（HOME 3 勝・DRAW 1・AWAY 1）
// ===========================================================================
test('TM-004: 各ボードに対局結果記録（HOME 3 勝・引分 1・AWAY 1）→ 200 × 5', async () => {
  expect(boardIds.length, 'TM-002 でボード作成済み').toBe(5)

  // ボード1: HOME RESIGNATION 勝ち
  await recordBoardResult(boardIds[0], 'HOME', 'RESIGNATION')
  // ボード2: HOME CHECKMATE 勝ち
  await recordBoardResult(boardIds[1], 'HOME', 'CHECKMATE')
  // ボード3: 引分（千日手）
  await recordBoardResult(boardIds[2], null, null)
  // ボード4: HOME DEFAULT_WIN（不戦勝）
  await recordBoardResult(boardIds[3], 'HOME', 'DEFAULT_WIN')
  // ボード5: AWAY RESIGNATION 勝ち
  await recordBoardResult(boardIds[4], 'AWAY', 'RESIGNATION')
})

// ===========================================================================
// TM-005: 親の勝ち星が子ボードから自動集計される（整数スケール）
// ===========================================================================
test('TM-005: 親の勝ち星が自動集計される（home=7・away=3・整数スケール 勝ち=2・引分=各1）', async () => {
  expect(parentMatchId, 'TM-001 で親作成済み').toBeTruthy()

  const res = await api.get(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${parentMatchId}`, {
    headers: authHeaders(adminToken),
  })
  expect(res.status(), '親試合取得は 200').toBe(200)
  const json = await res.json() as { data: { homeScore: number; awayScore: number; winMethod: string | null } }
  // HOME 3 勝 × 2 = 6、DRAW 1 × 1 = 1 → homeStars = 7
  // AWAY 1 勝 × 2 = 2、DRAW 1 × 1 = 1 → awayStars = 3
  expect(
    json.data.homeScore,
    '親 homeScore=7（HOME 3 勝×2 + DRAW 1×1）= 整数スケール勝ち星集計',
  ).toBe(7)
  expect(
    json.data.awayScore,
    '親 awayScore=3（AWAY 1 勝×2 + DRAW 1×1）= 整数スケール勝ち星集計',
  ).toBe(3)
  expect(
    json.data.winMethod,
    '団体戦の親は winMethod=NULL が正常（個別ボードの勝ち方の集合体・§4.3）',
  ).toBeNull()
})

// ===========================================================================
// TM-006: 親 COMPLETED 遷移（win_method=NULL が正常・MATCH_028 が出ないこと）
// ===========================================================================
test('TM-006: 親 COMPLETED 遷移 → 200 + status=COMPLETED（win_method=NULL で MATCH_028 が出ないこと）', async () => {
  expect(parentMatchId, 'TM-001 で親作成済み').toBeTruthy()

  const res = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${parentMatchId}/status`, {
    headers: authHeaders(adminToken),
    data: { status: 'COMPLETED' },
  })
  expect(
    res.status(),
    `親 COMPLETED 遷移は 200（win_method=NULL 免除・MATCH_028 なし）。応答: ${await res.text()}`,
  ).toBe(200)
  const json = await res.json() as { data: { status: string; homeScore: number; awayScore: number; winMethod: string | null } }
  expect(json.data.status, 'status=COMPLETED').toBe('COMPLETED')
  expect(json.data.homeScore, 'homeScore=7（勝ち星）').toBe(7)
  expect(json.data.awayScore, 'awayScore=3（勝ち星）').toBe(3)
  expect(json.data.winMethod, '親 winMethod=NULL（免除・§4.3）').toBeNull()
})

// ===========================================================================
// TM-007: 引分（勝ち星同数）でも親 COMPLETED 可
// ===========================================================================
test('TM-007: 勝ち星同数（引分）でも親 COMPLETED 可（DRAW は valid・MATCH_026 が出ないこと）', async () => {
  // 別の団体戦（引分=同数）を新規作成
  const create = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: { sport: 'SHOGI', kind: 'FRIENDLY', homeAway: 'HOME', opponentName: 'E2E Draw Team' },
  })
  expect(create.status(), '引分団体戦親作成は 201').toBe(201)
  const drawParentId = (await create.json() as { data: { id: string } }).data.id

  const drawBoardIds: string[] = []
  try {
    // ボード2局作成: 各引分（千日手）
    for (let i = 1; i <= 2; i++) {
      const b = await api.post(`${BE_API}/organizations/${orgId}/matches/${drawParentId}/boards`, {
        headers: authHeaders(adminToken),
        data: { boardNumber: i, opponentName: `E2E Draw Board ${i}` },
      })
      expect(b.status(), `ボード${i}作成は 201`).toBe(201)
      const bId = (await b.json() as { data: { id: string } }).data.id
      drawBoardIds.push(bId)
    }

    // 両ボード引分
    await recordBoardResult(drawBoardIds[0], null, null)
    await recordBoardResult(drawBoardIds[1], null, null)

    // 親スコアを確認（homeStars=2・awayStars=2 = 同数引分）
    const parentRes = await api.get(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${drawParentId}`, {
      headers: authHeaders(adminToken),
    })
    const pJson = await parentRes.json() as { data: { homeScore: number; awayScore: number } }
    expect(pJson.data.homeScore, '引分団体戦 homeStars=2（引分2×1）').toBe(2)
    expect(pJson.data.awayScore, '引分団体戦 awayStars=2（引分2×1）').toBe(2)

    // COMPLETED 遷移（同数 DRAW は valid）
    const status = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${drawParentId}/status`, {
      headers: authHeaders(adminToken),
      data: { status: 'COMPLETED' },
    })
    expect(
      status.status(),
      `勝ち星同数 DRAW でも COMPLETED は 200（MATCH_027 なし）。応答: ${await status.text()}`,
    ).toBe(200)
  } finally {
    for (const bid of drawBoardIds) {
      await api.delete(
        `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${bid}`,
        { headers: authHeaders(adminToken) },
      ).catch(() => {})
    }
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${drawParentId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
})

// ===========================================================================
// TM-008: ボード番号重複 → 400 + MATCH_024
// ===========================================================================
test('TM-008: 重複 board_number → 400 + MATCH_024（一意制約）', async () => {
  expect(parentMatchId, '既存の親 match が利用可能').toBeTruthy()

  // COMPLETED 済みの親 match に追加でボードを作ろうとするが、board_number=1 は既存ボードが持つ
  // → ただし COMPLETED 後の meta 操作は 403 になる可能性があるため、別の親を用意する
  const create = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: { sport: 'SHOGI', kind: 'FRIENDLY', homeAway: 'HOME', opponentName: 'E2E Dup Board' },
  })
  const dupParentId = (await create.json() as { data: { id: string } }).data.id

  try {
    // board_number=1 を作成
    const b1 = await api.post(`${BE_API}/organizations/${orgId}/matches/${dupParentId}/boards`, {
      headers: authHeaders(adminToken),
      data: { boardNumber: 1, opponentName: 'E2E Dup B1 first' },
    })
    expect(b1.status(), 'board_number=1 初回作成は 201').toBe(201)
    const b1Id = (await b1.json() as { data: { id: string } }).data.id

    // 同じ board_number=1 をもう一度作成 → 重複エラー
    const b1dup = await api.post(`${BE_API}/organizations/${orgId}/matches/${dupParentId}/boards`, {
      headers: authHeaders(adminToken),
      data: { boardNumber: 1, opponentName: 'E2E Dup B1 second' },
    })
    expect(
      b1dup.status(),
      `重複 board_number=1 は 400（MATCH_024）。応答: ${await b1dup.text()}`,
    ).toBe(400)
    const json = await b1dup.json() as { error: { code: string } }
    expect(json.error.code, 'MATCH_024 が返る').toBe('MATCH_024')

    // クリーンアップ
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${b1Id}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  } finally {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${dupParentId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
})

// ===========================================================================
// TM-009: IDOR ― 子ボードを他テナント親の ID で取得しようとしたら 404
// ===========================================================================
test('TM-009: IDOR チェック ― 存在しない親 ID でボード一覧を取得 → 404（存在を漏らさない）', async () => {
  const fakeParentId = '00000000-0000-0000-0000-000000000001'
  const res = await api.get(
    `${BE_API}/organizations/${orgId}/matches/${fakeParentId}/boards`,
    { headers: authHeaders(adminToken) },
  )
  expect(
    res.status(),
    `存在しない parentMatchId でボード一覧は 404（IDOR・存在を漏らさない）。応答: ${await res.text()}`,
  ).toBe(404)
  const json = await res.json() as { error: { code: string } }
  expect(json.error.code, 'MATCH_001 が返る').toBe('MATCH_001')
})

// ===========================================================================
// TM-010: ターン制でない試合への /boards 操作は 400 + MATCH_029
// ===========================================================================
test('TM-010: サッカー試合への /boards POST → 400 + MATCH_029（ターン制以外は不可）', async () => {
  // サッカー試合を作成
  const create = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: {
      sport: 'SOCCER',
      kind: 'FRIENDLY',
      homeAway: 'HOME',
      opponentName: 'E2E Soccer for MATCH_029',
      durationMinutes: 90,
    },
  })
  expect(create.status(), 'サッカー試合作成は 201').toBe(201)
  const soccerMatchId = (await create.json() as { data: { id: string } }).data.id

  try {
    // サッカー試合に /boards POST → MATCH_029
    const res = await api.post(`${BE_API}/organizations/${orgId}/matches/${soccerMatchId}/boards`, {
      headers: authHeaders(adminToken),
      data: { boardNumber: 1, opponentName: 'E2E Soccer Board' },
    })
    expect(
      res.status(),
      `サッカー試合への /boards は 400（MATCH_029）。応答: ${await res.text()}`,
    ).toBe(400)
    const json = await res.json() as { error: { code: string } }
    expect(json.error.code, 'MATCH_029 が返る').toBe('MATCH_029')
  } finally {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${soccerMatchId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
})
