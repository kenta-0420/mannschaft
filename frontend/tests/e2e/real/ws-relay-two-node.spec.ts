/**
 * WebSocket 外部ブローカー化 §7.4【2ノード relay・クロスノード配信】実機 E2E。
 *
 * 設計書: docs/architecture/websocket_external_broker_valkey.md §4（relay 設計）/ §7.4（ローカル2ノード
 * 再現手順）/ §7.4.2（ノード識別の観測手段）/ AC-1・AC-6。
 *
 * 【前提構成（本 spec は通常の検証用ポート規約とは別枠）】
 *   このテストは CLAUDE.md の「常駐サーバーのポート規約」の通常の検証用 1 ノード（BE 8081）とは別に、
 *   relay 検証専用の 2 ノードを直結で用意する必要がある:
 *     - NODE_A_URL（既定 http://localhost:8081）・NODE_B_URL（既定 http://localhost:8082）の
 *       2 プロセスを起動する（例: `./gradlew bootRun --args='--server.port=8081'` と `...8082`）。
 *     - 両ノードとも `MANNSCHAFT_WEBSOCKET_RELAY_ENABLED=true` を設定し、**同一の Valkey**
 *       （docker-compose の 6379）に接続すること（application.yml 既定は OFF）。
 *     - FE には依存しない（API + Node 側 STOMP クライアントで完結）。
 *
 *   環境が用意されていない場合は NODE_A_URL への疎通チェックで自動的に test.skip する
 *   （real 系は CI/スモーク対象外の運用・`project_real_admin_e2e_excluded_from_ci_smoke` のとおり、
 *   本番相当の2ノード環境がある時にのみ殿が手動実行する想定）。
 *
 * 【検証内容】
 *   1. 両ノードの /actuator/info の nodeId が異なること（別ノードであることの裏取り・§7.4.2）。
 *   2. ノード A の /ws に STOMP 接続し `/topic/channels/{channelId}` を購読 →
 *      ノード B の REST API 経由でメッセージ送信 → ノード A 接続側が受信する（クロスノード配信・AC-1）。
 *   3. 猶予期間内に同一メッセージの二重配信が発生しないこと（ループ防止の三重防御・AC-6）。
 *
 * 【使い捨てデータ】admin(e2e-admin) が使い捨てチーム＋チャンネルを新規作成し、afterAll で削除する。
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'
import { Client, type StompHeaders, type IMessage, type IFrame } from '@stomp/stompjs'
import WS from 'ws'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000)

const NODE_A = process.env.NODE_A_URL ?? 'http://localhost:8081'
const NODE_B = process.env.NODE_B_URL ?? 'http://localhost:8082'
// SockJS raw-websocket transport（f0810-live-spectator-ws.spec.ts で確認済みの Node 側接続パターン）。
const NODE_A_WS = `${NODE_A.replace(/^http/, 'ws')}/ws/websocket`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

// ── テスト状態 ──────────────────────────────────────────────────
let api: APIRequestContext
let adminToken: string
let channelId: number
/** 環境（2ノード稼働・relay ON 前提）が揃っているか。揃っていなければ各テストで skip する。 */
let envReady = false
let skipReason = ''

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

/** /actuator/health で疎通確認する（短いタイムアウトで即断）。 */
async function isReachable(origin: string): Promise<boolean> {
  try {
    const res = await api.get(`${origin}/actuator/health`, { timeout: 3_000 })
    if (!res.ok()) return false
    const body = await res.json() as { status?: string }
    return body.status === 'UP'
  } catch {
    return false
  }
}

/** /actuator/info の nodeId を取得する（§7.4.2・WebSocketNodeIdProvider が公開する値）。 */
async function fetchNodeId(origin: string): Promise<string | null> {
  try {
    const res = await api.get(`${origin}/actuator/info`, { timeout: 3_000 })
    if (!res.ok()) return null
    const body = await res.json() as { nodeId?: string }
    return body.nodeId ?? null
  } catch {
    return null
  }
}

