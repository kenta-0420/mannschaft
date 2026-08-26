/**
 * F08.10 多競技ライブ記録 実機 E2E ― ライブ観戦 WebSocket（STOMP）配信＋購読認可。
 *
 * モックなし・実バックエンド（既定 http://localhost:8080・BE_ORIGIN で上書き可）接続。
 * BE は再起動せず稼働中の最新 main をそのまま使う（7-A 配信 / 7-B 購読認可 / 7-C 観戦込み）。
 *
 * 【検証対象】07_realtime_spectator.md §J の WebSocket ライブ観戦を実運用経路で一気通貫実証する:
 *   1. 配信（§J.2）   : 記録者が HTTP でイベント記録 / スコア確定 / ステータス遷移 →
 *                        観戦者が STOMP {@code /topic/matches/{matchId}/live} で
 *                        MatchLiveUpdatePayload（type / serverSeq / event / score / status）を受信する。
 *   2. 購読認可（§J.3）: F00 可視性（MatchVisibilityResolver）を SUBSCRIBE 時に検証し、
 *                        可視性あり→購読成功・受信、可視性なし（他テナント会員）→ERROR フレームで購読拒否、
 *                        未認証→（現 MVP では可視性 null=fail-closed のため）購読拒否。
 *   3. 機微情報除外（§J.3.3）: ペイロードに内部 userId / owning_team_id / recorded_by_team_id 等が載らないこと。
 *
 * 【WS 接続の作法（実コードから確定）】
 *   - エンドポイント: WebSocketConfig が {@code registry.addEndpoint("/ws").withSockJS()} で登録。
 *     SockJS の raw-websocket transport（{@code /ws/websocket}）に node の `ws` で接続し、STOMP を載せる
 *     （sockjs-client 不要。@stomp/stompjs の webSocketFactory に ws を渡す）。
 *   - CONNECT 認証: WebSocketAuthChannelInterceptor が CONNECT フレームの native header
 *     {@code Authorization: Bearer <accessToken>} を検証し session 属性 userId を確定する。
 *   - SUBSCRIBE 認可: MatchLiveSubscriptionInterceptor が {@code /topic/matches/{uuid}/live} 宛のみ
 *     MatchAccessService.canView（→F00）で認可。不可視は MessagingException→ERROR フレーム返却。
 *
 * 【可視性の前提（実装：MatchVisibilityResolver）】
 *   MATCH 可視性は PUBLIC フラグ依存ではなく「閲覧者が当該試合の主体/相手チーム・主催組織のメンバー以上か」で判定。
 *   SystemAdmin は実存 match に常に可。未認証（userId=null）は fail-closed（false）。本 spec はこの実挙動に整合させる:
 *     - 可視性あり（受信できる）: e2e-admin（SystemAdmin）/ f0810-recorder（team 152/153・org 138 の ADMIN＝メンバー）
 *     - 可視性なし（拒否される）: e2e-user（FC東京U-18 等の会員だが f0810 org/team には非所属＝他テナント会員）
 *     - 未認証: 購読拒否（PUBLIC でも MVP では null=fail-closed）
 *
 * 【前提データ】backend/scripts/seed-f0810-multisport-e2e.js を実行済みであること。
 *   org slug=f0810-multisport-club / team slug=f0810-basketball-team。
 *   f0810-recorder=両チーム ADMIN。e2e-admin=SystemAdmin＋両チーム ADMIN。e2e-user=f0810 非所属。
 *
 * 【構成】API＋STOMP 完結（APIRequestContext + ws）。フロント dev サーバー（BASE_URL）に依存しない。
 *
 * 設計: docs/features/F08.10_match_record_analytics/07_realtime_spectator.md §J.2 / §J.3 / §J.3.3
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'
import { Client, type StompHeaders, type IMessage, type IFrame } from '@stomp/stompjs'
import WS from 'ws'

// storageState に依存せず、テスト内で API ログインする（f0810-entry1 / basketball spec と同作法）。
test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`
// SockJS raw-websocket transport。withSockJS() 有効ゆえ /ws/websocket が WebSocket で開く。
const WS_URL = `${BE.replace(/^http/, 'ws')}/ws/websocket`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
const RECORDER_EMAIL = 'f0810-recorder@test.mannschaft.local'
const OUTSIDER_EMAIL = 'e2e-user@test.mannschaft.local' // f0810 非所属の他テナント会員
const COMMON_PASSWORD = 'TestPass2026!'

const TEAM_SLUG = 'f0810-basketball-team'

// ── テスト状態 ──────────────────────────────────────────────────
let api: APIRequestContext
let adminToken: string
let recorderToken: string
let outsiderToken: string
let orgId: number
let teamId: number
let matchId: string | null = null

// ── HTTP ヘルパー ──────────────────────────────────────────────
async function login(email: string, password: string): Promise<string> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `login(${email}) は 200。応答: ${await res.text()}`).toBe(200)
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
  expect(team, `seed のチーム(${slug})が /me/teams に存在する（seed 未実行なら null）`).toBeTruthy()
  return { teamId: team!.id, orgId: team!.organizationId }
}

// ── STOMP ヘルパー ─────────────────────────────────────────────

type SpectatorSession = {
  client: Client
  messages: MatchLiveUpdatePayload[]
  /** SUBSCRIBE 認可拒否時に MatchLiveSubscriptionInterceptor が返す ERROR フレーム。 */
  stompErrors: IFrame[]
  close: () => Promise<void>
}

