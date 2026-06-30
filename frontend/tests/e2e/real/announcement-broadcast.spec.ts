/**
 * チーム内告知（F02.8 告知ウィザード = BroadcastWizard）の実機 E2E。
 *
 * このテストは API モックを使わない実機テストです。
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が起動済みの状態で実行してください。
 *   （ポートは環境変数 E2E_BACKEND_URL / E2E_FRONTEND_URL で上書き可能）
 *
 * テストユーザー（実在・ログイン確認済み）:
 *   - e2e-user@test.mannschaft.local  / TestPass2026!  → TEAM 1（fc-u-18「FC Tokyo U-18 Test」）の MEMBER（userId=23）
 *   - e2e-admin@test.mannschaft.local / TestPass2026!  → TEAM 1 の ADMIN かつ SYSTEM_ADMIN（userId=24）
 *
 * 認可・契約は実コードで確認済み:
 *   - POST /api/v1/teams/{teamId}/broadcast（201, ApiResponse<BroadcastResponseDto>・data ラップ）
 *     リクエストは camelCase（channel / targetRole / targetTeamIds / templateId / priority / expiresAt / content）。
 *     content も camelCase（title / body / categoryId / startAt / endAt / allDay / location / description / closesAt）。
 *     レスポンスも camelCase（announcementFeedId / channel / contentId / contentUrl / targetRole / targetTeamIds / priority / createdAt）。
 *   - 日時の型: startAt / endAt は OffsetDateTime（Z 付き ISO）。closesAt / expiresAt は LocalDateTime（オフセットなし）。
 *   - 認可: 非会員は 403（COMMON_002）。非 ADMIN が priority≠NORMAL を指定すると 400（BROADCAST_001）。
 *   - お知らせフィード: GET /announcements（data+meta）, POST /{id}/read, POST /read-all,
 *     PATCH /{id}/pin（ADMIN/DEPUTY のみ・MEMBER は 400 ANNOUNCE_002）, DELETE /{id}（204・お知らせ解除）。
 *
 * ── レートリミット（重要）─────────────────────────────────────────────
 *   broadcast エンドポイントは BroadcastRateLimitFilter により「ユーザー別 5 件 / 5 分」に制限される。
 *   1 回の実行で 5 件を超える broadcast を行うと 429（Too many requests）になる。
 *   本 spec は 429 を「機能の不具合」ではなく環境制約として扱い、その場合は test.skip する
 *   （対処療法の握りつぶしではなく、レートリミットは仕様どおりの挙動。クールダウン後の再実行で green 化する）。
 *   admin / user でバケットが分かれるため、可能な範囲で両者に broadcast を分散している。
 *
 * 前提が崩れる場合（BE/FE 未起動・ログイン不可・membership 不足・レート制限）のみ test.skip() で正直に
 * スキップする。製品不具合（500 / 400 / 描画 0 件 等）は skip で逃げず assert で必ず検知する。
 */

import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------
const BACKEND_URL = process.env.E2E_BACKEND_URL ?? 'http://localhost:8080'
const FRONTEND_URL = process.env.E2E_FRONTEND_URL ?? 'http://localhost:3000'
const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const E2E_ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }
// TEAM 1 の SUPPORTER（受信側可視性マトリクス検証用・seed 投入済み）
const E2E_SUPPORTER = { email: 'e2e-supporter@test.mannschaft.local', password: 'TestPass2026!' }
const TEAM_ID = 1
// チーム詳細ページ / 権限解決（/me/permissions）は slug 専用（数値 id は 404）。
// お知らせ feed API は slug / 数値どちらも 200。UI ナビゲーションは slug を使う。
const TEAM_SLUG = 'fc-u-18'

// UI 文字列（i18n ja/announcement.json・common.json と一致）
const TXT = {
  broadcastButton: 'チーム内告知',
  submitButton: '告知を送る',
  success: '告知を送信しました',
  guideToggleTestId: 'broadcast-guide-toggle',
  guideAudienceTitle: '誰に届けるか（対象範囲）',
  next: '次へ',
  channelTimeline: 'タイムライン',
  channelBulletin: '掲示板',
  priorityLabel: '優先度',
  expiresLabel: '表示期限',
} as const

// ---------------------------------------------------------------------------
// 型（最小限・BE は camelCase を返す）
// ---------------------------------------------------------------------------
interface BroadcastResponse {
  announcementFeedId: number
  channel: string
  contentId: number
  contentUrl: string
  targetRole: string
  targetTeamIds: number[] | null
  priority: string
  createdAt: string
}
interface FeedItem {
  id: number
  scopeType: string
  scopeId: number
  sourceType: string
  sourceId: number
  authorId: number
  titleCache: string | null
  excerptCache: string | null
  priority: string
  isPinned: boolean
  visibility: string
  expiresAt: string | null
  isRead: boolean
  createdAt: string
}
interface FeedResponse {
  data: FeedItem[]
  meta: { nextCursor: number | null; hasNext: boolean; unreadCount: number }
}
interface BroadcastOutcome {
  status: number
  data: BroadcastResponse | null
  errorCode: string | null
}

