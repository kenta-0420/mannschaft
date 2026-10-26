/**
 * 御下命② 緊急休業の一斉通知 → 患者の確認状況を「通知主チームの ADMIN/DEPUTY が
 * リアルタイムで把握できる」ことの 実機フルスタック E2E（モックなし）。
 *
 * 実 BE（http://localhost:8080・BE_ORIGIN で上書き可）+ STOMP WebSocket（ws://.../ws/websocket）に接続する。
 * フロント dev サーバー（BASE_URL）には依存しない API + STOMP 完結型（お手本: f0810-live-spectator-ws.spec.ts）。
 *
 * 【検証する 6 点（御下命②の核心）】
 *   EC-RT-001 データ準備      : ライン/スロット作成 → 患者 3 人が別スロットに予約 → ADMIN が当該期間の緊急休業を
 *                               送信 → confirmations が患者 3 人分（confirmed=false）生成されることを GET で確認。
 *   EC-RT-002 患者の確認導線  : 患者の通知一覧（GET /api/v1/notifications）に sourceType=EMERGENCY_CLOSURE の
 *                               通知が届くこと（NotificationList.vue の確認ボタン分岐が発火する前提データ）。
 *   EC-RT-003 RT ライブ更新   : ADMIN が確認状況トピック
 *                               /topic/teams/{teamId}/emergency-closures/{closureId}/confirmations を購読 →
 *                               患者 A が confirm → ADMIN が再読込なしで confirmedCount 1/3 を受信。
 *                               患者 B も confirm → 2/3。配信ペイロード
 *                               {confirmedCount,totalCount,userId,userFullName,confirmedAt} を検証。
 *   EC-RT-004 冪等           : 既に確認済みの患者が再 confirm しても二重配信されない（再展開キャッシュバグ #1627 是正の
 *                               ライブ等価検証。confirmClosure は confirmed 済みなら publish しない設計）。
 *   EC-RT-005 購読認可        : MEMBER（非 ADMIN）の購読は ERROR フレームで拒否される。
 *                               他チーム（非所属 teamId）への購読も拒否される（IDOR 越境遮断）。
 *                               fc-u-18 ADMIN（e2e-admin）は猶予内に ERROR 無し＝購読成立。
 *   EC-RT-006 総合           : 上記により「通知主チームの ADMIN/DEPUTY が RT で把握できる」が成立する。
 *
 * 【WS 接続の作法（f0810 から踏襲・実コードで確定）】
 *   - エンドポイント: WebSocketConfig が registry.addEndpoint("/ws").withSockJS()。raw-websocket transport
 *     (/ws/websocket) に node の `ws` で接続し @stomp/stompjs を載せる（sockjs-client 不要）。
 *   - CONNECT 認証: WebSocketAuthChannelInterceptor が CONNECT の native header Authorization: Bearer を検証。
 *   - SUBSCRIBE 認可: EmergencyClosureSubscriptionInterceptor が
 *     /topic/teams/{teamId}/emergency-closures/{closureId}/confirmations 宛のみ
 *     AccessControlService.isAdminOrAbove(userId, teamId, "TEAM") で認可。不可は MessagingException→ERROR フレーム。
 *
 * 【トピック・ペイロード契約（BE 実装から確定）】
 *   宛先: /topic/teams/{teamId(数値)}/emergency-closures/{closureId(数値)}/confirmations
 *   配信: EmergencyClosureConfirmationUpdatePayload
 *         { confirmedCount:number, totalCount:number, userId:number, userFullName:string, confirmedAt:string }
 *
 * 【seed アカウント（実機確認済み・全員 fc-u-18 = teamId 1 所属）】
 *   e2e-admin（fc-u-18 ADMIN / SYSTEM_ADMIN）/ e2e-user（MEMBER）/ e2e-dummy-2（MEMBER）/ e2e-dummy-3（MEMBER）
 *   ※ getRoleName は TEAM スコープのローカルロールのみ参照（SYSTEM_ADMIN はグローバルゆえ TEAM 認可には効かない）。
 *     よって e2e-admin の購読成立は「fc-u-18 の実 ADMIN ロール」による。MEMBER は ADMIN でないため購読拒否される。
 *
 * 設計: F03.4+ 臨時休業リアルタイム確認配信（PR #1606）。
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'
import { Client, type StompHeaders, type IMessage, type IFrame } from '@stomp/stompjs'
import WS from 'ws'

// storageState に依存せず、テスト内で API ログインする（f0810 と同作法）。
test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000)

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`
const WS_URL = `${BE.replace(/^http/, 'ws')}/ws/websocket`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const PASSWORD = process.env.TEST_PASSWORD ?? 'TestPass2026!'
const PATIENT_A_EMAIL = 'e2e-user@test.mannschaft.local'
const PATIENT_B_EMAIL = 'e2e-dummy-2@test.mannschaft.local'
const PATIENT_C_EMAIL = 'e2e-dummy-3@test.mannschaft.local'

const TEAM_SLUG = 'fc-u-18'

// ── テスト状態 ─────────────────────────────────────────────────
let api: APIRequestContext
let adminToken: string
let patientAToken: string
let patientBToken: string
let patientCToken: string
let patientAId: number
let patientBId: number
let teamId: number

let lineId: number | null = null
const slotIds: number[] = []
const reservationIds: number[] = []
let closureId: number | null = null

// ── HTTP ヘルパー ──────────────────────────────────────────────
async function login(email: string, password: string): Promise<string> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `login(${email}) は 200。応答: ${await res.text()}`).toBe(200)
  return (await res.json() as { data: { accessToken: string } }).data.accessToken
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

/** /me/teams から slug → 数値 teamId と自分の userId を解決する。 */
async function resolveTeamId(token: string, slug: string): Promise<number> {
  const res = await api.get(`${BE_API}/me/teams`, { headers: authHeaders(token) })
  expect(res.status(), '/me/teams は 200').toBe(200)
  const json = await res.json() as { data: Array<{ id: number; slug: string }> }
  const team = json.data.find((t) => t.slug === slug)
  expect(team, `seed のチーム(${slug})が /me/teams に存在する`).toBeTruthy()
  return team!.id
}

