/**
 * チーム予約タブ「空初回体験」導線根治（PR #2148・main済 8fd44208）— 実機フルスタック E2E テスト
 *
 * 御下命（今回の実機検証の肝）:
 *   過去の実機E2Eは seed 済み（予約対象/枠が既にある）チームで実行しており、
 *   「予約対象・枠が1件も無い新規チーム」を素通りしていた。
 *   本specは **使い捨てチームを新規作成**し、意図的に空初回状態（予約対象ゼロ）から
 *   検証することで、PR #2148 の役割別空状態導線を実証する。
 *
 * バックエンド http://localhost:8080（本陣・稼働中/停止厳禁）
 * フロントエンド http://localhost:3001（このworktreeで起動した検証用 dev server）
 * playwright.config.ts の chromium-real-admin プロジェクト（real/admin/ 配下）で動く。
 *
 * 検証対象（PR #2148 の変更点）:
 *   - SlotMatrixPicker: 予約対象ゼロ時は reservation.empty.book.admin_no_lines
 *     （「予約対象がまだありません」）+ CTA「予約対象の管理へ」（管理者のみ）。
 *     「この日の空き枠はありません」ではないことが回帰の要点。
 *   - CTA クリックで内側タブが activeTab=2「予約対象の管理」に切り替わる。
 *   - TeamReservationsPanel 上部の「使い方」ボタンでガイドモーダルが開き、
 *     管理者向け3ステップカード（①予約対象を作る/②予約枠を作る/③公開範囲を決める）を表示。
 *   - 予約対象を1件作ると（枠はまだ0件）admin_no_slots
 *     （「この週は空き枠がありません」）+ CTA「枠を管理する」に切り替わる。
 *
 * 【表示一本化に伴う追随（PR #2574 で旧 SlotPicker 撤去・本PRで枠ゼロ空状態をマトリックスへ実装）】
 *   枠ゼロ空状態はマトリックス（週表示）が出すため、文言が日単位「この日は…」から
 *   週単位「この週は…」に変わった。検証内容（管理者だけが枠ゼロ空状態＋管理CTAを見る）は不変。
 *
 * テストユーザー: e2e-admin@test.mannschaft.local（ADMIN・システム管理者相当）
 *
 * 後始末: 作成した予約対象（line）は削除する。使い捨てチームは無害なため放置可
 *   （teamアーカイブ/削除APIは本検証の対象外）。
 */