type MatchLiveUpdatePayload = {
  type: 'EVENT_ADDED' | 'EVENT_UPDATED' | 'EVENT_DELETED' | 'SCORE_UPDATED' | 'STATUS_CHANGED'
  matchId: string
  serverSeq: number
  event?: Record<string, unknown> | null
  eventId?: string | null
  score?: Record<string, unknown> | null
  status?: string | null
}

/**
 * STOMP CONNECT のみ確立する（SUBSCRIBE はしない）。token=null で未認証接続。
 * 接続できたら resolve、StompError/WS エラー/タイムアウトで reject する。
 */
function connectStomp(token: string | null): Promise<SpectatorSession> {
  const messages: MatchLiveUpdatePayload[] = []
  const stompErrors: IFrame[] = []
  const connectHeaders: StompHeaders = {}
  if (token) {
    connectHeaders.Authorization = `Bearer ${token}`
  }

  const client = new Client({
    webSocketFactory: () => new WS(WS_URL) as unknown as WebSocket,
    connectHeaders,
    reconnectDelay: 0, // 認可拒否時に無限再接続しない
    heartbeatIncoming: 0,
    heartbeatOutgoing: 0,
  })

  const session: SpectatorSession = {
    client,
    messages,
    stompErrors,
    close: () =>
      new Promise<void>((resolve) => {
        try {
          client.onDisconnect = () => resolve()
          void client.deactivate()
          // deactivate が即時に解決しないケースに備えた保険
          setTimeout(resolve, 1500)
        } catch {
          resolve()
        }
      }),
  }

  return new Promise<SpectatorSession>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('STOMP CONNECT タイムアウト（10s）')), 10_000)
    client.onConnect = () => {
      clearTimeout(timer)
      resolve(session)
    }
    client.onStompError = (frame) => {
      stompErrors.push(frame)
      clearTimeout(timer)
      reject(new Error(`STOMP ERROR(CONNECT): ${frame.headers['message'] ?? ''} ${frame.body ?? ''}`))
    }
    client.onWebSocketError = (e) => {
      clearTimeout(timer)
      reject(new Error(`WebSocket エラー: ${(e as { message?: string })?.message ?? String(e)}`))
    }
    client.activate()
  })
}

/**
 * 指定セッションで {@code /topic/matches/{matchId}/live} を購読する。
 *
 * <p>Spring の SimpleBroker は SUBSCRIBE に対し RECEIPT フレームを返さない（実機確認済み）ため、
 * 認可成否は次で判定する:</p>
 * <ul>
 *   <li><b>認可成功</b>: SUBSCRIBE 送出後、猶予時間内に ERROR フレームが来なければ確立とみなし resolve。
 *       実際の配信受信は別途 {@link waitForMessage} で能動検証する。</li>
 *   <li><b>認可拒否</b>: MatchLiveSubscriptionInterceptor が MessagingException→ERROR フレームを返し、
 *       直後に WebSocket が CLOSE される（実機確認済み）。ERROR を {@code stompErrors} に記録し reject。</li>
 * </ul>
 */