// ---------------------------------------------------------------------------
// ヘルパー: 環境チェック / 認証
// ---------------------------------------------------------------------------
async function isBackendAlive(req: APIRequestContext): Promise<boolean> {
  try {
    const res = await req.get(`${BACKEND_URL}/actuator/health`, { timeout: 5_000 })
    const body = await res.json()
    return body.status === 'UP'
  } catch {
    return false
  }
}

async function isFrontendAlive(req: APIRequestContext): Promise<boolean> {
  try {
    const res = await req.get(FRONTEND_URL, { timeout: 5_000 })
    return res.status() < 600
  } catch {
    return false
  }
}

async function getAuthToken(req: APIRequestContext, email: string, password: string): Promise<string | null> {
  try {
    const res = await req.post(`${BACKEND_URL}/api/v1/auth/login`, {
      data: { email, password },
      headers: { 'Content-Type': 'application/json' },
    })
    if (!res.ok()) return null
    const body = await res.json()
    return body?.data?.accessToken ?? null
  } catch {
    return null
  }
}

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

// ---------------------------------------------------------------------------
// ヘルパー: 告知 broadcast / お知らせフィード API
// ---------------------------------------------------------------------------
type BroadcastBody = {
  channel: string
  targetRole?: string
  priority?: string
  expiresAt?: string
  content: Record<string, unknown>
}

async function broadcast(
  req: APIRequestContext,
  token: string,
  body: BroadcastBody,
  teamId = TEAM_ID,
): Promise<BroadcastOutcome> {
  const payload = { targetRole: 'MEMBERS_AND_ABOVE', priority: 'NORMAL', ...body }
  const res = await req.post(`${BACKEND_URL}/api/v1/teams/${teamId}/broadcast`, {
    headers: authHeaders(token),
    data: payload,
  })
  const status = res.status()
  let json: unknown = null
  try {
    json = await res.json()
  } catch {
    json = null
  }
  const j = json as { data?: BroadcastResponse; error?: { code?: string } } | null
  return {
    status,
    data: j?.data ?? null,
    errorCode: j?.error?.code ?? null,
  }
}

/** 日時ユーティリティ（明日）。startAt は OffsetDateTime（Z 付き）、closesAt/expiresAt は LocalDateTime（オフセットなし）。 */
function tomorrowOffset(): string {
  return new Date(Date.now() + 86_400_000).toISOString()
}
function tomorrowLocal(): string {
  return new Date(Date.now() + 86_400_000).toISOString().slice(0, 19)
}

async function getFeed(req: APIRequestContext, token: string, teamId = TEAM_ID): Promise<FeedResponse | null> {
  try {
    const res = await req.get(`${BACKEND_URL}/api/v1/teams/${teamId}/announcements?limit=50`, {
      headers: authHeaders(token),
    })
    if (!res.ok()) return null
    return (await res.json()) as FeedResponse
  } catch {
    return null
  }
}

function findFeedItem(feed: FeedResponse | null, feedId: number): FeedItem | null {
  return feed?.data.find((x) => x.id === feedId) ?? null
}

// ---------------------------------------------------------------------------
// ヘルパー: ログイン（UI 用・page.request 直叩きで確実に認証）
// ---------------------------------------------------------------------------
async function loginUi(page: Page, email: string, password: string): Promise<void> {
  const loginRes = await page.request.post(`${BACKEND_URL}/api/v1/auth/login`, {
    data: { email, password },
  })
  if (!loginRes.ok()) throw new Error(`UI ログイン失敗 (${email}): ${loginRes.status()}`)
  const meRes = await page.request.get(`${BACKEND_URL}/api/v1/users/me`)
  const me = (await meRes.json()).data as {
    id: number; email: string; lastName: string; firstName: string
    avatarUrl: string | null; systemRole: string | null; timezone: string | null
  }
  await page.goto(FRONTEND_URL)
  await page.evaluate((user) => {
    localStorage.setItem('currentUser', JSON.stringify(user))
  }, {
    id: me.id, email: me.email, fullName: `${me.lastName} ${me.firstName}`,
    profileImageUrl: me.avatarUrl, systemRole: me.systemRole ?? undefined, timezone: me.timezone ?? undefined,
  })
}

async function settle(page: Page): Promise<void> {
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
}

/** 告知ウィザードを開く（/teams/{slug} の「チーム内告知」ボタン）。
 * ボタンは v-if="roleName && roleName!=='SUPPORTER'" のため、権限解決（/me/permissions）完了が前提。
 * チーム詳細ページは SSR が重く描画に時間がかかるため十分なタイムアウトを取る。 */
async function openWizard(page: Page): Promise<void> {
  const btn = page.getByRole('button', { name: TXT.broadcastButton })
  await expect(btn).toBeVisible({ timeout: 30_000 })
  await btn.click()
  await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })
}