import {
  test as base,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const BE = 'http://localhost:8080'
const FE_ORIGIN = process.env.BASE_URL ?? 'http://localhost:3001'
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

interface MeProfile {
  id: number
  email: string
  lastName: string
  firstName: string
  avatarUrl: string | null
  systemRole: string | null
  timezone: string | null
}

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

async function login(ctx: APIRequestContext, email: string, password: string): Promise<string> {
  const res = await ctx.post(`${BE}/api/v1/auth/login`, {
    headers: { 'Content-Type': 'application/json' },
    data: { email, password },
  })
  if (!res.ok()) throw new Error(`ログイン失敗(${email}): ${res.status()} ${await res.text()}`)
  return (await res.json()).data.accessToken as string
}

async function fetchMe(ctx: APIRequestContext, token: string): Promise<MeProfile> {
  const res = await ctx.get(`${BE}/api/v1/users/me`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!res.ok()) throw new Error(`/users/me 失敗: ${res.status()}`)
  return (await res.json()).data as MeProfile
}

/** 使い捨てチームを新規作成する（予約対象・枠ゼロの空初回状態を意図的に作る）。 */
async function createThrowawayTeam(
  ctx: APIRequestContext,
  adminToken: string,
): Promise<{ slug: string }> {
  // 【根治】名前を日本語のみ（例: "予約導線検証_<timestamp>"）にすると、BE の SlugGenerator が
  // 非ASCII文字を除去した結果、スラッグが純粋な数字列（timestamp 部分のみ）になってしまい、
  // 実在しないチームへ遷移して「情報を取得できませんでした」に落ちる不具合を実機で確認した
  // （2026-07-05）。ASCII プレフィックスを付け、有効なスラッグが生成されるようにする。
  const res = await ctx.post(`${BE}/api/v1/teams`, {
    headers: authHeaders(adminToken),
    data: { name: `RSV_Onboard_予約導線検証_${Date.now()}` },
  })
  if (!res.ok()) throw new Error(`チーム作成失敗: ${res.status()} ${await res.text()}`)
  const data = (await res.json()).data as { slug: string }
  return { slug: data.slug }
}

/** チーム機能カタログから reservation モジュールの moduleId を取得し、有効化する。 */
async function enableReservationModule(
  ctx: APIRequestContext,
  adminToken: string,
  slug: string,
): Promise<void> {
  const catalogRes = await ctx.get(`${BE}/api/v1/teams/${slug}/modules/catalog`, {
    headers: authHeaders(adminToken),
  })
  if (!catalogRes.ok()) {
    throw new Error(`モジュールカタログ取得失敗: ${catalogRes.status()} ${await catalogRes.text()}`)
  }
  const catalog = (await catalogRes.json()).data as {
    modules: { moduleId: number; slug: string; isEnabled: boolean }[]
  }
  const reservationModule = catalog.modules.find((m) => m.slug === 'reservation')
  if (!reservationModule) {
    throw new Error('カタログに reservation モジュールが見つからない')
  }
  const toggleRes = await ctx.patch(
    `${BE}/api/v1/teams/${slug}/modules/${reservationModule.moduleId}/toggle`,
    {
      headers: authHeaders(adminToken),
      data: { moduleId: reservationModule.moduleId, enabled: true },
    },
  )
  if (!toggleRes.ok()) {
    throw new Error(`予約モジュール有効化失敗: ${toggleRes.status()} ${await toggleRes.text()}`)
  }
}

async function createLine(
  ctx: APIRequestContext,
  adminToken: string,
  slug: string,
  name: string,
): Promise<{ id: number }> {
  const res = await ctx.post(`${BE}/api/v1/teams/${slug}/reservation-lines`, {
    headers: authHeaders(adminToken),
    data: { name },
  })
  if (!res.ok()) throw new Error(`createLine 失敗: ${res.status()} ${await res.text()}`)
  return (await res.json()).data
}

async function deleteLine(
  ctx: APIRequestContext,
  adminToken: string,
  slug: string,
  lineId: number,
): Promise<void> {
  await ctx
    .delete(`${BE}/api/v1/teams/${slug}/reservation-lines/${lineId}`, {
      headers: authHeaders(adminToken),
    })
    .catch(() => {})
}

/**
 * APIブリッジ（memory: feedback_e2e_wsl2_cors_apibridge）。
 * ブラウザの /api/v1 fetch を横取りし、Playwright の APIRequestContext で BE(8080) へ中継する。
 * ACAO をブラウザ実 origin に固定。
 *
 * 【根治】installApiBridge を page.goto より前（page.url()==='about:blank'）に呼ぶ既存specの写経では
 * `new URL('about:blank').origin` が文字列 "null" になり、ACAOへ不正値が入る。
 * NUXT_API_PROXY=true（同一オリジン化）で動かす限り実害は出ないが、本specはプロキシなしdevサーバー
 * （絶対URL:8080への真のクロスオリジン fetch）で動かすため、ブラウザの本物のCORSチェックに弾かれ
 * 「情報を取得できませんでした」の空データフォールバックに落ちる実害が出た（2026-07-05 実機で確認）。
 * 個々のリクエストが実際に運んでくる Origin ヘッダーから都度算出することで、about:blank由来の
 * 汚染を避ける。
 *
 * 【根治その2】ページ初回描画時は /api/v1/** への並行フェッチが20〜30本同時に発火する
 * （ナビゲーション未読数・通知・チーム詳細・予約設定 等）。まれに `page.request.fetch` が
 * "Target page, context or browser has been closed" で失敗する個体があり（バックグラウンド
 * ポーリングとテスト遷移の競合等）、try/catch なしだと route コールバック内の例外が
 * 未処理の rejection としてテスト全体を強制失敗させる実害を実機で確認した。
 * 1本のリクエスト失敗でテスト全体を落とさないよう route.abort() に握り替える。
 */
async function installApiBridge(page: Page, token: string): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const target = `http://127.0.0.1:8080${url.pathname}${url.search}`
    const pageOrigin = req.headers()['origin'] || FE_ORIGIN
    const headers: Record<string, string> = {
      ...req.headers(),
      origin: 'http://localhost:3000',
      referer: 'http://localhost:3000/',
      authorization: `Bearer ${token}`,
    }
    try {
      const relay = await page.request.fetch(target, {
        method: req.method(),
        headers,
        data: req.postData() ?? undefined,
        maxRedirects: 0,
      })
      const respHeaders: Record<string, string> = { ...relay.headers() }
      respHeaders['access-control-allow-origin'] = pageOrigin
      respHeaders['access-control-allow-credentials'] = 'true'
      await route.fulfill({
        status: relay.status(),
        headers: respHeaders,
        body: await relay.body(),
      })
    }
    catch {
      // ページ/コンテキストが閉じかけている等の一過性競合。このリクエスト単体を諦め、
      // テスト全体を未処理rejectionで落とさない（症状を隠さず、単に握りつぶさず中断応答にする）。
      await route.abort().catch(() => {})
    }
  })
}