function subscribeLive(session: SpectatorSession, mId: string, graceMs = 1_200): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    let settled = false
    const settleResolve = () => { if (!settled) { settled = true; resolve() } }
    const settleReject = (e: Error) => { if (!settled) { settled = true; reject(e) } }

    // ERROR フレーム＝購読拒否（onStompError に来る）。
    session.client.onStompError = (frame) => {
      session.stompErrors.push(frame)
      settleReject(new Error(`SUBSCRIBE 拒否(ERROR frame): ${frame.headers['message'] ?? ''} ${frame.body ?? ''}`))
    }
    // 拒否時は ERROR 直後に WS が CLOSE される。CLOSE を拒否のシグナルとしても拾う（取りこぼし防止）。
    session.client.onWebSocketClose = () => {
      settleReject(new Error('SUBSCRIBE 拒否(WebSocket CLOSE・認可不可で切断)'))
    }

    session.client.subscribe(
      `/topic/matches/${mId}/live`,
      (msg: IMessage) => {
        try {
          session.messages.push(JSON.parse(msg.body) as MatchLiveUpdatePayload)
        } catch {
          /* 非 JSON は無視（本トピックは常に JSON） */
        }
      },
      { id: `sub-${Math.random().toString(36).slice(2)}` },
    )

    // 猶予内に ERROR/CLOSE が来なければ確立成功とみなす。
    setTimeout(settleResolve, graceMs)
  })
}

/** session.messages に条件を満たすメッセージが届くまで最大 timeout ms 待つ。 */
async function waitForMessage(
  session: SpectatorSession,
  predicate: (m: MatchLiveUpdatePayload) => boolean,
  timeout = 8_000,
): Promise<MatchLiveUpdatePayload> {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const hit = session.messages.find(predicate)
    if (hit) return hit
    await new Promise((r) => setTimeout(r, 100))
  }
  throw new Error(
    `期待するライブメッセージが ${timeout}ms 以内に届かなかった。受信済み: ${JSON.stringify(session.messages.map((m) => m.type))}`,
  )
}

/** 記録イベントを POST し 201 をハードアサートする。 */
async function recordEvent(body: Record<string, unknown>): Promise<void> {
  const res = await api.post(`${BE_API}/organizations/${orgId}/matches/${matchId}/events`, {
    headers: authHeaders(recorderToken),
    data: body,
  })
  expect(res.status(), `イベント記録(${String(body.eventType)})は 201。応答: ${await res.text()}`).toBe(201)
}

// ── beforeAll / afterAll ────────────────────────────────────────
test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await login(ADMIN_EMAIL, ADMIN_PASSWORD)
  recorderToken = await login(RECORDER_EMAIL, COMMON_PASSWORD)
  outsiderToken = await login(OUTSIDER_EMAIL, COMMON_PASSWORD)
  const resolved = await resolveTeam(recorderToken, TEAM_SLUG)
  teamId = resolved.teamId
  orgId = resolved.orgId
})

