/**
 * WebSocket 外部ブローカー化 AC-11【チャット SUBSCRIBE 認可・実機】E2E（単一ノード・モックなし）。
 *
 * 設計書: docs/architecture/websocket_external_broker_valkey.md §2.6 / AC-11。
 * 対応実装: backend/.../chat/ws/ChatChannelSubscriptionInterceptor.java（`/topic/channels/{channelId}`
 * の SUBSCRIBE 時にチャネルメンバーシップを検査し、非メンバーの購読を ERROR フレームで拒否する）。
 *
 * 【検証内容】
 *   実ブラウザコンテキスト経由で（API 直叩きだけでなく、本物のブラウザの WebSocket 実装 / 同一オリジン
 *   条件下で）本番同等の経路の認可が効くことを確認する。ページの実 STOMP クライアントは、アプリの
 *   `useChatWebSocket` 内部シングルトンを流用せず、`page.evaluate` 内で sockjs-client を使わない生
 *   WebSocket + 最小限の STOMP テキストフレームを直接組み立て、SockJS raw-websocket transport
 *   （`/ws/websocket`）へ同一 Origin から接続する（WS_URL 定数のコメント参照）。
 *   - メンバー（チャンネル作成者）: 購読が成立し、自分が送信したメッセージを受信できる。
 *   - 非メンバー: 購読が ERROR フレーム（または直後の WebSocket CLOSE）で拒否され、
 *     その後にメンバーがメッセージを送信しても一切受信しない（漏洩なしの確認）。
 *
 * 【使い捨てデータ】
 *   e2e-user が使い捨てチーム＋チャンネルを新規作成する（作成者は自動的に OWNER として加入）。
 *   e2e-admin は当該チームに一切加入しないため、確実な非メンバーとして扱える
 *   （`feedback_authz_e2e_seed_membership_pollution` の作法どおり、非メンバー性を固定 seed の
 *   メンバーシップ状況に依存させず、テスト内で作成した新規スコープで担保する）。
 *   なお ChatChannelSubscriptionInterceptor はメンバーシップのみで判定し SYSTEM_ADMIN の
 *   ショートカットを持たないため、SYSTEM_ADMIN である e2e-admin であっても非メンバーなら拒否される
 *   （実コードで確認済み・本テストが期せずして検証する副次的事実）。
 *
 * 前提: backend/scripts/seed-e2e-data.js 実行済み。BE(8080)/FE(3000 or BASE_URL) 起動済み。
 */