async function seedBrowserAuth(page: Page, me: MeProfile): Promise<void> {
  const currentUser = {
    id: me.id,
    email: me.email,
    fullName: `${me.lastName} ${me.firstName}`,
    profileImageUrl: me.avatarUrl,
    systemRole: me.systemRole ?? undefined,
    timezone: me.timezone ?? undefined,
  }
  const farFuture = Date.now() + 24 * 60 * 60 * 1000
  await page.addInitScript(
    ({ user, expiresAt }) => {
      localStorage.setItem('currentUser', JSON.stringify(user))
      localStorage.setItem('tokenExpiresAt', String(expiresAt))
    },
    { user: currentUser, expiresAt: farFuture },
  )
}

// AC-4（一般会員視点）用: 検証専用の使い捨てユーザー。認可E2Eで既に流通実績のある捨てユーザー
// （memory: feedback_authz_e2e_seed_membership_pollution）を流用する。
const MEMBER_EMAIL = process.env.TEST_SUPPORTER_EMAIL ?? 'e2e-dummy-7@test.mannschaft.local'
const MEMBER_PASSWORD = process.env.TEST_SUPPORTER_PASSWORD ?? 'TestPass2026!'

const test = base.extend<
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type -- test-scoped の追加 fixture は無い
  {},
  { tokens: { admin: string; adminMe: MeProfile; member: string; memberMe: MeProfile } }
>({
  // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
  storageState: async ({}, use) => {
    await use(undefined)
  },
  tokens: [
    // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
    async ({}, use) => {
      const ctx = await playwrightRequest.newContext()
      const admin = await login(ctx, ADMIN_EMAIL, ADMIN_PASSWORD)
      const adminMe = await fetchMe(ctx, admin)
      const member = await login(ctx, MEMBER_EMAIL, MEMBER_PASSWORD)
      const memberMe = await fetchMe(ctx, member)
      await ctx.dispose()
      await use({ admin, adminMe, member, memberMe })
    },
    { scope: 'worker' },
  ],
})

test.setTimeout(120_000)