test.afterAll(async () => {
  if (recorderToken && matchId) {
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}`,
      { headers: authHeaders(recorderToken) },
    ).catch(() => {})
  }
  await api.dispose()
})

// ===========================================================================
// WS-000: 認証 + 観戦対象の非 PUBLIC（メンバー限定可視）試合を作成
// ===========================================================================
test('WS-000: 3 ロールでログイン + 観戦対象のバスケ試合（メンバー限定可視）を作成', async () => {
  expect(adminToken.length, 'admin トークン').toBeGreaterThanOrEqual(50)
  expect(recorderToken.length, 'recorder トークン').toBeGreaterThanOrEqual(50)
  expect(outsiderToken.length, 'outsider(e2e-user) トークン').toBeGreaterThanOrEqual(50)
  expect(teamId, 'teamId 解決').toBeGreaterThan(0)
  expect(orgId, 'orgId 解決').toBeGreaterThan(0)

  // opponentName 指定（相手チーム未登録）＝可視性は team 152/153・org 138 のメンバーシップのみで決まる
  // ＝ e2e-user（f0810 非所属）には不可視＝購読認可テストの最重要前提。
  const res = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(recorderToken),
    data: {
      sport: 'BASKETBALL',
      kind: 'FRIENDLY',
      homeAway: 'HOME',
      opponentName: 'E2E観戦相手',
      durationMinutes: 40,
    },
  })
  expect(res.status(), `試合作成は 201。応答: ${await res.text()}`).toBe(201)
  const json = await res.json() as { data: { id: string; status: string } }
  matchId = json.data.id
  expect(matchId, '試合 UUID が返る').toBeTruthy()
  expect(json.data.status, '初期 status は SCHEDULED').toBe('SCHEDULED')
})

// ===========================================================================
// WS-001【配信・§J.2】観戦者が購読 → 記録/スコア/ステータス更新を受信（serverSeq 単調増加）
// ===========================================================================
test('WS-001: 観戦者が購読 → EVENT_ADDED / SCORE_UPDATED / STATUS_CHANGED を受信し serverSeq が単調増加', async () => {
  expect(matchId, 'WS-000 で作成済み').toBeTruthy()

  // 観戦者（recorder＝可視性あり会員）が STOMP 接続→購読
  const spectator = await connectStomp(recorderToken)
  try {
    await subscribeLive(spectator, matchId!)

    // (1) イベント記録 → EVENT_ADDED 受信
    await recordEvent({ eventType: 'FIELD_GOAL_2', teamSide: 'HOME', period: 'QUARTER_1', minute: 3 })
    const added = await waitForMessage(spectator, (m) => m.type === 'EVENT_ADDED')
    expect(added.matchId, 'EVENT_ADDED の matchId が一致').toBe(matchId)
    expect(added.event, 'EVENT_ADDED は差分イベントを含む').toBeTruthy()
    expect((added.event as { eventType?: string }).eventType, '配信イベント種別が一致').toBe('FIELD_GOAL_2')

    // (2) スコア確定 → SCORE_UPDATED 受信（スコアサマリ付き）
    const scoreRes = await api.patch(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}/score`,
      { headers: authHeaders(recorderToken), data: { homeScore: 80, awayScore: 70 } },
    )
    expect(scoreRes.status(), `スコア確定は 200。応答: ${await scoreRes.text()}`).toBe(200)
    const scored = await waitForMessage(spectator, (m) => m.type === 'SCORE_UPDATED')
    expect(scored.score, 'SCORE_UPDATED はスコアサマリを含む').toBeTruthy()
    expect((scored.score as { homeScore?: number }).homeScore, '配信スコア home=80').toBe(80)
    expect((scored.score as { awayScore?: number }).awayScore, '配信スコア away=70').toBe(70)

    // (3) ステータス遷移 → STATUS_CHANGED 受信
    const statusRes = await api.patch(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${matchId}/status`,
      { headers: authHeaders(recorderToken), data: { status: 'COMPLETED' } },
    )
    expect(statusRes.status(), `COMPLETED 遷移は 200。応答: ${await statusRes.text()}`).toBe(200)
    const statusMsg = await waitForMessage(spectator, (m) => m.type === 'STATUS_CHANGED')
    expect(statusMsg.status, '配信ステータス=COMPLETED').toBe('COMPLETED')

    // serverSeq は配信のたびに単調増加する（§J.2.1）。受信順に厳密増加であることを確認。
    const seqs = spectator.messages.map((m) => m.serverSeq)
    expect(seqs.length, '少なくとも 3 件配信を受信').toBeGreaterThanOrEqual(3)
    for (let i = 1; i < seqs.length; i++) {
      const cur = seqs[i]!
      const prev = seqs[i - 1]!
      expect(cur, `serverSeq 単調増加 (${prev} < ${cur})`).toBeGreaterThan(prev)
    }
  } finally {
    await spectator.close()
  }
})

// ===========================================================================
// WS-002【機微情報除外・§J.3.3】配信ペイロードに内部 ID / 所有チーム ID が載らない
// ===========================================================================
test('WS-002: 配信ペイロードに内部 userId / owning_team_id / recorded_by_team_id が含まれない', async () => {
  expect(matchId, 'WS-000 で作成済み').toBeTruthy()
  // 新規試合で SCORED まで（前テストで COMPLETED 済みのため別 match を一時作成して配信を観測）
  const create = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(recorderToken),
    data: { sport: 'BASKETBALL', kind: 'FRIENDLY', homeAway: 'HOME', opponentName: 'E2E機微検証', durationMinutes: 40 },
  })
  expect(create.status(), `機微検証用 試合作成は 201。応答: ${await create.text()}`).toBe(201)
  const tmpMatchId = (await create.json() as { data: { id: string } }).data.id

  const spectator = await connectStomp(recorderToken)
  try {
    await subscribeLive(spectator, tmpMatchId)
    // 選手名つきイベントを記録（playerName は公開可・player_user_id は載らないことを検証）
    const evRes = await api.post(`${BE_API}/organizations/${orgId}/matches/${tmpMatchId}/events`, {
      headers: authHeaders(recorderToken),
      data: { eventType: 'FIELD_GOAL_3', teamSide: 'HOME', period: 'QUARTER_1', minute: 2, playerName: 'E2E太郎' },
    })
    expect(evRes.status(), `イベント記録は 201。応答: ${await evRes.text()}`).toBe(201)
    const added = await waitForMessage(spectator, (m) => m.type === 'EVENT_ADDED')

    // 公開可能フィールドは載る
    expect((added.event as { playerName?: string }).playerName, '選手表示名は公開可').toBe('E2E太郎')

    // 機微情報が「どの階層にも」現れないことを JSON 文字列全走査で担保（二重防御）。
    const blob = JSON.stringify(added)
    const forbiddenKeys = [
      'playerUserId', 'player_user_id',
      'relatedPlayerUserId', 'related_player_user_id',
      'recordedByTeamId', 'recorded_by_team_id',
      'owningTeamId', 'owning_team_id',
      'userId', 'user_id',
    ]
    for (const key of forbiddenKeys) {
      expect(blob.includes(`"${key}"`), `機微キー ${key} が配信ペイロードに含まれない`).toBe(false)
    }
  } finally {
    await spectator.close()
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${tmpMatchId}`,
      { headers: authHeaders(recorderToken) },
    ).catch(() => {})
  }
})