// ── STOMP ヘルパー（f0810-live-spectator-ws.spec.ts の connectStomp/subscribeLive と同一作法） ──
type RelaySession = {
  client: Client
  messages: string[]
  close: () => Promise<void>
}

function connectStomp(wsUrl: string, token: string): Promise<RelaySession> {
  const messages: string[] = []
  const connectHeaders: StompHeaders = { Authorization: `Bearer ${token}` }

  const client = new Client({
    webSocketFactory: () => new WS(wsUrl) as unknown as WebSocket,
    connectHeaders,
    reconnectDelay: 0,
    heartbeatIncoming: 0,
    heartbeatOutgoing: 0,
  })

  const session: RelaySession = {
    client,
    messages,
    close: () =>
      new Promise<void>((resolve) => {
        try {
          client.onDisconnect = () => resolve()
          void client.deactivate()
          setTimeout(resolve, 1_500)
        } catch {
          resolve()
        }
      }),
  }

  return new Promise<RelaySession>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('STOMP CONNECT タイムアウト（10s）')), 10_000)
    client.onConnect = () => {
      clearTimeout(timer)
      resolve(session)
    }
    client.onStompError = (frame: IFrame) => {
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

function subscribeChannel(session: RelaySession, channelId: number): void {
  session.client.subscribe(
    `/topic/channels/${channelId}`,
    (msg: IMessage) => {
      session.messages.push(msg.body)
    },
    { id: `sub-relay-${Math.random().toString(36).slice(2)}` },
  )
}

async function waitForMessages(session: RelaySession, minCount: number, timeoutMs = 12_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (session.messages.length >= minCount) return
    await new Promise((r) => setTimeout(r, 200))
  }
  throw new Error(
    `期待するメッセージ数 ${minCount} 件が ${timeoutMs}ms 以内に届かなかった（受信済み: ${session.messages.length}件）`,
  )
}

// ── beforeAll / afterAll ────────────────────────────────────────
test.beforeAll(async () => {
  api = await pwRequest.newContext()

  const nodeAUp = await isReachable(NODE_A)
  if (!nodeAUp) {
    skipReason = `NODE_A_URL(${NODE_A}) に疎通できないため2ノードrelayテストをskipします。` +
      '本テストは通常の検証用ポート規約(BE 8081単体)とは別に、8081/8082の2ノードをrelay ON+同一Valkeyで' +
      '起動している場合のみ実行対象です。'
    console.warn(`[ws-relay-two-node] SKIP: ${skipReason}`)
    return
  }
  const nodeBUp = await isReachable(NODE_B)
  if (!nodeBUp) {
    skipReason = `NODE_B_URL(${NODE_B}) に疎通できないため2ノードrelayテストをskipします。`
    console.warn(`[ws-relay-two-node] SKIP: ${skipReason}`)
    return
  }

  const nodeIdA = await fetchNodeId(NODE_A)
  const nodeIdB = await fetchNodeId(NODE_B)
  if (!nodeIdA || !nodeIdB) {
    skipReason = 'nodeId を /actuator/info から取得できないためskipします。'
    console.warn(`[ws-relay-two-node] SKIP: ${skipReason}`)
    return
  }
  if (nodeIdA === nodeIdB) {
    skipReason = `両ノードのnodeIdが同一(${nodeIdA})。別ノードとして構成されていないためskipします。`
    console.warn(`[ws-relay-two-node] SKIP: ${skipReason}`)
    return
  }
  console.warn(`[ws-relay-two-node] 2ノード確認OK: nodeIdA=${nodeIdA}, nodeIdB=${nodeIdB}`)

  // ログイン + 使い捨てチーム/チャンネル作成（ノードAのAPI経由。同一DBのためどちらのノード経由でも良い）。
  const loginRes = await api.post(`${NODE_A}/api/v1/auth/login`, {
    data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
  })
  if (!loginRes.ok()) {
    skipReason = `admin ログインに失敗(${loginRes.status()})したためskipします。`
    console.warn(`[ws-relay-two-node] SKIP: ${skipReason}`)
    return
  }
  adminToken = (await loginRes.json() as { data: { accessToken: string } }).data.accessToken

  const createTeam = await api.post(`${NODE_A}/api/v1/teams`, {
    headers: authHeaders(adminToken),
    data: { name: `E2E-WSRelay-${Date.now()}`, template: 'SPORTS', visibility: 'PUBLIC' },
  })
  expect(createTeam.status(), `使い捨てチーム作成は 201。応答: ${await createTeam.text()}`).toBe(201)
  const teamSlug = (await createTeam.json() as { data: { slug: string } }).data.slug

  const myTeamsRes = await api.get(`${NODE_A}/api/v1/me/teams?limit=200`, { headers: authHeaders(adminToken) })
  const myTeams = (await myTeamsRes.json() as { data: Array<{ id: number; slug: string }> }).data
  const teamId = myTeams.find((t) => t.slug === teamSlug)?.id
  expect(teamId, '作成した使い捨てチームが /me/teams に存在する').toBeTruthy()

  const createChannel = await api.post(`${NODE_A}/api/v1/chat/channels`, {
    headers: authHeaders(adminToken),
    data: { channelType: 'TEAM_PUBLIC', teamId, name: `E2E-Relay-${Date.now()}` },
  })
  expect(createChannel.status(), `使い捨てチャンネル作成は 201。応答: ${await createChannel.text()}`).toBe(201)
  channelId = (await createChannel.json() as { data: { id: number } }).data.id

  envReady = true
})

