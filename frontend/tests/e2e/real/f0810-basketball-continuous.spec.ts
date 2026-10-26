/**
 * F08.10 多競技ライブ記録 実機 E2E ― 連続時間制（バスケットボール）。
 *
 * モックなし・実バックエンド（http://localhost:8080）接続。BE は再起動せず稼働中の最新 main をそのまま使う。
 *
 * 【検証対象】sports/03_basketball.md の連続時間制フローを実運用 API で一気通貫実証する:
 *   試合作成（sport=BASKETBALL）→ 4 クォーター進行のライブ記録（2P/3P/FT・ファウル PF/TF＋理由コード）
 *   → スコア確定 → COMPLETED 遷移。
 *   FE の MatchEventSheetBasketball / useMatchTimerBasketball が叩く production エンドポイント
 *   （MatchRecordController / MatchRecordEventController）の実挙動をハードアサートする。
 *
 * 【前提データ】backend/scripts/seed-f0810-multisport-e2e.js を実行済みであること。
 *   - 固有名前空間（他 E2E 隊と非衝突）: org slug=f0810-multisport-club /
 *     team slug=f0810-basketball-team。
 *   - e2e-admin が当該チームの ADMIN（共同記録＝主体チーム ADMIN/DEPUTY のみ記録可・03 §C.1）。
 *   - org/team の数値 ID は環境依存のため固定値を持たず、GET /me/teams から slug で動的解決する。
 *
 * 【記録に ADMIN が必須な理由】ライブ記録（イベント POST）は MatchAccessService.assertCanRecordTimeline で
 *   主体チーム ADMIN/DEPUTY に限定される。本 spec は e2e-admin の storageState で実行する。
 *
 * 【構成】API 完結（APIRequestContext のみ・browser/page 不使用）。フロント dev サーバー（BASE_URL）には
 *   依存しない。これにより WSL2 の FE↔BE 到達/CORS の影響を受けず、記録 API の本物の挙動だけを検証できる。
 *
 * 設計: docs/features/F08.10_match_record_analytics/sports/03_basketball.md / 01_domain_and_ddl.md §D.6
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'

// storageState に依存せず、テスト内で API ログインする（f0810-entry1 spec と同作法）。
test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

// seed の固有 slug（環境非依存。数値 ID は /me/teams から解決する）
const TEAM_SLUG = 'f0810-basketball-team'

// ── テスト状態 ──────────────────────────────────────────────────
let api: APIRequestContext
let adminToken: string
let orgId: number
let teamId: number
let matchId: string | null = null

// ── ヘルパー ────────────────────────────────────────────────────
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

/** /me/teams から slug で numeric team_id / organization_id を解決する（環境非依存）。 */
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

/** 記録イベントを POST し 201 をハードアサートする。 */
async function recordEvent(body: Record<string, unknown>): Promise<void> {
  const res = await api.post(`${BE_API}/organizations/${orgId}/matches/${matchId}/events`, {
    headers: authHeaders(adminToken),
    data: body,
  })
  expect(
    res.status(),
    `イベント記録(${String(body.eventType)})は 201。応答: ${await res.text()}`,
  ).toBe(201)
  const json = await res.json() as { data: { eventType: string } }
  expect(json.data.eventType, 'eventType がエコーされる').toBe(body.eventType)
}

// ── beforeAll / afterAll ────────────────────────────────────────
test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await login(api)
  const resolved = await resolveTeam(adminToken, TEAM_SLUG)
  teamId = resolved.teamId
  orgId = resolved.orgId
})

test.afterAll(async () => {
  // 後始末: 試作試合を論理削除して環境を汚さない。
  if (adminToken && matchId) {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
  await api.dispose()
})

// ===========================================================================
// BB-000: 認証 + チーム解決
// ===========================================================================
test('BB-000: ADMIN ログイン + バスケチームの org/team ID を slug 解決', async () => {
  expect(adminToken.length, 'トークンは 50 文字以上').toBeGreaterThanOrEqual(50)
  expect(teamId, 'teamId が解決される').toBeGreaterThan(0)
  expect(orgId, 'orgId が解決される').toBeGreaterThan(0)
})

// ===========================================================================
// BB-001: バスケ試合を作成（sport=BASKETBALL・連続時間制・40 分）
// ===========================================================================
test('BB-001: バスケ試合を作成 → 201 + sport=BASKETBALL・status=SCHEDULED', async () => {
  const res = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(adminToken),
    data: {
      sport: 'BASKETBALL',
      kind: 'FRIENDLY',
      homeAway: 'HOME',
      opponentName: 'E2Eバスケ相手',
      durationMinutes: 40, // 連続時間制の COMPLETED 必須条件（02 §E.3・未設定は MATCH_023）
    },
  })
  expect(res.status(), `バスケ試合作成は 201。応答: ${await res.text()}`).toBe(201)
  const json = await res.json() as { data: { id: string; sport: string; status: string } }
  expect(json.data.sport, 'sport=BASKETBALL が永続化される').toBe('BASKETBALL')
  expect(json.data.status, '初期 status は SCHEDULED').toBe('SCHEDULED')
  matchId = json.data.id
  expect(matchId, '試合 UUID が返る').toBeTruthy()
})