// ===========================================================================
// WS-003【購読認可・§J.3・可視性あり】SystemAdmin は購読成功し受信できる
// ===========================================================================
test('WS-003: 可視性あり（e2e-admin / SystemAdmin）は購読成功し配信を受信できる', async () => {
  expect(matchId, 'WS-000 で作成済み').toBeTruthy()
  const create = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(recorderToken),
    data: { sport: 'BASKETBALL', kind: 'FRIENDLY', homeAway: 'HOME', opponentName: 'E2E認可OK', durationMinutes: 40 },
  })
  const okMatchId = (await create.json() as { data: { id: string } }).data.id

  const spectator = await connectStomp(adminToken)
  try {
    // 購読が拒否されないこと（resolve すれば認可通過）
    await expect(subscribeLive(spectator, okMatchId), 'SystemAdmin の購読は成功する').resolves.toBeUndefined()
    expect(spectator.stompErrors.length, '認可 ERROR フレームは無い').toBe(0)

    await api.post(`${BE_API}/organizations/${orgId}/matches/${okMatchId}/events`, {
      headers: authHeaders(recorderToken),
      data: { eventType: 'FIELD_GOAL_2', teamSide: 'AWAY', period: 'QUARTER_1', minute: 1 },
    })
    const added = await waitForMessage(spectator, (m) => m.type === 'EVENT_ADDED')
    expect(added.matchId, '可視性ありユーザーは配信を受信できる').toBe(okMatchId)
  } finally {
    await spectator.close()
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${okMatchId}`,
      { headers: authHeaders(recorderToken) },
    ).catch(() => {})
  }
})

// ===========================================================================
// WS-004【購読認可・§J.3・セキュリティ最重要】可視性なし（他テナント会員）は購読拒否される
// ===========================================================================
test('WS-004: 可視性なし（e2e-user・f0810 非所属の他テナント会員）の購読は ERROR フレームで拒否され、配信を一切受信しない', async () => {
  expect(matchId, 'WS-000 で作成済み').toBeTruthy()
  const create = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(recorderToken),
    data: { sport: 'BASKETBALL', kind: 'FRIENDLY', homeAway: 'HOME', opponentName: 'E2E認可NG', durationMinutes: 40 },
  })
  const denyMatchId = (await create.json() as { data: { id: string } }).data.id

  // outsider（e2e-user）は CONNECT 自体は成功する（フェイルオープン CONNECT・§J.3）。
  const outsider = await connectStomp(outsiderToken)
  try {
    // SUBSCRIBE は MatchLiveSubscriptionInterceptor が canView=false で MessagingException→ERROR フレームを返す。
    await expect(
      subscribeLive(outsider, denyMatchId),
      '他テナント会員の購読は拒否される（reject）',
    ).rejects.toThrow(/SUBSCRIBE 拒否|ERROR/)
    expect(outsider.stompErrors.length, '認可拒否の ERROR フレームを 1 件以上受領').toBeGreaterThanOrEqual(1)

    // 拒否後に記録しても、outsider は配信を一切受信しないこと（漏洩していない＝最重要）。
    await api.post(`${BE_API}/organizations/${orgId}/matches/${denyMatchId}/events`, {
      headers: authHeaders(recorderToken),
      data: { eventType: 'FIELD_GOAL_2', teamSide: 'HOME', period: 'QUARTER_1', minute: 4 },
    })
    await new Promise((r) => setTimeout(r, 2_000)) // 配信が来ないことの確認猶予
    expect(
      outsider.messages.length,
      '購読拒否された他テナント会員へは配信が漏れない（受信 0 件）',
    ).toBe(0)
  } finally {
    await outsider.close()
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${denyMatchId}`,
      { headers: authHeaders(recorderToken) },
    ).catch(() => {})
  }
})