test.afterAll(async () => {
  if (envReady && channelId) {
    await api.delete(`${NODE_A}/api/v1/chat/channels/${channelId}`, { headers: authHeaders(adminToken) }).catch(() => {})
  }
  await api?.dispose()
})

// ===========================================================================
// AC-1 / AC-6: クロスノード配信（ノードBの配信がノードA接続側に到達）＋ 二重配信なし
// ===========================================================================
test('ノードBの配信がノードA接続側にクロスノードで到達し、二重配信が発生しない', async () => {
  test.skip(!envReady, skipReason || '2ノード環境が未整備のためskip')

  // ノードAの/wsにSTOMP接続し、当該チャンネルを購読する。
  const session = await connectStomp(NODE_A_WS, adminToken)
  try {
    subscribeChannel(session, channelId)
    // SimpleBrokerはSUBSCRIBEにRECEIPTを返さないため、購読確立の猶予を置く。
    await new Promise((r) => setTimeout(r, 1_000))

    // ノードBのREST API経由でメッセージを送信する（relay ON前提: B→Valkey→Aへfan-out）。
    const uniqueBody = `E2E-Relay-CrossNode-${Date.now()}`
    const sendRes = await api.post(`${NODE_B}/api/v1/chat/channels/${channelId}/messages`, {
      headers: authHeaders(adminToken),
      data: { body: uniqueBody },
    })
    expect(sendRes.status(), `ノードB経由のメッセージ送信は201。応答: ${await sendRes.text()}`).toBe(201)

    // ノードA接続側が受信するまで待つ（クロスノード配信・AC-1の核心）。
    await waitForMessages(session, 1)
    const matched = session.messages.filter((body) => body.includes(uniqueBody))
    expect(matched.length, 'クロスノード配信されたメッセージを受信する').toBeGreaterThanOrEqual(1)

    // 二重配信なし（ループ防止の三重防御・AC-6）: 猶予を置いても受信数が増えないこと。
    await new Promise((r) => setTimeout(r, 3_000))
    const finalMatched = session.messages.filter((body) => body.includes(uniqueBody))
    expect(finalMatched.length, '同一メッセージの二重配信が発生しない（受信件数=1）').toBe(1)
  } finally {
    await session.close()
  }
})
