/**
 * F08.10 Phase5b 案A 実機 E2E — 大会直接入力 → canonical match 正本化 + 大会 sport 伝播 + 冪等 + 順位反映
 *
 * モックなし・実バックエンド（http://localhost:8080）接続。
 *
 * 【検証する案A の一気通貫（05 §H.1〜H.2.3 の実機実証）】
 *
 *   TOUR-001  認証セッション確立（Bearer 取得・org/team ID 解決）
 *   TOUR-002  VOLLEYBALL 大会を作成（sport=VOLLEYBALL 指定）
 *   TOUR-003  ディビジョン作成
 *   TOUR-004  参加チーム 2 チーム追加（home=volleyball / away=basketball）
 *   TOUR-005  第1節作成
 *   TOUR-006  対戦カード（fixture）を 1 件生成し fixture ID を取得
 *   TOUR-007  系統B 直接スコア入力（batchUpdateScores: home=3, away=1）→ 204
 *   TOUR-008  (a) fixture スナップショットにスコア反映（score.homeScore=3, awayScore=1, result=HOME_WIN）
 *   TOUR-009  (b) 順位表への反映（standings.played=1, wins=1, points=3 — ポーリング）
 *   TOUR-010  (c) canonical match 生成確認（by-fixture API → 非 null・home=3, away=1）
 *   TOUR-011  canonical match の sport が VOLLEYBALL（SOCCER 固定でない＝#1561 の根治実証）
 *   TOUR-012  冪等: 同一 fixture に再度スコア入力 → canonical match が 1 件のまま（UUID 同一）
 *   TOUR-013  冪等後のスコアが正しく更新されている（home=3, away=2 に変更）
 *   TOUR-014  後処理: 大会を削除（クリーンアップ）
 *
 * 【前提データ】
 *   backend/scripts/seed-f0810-multisport-e2e.js 実行済みであること。
 *   - org slug = f0810-multisport-club（ORG_ID 動的解決）
 *   - team slug = f0810-volleyball-team / f0810-basketball-team（HOME/AWAY）
 *   - e2e-admin が両チームの ADMIN
 *
 * 【構成】API 完結（APIRequestContext のみ）。FE dev サーバー不要。
 *
 * 設計: docs/features/F08.10_match_record_analytics/05_tournament_integration.md §H.1〜H.2.3
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

const ORG_SLUG = 'f0810-multisport-club'
const HOME_TEAM_SLUG = 'f0810-volleyball-team'
const AWAY_TEAM_SLUG = 'f0810-basketball-team'

// ── テスト状態 ──────────────────────────────────────────────────────
let api: APIRequestContext
let adminToken: string
let orgId: number
let homeTeamId: number      // volleyball チームの teamId（participant 追加に使用）
let awayTeamId: number      // basketball チームの teamId（participant 追加に使用）
let tournamentId: number | null = null
let divisionId: number | null = null
let matchdayId: number | null = null
let fixtureId: number | null = null
let homeParticipantId: number | null = null  // fixture の実際の homeParticipantId（自動生成後に解決）
// canonical match を by-fixture で lookup するために使う teamId。
// generateLeagueMatchdays は参加者リストの findByDivisionIdOrderBySeedAsc 順で home/away を割り当てるため、
// participant 追加順（homeTeamId=volleyball を先）と逆になる場合がある。
// fixture 生成後に home participant → teamId を participants API で解決して格納する。
let homeTeamIdForByFixture: number | null = null
let canonicalMatchId: string | null = null

// ─────────────────────────────────────────────────────────────────
// ヘルパー
// ─────────────────────────────────────────────────────────────────

async function login(apiCtx: APIRequestContext): Promise<string> {
  const res = await apiCtx.post(`${BE_API}/auth/login`, {
    data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
  })
  expect(res.status(), 'login は 200 を返す').toBe(200)
  const json = await res.json() as { data: { accessToken: string } }
  return json.data.accessToken
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function resolveOrg(token: string, slug: string): Promise<number> {
  const res = await api.get(`${BE_API}/me/organizations`, { headers: authHeaders(token) })
  expect(res.status(), '/me/organizations は 200').toBe(200)
  const json = await res.json() as {
    data: Array<{ id: number; slug: string }>
    meta: { hasNext: boolean; nextCursor: string | null }
  }
  const org = json.data.find((o) => o.slug === slug)
  expect(org, `seed 済み組織(${slug})が存在する`).toBeTruthy()
  return org!.id
}

async function resolveTeamId(token: string, slug: string): Promise<number> {
  const res = await api.get(`${BE_API}/me/teams`, { headers: authHeaders(token) })
  expect(res.status(), '/me/teams は 200').toBe(200)
  const json = await res.json() as {
    data: Array<{ id: number; slug: string }>
  }
  const team = json.data.find((t) => t.slug === slug)
  expect(team, `seed 済みチーム(${slug})が存在する`).toBeTruthy()
  return team!.id
}

// ─────────────────────────────────────────────────────────────────
// beforeAll / afterAll
// ─────────────────────────────────────────────────────────────────

test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await login(api)
})

test.afterAll(async () => {
  // 後始末: 大会を削除（全関連データをカスケード削除）
  if (adminToken && tournamentId !== null) {
    await api.delete(
      `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}`,
      { headers: authHeaders(adminToken) },
    ).catch(() => {})
  }
  await api.dispose()
})

// ─────────────────────────────────────────────────────────────────
// TOUR-001: 認証セッション確立
// ─────────────────────────────────────────────────────────────────
test('TOUR-001: ADMIN ログインで Bearer トークン取得・org/team ID 解決', async () => {
  expect(adminToken.length, 'トークンは 50 文字以上').toBeGreaterThanOrEqual(50)
  orgId = await resolveOrg(adminToken, ORG_SLUG)
  homeTeamId = await resolveTeamId(adminToken, HOME_TEAM_SLUG)
  awayTeamId = await resolveTeamId(adminToken, AWAY_TEAM_SLUG)
  expect(orgId, 'orgId が解決される').toBeGreaterThan(0)
  expect(homeTeamId, 'homeTeamId（volleyball）が解決される').toBeGreaterThan(0)
  expect(awayTeamId, 'awayTeamId（basketball）が解決される').toBeGreaterThan(0)
})

// ─────────────────────────────────────────────────────────────────
// TOUR-002: VOLLEYBALL 大会を作成（sport=VOLLEYBALL 指定）
// ─────────────────────────────────────────────────────────────────
test('TOUR-002: sport=VOLLEYBALL の大会を作成 → 201 + sport フィールドが VOLLEYBALL', async () => {
  const res = await api.post(`${BE_API}/organizations/${orgId}/tournaments`, {
    headers: authHeaders(adminToken),
    data: {
      name: 'Phase5b E2E バレーボール大会（自動削除）',
      format: 'LEAGUE',
      sport: 'VOLLEYBALL',
      visibility: 'PUBLIC',
    },
  })
  expect(res.status(), '大会作成は 201').toBe(201)
  const json = await res.json() as {
    data: {
      id: number
      content: { sport: string; name: string }
    }
  }
  expect(json.data.id, '大会 ID が返る').toBeGreaterThan(0)
  // sport は data.content.sport にネストされている（TournamentResponse.TournamentContentDto）
  expect(json.data.content.sport, '大会 sport が VOLLEYBALL').toBe('VOLLEYBALL')
  tournamentId = json.data.id
})

// ─────────────────────────────────────────────────────────────────
// TOUR-003: ディビジョン作成
// ─────────────────────────────────────────────────────────────────
test('TOUR-003: ディビジョンを作成 → 201 + divisionId 取得', async () => {
  expect(tournamentId, 'TOUR-002 で大会を作成済み').toBeTruthy()
  const res = await api.post(
    `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}/divisions`,
    {
      headers: authHeaders(adminToken),
      data: { name: 'Division A' },
    },
  )
  expect(res.status(), 'ディビジョン作成は 201').toBe(201)
  const json = await res.json() as { data: { id: number } }
  expect(json.data.id, 'divisionId が返る').toBeGreaterThan(0)
  divisionId = json.data.id
})

// ─────────────────────────────────────────────────────────────────
// TOUR-004: 参加チーム 2 チーム追加
// ─────────────────────────────────────────────────────────────────
test('TOUR-004: 参加チーム（volleyball / basketball）を追加 → 201 × 2', async () => {
  expect(divisionId, 'TOUR-003 で divisionId を取得済み').toBeTruthy()

  const homeRes = await api.post(
    `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}/divisions/${divisionId}/participants`,
    {
      headers: authHeaders(adminToken),
      data: { teamId: homeTeamId, displayName: 'バレー部（HOME）' },
    },
  )
  expect(homeRes.status(), 'HOME 参加者作成は 201').toBe(201)
  const homeJson = await homeRes.json() as { data: { id: number } }
  homeParticipantId = homeJson.data.id

  const awayRes = await api.post(
    `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}/divisions/${divisionId}/participants`,
    {
      headers: authHeaders(adminToken),
      data: { teamId: awayTeamId, displayName: 'バスケ部（AWAY）' },
    },
  )
  expect(awayRes.status(), 'AWAY 参加者作成は 201').toBe(201)

  expect(homeParticipantId, 'homeParticipantId が取得される').toBeGreaterThan(0)
})

// ─────────────────────────────────────────────────────────────────
// TOUR-005: 対戦カード自動生成（節 + fixture を一括生成）→ fixture ID 取得
// ─────────────────────────────────────────────────────────────────
// 注: 自動生成は参加チーム追加直後に呼ぶ（手動で節を先に作成すると matchdayNumber の
//     ユニーク制約 uq_tm_div_num に違反して 500 になるため、手動節作成は不要）。
test('TOUR-005: 対戦カード自動生成（節+fixture 一括生成）→ 201 + matchdayId/fixtureId 取得', async () => {
  expect(divisionId, 'TOUR-003 で divisionId を取得済み').toBeTruthy()

  // 対戦カード自動生成（generateLeagueMatchdays: 参加2チーム → 第1節1試合）
  const genRes = await api.post(
    `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}/divisions/${divisionId}/matchdays/generate`,
    { headers: authHeaders(adminToken) },
  )
  expect(genRes.status(), '対戦カード生成は 201').toBe(201)
  const genJson = await genRes.json() as {
    data: Array<{
      id: number
      matches: Array<{
        id: number
        participants: { homeParticipantId: number; awayParticipantId: number }
      }>
    }>
  }
  expect(genJson.data.length, '1 節以上が生成される').toBeGreaterThanOrEqual(1)
  const firstMatchday = genJson.data[0]
  expect(firstMatchday, '第1節が生成される').toBeTruthy()
  if (!firstMatchday) return
  matchdayId = firstMatchday.id
  expect(firstMatchday.matches.length, '対戦カードが 1 件以上生成される').toBeGreaterThanOrEqual(1)
  const firstMatch = firstMatchday.matches[0]
  expect(firstMatch, '第1節の対戦カードが生成される').toBeTruthy()
  if (!firstMatch) return
  fixtureId = firstMatch.id
  // fixture の実際の homeParticipantId を解決する（generateLeagueMatchdays の割当順は
  // findByDivisionIdOrderBySeedAsc に依存するため、登録順と逆になる場合がある）。
  // TOUR-008/009/010 の winner/standings アサートはこの値を使う。
  homeParticipantId = firstMatch.participants.homeParticipantId
  expect(matchdayId, 'matchdayId が取得される').toBeGreaterThan(0)
  expect(fixtureId, 'fixtureId が取得される').toBeGreaterThan(0)
  expect(homeParticipantId, 'fixture の homeParticipantId が取得される').toBeGreaterThan(0)

  // home participant の teamId を participants API で解決する（by-fixture lookup に必要）。
  // canonical match は home participant の teamId に帰属して作成される（recordMatchCanonical §H.1.2）。
  const partRes = await api.get(
    `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}/divisions/${divisionId}/participants`,
    { headers: authHeaders(adminToken) },
  )
  expect(partRes.status(), 'participants は 200').toBe(200)
  const partJson = await partRes.json() as {
    data: Array<{ id: number; teamId: number }>
  }
  const homeParticipant = partJson.data.find((p) => p.id === homeParticipantId)
  expect(homeParticipant, 'home participant が参加者リストに存在する').toBeTruthy()
  homeTeamIdForByFixture = homeParticipant!.teamId
  expect(homeTeamIdForByFixture, 'home participant の teamId が解決される').toBeGreaterThan(0)
})

// ─────────────────────────────────────────────────────────────────
// TOUR-006: fixture 詳細確認（生成直後の初期状態）
// ─────────────────────────────────────────────────────────────────
test('TOUR-006: 生成直後の fixture 詳細が取得できる（スコア未入力・PENDING）', async () => {
  expect(fixtureId, 'TOUR-005 で fixtureId を取得済み').toBeTruthy()

  const res = await api.get(
    `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}/matches/${fixtureId}`,
    { headers: authHeaders(adminToken) },
  )
  expect(res.status(), 'fixture 詳細は 200').toBe(200)
  const json = await res.json() as {
    data: {
      id: number
      score: { homeScore: number | null; awayScore: number | null }
      info: { result: string; status: string }
    }
  }
  expect(json.data.id, 'fixtureId が一致する').toBe(fixtureId)
  expect(json.data.score.homeScore, '初期 homeScore は null').toBeNull()
  expect(json.data.score.awayScore, '初期 awayScore は null').toBeNull()
  expect(json.data.info.result, '初期 result は PENDING').toBe('PENDING')
})

// ─────────────────────────────────────────────────────────────────
// TOUR-007: 系統B 直接スコア入力（batchUpdateScores）→ 204
// ─────────────────────────────────────────────────────────────────
test('TOUR-007: 系統B 直接スコア入力（home=3, away=1）→ 204', async () => {
  expect(fixtureId, 'TOUR-006 で fixtureId を取得済み').toBeTruthy()

  // fixture の version を取得（楽観ロック用）
  const fixtureRes = await api.get(
    `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}/matches/${fixtureId}`,
    { headers: authHeaders(adminToken) },
  )
  expect(fixtureRes.status(), 'fixture 詳細は 200').toBe(200)
  const fixtureJson = await fixtureRes.json() as {
    data: { audit: { version: number } }
  }
  const version = fixtureJson.data.audit.version

  const res = await api.post(
    `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}/divisions/${divisionId}/matchdays/${matchdayId}/scores/batch`,
    {
      headers: authHeaders(adminToken),
      data: {
        scores: [
          {
            matchId: fixtureId,
            homeScore: 3,
            awayScore: 1,
            version: version,
          },
        ],
      },
    },
  )
  expect(res.status(), 'batchUpdateScores は 204').toBe(204)
  // 非同期処理（StandingsRecalculationEvent / @Async）の完了を待つ
  await new Promise((resolve) => setTimeout(resolve, 3_000))
})

// ─────────────────────────────────────────────────────────────────
// TOUR-008: fixture スナップショットにスコア反映確認
// ─────────────────────────────────────────────────────────────────
test('TOUR-008: fixture スナップショットにスコアが反映される（home=3, away=1, result=HOME_WIN）', async () => {
  expect(fixtureId, 'TOUR-006 で fixtureId を取得済み').toBeTruthy()

  const res = await api.get(
    `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}/matches/${fixtureId}`,
    { headers: authHeaders(adminToken) },
  )
  expect(res.status(), 'fixture 詳細は 200').toBe(200)
  const json = await res.json() as {
    data: {
      score: { homeScore: number; awayScore: number }
      info: { result: string; status: string }
      participants: { winnerParticipantId: number }
    }
  }
  expect(json.data.score.homeScore, 'fixture.homeScore=3').toBe(3)
  expect(json.data.score.awayScore, 'fixture.awayScore=1').toBe(1)
  expect(json.data.info.result, 'fixture.result=HOME_WIN').toBe('HOME_WIN')
  expect(json.data.info.status, 'fixture.status=COMPLETED').toBe('COMPLETED')
  expect(
    json.data.participants.winnerParticipantId,
    'fixture.winnerParticipantId が homeParticipantId',
  ).toBe(homeParticipantId)
})

// ─────────────────────────────────────────────────────────────────
// TOUR-009: 順位表への自動反映確認（ポーリング最大 10 秒）
// ─────────────────────────────────────────────────────────────────
type StandingRow = {
  meta: { participantId: number }
  record: { played: number; wins: number; draws: number; losses: number }
  score: { points: number }
}

test('TOUR-009: 順位表が自動反映される（home participant: played=1, wins=1, points=3）', async () => {
  expect(homeParticipantId, 'TOUR-004 で homeParticipantId を取得済み').toBeTruthy()

  const deadline = Date.now() + 10_000
  let homeStanding: StandingRow | undefined

  while (Date.now() < deadline) {
    const res = await api.get(
      `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}/divisions/${divisionId}/standings`,
      { headers: authHeaders(adminToken) },
    )
    expect(res.status(), 'standings は 200').toBe(200)
    const json = await res.json() as { data: StandingRow[] }
    const candidate = json.data.find((s) => s.meta.participantId === homeParticipantId)
    if (candidate && candidate.record.played === 1) {
      homeStanding = candidate
      break
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000))
  }

  expect(homeStanding, 'HOME participant の standings が自動反映される').toBeTruthy()
  expect(homeStanding!.record.played, 'played=1').toBe(1)
  expect(homeStanding!.record.wins, 'wins=1').toBe(1)
  expect(homeStanding!.record.draws, 'draws=0').toBe(0)
  expect(homeStanding!.record.losses, 'losses=0').toBe(0)
  expect(homeStanding!.score.points, 'points=3').toBe(3)
})

// ─────────────────────────────────────────────────────────────────
// TOUR-010: canonical match 生成確認（by-fixture API → 非 null・スコア一致）
// ─────────────────────────────────────────────────────────────────
test('TOUR-010: canonical match が生成される（by-fixture API → 非 null・home=3, away=1）', async () => {
  expect(fixtureId, 'TOUR-006 で fixtureId を取得済み').toBeTruthy()

  // by-fixture: fixture の home participant の teamId で match を解決する。
  // recordMatchCanonical は home participant の teamId に match を帰属させる（05 §H.1.2）。
  // homeTeamIdForByFixture は TOUR-005 で participants API 経由で解決済み。
  expect(homeTeamIdForByFixture, 'homeTeamIdForByFixture が解決済み').toBeTruthy()
  const res = await api.get(
    `${BE_API}/organizations/${orgId}/teams/${homeTeamIdForByFixture}/matches/by-fixture/${fixtureId}`,
    { headers: authHeaders(adminToken) },
  )
  expect(res.status(), 'by-fixture は 200').toBe(200)
  const json = await res.json() as {
    data: {
      id: string
      homeScore: number | null
      awayScore: number | null
      status: string
    } | null
  }
  expect(json.data, 'canonical match が生成されている（null でない）').not.toBeNull()
  expect(json.data!.id, 'canonical match UUID が返る').toBeTruthy()
  expect(json.data!.homeScore, 'canonical match.homeScore=3').toBe(3)
  expect(json.data!.awayScore, 'canonical match.awayScore=1').toBe(1)
  expect(json.data!.status, 'canonical match.status=COMPLETED').toBe('COMPLETED')
  canonicalMatchId = json.data!.id
})

// ─────────────────────────────────────────────────────────────────
// TOUR-011: canonical match の sport が VOLLEYBALL（#1561 根治実証）
// ─────────────────────────────────────────────────────────────────
test('TOUR-011: canonical match の sport が VOLLEYBALL（SOCCER 固定でない・#1561 根治実証）', async () => {
  expect(canonicalMatchId, 'TOUR-010 で canonicalMatchId を取得済み').toBeTruthy()

  expect(homeTeamIdForByFixture, 'homeTeamIdForByFixture が解決済み').toBeTruthy()
  const res = await api.get(
    `${BE_API}/organizations/${orgId}/teams/${homeTeamIdForByFixture}/matches/${canonicalMatchId}`,
    { headers: authHeaders(adminToken) },
  )
  expect(res.status(), 'match 詳細は 200').toBe(200)
  const json = await res.json() as {
    data: { sport: string }
  }
  expect(
    json.data.sport,
    'canonical match.sport=VOLLEYBALL（大会 sport が正しく伝播・SOCCER 固定でない）',
  ).toBe('VOLLEYBALL')
})

// ─────────────────────────────────────────────────────────────────
// TOUR-012: 冪等 — 同一 fixture に再度スコア入力 → canonical match が 1 件のまま（UUID 同一）
// ─────────────────────────────────────────────────────────────────
test('TOUR-012: 同一 fixture に再スコア入力 → canonical match が二重作成されない（UUID 同一）', async () => {
  expect(fixtureId, 'TOUR-006 で fixtureId を取得済み').toBeTruthy()
  expect(canonicalMatchId, 'TOUR-010 で canonicalMatchId を取得済み').toBeTruthy()

  // fixture の最新 version を取得（楽観ロック用）
  const fixtureRes = await api.get(
    `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}/matches/${fixtureId}`,
    { headers: authHeaders(adminToken) },
  )
  expect(fixtureRes.status()).toBe(200)
  const fixtureJson = await fixtureRes.json() as { data: { audit: { version: number } } }
  const newVersion = fixtureJson.data.audit.version

  // スコアを変更して再入力（home=3, away=2）
  const res = await api.post(
    `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}/divisions/${divisionId}/matchdays/${matchdayId}/scores/batch`,
    {
      headers: authHeaders(adminToken),
      data: {
        scores: [
          {
            matchId: fixtureId,
            homeScore: 3,
            awayScore: 2,
            version: newVersion,
          },
        ],
      },
    },
  )
  expect(res.status(), '再スコア入力は 204').toBe(204)
  await new Promise((resolve) => setTimeout(resolve, 2_000))

  // by-fixture で canonical match を再解決 → UUID が同一であること
  expect(homeTeamIdForByFixture, 'homeTeamIdForByFixture が解決済み').toBeTruthy()
  const byFixtureRes = await api.get(
    `${BE_API}/organizations/${orgId}/teams/${homeTeamIdForByFixture}/matches/by-fixture/${fixtureId}`,
    { headers: authHeaders(adminToken) },
  )
  expect(byFixtureRes.status(), 'by-fixture は 200').toBe(200)
  const byFixtureJson = await byFixtureRes.json() as {
    data: { id: string } | null
  }
  expect(byFixtureJson.data, '再入力後も canonical match が存在する').not.toBeNull()
  expect(
    byFixtureJson.data!.id,
    '再入力後の canonical match UUID が TOUR-010 と同一（二重作成されていない）',
  ).toBe(canonicalMatchId)
})

// ─────────────────────────────────────────────────────────────────
// TOUR-013: 冪等後のスコアが正しく更新されている
// ─────────────────────────────────────────────────────────────────
test('TOUR-013: 再入力後の canonical match スコアが更新される（home=3, away=2）', async () => {
  expect(canonicalMatchId, 'TOUR-010 で canonicalMatchId を取得済み').toBeTruthy()
  expect(homeTeamIdForByFixture, 'homeTeamIdForByFixture が解決済み').toBeTruthy()

  const res = await api.get(
    `${BE_API}/organizations/${orgId}/teams/${homeTeamIdForByFixture}/matches/${canonicalMatchId}`,
    { headers: authHeaders(adminToken) },
  )
  expect(res.status(), 'match 詳細は 200').toBe(200)
  const json = await res.json() as {
    data: { homeScore: number; awayScore: number }
  }
  expect(json.data.homeScore, '冪等後の homeScore=3').toBe(3)
  expect(json.data.awayScore, '冪等後の awayScore=2（更新されている）').toBe(2)
})

// ─────────────────────────────────────────────────────────────────
// TOUR-014: 後処理（afterAll でも実施するが、明示テストとして成功を記録）
// ─────────────────────────────────────────────────────────────────
test('TOUR-014: 大会削除（テストデータクリーンアップ・afterAll でも実施）', async () => {
  expect(tournamentId, 'TOUR-002 で大会を作成済み').toBeTruthy()
  const res = await api.delete(
    `${BE_API}/organizations/${orgId}/tournaments/${tournamentId}`,
    { headers: authHeaders(adminToken) },
  )
  // 204 または 200 を許容（削除成功）
  expect([200, 204], '大会削除は 200/204').toContain(res.status())
  // afterAll での二重削除は 404 になる可能性があるが、afterAll はエラーを握りつぶすため問題なし
})
