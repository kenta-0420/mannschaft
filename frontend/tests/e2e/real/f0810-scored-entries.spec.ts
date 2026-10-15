/**
 * F08.10 採点競技 実機 E2E ― 多人数順位制（match_score_entries）。
 *
 * モックなし・実バックエンド（http://localhost:8080）接続。BE は再起動せず稼働中の最新 main をそのまま使う。
 *
 * 【検証対象】sports/07_scored.md §5B / §6 / §9 / §11 / 01 §B.1.2 の多人数順位制フローを実 API で一気通貫実証する:
 *   試合作成（sport=FIGURE_SKATING / GYMNASTICS）
 *   → PUT /organizations/{orgId}/matches/{matchId}/score-entries（全置換・出場者エントリ）
 *   → サーバーが合計点降順で順位算出（標準順位法 1,2,2,4）
 *   → GET /score-entries で順位確認
 *   → 二層正本: home_score に最上位合計点が再導出されること
 *   → COMPLETED 遷移
 *
 * 【実証ポイント】
 *   SE-001: N 人スコア→順位算出（4 人 100/90/90/80 → 1,2,2,4）標準順位法
 *   SE-002: 3 人同点（100/100/100/90 → 1,1,1,4）
 *   SE-003: 全置換冪等（2 回 PUT → 2 回目の件数・スコアに上書き）
 *   SE-004: 二層正本（score-entries PUT 後 home_score = 最上位合計点 / away_score = 0）
 *   SE-005: 既存共存非破壊（PUT /scored-result・PUT /scored-components が引き続き動作）
 *   SE-006: rank 非送信（クライアントが rank を送らなくてもサーバーが算出・DTOに rank 未定義）
 *   SE-007: 採点競技以外（BASKETBALL）への PUT /score-entries → 400 MATCH_029
 *   SE-008: エントリ COMPLETED 完遂（entries 記録後 COMPLETED 遷移 → 200）
 *
 * 【前提データ】backend/scripts/seed-f0810-multisport-e2e.js を実行済みであること。
 *   - 固有名前空間: org slug=f0810-multisport-club / team slug=f0810-scored-team。
 *   - e2e-admin が当該チームの ADMIN（採点記録は assertCanEditMeta 委譲・ADMIN/DEPUTY 必須）。
 *   - org/team の数値 ID は環境依存のため固定値を持たず、GET /me/teams から slug で動的解決する。
 *
 * 【構成】API 完結（APIRequestContext のみ）。FE dev サーバー（BASE_URL）に依存しない。
 *
 * 設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §5B / §6 / §9 / §11 / 01 §B.1.2 / §D.8
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
let se001MatchId: string | null = null
let se002MatchId: string | null = null
let se003MatchId: string | null = null
let se004MatchId: string | null = null
let se005MatchId: string | null = null
let se006MatchId: string | null = null
let se007MatchId: string | null = null
let se008MatchId: string | null = null

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

/** 試合を作成して matchId を返す。 */
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

/** PUT /score-entries でエントリを全置換投入し、応答（エントリ一覧）を返す。 */
async function putScoreEntries(
  matchId: string,
  entries: Array<{
    competitorName?: string
    competitorUserId?: number
    competitorTeamId?: number
    totalScaled: number
  }>,
  expectedStatus: number = 200,
): Promise<{
  status: number
  body: string
  data?: Array<{
    id: string
    competitorName: string | null
    competitorUserId: number | null
    totalScaled: number
    rankPosition: number
  }>
}> {
  const res = await api.put(`${BE_API}/organizations/${orgId}/matches/${matchId}/score-entries`, {
    headers: authHeaders(adminToken),
    data: { entries },
  })
  const body = await res.text()
  const httpStatus = res.status()
  if (httpStatus === expectedStatus && expectedStatus === 200) {
    const json = JSON.parse(body) as {
      data: Array<{
        id: string
        competitorName: string | null
        competitorUserId: number | null
        totalScaled: number
        rankPosition: number
      }>
    }
    return { status: httpStatus, body, data: json.data }
  }
  return { status: httpStatus, body }
}