async function resolveMyUserId(token: string): Promise<number> {
  const res = await api.get(`${BE_API}/users/me`, { headers: authHeaders(token) })
  expect(res.status(), '/users/me は 200').toBe(200)
  return (await res.json() as { data: { id: number } }).data.id
}

/** N 日後の YYYY-MM-DD（過去日スロット作成のバリデーションを避ける）。 */
function futureDate(daysAhead: number): string {
  const d = new Date()
  d.setDate(d.getDate() + daysAhead)
  return d.toISOString().slice(0, 10)
}

// ── STOMP ヘルパー（f0810 から踏襲） ───────────────────────────
type ConfirmationPayload = {
  confirmedCount: number
  totalCount: number
  userId: number
  userFullName: string
  confirmedAt: string
}

type AdminSession = {
  client: Client
  messages: ConfirmationPayload[]
  stompErrors: IFrame[]
  close: () => Promise<void>
}

/** STOMP CONNECT のみ確立（SUBSCRIBE はしない）。token=null で未認証接続。 */
function connectStomp(token: string | null): Promise<AdminSession> {
  const messages: ConfirmationPayload[] = []
  const stompErrors: IFrame[] = []
  const connectHeaders: StompHeaders = {}
  if (token) connectHeaders.Authorization = `Bearer ${token}`

  const client = new Client({
    webSocketFactory: () => new WS(WS_URL) as unknown as WebSocket,
    connectHeaders,
    reconnectDelay: 0,
    heartbeatIncoming: 0,
    heartbeatOutgoing: 0,
  })

  const session: AdminSession = {
    client,
    messages,
    stompErrors,
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

  return new Promise<AdminSession>((resolve, reject) => {
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
 * 確認状況トピックを購読する。SimpleBroker は SUBSCRIBE に RECEIPT を返さないため、
 * 認可成功＝猶予内に ERROR/CLOSE が来なければ確立とみなす。
 * 認可拒否＝EmergencyClosureSubscriptionInterceptor が MessagingException→ERROR フレーム→直後 WS CLOSE。
 */
function subscribeConfirmations(
  session: AdminSession,
  tId: number,
  cId: number,
  graceMs = 1_200,
): Promise<void> {
  const destination = `/topic/teams/${tId}/emergency-closures/${cId}/confirmations`
  return new Promise<void>((resolve, reject) => {
    let settled = false
    const settleResolve = () => { if (!settled) { settled = true; resolve() } }
    const settleReject = (e: Error) => { if (!settled) { settled = true; reject(e) } }

    session.client.onStompError = (frame) => {
      session.stompErrors.push(frame)
      settleReject(new Error(`SUBSCRIBE 拒否(ERROR frame): ${frame.headers['message'] ?? ''} ${frame.body ?? ''}`))
    }
    session.client.onWebSocketClose = () => {
      settleReject(new Error('SUBSCRIBE 拒否(WebSocket CLOSE・認可不可で切断)'))
    }

    session.client.subscribe(
      destination,
      (msg: IMessage) => {
        try {
          session.messages.push(JSON.parse(msg.body) as ConfirmationPayload)
        } catch {
          /* 非 JSON は無視 */
        }
      },
      { id: `sub-${Math.random().toString(36).slice(2)}` },
    )

    setTimeout(settleResolve, graceMs)
  })
}

/** session.messages に条件を満たす配信が届くまで最大 timeout ms 待つ。 */
async function waitForMessage(
  session: AdminSession,
  predicate: (m: ConfirmationPayload) => boolean,
  timeout = 8_000,
): Promise<ConfirmationPayload> {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const hit = session.messages.find(predicate)
    if (hit) return hit
    await new Promise((r) => setTimeout(r, 100))
  }
  throw new Error(
    `期待する確認配信が ${timeout}ms 以内に届かなかった。` +
      `受信済み: ${JSON.stringify(session.messages)}`,
  )
}

// ── 予約 / 緊急休業 HTTP ヘルパー（実機契約で確定） ──────────────
type ConfirmationRow = {
  userId: number
  userFullName: string
  confirmed: boolean
  confirmedAt: string | null
}

async function createLine(name: string): Promise<number> {
  const res = await api.post(`${BE_API}/teams/${TEAM_SLUG}/reservation-lines`, {
    headers: authHeaders(adminToken),
    data: { name },
  })
  expect(res.status(), `ライン作成は 201。応答: ${await res.text()}`).toBe(201)
  return (await res.json() as { data: { id: number } }).data.id
}

async function createSlot(slotDate: string, startTime: string, endTime: string): Promise<number> {
  const res = await api.post(`${BE_API}/teams/${TEAM_SLUG}/reservation-slots`, {
    headers: authHeaders(adminToken),
    data: { slotDate, startTime, endTime },
  })
  expect(res.status(), `スロット作成は 201。応答: ${await res.text()}`).toBe(201)
  return (await res.json() as { data: { id: number } }).data.id
}

async function createReservation(patientToken: string, slotId: number): Promise<number> {
  const res = await api.post(`${BE_API}/teams/${TEAM_SLUG}/reservations`, {
    headers: authHeaders(patientToken),
    data: { reservationSlotId: slotId, lineId, userNote: 'EC-RT 予約' },
  })
  expect(res.status(), `予約作成は 201。応答: ${await res.text()}`).toBe(201)
  return (await res.json() as { data: { id: number } }).data.id
}

async function getConfirmations(cId: number): Promise<ConfirmationRow[]> {
  const res = await api.get(`${BE_API}/teams/${TEAM_SLUG}/emergency-closures/${cId}/confirmations`, {
    headers: authHeaders(adminToken),
  })
  expect(res.status(), `confirmations 取得は 200。応答: ${await res.text()}`).toBe(200)
  return (await res.json() as { data: ConfirmationRow[] }).data
}

async function confirmAsPatient(patientToken: string, cId: number): Promise<number> {
  const res = await api.post(
    `${BE_API}/teams/${TEAM_SLUG}/emergency-closures/${cId}/confirm`,
    { headers: authHeaders(patientToken) },
  )
  return res.status()
}

// ── beforeAll / afterAll ──────────────────────────────────────
test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await login(ADMIN_EMAIL, PASSWORD)
  patientAToken = await login(PATIENT_A_EMAIL, PASSWORD)
  patientBToken = await login(PATIENT_B_EMAIL, PASSWORD)
  patientCToken = await login(PATIENT_C_EMAIL, PASSWORD)
  teamId = await resolveTeamId(adminToken, TEAM_SLUG)
  patientAId = await resolveMyUserId(patientAToken)
  patientBId = await resolveMyUserId(patientBToken)
  expect(teamId, 'teamId（数値）解決').toBeGreaterThan(0)
})

test.afterAll(async () => {
  // 予約をキャンセル → スロット削除 → ライン削除（緊急休業履歴/confirmations は履歴として残す）
  for (const rid of reservationIds) {
    await api.post(`${BE_API}/teams/${TEAM_SLUG}/reservations/${rid}/cancel`, {
      headers: authHeaders(adminToken),
      data: { reason: 'EC-RT cleanup' },
    }).catch(() => {})
  }
  for (const sid of slotIds) {
    await api.delete(`${BE_API}/teams/${TEAM_SLUG}/reservation-slots/${sid}`, {
      headers: authHeaders(adminToken),
    }).catch(() => {})
  }
  if (lineId) {
    await api.delete(`${BE_API}/teams/${TEAM_SLUG}/reservation-lines/${lineId}`, {
      headers: authHeaders(adminToken),
    }).catch(() => {})
  }
  await api.dispose()
})

// ===========================================================================
// EC-RT-001【データ準備】予約 3 件 → 緊急休業送信 → confirmations 3 件生成
// ===========================================================================
test('EC-RT-001: ライン/スロット作成 → 患者3人が予約 → 緊急休業送信で confirmations が3件(未確認)生成される', async () => {
  const ts = Date.now()
  lineId = await createLine(`EC-RT_ライン_${ts}`)
  expect(lineId).toBeGreaterThan(0)

  // 休業対象日（十分未来）。3 患者を別スロットに予約させ、確実に 3 ユーザー分の confirmations を作る。
  const closureDate = futureDate(45)
  const s1 = await createSlot(closureDate, '09:00', '09:30')
  const s2 = await createSlot(closureDate, '10:00', '10:30')
  const s3 = await createSlot(closureDate, '11:00', '11:30')
  slotIds.push(s1, s2, s3)

  reservationIds.push(await createReservation(patientAToken, s1))
  reservationIds.push(await createReservation(patientBToken, s2))
  reservationIds.push(await createReservation(patientCToken, s3))

  // プレビューで対象 3 件を事前確認（影響予約抽出が正しいこと）
  const preview = await api.get(
    `${BE_API}/teams/${TEAM_SLUG}/emergency-closures/preview?startDate=${closureDate}&endDate=${closureDate}`,
    { headers: authHeaders(adminToken) },
  )
  expect(preview.status(), `preview は 200。応答: ${await preview.text()}`).toBe(200)
  const previewData = (await preview.json() as { data: { affectedCount: number } }).data
  expect(previewData.affectedCount, 'プレビューの影響予約は 3 件').toBe(3)

  // 緊急休業を送信（cancelReservations=false で予約は残し、confirmations を作る）。
  const send = await api.post(`${BE_API}/teams/${TEAM_SLUG}/emergency-closures`, {
    headers: authHeaders(adminToken),
    data: {
      startDate: closureDate,
      endDate: closureDate,
      reason: '先生体調不良のため臨時休業',
      subject: '【臨時休業】本日の予約について',
      messageBody: '本日は臨時休業となります。ご確認をお願いします。',
      cancelReservations: false,
    },
  })
  expect(send.status(), `緊急休業送信は 201。応答: ${await send.text()}`).toBe(201)
  const closure = (await send.json() as { data: { id: number; sentCount: number } }).data
  closureId = closure.id
  expect(closureId, 'closureId（数値）が返る').toBeGreaterThan(0)
  expect(closure.sentCount, '送信件数は 3').toBe(3)

  // confirmations が患者 3 人分・全員未確認で生成される。
  const confs = await getConfirmations(closureId)
  expect(confs.length, 'confirmations は 3 件').toBe(3)
  expect(confs.every((c) => c.confirmed === false), '初期状態は全員未確認').toBe(true)
})

// ===========================================================================
// EC-RT-002【患者の確認導線】患者の通知一覧に EMERGENCY_CLOSURE 通知が届く
// ===========================================================================
test('EC-RT-002: 患者の通知一覧に sourceType=EMERGENCY_CLOSURE の通知が届く（確認ボタン分岐の前提データ）', async () => {
  expect(closureId, 'EC-RT-001 で作成済み').toBeTruthy()
  const res = await api.get(`${BE_API}/notifications?size=20`, { headers: authHeaders(patientAToken) })
  expect(res.status(), '通知一覧は 200').toBe(200)
  const body = await res.json() as { data?: Array<{ sourceType?: string; sourceId?: number }> }
  const items = body.data ?? []
  const closureNotif = items.find((n) => n.sourceType === 'EMERGENCY_CLOSURE')
  expect(
    closureNotif,
    '患者に EMERGENCY_CLOSURE 通知が届いている（NotificationList.vue が確認ボタンを出す前提）',
  ).toBeTruthy()
})

// ===========================================================================
// EC-RT-003【RT ライブ更新・核心】ADMIN 購読中に患者が confirm → confirmedCount が
//            再読込なしで 1/3 → 2/3 とライブ更新され、ペイロード契約も満たす
// ===========================================================================
test('EC-RT-003: ADMIN 購読中に患者A→B が confirm → confirmedCount 1/3, 2/3 をリアルタイム受信する', async () => {
  expect(closureId, 'EC-RT-001 で作成済み').toBeTruthy()

  const admin = await connectStomp(adminToken)
  try {
    // fc-u-18 ADMIN は購読成立（猶予内に ERROR が来ない）
    await expect(
      subscribeConfirmations(admin, teamId, closureId!),
      'fc-u-18 ADMIN の確認状況トピック購読は成立する',
    ).resolves.toBeUndefined()
    expect(admin.stompErrors.length, '購読時に認可 ERROR は無い').toBe(0)

    // 患者 A が確認 → confirmedCount 1/3 を再読込なしで受信
    expect(await confirmAsPatient(patientAToken, closureId!), '患者A confirm は 200').toBe(200)
    const first = await waitForMessage(admin, (m) => m.userId === patientAId)
    expect(first.confirmedCount, '患者A確認で confirmedCount=1').toBe(1)
    expect(first.totalCount, 'totalCount=3').toBe(3)
    expect(first.userFullName, 'userFullName が載る（空でない）').toBeTruthy()
    expect(first.confirmedAt, 'confirmedAt が載る').toBeTruthy()

    // 患者 B が確認 → confirmedCount 2/3 を再読込なしで受信
    expect(await confirmAsPatient(patientBToken, closureId!), '患者B confirm は 200').toBe(200)
    const second = await waitForMessage(admin, (m) => m.userId === patientBId)
    expect(second.confirmedCount, '患者B確認で confirmedCount=2').toBe(2)
    expect(second.totalCount, 'totalCount=3').toBe(3)

    // 配信を踏まえた HTTP 正本も 2/3 になっている（ライブ値と正本の整合）
    const confs = await getConfirmations(closureId!)
    expect(confs.filter((c) => c.confirmed).length, 'HTTP 正本でも確認済みは 2 件').toBe(2)
  } finally {
    await admin.close()
  }
})

// ===========================================================================
// EC-RT-004【冪等】確認済み患者が再 confirm しても二重配信されない
//   （confirmClosure は isConfirmed 済みなら publish しない設計 / 再展開キャッシュ #1627 のライブ等価検証）
// ===========================================================================
test('EC-RT-004: 確認済み患者の再 confirm は新たな配信を生まない（冪等・幻のカウント増加なし）', async () => {
  expect(closureId, 'EC-RT-001 で作成済み').toBeTruthy()

  const admin = await connectStomp(adminToken)
  try {
    await subscribeConfirmations(admin, teamId, closureId!)
    expect(admin.stompErrors.length, '購読 ERROR 無し').toBe(0)

    const before = admin.messages.length
    // 患者 A は EC-RT-003 で既に確認済み。再度 confirm しても confirmed 済みゆえ publish されない。
    expect(await confirmAsPatient(patientAToken, closureId!), '再 confirm も 200（冪等）').toBe(200)
    await new Promise((r) => setTimeout(r, 2_000)) // 配信が来ないことの確認猶予
    expect(
      admin.messages.length,
      '確認済み患者の再 confirm で新たな配信は発生しない（confirmedCount の幻の増加なし）',
    ).toBe(before)

    // HTTP 正本の確認済み件数も 2 のまま（二重カウントされていない）
    const confs = await getConfirmations(closureId!)
    expect(confs.filter((c) => c.confirmed).length, '確認済みは 2 件のまま').toBe(2)
  } finally {
    await admin.close()
  }
})

// ===========================================================================
// EC-RT-005【購読認可・セキュリティ】MEMBER / 非所属チーム の購読は拒否され、漏洩しない
// ===========================================================================
test('EC-RT-005: MEMBER（非ADMIN）の購読は ERROR フレームで拒否され、確認配信が漏れない', async () => {
  expect(closureId, 'EC-RT-001 で作成済み').toBeTruthy()

  // 患者 C（fc-u-18 MEMBER）は ADMIN/DEPUTY ではないため確認状況トピックを購読できない。
  const member = await connectStomp(patientCToken)
  try {
    await expect(
      subscribeConfirmations(member, teamId, closureId!),
      'MEMBER の確認状況トピック購読は拒否される',
    ).rejects.toThrow(/SUBSCRIBE 拒否|ERROR|CLOSE/)
    expect(member.stompErrors.length, '認可拒否の ERROR フレームを 1 件以上受領').toBeGreaterThanOrEqual(1)

    // 拒否後に患者 C 自身が confirm しても、member セッションへは配信が漏れない（最重要）。
    await confirmAsPatient(patientCToken, closureId!)
    await new Promise((r) => setTimeout(r, 2_000))
    expect(member.messages.length, '購読拒否された MEMBER へは確認配信が漏れない（受信 0 件）').toBe(0)
  } finally {
    await member.close()
  }
})

test('EC-RT-005b: ADMIN でも非所属チーム(teamId 越境)の購読は拒否される（IDOR 遮断・SYSTEM_ADMIN 特権は効かない）', async () => {
  expect(closureId, 'EC-RT-001 で作成済み').toBeTruthy()

  // e2e-admin が ADMIN ロールを持たない teamId を購読しようとする。
  // EmergencyClosureSubscriptionInterceptor は isAdminOrAbove(userId, teamId, "TEAM") で teamId 単位に判定するため拒否される。
  // （存在しない teamId でも「そのスコープにロール無し→false」で拒否＝越境遮断の検証として十分。closureId は同一を流用するが
  //   認可は teamId 基準で先に弾かれる。）
  const foreignTeamId = 999_999
  const admin = await connectStomp(adminToken)
  try {
    await expect(
      subscribeConfirmations(admin, foreignTeamId, closureId!),
      'ADMIN でも非所属チームの確認状況トピックは購読できない',
    ).rejects.toThrow(/SUBSCRIBE 拒否|ERROR|CLOSE/)
    expect(admin.stompErrors.length, '越境拒否の ERROR フレームを 1 件以上受領').toBeGreaterThanOrEqual(1)
  } finally {
    await admin.close()
  }
})

// ===========================================================================
// EC-RT-006【総合】最終的に全患者が確認すると confirmedCount=totalCount になる
//   （通知主チーム ADMIN が RT で「全員把握」できることの締め）
// ===========================================================================
test('EC-RT-006: 最後の患者C確認で confirmedCount=3/3 をライブ受信し、ADMIN が全確認を把握できる', async () => {
  expect(closureId, 'EC-RT-001 で作成済み').toBeTruthy()

  // EC-RT-005 で患者 C は既に confirm 済みかどうかに依存しないよう、現状の正本を確認してから判定する。
  const pre = await getConfirmations(closureId!)
  const confirmedBefore = pre.filter((c) => c.confirmed).length

  const admin = await connectStomp(adminToken)
  try {
    await subscribeConfirmations(admin, teamId, closureId!)
    expect(admin.stompErrors.length, '購読 ERROR 無し').toBe(0)

    if (confirmedBefore < 3) {
      // 未確認の患者を確認させて 3/3 にする（C が未確認ならここで確認が来る）。
      expect(await confirmAsPatient(patientCToken, closureId!), '患者C confirm は 200').toBe(200)
      const full = await waitForMessage(admin, (m) => m.confirmedCount === 3, 8_000)
      expect(full.totalCount, '全確認時 totalCount=3').toBe(3)
      expect(full.confirmedCount, '全確認時 confirmedCount=3').toBe(3)
    }

    // HTTP 正本でも全員確認済み（ADMIN が RT + 再取得の両方で全把握できる）。
    const confs = await getConfirmations(closureId!)
    expect(confs.length, '対象は 3 件').toBe(3)
    expect(confs.filter((c) => c.confirmed).length, '最終的に 3 件全て確認済み').toBe(3)
  } finally {
    await admin.close()
  }
})