// ===========================================================================
// BB-002: 4 クォーター進行のライブ記録（2P/3P/FT・ファウル PF/TF＋理由コード）
// ===========================================================================
test('BB-002: ライブ記録（Q1〜Q4・2P/3P/FT・ファウル PF/TF＋理由コード）が全て 201', async () => {
  expect(matchId, 'BB-001 で試合作成済み').toBeTruthy()

  // Q1: ホーム 2P・ホーム 3P（バスケ得点種別 = FIELD_GOAL_2 / FIELD_GOAL_3・GOAL は使わない）
  await recordEvent({ eventType: 'FIELD_GOAL_2', teamSide: 'HOME', period: 'QUARTER_1', minute: 3 })
  await recordEvent({ eventType: 'FIELD_GOAL_3', teamSide: 'HOME', period: 'QUARTER_1', minute: 5 })
  // Q2: アウェイ FT（フリースロー +1）、ホームのシューティングファウル（理由コード SF）
  await recordEvent({ eventType: 'FREE_THROW', teamSide: 'AWAY', period: 'QUARTER_2', minute: 12 })
  await recordEvent({
    eventType: 'PERSONAL_FOUL', teamSide: 'HOME', period: 'QUARTER_2', minute: 15,
    cardReasonCode: 'SF', // BasketballFoulCode.SF（シューティングファウル・§5）
  })
  // Q3: アウェイのテクニカルファウル（理由コード TF）
  await recordEvent({
    eventType: 'TECHNICAL_FOUL', teamSide: 'AWAY', period: 'QUARTER_3', minute: 22,
    cardReasonCode: 'TF',
  })
  // Q4: ホーム 2P（クォーターを跨いだ連続記録が成立すること）
  await recordEvent({ eventType: 'FIELD_GOAL_2', teamSide: 'HOME', period: 'QUARTER_4', minute: 38 })

  // タイムラインに 6 件のイベントが反映されること（記録の永続化＝MatchEventSheetBasketball の出力先）
  const res = await api.get(`${BE_API}/organizations/${orgId}/matches/${matchId}/events`, {
    headers: authHeaders(adminToken),
  })
  expect(res.status(), 'events 一覧は 200').toBe(200)
  const json = await res.json() as { data: { events: Array<{ eventType: string; cardReasonCode: string | null }> } }
  const events = json.data.events
  expect(events.length, '記録した 6 イベントが反映される').toBe(6)
  // ファウル理由コードが正しく永続化される（バスケ固有 card_reason_code の実証）
  const fouls = events.filter((e) => e.eventType === 'PERSONAL_FOUL' || e.eventType === 'TECHNICAL_FOUL')
  expect(fouls.map((f) => f.cardReasonCode).sort(), 'ファウル理由コード SF/TF が永続化される').toEqual(['SF', 'TF'])
})

// ===========================================================================
// BB-003: スコア確定（78-74）
// ===========================================================================
test('BB-003: スコア確定（78-74）→ 200 + スコアが反映される', async () => {
  expect(matchId, 'BB-001 で試合作成済み').toBeTruthy()
  const res = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}/score`, {
    headers: authHeaders(adminToken),
    data: { homeScore: 78, awayScore: 74 },
  })
  expect(res.status(), `スコア確定は 200。応答: ${await res.text()}`).toBe(200)
  const json = await res.json() as { data: { homeScore: number; awayScore: number } }
  expect(json.data.homeScore, 'home=78').toBe(78)
  expect(json.data.awayScore, 'away=74').toBe(74)
})

// ===========================================================================
// BB-004: COMPLETED 遷移（連続時間制＝duration 必須を満たすこと）
// ===========================================================================
test('BB-004: COMPLETED 遷移 → 200 + status=COMPLETED（連続時間制の終了条件成立）', async () => {
  expect(matchId, 'BB-001 で試合作成済み').toBeTruthy()
  const res = await api.patch(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}/status`, {
    headers: authHeaders(adminToken),
    data: { status: 'COMPLETED' },
  })
  expect(res.status(), `COMPLETED 遷移は 200（MATCH_023 等が出ないこと）。応答: ${await res.text()}`).toBe(200)
  const json = await res.json() as { data: { status: string } }
  expect(json.data.status, 'status=COMPLETED').toBe('COMPLETED')

  // 確定後の取得でスコア・状態が確定していること
  const get = await api.get(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}`, {
    headers: authHeaders(adminToken),
  })
  expect(get.status(), '試合取得は 200').toBe(200)
  const detail = await get.json() as { data: { status: string; homeScore: number; awayScore: number } }
  expect(detail.data.status, '取得しても COMPLETED').toBe('COMPLETED')
  expect(detail.data.homeScore, 'home スコアが確定値 78').toBe(78)
  expect(detail.data.awayScore, 'away スコアが確定値 74').toBe(74)
})