test.describe('RSV-EMPTY-ONBOARD: 予約タブの空初回体験（役割別導線・使い方ガイド）', () => {
  let teamSlug = ''
  let createdLineId: number | null = null

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    const team = await createThrowawayTeam(ctx, tokens.admin)
    teamSlug = team.slug
    await enableReservationModule(ctx, tokens.admin, teamSlug)
    await ctx.dispose()
  })

  test.afterAll(async ({ tokens }) => {
    if (createdLineId) {
      const ctx = await playwrightRequest.newContext()
      await deleteLine(ctx, tokens.admin, teamSlug, createdLineId)
      await ctx.dispose()
    }
  })

  test('AC-1/2: 予約対象ゼロで「予約対象がまだありません」+ CTA「予約対象の管理へ」が出る（「空き枠なし」ではない）', async ({
    page,
    tokens,
  }) => {
    await installApiBridge(page, tokens.admin)
    await seedBrowserAuth(page, tokens.adminMe)

    await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    // 既定タブ「予約する」が開いている前提でメッセージを確認
    await expect(
      page.getByText('予約対象がまだありません'),
      'admin_no_lines（予約対象ゼロの管理者向けメッセージ）が表示されること',
    ).toBeVisible({ timeout: 20_000 })

    await expect(
      page.getByText('この日の空き枠はありません'),
      '回帰対象: 予約対象ゼロなのに「空き枠なし」（no_available_slots）が出てはいけない',
    ).toHaveCount(0)

    const ctaButton = page.getByRole('button', { name: '予約対象の管理へ' })
    await expect(ctaButton, 'CTA「予約対象の管理へ」（管理者のみ）が表示されること').toBeVisible({
      timeout: 10_000,
    })

    await page.screenshot({
      path: 'test-results/rsv-empty-onboard-01-admin-no-lines.png',
      fullPage: true,
    })

    // AC-2: CTA クリックで内側タブが「予約対象の管理」に切り替わる
    await ctaButton.click()
    await expect(
      page.getByRole('button', { name: '予約対象を追加' }),
      'CTA クリックで「予約対象の管理」タブ（LineManagerの追加ボタン）に切り替わること',
    ).toBeVisible({ timeout: 10_000 })

    await page.screenshot({
      path: 'test-results/rsv-empty-onboard-02-line-manage-tab.png',
      fullPage: true,
    })
  })

  test('AC-8/9: 「使い方」ボタンでガイドモーダルが開き、管理者向け3ステップカードが表示される', async ({
    page,
    tokens,
  }) => {
    await installApiBridge(page, tokens.admin)
    await seedBrowserAuth(page, tokens.adminMe)

    await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    const helpButton = page.getByRole('button', { name: '使い方' })
    await expect(helpButton, '「使い方」ボタンが常時表示されること').toBeVisible({ timeout: 15_000 })
    await helpButton.click()

    const modal = page.getByTestId('team-reservation-guide-modal')
    await expect(modal, '使い方ガイドモーダルが開くこと').toBeVisible({ timeout: 10_000 })

    await expect(modal.getByText('① 予約対象を作る')).toBeVisible()
    await expect(modal.getByText('② 予約枠を作る')).toBeVisible()
    await expect(modal.getByText('③ 公開範囲を決める')).toBeVisible()

    await page.screenshot({
      path: 'test-results/rsv-empty-onboard-03-guide-modal.png',
      fullPage: true,
    })

    // Dialog にはヘッダーの既定×ボタン（アイコンのみ・aria-label="閉じる"）と、
    // フッターの独自「閉じる」ボタン（visible text）の2つが同じ accessible name を持つ。
    // フッター側（DOM順で後）を明示的に狙う。
    await modal.getByRole('button', { name: '閉じる' }).last().click()
    await expect(modal).toBeHidden({ timeout: 5_000 })
  })

  test('AC-4: 一般会員視点は予約対象ゼロで「受付準備中」が出て管理CTAは出ない', async ({
    page,
    tokens,
    request,
  }) => {
    // 使い捨てチームをフォローして所属化する（isMember/SUPPORTER のいずれかで isAffiliated=true にする）。
    const followRes = await request.post(`${BE}/api/v1/teams/${teamSlug}/follow`, {
      headers: authHeaders(tokens.member),
    })
    if (!followRes.ok()) {
      throw new Error(`フォロー失敗: ${followRes.status()} ${await followRes.text()}`)
    }

    await installApiBridge(page, tokens.member)
    await seedBrowserAuth(page, tokens.memberMe)

    await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    await expect(
      page.getByText('予約の受付準備中です'),
      'member_no_lines（予約対象ゼロの一般会員向けメッセージ）が表示されること',
    ).toBeVisible({ timeout: 20_000 })

    await expect(
      page.getByRole('button', { name: '予約対象の管理へ' }),
      '一般会員には管理CTAが出ないこと',
    ).toHaveCount(0)

    await page.screenshot({
      path: 'test-results/rsv-empty-onboard-05-member-no-lines.png',
      fullPage: true,
    })

    // 後始末: フォローを取り消して所属を解く（他specへの汚染防止）。
    await request.delete(`${BE}/api/v1/teams/${teamSlug}/follow`, {
      headers: authHeaders(tokens.member),
    }).catch(() => {})
  })

  test('AC-3: 予約対象を1件作ると「この週は空き枠がありません」+ CTA「枠を管理する」に変わる', async ({
    page,
    tokens,
    request,
  }) => {
    const line = await createLine(request, tokens.admin, teamSlug, `検証用対象_${Date.now()}`)
    createdLineId = line.id

    await installApiBridge(page, tokens.admin)
    await seedBrowserAuth(page, tokens.adminMe)

    await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    await expect(
      page.getByText('この週は空き枠がありません'),
      'admin_no_slots（予約対象はあるが枠ゼロの管理者向けメッセージ）が表示されること',
    ).toBeVisible({ timeout: 20_000 })

    await expect(
      page.getByRole('button', { name: '枠を管理する' }),
      'CTA「枠を管理する」（管理者のみ）が表示されること',
    ).toBeVisible({ timeout: 10_000 })

    await page.screenshot({
      path: 'test-results/rsv-empty-onboard-04-admin-no-slots.png',
      fullPage: true,
    })
  })
})