import { test, expect, type Page, request as pwRequest, type APIRequestContext, type Browser } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000)

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`
// SockJS raw-websocket transport（/ws/websocket）に生 WebSocket で接続する。
// 実測（2026-07-11）: BE は SockJS エンドポイントのみ登録しており、bare `/ws` への
// WebSocket アップグレードは HTTP 400 で拒否される（f0810-live-spectator-ws.spec.ts と同知見）。
// ※ FE アプリ本体（useChatWebSocket.buildWsUrl）は bare `/ws` に接続しており接続不能
//   （アプリ側バグとして別途報告済み）。本 spec は SUBSCRIBE 認可インターセプタの検証が目的のため、
//   実際に成立する本番同等 STOMP 経路（SockJS raw transport）で検証する。
const WS_URL = `${BE.replace(/^http/, 'ws')}/ws/websocket`

const MEMBER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const MEMBER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const OUTSIDER_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const OUTSIDER_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

// ── ブラウザ内 STOMP フレームの型（window に保持する探査状態） ───────────────
interface StompFrame {
  command: string
  headers: Record<string, string>
  body: string
}

interface WsProbeState {
  socket: WebSocket
  frames: StompFrame[]
  connected: boolean
  closed: boolean
}

declare global {
  interface Window {
    __wsProbe?: WsProbeState
  }
}

// ── テスト状態 ──────────────────────────────────────────────────
let api: APIRequestContext
let memberToken: string
let outsiderToken: string
let teamSlug: string
let channelId: number

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function login(email: string, password: string): Promise<string> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `login(${email}) は 200。応答: ${await res.text()}`).toBe(200)
  return (await res.json() as { data: { accessToken: string } }).data.accessToken
}

// ── ブラウザ内 STOMP 探査ヘルパー（page.evaluate 経由・アプリの実 WS エンドポイントへ直結） ──

/**
 * 生 WebSocket で CONNECT のみ行う（SUBSCRIBE はしない）。`window.__wsProbe` に接続状態を保持し、
 * 以降の `subscribeAndWaitGrace` / `waitForMessageContaining` / `countMessages` から参照する。
 */
async function openProbe(page: Page, wsUrl: string, token: string): Promise<{ connected: boolean }> {
  return page.evaluate(({ wsUrl: url, token: bearer }) => {
    return new Promise<{ connected: boolean }>((resolve) => {
      const NUL = String.fromCharCode(0)

      function encodeFrame(command: string, headers: Record<string, string>, body = ''): string {
        let out = `${command}\n`
        for (const key of Object.keys(headers)) out += `${key}:${headers[key]}\n`
        return `${out}\n${body}${NUL}`
      }

      function parseFrame(raw: string): StompFrame {
        const trimmed = raw.replace(/^\n+/, '')
        const splitIdx = trimmed.indexOf('\n\n')
        const headerPart = splitIdx >= 0 ? trimmed.slice(0, splitIdx) : trimmed
        const body = splitIdx >= 0 ? trimmed.slice(splitIdx + 2) : ''
        const lines = headerPart.split('\n')
        const command = lines[0] ?? ''
        const headers: Record<string, string> = {}
        for (let i = 1; i < lines.length; i++) {
          const line = lines[i]
          if (!line) continue
          const idx = line.indexOf(':')
          if (idx > 0) headers[line.slice(0, idx)] = line.slice(idx + 1)
        }
        return { command, headers, body }
      }

      const socket = new WebSocket(url)
      let buffer = ''
      let settled = false
      window.__wsProbe = { socket, frames: [], connected: false, closed: false }

      const timer = setTimeout(() => {
        if (!settled) {
          settled = true
          resolve({ connected: false })
        }
      }, 10_000)

      socket.onopen = () => {
        socket.send(encodeFrame('CONNECT', {
          'accept-version': '1.2',
          'heart-beat': '0,0',
          Authorization: `Bearer ${bearer}`,
        }))
      }

      socket.onmessage = (ev) => {
        buffer += ev.data as string
        let idx: number
        while ((idx = buffer.indexOf(NUL)) >= 0) {
          const raw = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 1)
          if (raw.trim().length === 0) continue // ハートビート（空行のみ）は無視
          const frame = parseFrame(raw)
          window.__wsProbe?.frames.push(frame)
          if (frame.command === 'CONNECTED' && !settled) {
            if (window.__wsProbe) window.__wsProbe.connected = true
            settled = true
            clearTimeout(timer)
            resolve({ connected: true })
          } else if (frame.command === 'ERROR' && !settled) {
            settled = true
            clearTimeout(timer)
            resolve({ connected: false })
          }
        }
      }

      socket.onclose = () => {
        if (window.__wsProbe) window.__wsProbe.closed = true
        if (!settled) {
          settled = true
          clearTimeout(timer)
          resolve({ connected: false })
        }
      }

      socket.onerror = () => {
        if (!settled) {
          settled = true
          clearTimeout(timer)
          resolve({ connected: false })
        }
      }
    })
  }, { wsUrl, token })
}

/**
 * 既に CONNECT 済みのプローブで destination を SUBSCRIBE し、猶予期間内に ERROR フレームまたは
 * WebSocket CLOSE が来たかどうかで拒否判定する（SimpleBroker は SUBSCRIBE に RECEIPT を返さないため、
 * f0810-live-spectator-ws.spec.ts の subscribeLive と同じ判定方式）。
 */
async function subscribeAndWaitGrace(
  page: Page,
  destination: string,
  graceMs = 1_500,
): Promise<{ rejected: boolean; closed: boolean; errorBody: string | null }> {
  return page.evaluate(({ destination: dest, graceMs: grace }) => {
    return new Promise<{ rejected: boolean; closed: boolean; errorBody: string | null }>((resolve) => {
      const probe = window.__wsProbe
      if (!probe) {
        resolve({ rejected: true, closed: true, errorBody: 'プローブ未初期化（CONNECT 未完了）' })
        return
      }
      const NUL = String.fromCharCode(0)
      const subId = `sub-${Math.random().toString(36).slice(2)}`
      const framesBefore = probe.frames.length
      probe.socket.send(`SUBSCRIBE\nid:${subId}\ndestination:${dest}\n\n${NUL}`)
      setTimeout(() => {
        const newFrames = probe.frames.slice(framesBefore)
        const errorFrame = newFrames.find((f) => f.command === 'ERROR')
        resolve({
          rejected: Boolean(errorFrame) || probe.closed,
          closed: probe.closed,
          errorBody: errorFrame ? (errorFrame.body || errorFrame.headers['message'] || null) : null,
        })
      }, grace)
    })
  }, { destination, graceMs })
}

/** 受信済みの MESSAGE フレームに substring を含むものが現れるまで最大 timeoutMs 待つ。 */
async function waitForMessageContaining(page: Page, substring: string, timeoutMs = 8_000): Promise<boolean> {
  return page.evaluate(({ substring: needle, timeoutMs: timeout }) => {
    return new Promise<boolean>((resolve) => {
      const deadline = Date.now() + timeout
      const check = () => {
        const probe = window.__wsProbe
        const found = probe?.frames.some((f) => f.command === 'MESSAGE' && f.body.includes(needle)) ?? false
        if (found) {
          resolve(true)
          return
        }
        if (Date.now() > deadline) {
          resolve(false)
          return
        }
        setTimeout(check, 200)
      }
      check()
    })
  }, { substring, timeoutMs })
}

/** これまでに受信した MESSAGE フレームの件数を返す（漏洩なし=0件の確認用）。 */
async function countMessages(page: Page): Promise<number> {
  return page.evaluate(() => window.__wsProbe?.frames.filter((f) => f.command === 'MESSAGE').length ?? 0)
}

/** プローブの WebSocket を閉じ、状態を破棄する。 */
async function closeProbe(page: Page): Promise<void> {
  await page.evaluate(() => {
    window.__wsProbe?.socket.close()
    window.__wsProbe = undefined
  })
}

// ── beforeAll / afterAll ────────────────────────────────────────
test.beforeAll(async () => {
  api = await pwRequest.newContext()
  memberToken = await login(MEMBER_EMAIL, MEMBER_PASSWORD)
  outsiderToken = await login(OUTSIDER_EMAIL, OUTSIDER_PASSWORD)

  // 使い捨てチーム作成（e2e-user が作成者=唯一のメンバーになる）
  const uniqueName = `E2E-WSAuthz-${Date.now()}`
  const createTeam = await api.post(`${BE_API}/teams`, {
    headers: authHeaders(memberToken),
    data: { name: uniqueName, template: 'SPORTS', visibility: 'PUBLIC' },
  })
  expect(createTeam.status(), `使い捨てチーム作成は 201。応答: ${await createTeam.text()}`).toBe(201)
  teamSlug = (await createTeam.json() as { data: { slug: string } }).data.slug

  const myTeamsRes = await api.get(`${BE_API}/me/teams?limit=200`, { headers: authHeaders(memberToken) })
  const myTeams = (await myTeamsRes.json() as { data: Array<{ id: number; slug: string }> }).data
  const created = myTeams.find((t) => t.slug === teamSlug)
  expect(created, '作成した使い捨てチームが /me/teams に存在する').toBeTruthy()
  const teamId = created!.id

  // e2e-admin が非メンバーであることを裏取り（IDOR 検証の前提・seed 汚染対策）。
  const outsiderTeamsRes = await api.get(`${BE_API}/me/teams?limit=200`, { headers: authHeaders(outsiderToken) })
  const outsiderTeams = (await outsiderTeamsRes.json() as { data: Array<{ id: number }> }).data
  expect(
    outsiderTeams.some((t) => t.id === teamId),
    'e2e-admin は使い捨てチームの非会員である（AC-11 検証の前提）',
  ).toBe(false)

  // 使い捨てチャンネル作成（e2e-user=作成者=OWNER のみがメンバー）
  const createChannel = await api.post(`${BE_API}/chat/channels`, {
    headers: authHeaders(memberToken),
    data: { channelType: 'TEAM_PUBLIC', teamId, name: `E2E-Authz-${Date.now()}` },
  })
  expect(createChannel.status(), `使い捨てチャンネル作成は 201。応答: ${await createChannel.text()}`).toBe(201)
  channelId = (await createChannel.json() as { data: { id: number } }).data.id
})

test.afterAll(async () => {
  if (channelId) {
    await api.delete(`${BE_API}/chat/channels/${channelId}`, { headers: authHeaders(memberToken) }).catch((e: unknown) => { console.warn('[後片付け] 削除に失敗（試験結果には影響しないが残骸が残る）:', e) })
  }
  if (teamSlug) {
    await api.delete(`${BE_API}/teams/${teamSlug}`, { headers: authHeaders(memberToken) }).catch((e: unknown) => { console.warn('[後片付け] 削除に失敗（試験結果には影響しないが残骸が残る）:', e) })
  }
  await api?.dispose()
})

// ===========================================================================
// AC-11-A: メンバー（チャンネル作成者）は購読が成立し、自分の送信メッセージを受信できる
// ===========================================================================
test('AC-11-A: メンバーはチャンネル購読が成立し配信を受信できる', async ({ browser }: { browser: Browser }) => {
  const context = await browser.newContext()
  const page = await context.newPage()
  try {
    await loginViaApi(page, { email: MEMBER_EMAIL, password: MEMBER_PASSWORD }, { apiBaseUrl: BE })

    const connectResult = await openProbe(page, WS_URL, memberToken)
    expect(connectResult.connected, 'メンバーの CONNECT は成功する').toBe(true)

    const subscribeResult = await subscribeAndWaitGrace(page, `/topic/channels/${channelId}`)
    expect(subscribeResult.rejected, 'メンバーの購読は拒否されない').toBe(false)

    const uniqueBody = `E2E-Authz-Member-${Date.now()}`
    const sendRes = await api.post(`${BE_API}/chat/channels/${channelId}/messages`, {
      headers: authHeaders(memberToken),
      data: { body: uniqueBody },
    })
    expect(sendRes.status(), `メッセージ送信は 201。応答: ${await sendRes.text()}`).toBe(201)

    const received = await waitForMessageContaining(page, uniqueBody)
    expect(received, 'メンバーは自身が送信したメッセージの配信を受信できる').toBe(true)

    await closeProbe(page)
  } finally {
    await context.close()
  }
})

// ===========================================================================
// AC-11-B【セキュリティ最重要】: 非メンバーの購読は拒否され、配信を一切受信しない
// ===========================================================================
test('AC-11-B: 非メンバー（e2e-admin）のチャンネル購読は拒否され、配信も漏洩しない', async ({ browser }: { browser: Browser }) => {
  const context = await browser.newContext()
  const page = await context.newPage()
  try {
    await loginViaApi(page, { email: OUTSIDER_EMAIL, password: OUTSIDER_PASSWORD }, { apiBaseUrl: BE })

    const connectResult = await openProbe(page, WS_URL, outsiderToken)
    // CONNECT 自体はメンバーシップに依存しない（認証さえ有効なら成立する）。
    expect(connectResult.connected, '非メンバーでも CONNECT 自体は成功する').toBe(true)

    const subscribeResult = await subscribeAndWaitGrace(page, `/topic/channels/${channelId}`)
    expect(
      subscribeResult.rejected,
      `非メンバーの購読は ERROR フレームまたは WebSocket CLOSE で拒否される（errorBody=${subscribeResult.errorBody}）`,
    ).toBe(true)

    // 拒否後にメンバーがメッセージを送信しても、非メンバーには一切届かないこと（漏洩なし＝最重要）。
    const leakProbeBody = `E2E-Authz-Leak-${Date.now()}`
    const sendRes = await api.post(`${BE_API}/chat/channels/${channelId}/messages`, {
      headers: authHeaders(memberToken),
      data: { body: leakProbeBody },
    })
    expect(sendRes.status(), `メッセージ送信は 201。応答: ${await sendRes.text()}`).toBe(201)

    // 配信が来ないことの確認猶予
    await page.waitForTimeout(2_000)
    const messageCount = await countMessages(page)
    expect(messageCount, '購読拒否された非メンバーへは配信が漏れない（受信0件）').toBe(0)

    await closeProbe(page)
  } finally {
    await context.close()
  }
})