// ===========================================================================
// WS-005【購読認可・§J.3・未認証】未認証接続は非 PUBLIC 試合を購読できない
// ===========================================================================
test('WS-005: 未認証接続は非 PUBLIC（メンバー限定）試合の購読を拒否される（漏洩なし）', async () => {
  expect(matchId, 'WS-000 で作成済み').toBeTruthy()
  const create = await api.post(`${BE_API}/organizations/${orgId}/teams/${teamId}/matches`, {
    headers: authHeaders(recorderToken),
    data: { sport: 'BASKETBALL', kind: 'FRIENDLY', homeAway: 'HOME', opponentName: 'E2E未認証', durationMinutes: 40 },
  })
  const anonMatchId = (await create.json() as { data: { id: string } }).data.id

  // token=null＝Authorization ヘッダー無し（匿名 CONNECT は許可される＝フェイルオープン CONNECT）。
  const anon = await connectStomp(null)
  try {
    await expect(
      subscribeLive(anon, anonMatchId),
      '未認証はメンバー限定試合を購読できない（canView(null)=fail-closed）',
    ).rejects.toThrow(/SUBSCRIBE 拒否|ERROR/)
    expect(anon.stompErrors.length, '未認証拒否の ERROR フレーム').toBeGreaterThanOrEqual(1)

    await api.post(`${BE_API}/organizations/${orgId}/matches/${anonMatchId}/events`, {
      headers: authHeaders(recorderToken),
      data: { eventType: 'FIELD_GOAL_3', teamSide: 'HOME', period: 'QUARTER_1', minute: 6 },
    })
    await new Promise((r) => setTimeout(r, 2_000))
    expect(anon.messages.length, '未認証へは配信が漏れない（受信 0 件）').toBe(0)
  } finally {
    await anon.close()
    await api.delete(
      `${BE_API}/organizations/${orgId}/teams/${teamId}/matches/${anonMatchId}`,
      { headers: authHeaders(recorderToken) },
    ).catch(() => {})
  }
})