/** GET /score-entries でエントリ一覧を取得する。 */
async function getScoreEntries(matchId: string): Promise<Array<{
  id: string
  competitorName: string | null
  totalScaled: number
  rankPosition: number
}>> {
  const res = await api.get(`${BE_API}/organizations/${orgId}/matches/${matchId}/score-entries`, {
    headers: authHeaders(adminToken),
  })
  expect(res.status(), `GET /score-entries は 200`).toBe(200)
  const json = await res.json() as {
    data: Array<{ id: string; competitorName: string | null; totalScaled: number; rankPosition: number }>
  }
  return json.data
}

/** 試合の現在の状態を取得する。 */
async function getMatch(matchId: string): Promise<{
  status: string
  homeScore: number | null
  awayScore: number | null
  winMethod: string | null
}> {
  const res = await api.get(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}`, {
    headers: authHeaders(adminToken),
  })
  expect(res.status(), '試合取得は 200').toBe(200)
  return (await res.json() as {
    data: { status: string; homeScore: number | null; awayScore: number | null; winMethod: string | null }
  }).data
}

/** status を変更する。 */
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

/** クリーンアップ（失敗しても握りつぶす）。 */
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
// SE-000: 認証 + チーム解決
// ===========================================================================
test('SE-000: ADMIN ログイン + 採点競技チームの org/team ID を slug 解決', async () => {
  expect(adminToken.length, 'トークンは 50 文字以上').toBeGreaterThanOrEqual(50)
  expect(teamId, 'teamId が解決される').toBeGreaterThan(0)
  expect(orgId, 'orgId が解決される').toBeGreaterThan(0)
})

// ===========================================================================
// SE-001: N 人スコア → 順位算出（標準順位法 1,2,2,4）
//   4 人: 100/90/90/80 → rank 1,2,2,4
//   同点は同順位・次順位を飛ばす（§5B.2 / §6）
// ===========================================================================
test('SE-001a: フィギュアスケート試合作成 → 201', async () => {
  se001MatchId = await createMatch('FIGURE_SKATING', 'SE001順位算出テスト相手')
})

test('SE-001b: PUT /score-entries（4 人 100/90/90/80）→ 200 + 標準順位法で rank=1,2,2,4 がサーバー算出される', async () => {
  expect(se001MatchId, 'SE-001a で試合作成済み').toBeTruthy()
  const result = await putScoreEntries(se001MatchId!, [
    { competitorName: 'SE001選手A', totalScaled: 100000 },
    { competitorName: 'SE001選手B', totalScaled: 90000 },
    { competitorName: 'SE001選手C', totalScaled: 90000 },
    { competitorName: 'SE001選手D', totalScaled: 80000 },
  ])
  expect(result.status, `PUT /score-entries は 200。応答: ${result.body}`).toBe(200)
  expect(result.data, 'レスポンスに data が含まれる').toBeTruthy()
  const entries = result.data!
  expect(entries.length, '4 件のエントリが返る').toBe(4)

  // 順位昇順で返却される（§5B.2）
  const ranks = entries.map((e) => e.rankPosition)
  const scores = entries.map((e) => e.totalScaled)
  // 最初のエントリが最高点（順位 1 番）
  expect(ranks[0], '1 位エントリの rank=1').toBe(1)
  expect(scores[0], '1 位エントリの totalScaled=100000').toBe(100000)
  // 2,3 番目は同点 rank=2
  expect(ranks[1], '2 位エントリの rank=2（同点同順位）').toBe(2)
  expect(ranks[2], '3 位エントリの rank=2（同点同順位・次順位を飛ばす）').toBe(2)
  expect(scores[1], '2 位エントリの totalScaled=90000').toBe(90000)
  expect(scores[2], '3 位エントリの totalScaled=90000').toBe(90000)
  // 4 番目は rank=4（2 位が 2 人いるため 3 を飛ばす）
  expect(ranks[3], '4 位エントリの rank=4（2位2人のため3を飛ばす・標準順位法）').toBe(4)
  expect(scores[3], '4 位エントリの totalScaled=80000').toBe(80000)
})

test('SE-001c: GET /score-entries → 順位昇順で 4 件・rank=1,2,2,4 が永続化されている', async () => {
  expect(se001MatchId, 'SE-001a で試合作成済み').toBeTruthy()
  const entries = await getScoreEntries(se001MatchId!)
  expect(entries.length, 'GET で 4 件取得できる').toBe(4)
  const ranks = entries.map((e) => e.rankPosition)
  // GET も順位昇順（§5B.2: 順位表向き）
  expect(ranks[0], 'GET 結果: 1 位').toBe(1)
  expect(ranks[1], 'GET 結果: 2 位（同点）').toBe(2)
  expect(ranks[2], 'GET 結果: 2 位（同点）').toBe(2)
  expect(ranks[3], 'GET 結果: 4 位（標準順位法・3 飛ばし）').toBe(4)
})

// ===========================================================================
// SE-002: 3 人同点（100/100/100/90 → rank=1,1,1,4）
//   3 者が同点 1 位 → 次は rank=4（2,3 を飛ばす）
// ===========================================================================
test('SE-002: 3 人同点 — 100/100/100/90 → rank=1,1,1,4（2,3 を飛ばす標準順位法）', async () => {
  se002MatchId = await createMatch('FIGURE_SKATING', 'SE002同点テスト相手')

  const result = await putScoreEntries(se002MatchId!, [
    { competitorName: 'SE002選手A', totalScaled: 100000 },
    { competitorName: 'SE002選手B', totalScaled: 100000 },
    { competitorName: 'SE002選手C', totalScaled: 100000 },
    { competitorName: 'SE002選手D', totalScaled: 90000 },
  ])
  expect(result.status, `3 人同点 PUT /score-entries は 200。応答: ${result.body}`).toBe(200)
  const entries = result.data!
  expect(entries.length, '4 件のエントリが返る').toBe(4)

  const rankMap = new Map(entries.map((e) => [e.totalScaled, e.rankPosition]))
  // 同点 3 者はいずれも rank=1
  const rankOf100 = rankMap.get(100000)
  expect(rankOf100, '同点 3 者（100000）は rank=1').toBe(1)
  // 4 番目は rank=4（3 者が 1 位なので 2,3 を飛ばして 4）
  const rank1s = entries.filter((e) => e.rankPosition === 1)
  expect(rank1s.length, 'rank=1 が 3 件（3 者同点）').toBe(3)
  const rank4Entry = entries.find((e) => e.totalScaled === 90000)
  expect(rank4Entry!.rankPosition, '90000 点の選手は rank=4（2,3 を飛ばす）').toBe(4)
})

// ===========================================================================
// SE-003: 全置換冪等
//   1 回目: 3 件投入 → 2 回目: 2 件投入（全置換）
//   GET で 2 件・1 回目のデータが消えていることを確認
// ===========================================================================
test('SE-003: 全置換冪等 — 2 回目の PUT で 1 回目のエントリが完全に置き換わること', async () => {
  se003MatchId = await createMatch('FIGURE_SKATING', 'SE003冪等テスト相手')

  // 1 回目: 3 件（score 高い順: 90000/80000/70000）
  const result1 = await putScoreEntries(se003MatchId!, [
    { competitorName: 'SE003選手X', totalScaled: 90000 },
    { competitorName: 'SE003選手Y', totalScaled: 80000 },
    { competitorName: 'SE003選手Z', totalScaled: 70000 },
  ])
  expect(result1.status, `1 回目 PUT は 200。応答: ${result1.body}`).toBe(200)
  expect(result1.data!.length, '1 回目 PUT の応答は 3 件').toBe(3)

  // GET で 3 件確認
  const entries1 = await getScoreEntries(se003MatchId!)
  expect(entries1.length, '1 回目 PUT 後 GET で 3 件').toBe(3)

  // 2 回目: 2 件（全置換・1 回目の 3 件を完全に置き換える）
  const result2 = await putScoreEntries(se003MatchId!, [
    { competitorName: 'SE003新選手P', totalScaled: 95000 },
    { competitorName: 'SE003新選手Q', totalScaled: 85000 },
  ])
  expect(result2.status, `2 回目 PUT（全置換・冪等）は 200。応答: ${result2.body}`).toBe(200)
  const entries2Resp = result2.data!
  expect(entries2Resp.length, '2 回目 PUT の応答は 2 件（3 件ではない）').toBe(2)
  // 2 回目のスコアが正しい（1 回目の値が混入していない）
  expect(entries2Resp[0]!.totalScaled, '2 回目 1 位: 95000（1 回目の 90000 ではない）').toBe(95000)
  expect(entries2Resp[0]!.rankPosition, '2 回目 1 位: rank=1').toBe(1)
  expect(entries2Resp[1]!.totalScaled, '2 回目 2 位: 85000').toBe(85000)
  expect(entries2Resp[1]!.rankPosition, '2 回目 2 位: rank=2').toBe(2)

  // GET で 2 件確認（全置換で 1 回目の 3 件が削除されている）
  const entries2Get = await getScoreEntries(se003MatchId!)
  expect(entries2Get.length, '2 回目 PUT 後 GET で 2 件（全置換で 1 回目の 3 件は消えた）').toBe(2)
  // 1 回目の選手名が残っていないことを確認
  const names = entries2Get.map((e) => e.competitorName)
  expect(names.includes('SE003選手X'), '1 回目の選手X が残っていない（全置換済み）').toBe(false)
  expect(names.includes('SE003新選手P'), '2 回目の新選手P が存在する').toBe(true)
})

// ===========================================================================
// SE-004: 二層正本
//   score-entries PUT 後、matches.home_score に最上位合計点が再導出される（§5B.2）。
//   away_score は多人数順位制では 0 に正規化される。
// ===========================================================================
test('SE-004: 二層正本 — score-entries PUT 後 matches.home_score = 最上位合計点 / away_score = 0', async () => {
  se004MatchId = await createMatch('FIGURE_SKATING', 'SE004二層正本テスト相手')

  // 3 人投入（最上位は 120000）
  const result = await putScoreEntries(se004MatchId!, [
    { competitorName: 'SE004選手A', totalScaled: 120000 },
    { competitorName: 'SE004選手B', totalScaled: 110000 },
    { competitorName: 'SE004選手C', totalScaled: 95000 },
  ])
  expect(result.status, `PUT /score-entries は 200。応答: ${result.body}`).toBe(200)

  // 試合の home_score / away_score を確認
  const match = await getMatch(se004MatchId!)
  expect(
    match.homeScore,
    'home_score に最上位合計点（120000）が補助的に再導出される（二層正本・§5B.2）',
  ).toBe(120000)
  expect(
    match.awayScore,
    'away_score は多人数順位制では 0 に正規化される（§5B.2・2 者列は本役でない）',
  ).toBe(0)
  // 採点競技は win_method を持たない（§10）
  expect(match.winMethod, 'win_method は null（採点競技に勝ち方概念なし）').toBeNull()
})

// ===========================================================================
// SE-005: 既存共存非破壊
//   score-entries 記録後でも PUT /scored-result（2 者合計直接）・PUT /scored-components（審判内訳）が
//   独立して動作すること。
//   → 多人数順位制（score-entries）は追加モードであり既存 API を壊さない（§5B・設計要件）。
// ===========================================================================
test('SE-005a: score-entries 記録後も PUT /scored-result（2 者合計直接）が 200 で動作する', async () => {
  se005MatchId = await createMatch('FIGURE_SKATING', 'SE005共存非破壊テスト相手')

  // 先に score-entries で多人数エントリを記録
  const entResult = await putScoreEntries(se005MatchId!, [
    { competitorName: 'SE005エントリ選手A', totalScaled: 88000 },
    { competitorName: 'SE005エントリ選手B', totalScaled: 77000 },
  ])
  expect(entResult.status, `score-entries PUT は 200`).toBe(200)

  // 既存の 2 者 MVP 合計点直接入力（PUT /scored-result）が独立して動作すること
  const srRes = await api.put(`${BE_API}/organizations/${orgId}/matches/${se005MatchId!}/scored-result`, {
    headers: authHeaders(adminToken),
    data: { homeScoreScaled: 198450, awayScoreScaled: 201320 },
  })
  const srBody = await srRes.text()
  expect(
    srRes.status(),
    `PUT /scored-result は 200（score-entries と共存・非破壊）。応答: ${srBody}`,
  ).toBe(200)
  const srData = JSON.parse(srBody) as { data: { homeScore: number; awayScore: number } }
  expect(srData.data.homeScore, 'homeScore=198450 が格納される').toBe(198450)
  expect(srData.data.awayScore, 'awayScore=201320 が格納される').toBe(201320)
})

test('SE-005b: score-entries 記録後も PUT /scored-components（審判内訳）が 200 で動作する', async () => {
  expect(se005MatchId, 'SE-005a で試合作成済み').toBeTruthy()

  // 既存の審判別内訳 PUT /scored-components も独立して動作すること
  const scRes = await api.put(`${BE_API}/organizations/${orgId}/matches/${se005MatchId!}/scored-components`, {
    headers: authHeaders(adminToken),
    data: {
      components: [
        { competitorSide: 'HOME', componentType: 'TES', pointsScaled: 80000 },
        { competitorSide: 'HOME', componentType: 'PCS', pointsScaled: 90000 },
        { competitorSide: 'AWAY', componentType: 'TES', pointsScaled: 75000 },
      ],
    },
  })
  const scBody = await scRes.text()
  expect(
    scRes.status(),
    `PUT /scored-components は 200（score-entries と共存・非破壊）。応答: ${scBody}`,
  ).toBe(200)
  // 内訳から合計が集計されていること（HOME: TES=80000 + PCS=90000 = 170000）
  const scData = JSON.parse(scBody) as { data: { homeScore: number; awayScore: number } }
  expect(scData.data.homeScore, 'scored-components の合計: HOME=170000').toBe(170000)
  expect(scData.data.awayScore, 'scored-components の合計: AWAY=75000').toBe(75000)
})

test('SE-005c: score-entries・scored-result・scored-components が共存した状態で COMPLETED → 200', async () => {
  expect(se005MatchId, 'SE-005a で試合作成済み').toBeTruthy()

  // scored-result が確定しているので COMPLETED 遷移可能
  const { status, body } = await changeStatus(se005MatchId!, 'COMPLETED')
  expect(status, `三方式共存後の COMPLETED 遷移は 200。応答: ${body}`).toBe(200)
  const match = await getMatch(se005MatchId!)
  expect(match.status, 'status=COMPLETED').toBe('COMPLETED')
})

// ===========================================================================
// SE-006: rank 非送信（クライアントが rank を送らない前提の確認）
//   MatchScoreEntryRequest.Line に rankPosition フィールドが存在しないことを実証。
//   サーバーが totalScaled 降順で rank を算出し、2 者投入で High=1,Low=2 となること。
// ===========================================================================
test('SE-006: rank 非送信 — クライアントは totalScaled のみ送信・サーバーが rank を算出する', async () => {
  se006MatchId = await createMatch('FIGURE_SKATING', 'SE006rank非送信テスト相手')

  // rank を送らず totalScaled だけ送信（DTO に rankPosition フィールドがないことを実証）
  const result = await putScoreEntries(se006MatchId!, [
    { competitorName: 'SE006選手Low', totalScaled: 50000 },
    { competitorName: 'SE006選手High', totalScaled: 99000 },
  ])
  expect(result.status, `rank 非送信 PUT は 200。応答: ${result.body}`).toBe(200)
  const entries = result.data!
  expect(entries.length, '2 件のエントリが返る').toBe(2)

  // サーバーが totalScaled 降順で rank を算出（99000 > 50000 → 1,2）
  const highEntry = entries.find((e) => e.totalScaled === 99000)
  const lowEntry = entries.find((e) => e.totalScaled === 50000)
  expect(highEntry, 'High 選手（99000）が存在する').toBeTruthy()
  expect(lowEntry, 'Low 選手（50000）が存在する').toBeTruthy()
  expect(highEntry!.rankPosition, 'High 選手（99000）は rank=1（サーバー算出）').toBe(1)
  expect(lowEntry!.rankPosition, 'Low 選手（50000）は rank=2（サーバー算出）').toBe(2)
})

// ===========================================================================
// SE-007: 採点競技以外（BASKETBALL）への PUT /score-entries → 400 MATCH_029
//   非採点競技に score-entries を投入してもサーバーが弾く（症状を隠さない・根治治療）。
// ===========================================================================
test('SE-007: 採点競技以外（BASKETBALL）に PUT /score-entries → 400 MATCH_029', async () => {
  // BASKETBALL 試合を scored-team で作成（sport は matches 側で決まる）
  se007MatchId = await createMatch('BASKETBALL', 'SE007採点誤操作テスト相手')

  const result = await putScoreEntries(se007MatchId!, [
    { competitorName: 'SE007選手A', totalScaled: 100 },
  ], 400)

  expect(
    result.status,
    `BASKETBALL 試合への PUT /score-entries は 400（MATCH_029・採点競技以外は拒否）。応答: ${result.body}`,
  ).toBe(400)
  expect(
    result.body,
    '応答本文に MATCH_029 が含まれる（採点競技以外への操作を弾く）',
  ).toContain('MATCH_029')
})

// ===========================================================================
// SE-008: エントリ記録 → COMPLETED 完遂
//   score-entries を記録した採点競技試合が COMPLETED に遷移できることを確認。
//   （scored-result なし・entries のみで home_score が確定するため MATCH_035 が出ない）
// ===========================================================================
test('SE-008: score-entries 記録のみで COMPLETED 遷移 → 200（二層正本により home_score が確定）', async () => {
  se008MatchId = await createMatch('GYMNASTICS', 'SE008COMPLETED完遂テスト相手')

  // score-entries のみ記録（scored-result は呼ばない）
  const result = await putScoreEntries(se008MatchId!, [
    { competitorName: 'SE008体操選手1', totalScaled: 875600 },
    { competitorName: 'SE008体操選手2', totalScaled: 863200 },
    { competitorName: 'SE008体操選手3', totalScaled: 842100 },
  ])
  expect(result.status, `体操 PUT /score-entries は 200。応答: ${result.body}`).toBe(200)

  // 二層正本により home_score = 875600 に確定済みのはずなので COMPLETED に遷移できる
  const { status, body } = await changeStatus(se008MatchId!, 'COMPLETED')
  expect(
    status,
    `score-entries 記録後の COMPLETED 遷移は 200（二層正本で home_score 確定済み）。応答: ${body}`,
  ).toBe(200)

  const match = await getMatch(se008MatchId!)
  expect(match.status, 'status=COMPLETED').toBe('COMPLETED')
  expect(match.homeScore, 'home_score=875600（最上位エントリの合計点）').toBe(875600)
  expect(match.awayScore, 'away_score=0（多人数順位制では 0 正規化）').toBe(0)
  expect(match.winMethod, '採点競技の win_method は null（勝ち方概念なし・§10）').toBeNull()
})

// ===========================================================================
// クリーンアップ
// ===========================================================================
test.afterAll(async () => {
  for (const mId of [
    se001MatchId,
    se002MatchId,
    se003MatchId,
    se004MatchId,
    se005MatchId,
    se006MatchId,
    se007MatchId,
    se008MatchId,
  ]) {
    if (mId) await deleteMatch(mId)
  }
  await api.dispose()
})
