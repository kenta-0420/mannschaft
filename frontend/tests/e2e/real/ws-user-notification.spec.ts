/**
 * WebSocket 外部ブローカー化 AC-9【実ブラウザ到達】実機 E2E（単一ノード・モックなし）。
 *
 * 設計書: docs/architecture/websocket_external_broker_valkey.md §2.5 / §7.4 / AC-9。
 * 構成: FE 3000（BASE_URL）→ BE 8080（BE_ORIGIN）。単一ノードで完結する（隊5 の FE 受け口 +
 * 隊1 の Principal 配線が揃って初めて成立する経路）。
 *
 * 【検証内容】
 *   実ブラウザ（アプリの実購読コード＝ useUserNotificationSocket composable 経由。
 *   テスト用 STOMP クライアントは使わない）で `/user/queue/notifications` の通知が到達し、
 *   `useNotificationStore` に反映され、その読者である `WidgetAdminBusinessAlert` の
 *   「問い合わせ」バッジがページリロードなしでリアルタイム更新されることを検証する。
 *   （設計書 §2.5 で確認済み: 本ウィジェットが `latestNotification` の唯一の実読者）
 *
 * 【通知発火手段】
 *   F10.7 問い合わせ通知（`InquiryChatEventListener` → `NotificationDispatchService.sendViaWebSocket`）。
 *   問い合わせチャンネルへの非 ADMIN メンバーのメッセージ送信が確実に
 *   `NotificationDispatchService` の `convertAndSendToUser` 経路を通る、既存 BA-005/006
 *   （`admin/business-alert.spec.ts`）と同一の発火手段のうち最も安定した選択肢として採用した。
 *
 * 【手順（設計書 §7.4.1 / §1.3 の注意に整合）】
 *   Principal 配線は CONNECT 時にのみ行われるため、通知発火前に必ず**フルリロードで新規 CONNECT**
 *   させてから検証する。
 *
 * 【使い捨てデータ】
 *   team1（fc-u-18・e2e-admin=ADMIN／e2e-user=MEMBER の安定フィクスチャ。announcement-broadcast.spec.ts
 *   等で確立済み）配下に、本テスト専用の使い捨てチャットチャンネルを新規作成し問い合わせ設定する。
 *   チャンネル作成のみを使い捨てにすることで、team1 の既存メンバーシップ（seed 汚染対策不要な
 *   既知の安定フィクスチャ）を再利用しつつ、他の並行テスト（BA-005/006 等）の問い合わせチャンネルと
 *   衝突しないようにする（同チームの問い合わせチャンネルは 1 個のみ許可される制約への対策として、
 *   beforeAll で既存の問い合わせチャンネルを一旦解除してから作成する）。afterAll で解除・削除する。
 *
 * 前提: backend/scripts/seed-e2e-data.js 実行済み。BE(8080)/FE(3000 or BASE_URL) 起動済み。
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000)

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
const MEMBER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const MEMBER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

const TEAM_SLUG = 'fc-u-18'
const WIDGET_TITLE = '業務アラート'
const INQUIRY_LABEL = '問い合わせ'

type MyTeam = { id: number; slug: string; name: string }
type ChannelListItem = {
  id: number
  identity: { teamId: number | null }
  settings: { isInquiryChannel: boolean | null }
}

// ── テスト状態 ──────────────────────────────────────────────────
let api: APIRequestContext
let adminToken: string
let memberToken: string
let teamId: number
let teamName: string
let channelId: number

// ── ヘルパー ────────────────────────────────────────────────────
function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function login(email: string, password: string): Promise<string> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `login(${email}) は 200。応答: ${await res.text()}`).toBe(200)
  return (await res.json() as { data: { accessToken: string } }).data.accessToken
}

test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await login(ADMIN_EMAIL, ADMIN_PASSWORD)
  memberToken = await login(MEMBER_EMAIL, MEMBER_PASSWORD)

  // team1（fc-u-18）を解決する（admin 視点）
  const teamsRes = await api.get(`${BE_API}/me/teams?limit=200`, { headers: authHeaders(adminToken) })
  expect(teamsRes.status(), '/me/teams は 200').toBe(200)
  const teams = (await teamsRes.json() as { data: MyTeam[] }).data
  const team = teams.find((t) => t.slug === TEAM_SLUG)
  expect(team, `${TEAM_SLUG} が admin の /me/teams に存在する（seed 未実行なら null）`).toBeTruthy()
  teamId = team!.id
  teamName = team!.name

  // e2e-user が team1 のメンバーであること（既存の安定フィクスチャ前提）を裏取りする
  const memberTeamsRes = await api.get(`${BE_API}/me/teams?limit=200`, { headers: authHeaders(memberToken) })
  const memberTeams = (await memberTeamsRes.json() as { data: MyTeam[] }).data
  expect(
    memberTeams.some((t) => t.id === teamId),
    'e2e-user が team1 のメンバーである（announcement-broadcast.spec.ts 等と同一フィクスチャ前提）',
  ).toBe(true)

  // 同チームに問い合わせチャンネルは 1 個しか設定できない制約への対策:
  // 前回実行の残骸（クラッシュ等で解除されなかったもの）があれば先に解除しておく。
  const channelsRes = await api.get(`${BE_API}/chat/channels`, { headers: authHeaders(adminToken) })
  if (channelsRes.ok()) {
    const channels = (await channelsRes.json() as { data: ChannelListItem[] }).data
    for (const ch of channels) {
      if (ch.identity.teamId === teamId && ch.settings.isInquiryChannel) {
        await api.patch(`${BE_API}/chat/channels/${ch.id}/inquiry`, {
          headers: authHeaders(adminToken),
          data: { is_inquiry_channel: false },
        }).catch(() => {})
      }
    }
  }

  // e2e-user の userId を解決し、使い捨てチャンネルの明示メンバーに加える
  // （作成者 admin は自動的に OWNER として加入するため memberUserIds への追加は不要）。
  const meRes = await api.get(`${BE_API}/users/me`, { headers: authHeaders(memberToken) })
  expect(meRes.status(), '/users/me(member) は 200').toBe(200)
  const memberUserId = (await meRes.json() as { data: { id: number } }).data.id

  const createRes = await api.post(`${BE_API}/chat/channels`, {
    headers: authHeaders(adminToken),
    data: {
      channelType: 'TEAM_PUBLIC',
      teamId,
      name: `E2E-WS通知-${Date.now()}`,
      memberUserIds: [memberUserId],
    },
  })
  expect(createRes.status(), `使い捨てチャンネル作成は 201。応答: ${await createRes.text()}`).toBe(201)
  channelId = (await createRes.json() as { data: { id: number } }).data.id

  const patchRes = await api.patch(`${BE_API}/chat/channels/${channelId}/inquiry`, {
    headers: authHeaders(adminToken),
    data: { is_inquiry_channel: true },
  })
  expect(patchRes.status(), `問い合わせチャンネル設定は 200。応答: ${await patchRes.text()}`).toBe(200)
  const patchBody = await patchRes.json() as { data: { settings: { isInquiryChannel: boolean } } }
  expect(patchBody.data.settings.isInquiryChannel, '問い合わせチャンネルフラグが ON になる').toBe(true)
})

test.afterAll(async () => {
  if (channelId) {
    await api.patch(`${BE_API}/chat/channels/${channelId}/inquiry`, {
      headers: authHeaders(adminToken),
      data: { is_inquiry_channel: false },
    }).catch(() => {})
    await api.delete(`${BE_API}/chat/channels/${channelId}`, { headers: authHeaders(adminToken) }).catch(() => {})
  }
  await api?.dispose()
})

// ===========================================================================
// AC-9: 実ブラウザ到達（実UI観測: WidgetAdminBusinessAlert の「問い合わせ」バッジ）
// ===========================================================================
test('AC-9: 問い合わせ通知が実ブラウザにWebSocketでリアルタイム反映される（リロード不要）', async ({ page }) => {
  // 1. admin として API ログイン → /dashboard へ遷移 → 明示的にフルリロードして
  //    新規 CONNECT を確定させる（Principal 配線は CONNECT 時のみ有効・設計書 §1.3 の注意）。
  await loginViaApi(page, { email: ADMIN_EMAIL, password: ADMIN_PASSWORD }, { apiBaseUrl: BE })
  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.reload({ waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // ウィジェット本体が描画されるまで待つ
  await expect(page.getByRole('heading', { name: WIDGET_TITLE })).toBeVisible({ timeout: 20_000 })

  // useChatWebSocket の共有シングルトン STOMP 接続が確立するまでの猶予
  // （useUserNotificationSocket はこの共有接続に相乗りする・default.vue の onMounted で start() 済み）。
  await page.waitForTimeout(2_000)

  // ウィジェットカード（rounded-xl の外枠）にスコープしてから該当チームの行を特定する
  // （team.teamName が他ウィジェットにも出現しうるため、ウィジェット単位で絞り込む）。
  const widgetCard = page.locator('div.rounded-xl').filter({ has: page.getByRole('heading', { name: WIDGET_TITLE }) })
  const teamRow = widgetCard.locator('div.py-3').filter({ hasText: teamName })
  const inquiryButton = teamRow.getByRole('button', { name: new RegExp(INQUIRY_LABEL) })
  await expect(inquiryButton).toBeVisible({ timeout: 15_000 })

  /** ボタン内テキストから未読件数（数字）を抽出する。 */
  async function readUnreadCount(): Promise<number> {
    const text = await inquiryButton.innerText()
    const match = text.match(/(\d+)/)
    return match ? Number(match[1]) : Number.NaN
  }

  // ── 発火前（時系列の裏取り）: 使い捨てチャンネルはまだ未読メッセージが無いため 0 ──
  await expect.poll(readUnreadCount, { timeout: 10_000 }).toBe(0)

  // 2. 別アクター（非 ADMIN メンバー）が API 経由で問い合わせチャンネルへメッセージを送信する。
  //    InquiryChatEventListener（AFTER_COMMIT）→ NotificationDispatchService.sendViaWebSocket
  //    → convertAndSendToUser(admin, "/queue/notifications") → admin ブラウザの
  //    useUserNotificationSocket が受信 → useNotificationStore.setLatestNotification
  //    → WidgetAdminBusinessAlert の watch が即時 fetchSummary() を実行する。
  const uniqueBody = `E2E-WS通知-発火-${Date.now()}`
  const sendRes = await api.post(`${BE_API}/chat/channels/${channelId}/messages`, {
    headers: authHeaders(memberToken),
    data: { body: uniqueBody },
  })
  expect(sendRes.status(), `メッセージ送信は 201。応答: ${await sendRes.text()}`).toBe(201)

  // 3. ページをリロードせずにバッジが 0→1 へ更新されること（実UI観測・リアルタイム性の核心）。
  await expect.poll(readUnreadCount, { timeout: 15_000 }).toBe(1)

  // 4. 補助的裏取り: REST サマリー API でも同値であることを直接確認する（実APIでの二重検証）。
  const summaryRes = await api.get(`${BE_API}/admin/business-alerts/summary`, { headers: authHeaders(adminToken) })
  expect(summaryRes.status(), 'サマリーAPIは200').toBe(200)
  const summaryBody = await summaryRes.json() as {
    data: { data?: { teams: Array<{ teamId: number; alerts: { unreadInquiries: number } }> }, teams?: Array<{ teamId: number; alerts: { unreadInquiries: number } }> }
  }
  const summaryData = summaryBody.data.data ?? summaryBody.data
  const teamAlert = summaryData.teams?.find((t) => t.teamId === teamId)
  expect(teamAlert?.alerts.unreadInquiries, 'REST側でも未読問い合わせ数が1であること').toBe(1)
})