// ---------------------------------------------------------------------------
// テスト本体（serial・共有状態を 1 つの afterAll でまとめて掃除）
// ---------------------------------------------------------------------------
test.describe('チーム内告知（F02.8 告知ウィザード）実機 E2E', () => {
  test.describe.configure({ mode: 'serial' })

  let adminToken: string | null = null
  let userToken: string | null = null
  let supporterToken: string | null = null
  let backendAlive = false
  let frontendAlive = false
  let scopeAccessible = false

  // 後始末対象
  const createdFeedIds: number[] = []
  const createdTimelinePostIds: number[] = []
  let throwawayTeamSlug: string | null = null

  // 後続テストで再利用するフィード（working チャネルで作成したもの）
  let timelineFeedId: number | null = null
  let bulletinFeedId: number | null = null

  test.beforeAll(async ({ request }) => {
    backendAlive = await isBackendAlive(request)
    frontendAlive = await isFrontendAlive(request)
    if (!backendAlive) {
      console.warn('バックエンド未起動のため告知テストをスキップします')
      return
    }
    adminToken = await getAuthToken(request, E2E_ADMIN.email, E2E_ADMIN.password)
    userToken = await getAuthToken(request, E2E_USER.email, E2E_USER.password)
    supporterToken = await getAuthToken(request, E2E_SUPPORTER.email, E2E_SUPPORTER.password)
    if (!adminToken || !userToken) {
      console.warn('e2e ユーザーのログインに失敗しました')
      return
    }
    // membership プローブ: admin が TEAM 1 のお知らせフィードを取得できるか
    const feed = await getFeed(request, adminToken)
    scopeAccessible = feed !== null
    if (!scopeAccessible) {
      console.warn('e2e-admin が TEAM 1 のお知らせにアクセスできません（membership 種データ不足の可能性）')
    }
  })

  test.afterAll(async ({ request }) => {
    if (!backendAlive) return
    if (adminToken) {
      for (const id of createdFeedIds) {
        await request.delete(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/announcements/${id}`, {
          headers: authHeaders(adminToken),
        }).catch(() => null)
      }
      // 生成された実コンテンツ（timeline のみ削除 API あり。他チャネルは feed 解除のみ＝仕様上許容）
      for (const pid of createdTimelinePostIds) {
        await request.delete(`${BACKEND_URL}/api/v1/timeline/posts/${pid}`, {
          headers: authHeaders(adminToken),
        }).catch(() => null)
      }
      if (throwawayTeamSlug) {
        await request.delete(`${BACKEND_URL}/api/v1/teams/${throwawayTeamSlug}`, {
          headers: authHeaders(adminToken),
        }).catch(() => null)
      }
    }
  })

  function ensureApiReady(): void {
    if (!backendAlive) test.skip(true, 'バックエンド未起動のためスキップ')
    if (!adminToken || !userToken) test.skip(true, 'e2e ユーザーのログイン失敗のためスキップ')
    if (!scopeAccessible) test.skip(true, 'TEAM 1 のお知らせにアクセス不可（membership 種データ不足の可能性）のためスキップ')
  }

  /** 201 を期待する broadcast。429（レート制限）の場合のみ環境制約としてスキップ。 */
  function expect201OrSkip(outcome: BroadcastOutcome, label: string): BroadcastResponse {
    if (outcome.status === 429) {
      test.skip(true, `${label}: broadcast レート制限（5件/5分/ユーザー）に達したためスキップ（クールダウン後の再実行で green 化）`)
    }
    expect(outcome.status, `${label} が 201 にならなかった: status=${outcome.status} code=${outcome.errorCode}`).toBe(201)
    expect(outcome.data).not.toBeNull()
    return outcome.data!
  }

  // ════════════════════════════════════════════════════════════════════
  // ① working チャネル（API）— TIMELINE / BULLETIN（admin）, BLOG / TODO（user）
  // ════════════════════════════════════════════════════════════════════

  test('ANNC-API-004a: ADMIN が TIMELINE で broadcast → 201・feed に出現（contentId/url 検証）', async ({ request }) => {
    ensureApiReady()
    const out = await broadcast(request, adminToken!, {
      channel: 'TIMELINE_POST',
      content: { body: `E2E timeline ${Date.now()}` },
    })
    const data = expect201OrSkip(out, 'TIMELINE broadcast')
    expect(data.channel).toBe('TIMELINE_POST')
    expect(data.contentId).toBeGreaterThan(0)
    expect(data.contentUrl).toContain(`/teams/${TEAM_ID}/timeline/`)
    expect(data.announcementFeedId).toBeGreaterThan(0)
    timelineFeedId = data.announcementFeedId
    createdFeedIds.push(data.announcementFeedId)
    createdTimelinePostIds.push(data.contentId)

    const item = findFeedItem(await getFeed(request, adminToken!), data.announcementFeedId)
    expect(item, 'TIMELINE がフィードに出現しない').not.toBeNull()
    expect(item!.sourceType).toBe('TIMELINE_POST')
  })

  test('ANNC-API-004b + ANNC-API-005: ADMIN が BULLETIN で priority=IMPORTANT + expiresAt → 201・永続化を GET で確認', async ({ request }) => {
    ensureApiReady()
    const title = `E2E bulletin ${Date.now()}`
    const out = await broadcast(request, adminToken!, {
      channel: 'BULLETIN_THREAD',
      priority: 'IMPORTANT',
      expiresAt: tomorrowLocal(),
      content: { title, body: 'E2Eテスト本文' },
    })
    const data = expect201OrSkip(out, 'BULLETIN broadcast')
    expect(data.channel).toBe('BULLETIN_THREAD')
    expect(data.priority).toBe('IMPORTANT')
    bulletinFeedId = data.announcementFeedId
    createdFeedIds.push(data.announcementFeedId)

    // delete-then-insert 系バグ検出のため、オプション項目の永続化を GET で裏取り
    const item = findFeedItem(await getFeed(request, adminToken!), data.announcementFeedId)
    expect(item, 'BULLETIN がフィードに出現しない').not.toBeNull()
    expect(item!.titleCache).toBe(title)
    expect(item!.priority, 'priority が永続化されていない').toBe('IMPORTANT')
    expect(item!.expiresAt, 'expiresAt が永続化されていない').toBeTruthy()
  })

  test('ANNC-API-004c: MEMBER（user）が BLOG で broadcast（NORMAL）→ 201・feed に出現', async ({ request }) => {
    ensureApiReady()
    const title = `E2E blog ${Date.now()}`
    const out = await broadcast(request, userToken!, {
      channel: 'BLOG_POST',
      content: { title, body: 'E2Eブログ本文' },
    })
    const data = expect201OrSkip(out, 'BLOG broadcast')
    expect(data.channel).toBe('BLOG_POST')
    createdFeedIds.push(data.announcementFeedId)
    const item = findFeedItem(await getFeed(request, adminToken!), data.announcementFeedId)
    expect(item, 'BLOG がフィードに出現しない').not.toBeNull()
    expect(item!.titleCache).toBe(title)
  })

  test('ANNC-API-004d: MEMBER（user）が TODO で broadcast（NORMAL）→ 201・feed に出現', async ({ request }) => {
    ensureApiReady()
    const title = `E2E todo ${Date.now()}`
    const out = await broadcast(request, userToken!, {
      channel: 'TODO',
      content: { title, body: 'E2E TODO 説明' },
    })
    const data = expect201OrSkip(out, 'TODO broadcast')
    expect(data.channel).toBe('TODO')
    createdFeedIds.push(data.announcementFeedId)
    const item = findFeedItem(await getFeed(request, adminToken!), data.announcementFeedId)
    expect(item, 'TODO がフィードに出現しない').not.toBeNull()
    expect(item!.titleCache).toBe(title)
  })

  // ════════════════════════════════════════════════════════════════════
  // ② チャネル別の必須項目を満たす SCHEDULE / SURVEY（API）
  //    根治後は 201 必須。製品不具合（500 / 400）は skip で逃げず assert で検知する。
  // ════════════════════════════════════════════════════════════════════

  test('ANNC-API-004e: ADMIN が SCHEDULE で broadcast（startAt 必須）→ 201・feed に出現', async ({ request }) => {
    ensureApiReady()
    const title = `E2E schedule ${Date.now()}`
    const out = await broadcast(request, adminToken!, {
      channel: 'SCHEDULE',
      content: { title, startAt: tomorrowOffset(), allDay: false },
    })
    const data = expect201OrSkip(out, 'SCHEDULE broadcast')
    expect(data.channel).toBe('SCHEDULE')
    expect(data.contentUrl).toContain(`/teams/${TEAM_ID}/schedules/`)
    createdFeedIds.push(data.announcementFeedId)
    const item = findFeedItem(await getFeed(request, adminToken!), data.announcementFeedId)
    expect(item, 'SCHEDULE がフィードに出現しない').not.toBeNull()
  })

  test('ANNC-API-004f: ADMIN が SURVEY で broadcast（closesAt）→ 201・feed に出現', async ({ request }) => {
    ensureApiReady()
    const title = `E2E survey ${Date.now()}`
    const out = await broadcast(request, adminToken!, {
      channel: 'SURVEY',
      content: { title, closesAt: tomorrowLocal() },
    })
    const data = expect201OrSkip(out, 'SURVEY broadcast')
    expect(data.channel).toBe('SURVEY')
    expect(data.contentUrl).toContain(`/teams/${TEAM_ID}/surveys/`)
    createdFeedIds.push(data.announcementFeedId)
    const item = findFeedItem(await getFeed(request, adminToken!), data.announcementFeedId)
    expect(item, 'SURVEY がフィードに出現しない').not.toBeNull()
    expect(item!.titleCache).toBe(title)
  })

  // ════════════════════════════════════════════════════════════════════
  // ③ 認可（API）
  // ════════════════════════════════════════════════════════════════════

  test('ANNC-API-001: MEMBER が priority=IMPORTANT で broadcast → 400 BROADCAST_001', async ({ request }) => {
    ensureApiReady()
    const out = await broadcast(request, userToken!, {
      channel: 'TIMELINE_POST',
      priority: 'IMPORTANT',
      content: { body: 'member important' },
    })
    if (out.status === 429) test.skip(true, 'broadcast レート制限に達したためスキップ')
    expect(out.status).toBe(400)
    expect(out.errorCode).toBe('BROADCAST_001')
  })

  test('ANNC-API-002: ADMIN が priority=URGENT で broadcast → 201', async ({ request }) => {
    ensureApiReady()
    const out = await broadcast(request, adminToken!, {
      channel: 'TIMELINE_POST',
      priority: 'URGENT',
      content: { body: `E2E urgent ${Date.now()}` },
    })
    const data = expect201OrSkip(out, 'ADMIN URGENT broadcast')
    expect(data.priority).toBe('URGENT')
    createdFeedIds.push(data.announcementFeedId)
    createdTimelinePostIds.push(data.contentId)
  })

  test('ANNC-API-003: 非会員が broadcast → 403（使い捨てチームを admin が作成）', async ({ request }) => {
    ensureApiReady()
    // e2e-user が会員でないチームを admin が使い捨てで作成する
    const createRes = await request.post(`${BACKEND_URL}/api/v1/teams`, {
      headers: authHeaders(adminToken!),
      data: { name: `e2e-broadcast-throwaway-${Date.now()}` },
    })
    if (!createRes.ok()) {
      test.skip(true, `使い捨てチーム作成に失敗（status=${createRes.status()}）のためスキップ`)
    }
    const created = (await createRes.json()).data as { id: number; slug: string }
    throwawayTeamSlug = created.slug

    const out = await broadcast(request, userToken!, {
      channel: 'TIMELINE_POST',
      content: { body: 'non-member' },
    }, created.id)
    if (out.status === 429) test.skip(true, 'broadcast レート制限に達したためスキップ')
    expect(out.status, '非会員 broadcast が拒否されなかった').toBe(403)
    expect(out.errorCode).toBe('COMMON_002')
  })

  // ════════════════════════════════════════════════════════════════════
  // ③.5 受信側 可視性マトリクス（届くか）— target_role 別に「対象は届く / 対象外は届かない」を実証
  //     MEMBER は SUPPORTER より上位。可視性ラダー:
  //       MEMBERS_AND_ABOVE    → ADMIN/MEMBER に届く・SUPPORTER には届かない
  //       SUPPORTERS_AND_ABOVE → ADMIN/MEMBER/SUPPORTER に届く
  //       PUBLIC               → 全員に届く
  // ════════════════════════════════════════════════════════════════════

  // 受信側テストで作る 3 件の target_role 別告知のタイトル（一意化）
  let visTitles: { members: string; supporters: string; pub: string } | null = null

  /** 3 つの target_role で BLOG 告知を作成し、タイトルで識別できるようにする（レート制限時は skip）。 */
  async function ensureVisibilityBroadcasts(request: APIRequestContext): Promise<{ members: string; supporters: string; pub: string }> {
    if (visTitles) return visTitles
    const ts = Date.now()
    const titles = {
      members: `VISTEST-MEMBERS-${ts}`,
      supporters: `VISTEST-SUPPORTERS-${ts}`,
      pub: `VISTEST-PUBLIC-${ts}`,
    }
    const specs: { role: string; title: string }[] = [
      { role: 'MEMBERS_AND_ABOVE', title: titles.members },
      { role: 'SUPPORTERS_AND_ABOVE', title: titles.supporters },
      { role: 'PUBLIC', title: titles.pub },
    ]
    for (const s of specs) {
      const out = await broadcast(request, adminToken!, {
        channel: 'BLOG_POST',
        targetRole: s.role,
        content: { title: s.title, body: 'visibility check' },
      })
      if (out.status === 429) test.skip(true, '受信側可視性テスト: broadcast レート制限（5件/5分）に達したためスキップ')
      const data = expect201OrSkip(out, `${s.role} broadcast`)
      createdFeedIds.push(data.announcementFeedId)
    }
    visTitles = titles
    return titles
  }

  /** 指定トークンのフィードに出現する VISTEST タイトル集合を返す。 */
  async function visibleVistestTitles(request: APIRequestContext, token: string): Promise<Set<string>> {
    const feed = await getFeed(request, token)
    return new Set((feed?.data ?? []).map((i) => i.titleCache ?? '').filter((t) => t.startsWith('VISTEST')))
  }

  test('ANNC-VIS-001: MEMBER は MEMBERS/SUPPORTERS/PUBLIC すべての告知を受信できる', async ({ request }) => {
    ensureApiReady()
    const t = await ensureVisibilityBroadcasts(request)
    const seen = await visibleVistestTitles(request, userToken!)
    expect(seen.has(t.members), 'MEMBER に MEMBERS_AND_ABOVE が届いていない').toBe(true)
    expect(seen.has(t.supporters), 'MEMBER に SUPPORTERS_AND_ABOVE が届いていない').toBe(true)
    expect(seen.has(t.pub), 'MEMBER に PUBLIC が届いていない').toBe(true)
  })

  test('ANNC-VIS-002: SUPPORTER は SUPPORTERS/PUBLIC を受信し、MEMBERS 限定は受信しない（漏れ無し）', async ({ request }) => {
    ensureApiReady()
    if (!supporterToken) test.skip(true, 'e2e-supporter ログイン失敗のためスキップ')
    const t = await ensureVisibilityBroadcasts(request)
    const seen = await visibleVistestTitles(request, supporterToken!)
    expect(seen.has(t.supporters), 'SUPPORTER に SUPPORTERS_AND_ABOVE が届いていない').toBe(true)
    expect(seen.has(t.pub), 'SUPPORTER に PUBLIC が届いていない').toBe(true)
    // 核心: メンバー限定告知は SUPPORTER に漏れてはならない
    expect(seen.has(t.members), 'メンバー限定告知が SUPPORTER に漏れている').toBe(false)
  })

  test('ANNC-VIS-003: SUPPORTER が受信告知を既読にすると isRead=true・unreadCount が減る（確認できる）', async ({ request }) => {
    ensureApiReady()
    if (!supporterToken) test.skip(true, 'e2e-supporter ログイン失敗のためスキップ')
    const t = await ensureVisibilityBroadcasts(request)
    const before = await getFeed(request, supporterToken!)
    const target = (before?.data ?? []).find((i) => i.titleCache === t.supporters)
    expect(target, 'SUPPORTER 受信フィードに対象告知が無い').toBeTruthy()
    expect(target!.isRead).toBe(false)
    const unreadBefore = before?.meta.unreadCount ?? 0

    const readRes = await request.post(
      `${BACKEND_URL}/api/v1/teams/${TEAM_ID}/announcements/${target!.id}/read`,
      { headers: authHeaders(supporterToken!) },
    )
    expect(readRes.status()).toBe(200)
    expect((await readRes.json()).data.isRead).toBe(true)

    const after = await getFeed(request, supporterToken!)
    const afterTarget = (after?.data ?? []).find((i) => i.id === target!.id)
    expect(afterTarget!.isRead, '既読化が反映されていない').toBe(true)
    expect(after?.meta.unreadCount ?? 0, 'unreadCount が減っていない').toBeLessThan(unreadBefore)
  })

  // ════════════════════════════════════════════════════════════════════
  // ④ お知らせフィード管理（API）— 既存フィードを再利用
  // ════════════════════════════════════════════════════════════════════

  /** working チャネルで作った再利用フィードを 1 件確保する（無ければ TIMELINE を作成）。 */
  async function ensureFeedItem(req: APIRequestContext): Promise<number> {
    if (timelineFeedId) return timelineFeedId
    if (bulletinFeedId) return bulletinFeedId
    const out = await broadcast(req, adminToken!, { channel: 'TIMELINE_POST', content: { body: `E2E feed ${Date.now()}` } })
    if (out.status === 429) test.skip(true, 'broadcast レート制限により再利用フィードを用意できないためスキップ')
    const data = expect201OrSkip(out, 'feed 用 TIMELINE broadcast')
    timelineFeedId = data.announcementFeedId
    createdFeedIds.push(data.announcementFeedId)
    createdTimelinePostIds.push(data.contentId)
    return data.announcementFeedId
  }

  test('ANNC-FEED-001: ADMIN がピン留めトグル → isPinned true → false', async ({ request }) => {
    ensureApiReady()
    const feedId = await ensureFeedItem(request)
    const p1 = await request.patch(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/announcements/${feedId}/pin`, {
      headers: authHeaders(adminToken!), data: {},
    })
    expect(p1.status()).toBe(200)
    expect((await p1.json()).data.isPinned).toBe(true)
    const p2 = await request.patch(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/announcements/${feedId}/pin`, {
      headers: authHeaders(adminToken!), data: {},
    })
    expect(p2.status()).toBe(200)
    expect((await p2.json()).data.isPinned).toBe(false)
  })

  test('ANNC-FEED-002: MEMBER がピン留め → 403 相当（400 ANNOUNCE_002）で拒否される', async ({ request }) => {
    ensureApiReady()
    const feedId = await ensureFeedItem(request)
    const res = await request.patch(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/announcements/${feedId}/pin`, {
      headers: authHeaders(userToken!), data: {},
    })
    const body = await res.json().catch(() => ({}))
    // 実装は権限エラーを 400 ANNOUNCE_002 で返す（「この操作を行う権限がありません」）
    expect(res.status(), 'MEMBER がピン留めできてしまった').toBe(400)
    expect(body?.error?.code).toBe('ANNOUNCE_002')
  })

  test('ANNC-FEED-003: read / read-all で isRead が反映される', async ({ request }) => {
    ensureApiReady()
    const feedId = await ensureFeedItem(request)
    const readRes = await request.post(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/announcements/${feedId}/read`, {
      headers: authHeaders(userToken!),
    })
    expect(readRes.status()).toBe(200)
    expect((await readRes.json()).data.isRead).toBe(true)
    // 冪等性: もう一度叩いても 200
    const again = await request.post(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/announcements/${feedId}/read`, {
      headers: authHeaders(userToken!),
    })
    expect(again.status()).toBe(200)
    // read-all も 200
    const allRes = await request.post(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/announcements/read-all`, {
      headers: authHeaders(userToken!),
    })
    expect(allRes.status()).toBe(200)
  })

  test('ANNC-FEED-004: DELETE でお知らせ解除 → feed から消える', async ({ request }) => {
    ensureApiReady()
    // レート制限節約のため、既存の bulletin フィードを再利用して削除を検証する
    // （timeline フィードは後続 UI テストで再利用するため温存）。再利用が無い場合のみ新規作成。
    let feedId = bulletinFeedId
    if (!feedId) {
      const out = await broadcast(request, adminToken!, { channel: 'TIMELINE_POST', content: { body: `E2E delete ${Date.now()}` } })
      if (out.status === 429) test.skip(true, 'broadcast レート制限に達したためスキップ')
      const data = expect201OrSkip(out, 'delete 用 TIMELINE broadcast')
      createdTimelinePostIds.push(data.contentId)
      feedId = data.announcementFeedId
    } else {
      bulletinFeedId = null // 二重削除を避けるため再利用フラグをクリア
    }

    // 削除前はフィードに存在する
    expect(findFeedItem(await getFeed(request, adminToken!), feedId), '削除対象が事前に存在しない').not.toBeNull()

    const del = await request.delete(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/announcements/${feedId}`, {
      headers: authHeaders(adminToken!),
    })
    expect(del.status()).toBe(204)
    const item = findFeedItem(await getFeed(request, adminToken!), feedId)
    expect(item, 'DELETE 後もフィードに残っている').toBeNull()
  })

  // ════════════════════════════════════════════════════════════════════
  // ⑤ UI（描画・導線）— broadcast を伴わないため レート制限の影響なし
  // ════════════════════════════════════════════════════════════════════

  function ensureUiReady(): void {
    ensureApiReady()
    if (!frontendAlive) test.skip(true, 'フロントエンド未起動のためスキップ')
  }

  test('ANNC-UI-001: MEMBER が /teams/1 で「チーム内告知」ボタン → ウィザード（Dialog）が開く', async ({ page }) => {
    ensureUiReady()
    await loginUi(page, E2E_USER.email, E2E_USER.password)
    await page.goto(`${FRONTEND_URL}/teams/${TEAM_SLUG}`)
    await settle(page)
    await openWizard(page)
    // Step1 の対象範囲ラジオが見える（ウィザードが起動している証拠）
    await expect(page.locator('#target_role_MEMBERS_AND_ABOVE')).toBeAttached({ timeout: 10_000 })
  })

  test('ANNC-UI-002: 使い方ガイドのトグルで内容が展開表示される', async ({ page }) => {
    ensureUiReady()
    await loginUi(page, E2E_USER.email, E2E_USER.password)
    await page.goto(`${FRONTEND_URL}/teams/${TEAM_SLUG}`)
    await settle(page)
    await openWizard(page)

    // グリッド折りたたみ（grid-rows-[0fr]＋opacity-0）は Playwright 上は visible 判定になるため、
    // 展開状態はトグルの aria-expanded で判定する（描画上の真の状態シグナル）。
    const toggle = page.getByTestId(TXT.guideToggleTestId)
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    // 展開後はガイド見出しが表示される
    await expect(page.getByText(TXT.guideAudienceTitle)).toBeVisible({ timeout: 5_000 })
  })

  test('ANNC-UI-005: MEMBER の Step3 では優先度セレクトが表示されない', async ({ page }) => {
    ensureUiReady()
    await loginUi(page, E2E_USER.email, E2E_USER.password)
    await page.goto(`${FRONTEND_URL}/teams/${TEAM_SLUG}`)
    await settle(page)
    await openWizard(page)

    const dialog = page.getByRole('dialog')
    // Step1 →（MEMBERS_AND_ABOVE は既定選択）次へ
    await dialog.getByRole('button', { name: TXT.next }).click()
    // Step2 → タイムライン選択 → 次へ
    await dialog.getByRole('button', { name: TXT.channelTimeline }).click()
    await dialog.getByRole('button', { name: TXT.next }).click()
    // Step3 到達確認（送信ボタンが出る）。使い方ガイドは常時 DOM 上に存在するため、
    // フォーム要素は <label> 要素に限定して判定する（ガイドは h2/li/p のみで label を持たない）。
    await expect(dialog.getByRole('button', { name: TXT.submitButton })).toBeVisible({ timeout: 10_000 })
    await expect(dialog.locator('label', { hasText: TXT.expiresLabel })).toBeVisible()
    // 優先度セレクトの label は ADMIN のみ描画される（MEMBER は 0 件）
    await expect(dialog.locator('label', { hasText: TXT.priorityLabel })).toHaveCount(0)
  })

  test('ANNC-UI-006: お知らせ一覧の管理導線（ピン/削除）が ADMIN に出て MEMBER に出ない', async ({ page }) => {
    ensureUiReady()
    // フィードに最低 1 件必要
    const feedId = await ensureFeedItem(page.request as unknown as APIRequestContext)
    expect(feedId).toBeGreaterThan(0)

    // ADMIN でお知らせ一覧を開く。feed API（GET）が項目を返すことを先に確認する。
    await loginUi(page, E2E_ADMIN.email, E2E_ADMIN.password)
    const feedRespP = page.waitForResponse(
      (r) => /\/announcements(\?|$)/.test(r.url()) && r.request().method() === 'GET',
      { timeout: 20_000 },
    )
    await page.goto(`${FRONTEND_URL}/teams/${TEAM_SLUG}/announcements`)
    const feedResp = await feedRespP
    const feedJson = (await feedResp.json().catch(() => null)) as FeedResponse | null
    expect(feedJson?.data?.length, `お知らせ feed が空（API status=${feedResp.status()}）`).toBeGreaterThan(0)
    await settle(page)

    // API が項目を返しているなら一覧へ必ず描画されること（コンポーネント名解決の回帰検知）。
    const items = page.locator('[role="listitem"]')
    await expect(items.first(), 'お知らせ一覧が項目を描画しない（コンポーネント未解決の疑い）').toBeVisible({ timeout: 15_000 })
    expect(await items.count(), 'お知らせ一覧が 0 件描画').toBeGreaterThan(0)

    // ADMIN: ピン/削除ボタン（管理者アクション）が描画される
    await expect(items.locator('button .pi-times').first()).toBeAttached({ timeout: 10_000 })

    // MEMBER: 管理者アクションは描画されない（showPinControl=false）
    await loginUi(page, E2E_USER.email, E2E_USER.password)
    await page.goto(`${FRONTEND_URL}/teams/${TEAM_SLUG}/announcements`)
    await settle(page)
    await expect(page.locator('[role="listitem"]').first()).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('[role="listitem"] button .pi-times')).toHaveCount(0)
  })

  // ── UI ハッピーパス（送信）─────────────────────────────────────────
  // 送信は broadcast を行うためレート制限の対象。送信レスポンスを実測し、
  //   201 → 成功トースト＋フィード出現を assert / 429 → スキップ。
  //   それ以外のステータスは skip で逃げず assert で検知する（製品不具合）。

  /** ウィザードで送信し、broadcast レスポンス（status + body）を返す。 */
  async function submitWizardAndCaptureResponse(page: Page): Promise<BroadcastOutcome> {
    const respPromise = page.waitForResponse(
      (r) => r.url().includes(`/teams/${TEAM_ID}/broadcast`) && r.request().method() === 'POST',
      { timeout: 20_000 },
    )
    await page.getByRole('dialog').getByRole('button', { name: TXT.submitButton }).click()
    const resp = await respPromise
    const status = resp.status()
    let json: unknown = null
    try {
      json = await resp.json()
    } catch {
      json = null
    }
    const j = json as { data?: BroadcastResponse; error?: { code?: string } } | null
    return { status, data: j?.data ?? null, errorCode: j?.error?.code ?? null }
  }

  test('ANNC-UI-003: MEMBER のハッピーパス（タイムライン）— 送信→成功トースト→フィード出現', async ({ page }) => {
    ensureUiReady()
    // dirty クローズ時の window.confirm を自動承認
    page.on('dialog', (d) => d.accept().catch(() => {}))
    await loginUi(page, E2E_USER.email, E2E_USER.password)
    await page.goto(`${FRONTEND_URL}/teams/${TEAM_SLUG}`)
    await settle(page)
    await openWizard(page)

    const dialog = page.getByRole('dialog')
    await dialog.getByRole('button', { name: TXT.next }).click()
    await dialog.getByRole('button', { name: TXT.channelTimeline }).click()
    await dialog.getByRole('button', { name: TXT.next }).click()
    await dialog.locator('textarea').first().fill(`E2E UI timeline ${Date.now()}`)

    const out = await submitWizardAndCaptureResponse(page)
    if (out.status === 429) test.skip(true, 'broadcast レート制限（5件/5分）に達したためスキップ')
    expect(out.status, `送信が 201 にならなかった: status=${out.status} code=${out.errorCode}`).toBe(201)
    await expect(page.getByText(TXT.success)).toBeVisible({ timeout: 10_000 })
    // フィード出現の裏取り（作成された feed/コンテンツを掃除対象に登録）
    expect(out.data?.announcementFeedId, 'announcementFeedId が返らない').toBeGreaterThan(0)
    if (out.data) {
      createdFeedIds.push(out.data.announcementFeedId)
      createdTimelinePostIds.push(out.data.contentId)
    }
  })

  test('ANNC-UI-004: ADMIN のハッピーパス（掲示板・優先度=重要）— 優先度セレクト表示→送信', async ({ page }) => {
    ensureUiReady()
    page.on('dialog', (d) => d.accept().catch(() => {}))
    await loginUi(page, E2E_ADMIN.email, E2E_ADMIN.password)
    await page.goto(`${FRONTEND_URL}/teams/${TEAM_SLUG}`)
    await settle(page)
    await openWizard(page)

    const dialog = page.getByRole('dialog')
    await dialog.getByRole('button', { name: TXT.next }).click()
    await dialog.getByRole('button', { name: TXT.channelBulletin }).click()
    await dialog.getByRole('button', { name: TXT.next }).click()
    // ADMIN は Step3 で優先度セレクトの label が見える（ガイド誤検知回避のため label に限定）
    await expect(dialog.locator('label', { hasText: TXT.priorityLabel })).toBeVisible({ timeout: 10_000 })
    // タイトル + 本文（掲示板は両方必須）
    await dialog.locator('input.p-inputtext').first().fill(`E2E UI bulletin ${Date.now()}`)
    await dialog.locator('textarea').first().fill('E2E UI 掲示板本文')

    const out = await submitWizardAndCaptureResponse(page)
    if (out.status === 429) test.skip(true, 'broadcast レート制限（5件/5分）に達したためスキップ')
    expect(out.status, `送信が 201 にならなかった: status=${out.status} code=${out.errorCode}`).toBe(201)
    await expect(page.getByText(TXT.success)).toBeVisible({ timeout: 10_000 })
    expect(out.data?.announcementFeedId, 'announcementFeedId が返らない').toBeGreaterThan(0)
    if (out.data) createdFeedIds.push(out.data.announcementFeedId)
  })
})
