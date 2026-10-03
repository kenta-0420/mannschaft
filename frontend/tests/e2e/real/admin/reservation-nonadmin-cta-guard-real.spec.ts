/**
 * 予約タブ「予約対象あり・枠ゼロ」象限での非管理者向け管理CTA非表示 — 実機フルスタック E2E テスト
 *
 * 御下命（マスターの明示懸念事項）:
 *   TeamReservationsPanel の空状態導線（PR #2148 / #2151）は役割別に出し分ける設計で、
 *   SlotPicker.vue / SlotGridPicker.vue の管理CTAは `<template v-if="isAdmin" #action>` で
 *   ガードされている。既存の実機E2E（reservation-empty-onboarding-real.spec.ts）は
 *   「予約対象ゼロ」象限（member_no_lines）の非管理者視点は撮っているが、
 *   「予約対象はあるが枠ゼロ」象限（member_no_slots）の非管理者視点の実機スクショが
 *   未取得だった。本specはその欠落を埋め、非管理者（SUPPORTER/MEMBER）に対して
 *   「枠を管理する」「予約対象の管理へ」ボタン・「予約対象の管理」タブが
 *   一切表示されないことを実ブラウザで確実に実証する。
 *
 * バックエンド http://localhost:8080（本陣・稼働中/停止厳禁）
 * フロントエンド http://localhost:3003（このworktreeで起動した検証用 dev server。
 *   :3001 が他worktreeのゾンビ占有だったため空きポートへ移動した実績あり）
 * playwright.config.ts の chromium-real-admin プロジェクト（real/admin/ 配下）で動く。
 *
 * 検証対象（マスター懸念の核心）:
 *   - SlotMatrixPicker: 予約対象1件・枠0件のとき member_no_slots
 *     （「この週は空き枠がありません」+「別の週を選んでお試しください」）が出て、
 *     管理CTA（「枠を管理する」）は isAdmin=false のとき描画されない。
 *
 * 【表示一本化に伴う追随（PR #2574 旧表示撤去 → 本PRで枠ゼロ空状態をマトリックスへ実装）】
 *   枠ゼロ空状態の出し手が SlotPicker/SlotGridPicker からマトリックス（週表示）へ移り、
 *   文言が日単位「この日は…」から週単位「この週は…」になった。また「リスト/グリッド表示」
 *   切替が消えたため旧AC-6（両表示での確認）はページ全体走査に置き換えた。
 *   検証の実質（非管理者に管理CTA・管理タブが一切出ない）は不変。
 *   - TeamReservationsPanel: 「予約対象の管理」タブ自体が v-if="isAdmin" で
 *     非管理者には描画されない。
 *   - 対照として ADMIN で同じ枠ゼロ状態を開くと「枠を管理する」CTA が出ることも撮る。
 *
 * テストユーザー:
 *   - ADMIN: e2e-admin@test.mannschaft.local（使い捨てチーム作成者）
 *   - SUPPORTER: e2e-dummy-7@test.mannschaft.local（team follow で SUPPORTER 化。
 *     memory: feedback_authz_e2e_seed_membership_pollution で流通実績あり）
 *   - MEMBER: e2e-user@test.mannschaft.local（招待トークン roleId=4 経由で参加。
 *     reservation-authz-gate-real.spec.ts で「fc-u-18 MEMBER」として既に流通実績あり。
 *     本specの使い捨てチームには未所属なので、招待トークン参加で新規に MEMBER 化する）
 *
 * 後始末: 作成した予約対象（line）は削除する。使い捨てチームは無害なため放置可
 *   （teamアーカイブ/削除APIは本検証の対象外）。SUPPORTER/MEMBER の所属解除は行わない
 *   （使い捨てチームなので汚染の実害なし。招待トークンは失効させる）。
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
const FE_ORIGIN = process.env.BASE_URL ?? 'http://localhost:3003'
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
// fc-u-18 に所属しない dummy。本spec内で follow して SUPPORTER 化する。
const SUPPORTER_EMAIL = process.env.TEST_SUPPORTER_EMAIL ?? 'e2e-dummy-7@test.mannschaft.local'
const SUPPORTER_PASSWORD = process.env.TEST_SUPPORTER_PASSWORD ?? 'TestPass2026!'
// fc-u-18 の MEMBER だが本specの使い捨てチームには未所属。招待トークン参加で新規 MEMBER 化する。
const MEMBER_EMAIL = process.env.TEST_MEMBER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const MEMBER_PASSWORD = process.env.TEST_MEMBER_PASSWORD ?? 'TestPass2026!'

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

/**
 * ログインして accessToken を返す。
 *
 * 複数 worker（setup-real-admin + chromium-real-admin の並列テスト）が同時刻にログインを
 * バーストすると、BE が一過性の 500 COMMON_999 を返すことが実機で確認された
 * （memory: reservation-authz-gate-real.spec.ts の既知の落とし穴と同一事象）。
 * 認可検証の本筋ではないインフラ起因のノイズなので、指数バックオフで吸収する。
 */
async function login(ctx: APIRequestContext, email: string, password: string): Promise<string> {
  const maxAttempts = 6
  let lastErr = ''
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    const res = await ctx.post(`${BE}/api/v1/auth/login`, {
      headers: { 'Content-Type': 'application/json' },
      data: { email, password },
    })
    if (res.ok()) return (await res.json()).data.accessToken as string
    const status = res.status()
    lastErr = `${status} ${await res.text()}`
    // 401/403 は認証情報そのものの誤りなので即時失敗（リトライしても無駄）
    if (status === 401 || status === 403) break
    await new Promise((r) => setTimeout(r, 400 * attempt + Math.floor(Math.random() * 300)))
  }
  throw new Error(`ログイン失敗(${email}): ${lastErr}`)
}

async function fetchMe(ctx: APIRequestContext, token: string): Promise<MeProfile> {
  const res = await ctx.get(`${BE}/api/v1/users/me`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!res.ok()) throw new Error(`/users/me 失敗: ${res.status()}`)
  return (await res.json()).data as MeProfile
}

/**
 * 使い捨てチームを新規作成する（予約対象1件・枠ゼロの member_no_slots 状態を意図的に作る）。
 *
 * 【根治】名前を日本語のみにすると BE の SlugGenerator が非ASCII文字を除去し、
 * スラッグが純粋な数字列になり実在しないチームへ遷移する不具合が実機で確認されている
 * （reservation-empty-onboarding-real.spec.ts の既知の落とし穴）。ASCII プレフィックスを付ける。
 */
async function createThrowawayTeam(
  ctx: APIRequestContext,
  adminToken: string,
): Promise<{ slug: string }> {
  const res = await ctx.post(`${BE}/api/v1/teams`, {
    headers: authHeaders(adminToken),
    data: { name: `RsvGuard_${Date.now()}` },
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

/** SUPPORTER 化: チームフォロー（既定 autoApprove=true で即時 SUPPORTER メンバーシップが付与される）。 */
async function followTeam(ctx: APIRequestContext, supporterToken: string, slug: string): Promise<void> {
  const res = await ctx.post(`${BE}/api/v1/teams/${slug}/follow`, {
    headers: authHeaders(supporterToken),
  })
  if (!res.ok()) throw new Error(`フォロー失敗: ${res.status()} ${await res.text()}`)
}

/**
 * MEMBER 化: 招待トークン（roleId=4=メンバー。InviteTokenList.vue の roleOptions と同一値）を
 * ADMIN が発行し、対象ユーザーが参加することで memberships に role_kind=MEMBER で入会させる
 * （InviteService.joinByInvite は roleId に関わらず membership 側は MEMBER 固定・§F00.5 根治）。
 */
async function createInviteToken(
  ctx: APIRequestContext,
  adminToken: string,
  slug: string,
): Promise<{ token: string; id: number }> {
  const res = await ctx.post(`${BE}/api/v1/teams/${slug}/invite-tokens`, {
    headers: authHeaders(adminToken),
    data: { roleId: 4, expiresIn: '1d', maxUses: 5 },
  })
  if (!res.ok()) throw new Error(`招待トークン作成失敗: ${res.status()} ${await res.text()}`)
  return (await res.json()).data as { token: string; id: number }
}

async function joinByInvite(ctx: APIRequestContext, memberToken: string, inviteToken: string): Promise<void> {
  const res = await ctx.post(`${BE}/api/v1/invite/${inviteToken}/join`, {
    headers: authHeaders(memberToken),
  })
  if (!res.ok()) throw new Error(`招待参加失敗: ${res.status()} ${await res.text()}`)
}

async function revokeInviteToken(
  ctx: APIRequestContext,
  adminToken: string,
  slug: string,
  tokenId: number,
): Promise<void> {
  await ctx
    .delete(`${BE}/api/v1/teams/${slug}/invite-tokens/${tokenId}`, {
      headers: authHeaders(adminToken),
    })
    .catch(() => {})
}

/**
 * APIブリッジ（memory: feedback_e2e_wsl2_cors_apibridge）。写経元と同一実装。
 * ブラウザの /api/v1 fetch を横取りし、Playwright の APIRequestContext で BE(8080) へ中継する。
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
      // ページ/コンテキストが閉じかけている等の一過性競合。単体を諦め全体を落とさない。
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

const test = base.extend<
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type -- test-scoped の追加 fixture は無い
  {},
  {
    tokens: {
      admin: string
      adminMe: MeProfile
      supporter: string
      supporterMe: MeProfile
      member: string
      memberMe: MeProfile
    }
  }
>({
  // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
  storageState: async ({}, use) => {
    await use(undefined)
  },
  tokens: [
    // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
    async ({}, use) => {
      const ctx = await playwrightRequest.newContext()
      // 直列＋小休止（同時バーストによる BE の一過性 500 を避ける。login() 自体もリトライする）
      const gap = () => new Promise((r) => setTimeout(r, 250))
      const admin = await login(ctx, ADMIN_EMAIL, ADMIN_PASSWORD)
      const adminMe = await fetchMe(ctx, admin)
      await gap()
      const supporter = await login(ctx, SUPPORTER_EMAIL, SUPPORTER_PASSWORD)
      const supporterMe = await fetchMe(ctx, supporter)
      await gap()
      const member = await login(ctx, MEMBER_EMAIL, MEMBER_PASSWORD)
      const memberMe = await fetchMe(ctx, member)
      await ctx.dispose()
      await use({ admin, adminMe, supporter, supporterMe, member, memberMe })
    },
    { scope: 'worker' },
  ],
})

test.setTimeout(120_000)

test.describe('RSV-NONADMIN-CTA-GUARD: 予約対象あり・枠ゼロ象限での非管理者向け管理CTA非表示', () => {
  let teamSlug = ''
  let createdLineId: number | null = null
  let inviteTokenId: number | null = null

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    const team = await createThrowawayTeam(ctx, tokens.admin)
    teamSlug = team.slug
    await enableReservationModule(ctx, tokens.admin, teamSlug)

    // 予約対象を1件だけ作成し、枠は作らない（= member_no_slots を作る条件。対象ゼロではない）。
    const line = await createLine(ctx, tokens.admin, teamSlug, `検証用対象_${Date.now()}`)
    createdLineId = line.id

    // SUPPORTER化
    await followTeam(ctx, tokens.supporter, teamSlug)

    // MEMBER化（招待トークン経由）
    const invite = await createInviteToken(ctx, tokens.admin, teamSlug)
    inviteTokenId = invite.id
    await joinByInvite(ctx, tokens.member, invite.token)

    await ctx.dispose()
  })

  test.afterAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    if (createdLineId) await deleteLine(ctx, tokens.admin, teamSlug, createdLineId)
    if (inviteTokenId) await revokeInviteToken(ctx, tokens.admin, teamSlug, inviteTokenId)
    await ctx.dispose()
  })

  test('AC-SUPPORTER: SUPPORTER視点は枠ゼロで管理CTA・管理タブが一切出ない', async ({
    page,
    tokens,
  }) => {
    await installApiBridge(page, tokens.supporter)
    await seedBrowserAuth(page, tokens.supporterMe)

    await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    // member_no_slots メッセージ + ヒント
    await expect(
      page.getByText('この週は空き枠がありません'),
      'member_no_slots（予約対象はあるが枠ゼロの非管理者向けメッセージ）が表示されること',
    ).toBeVisible({ timeout: 20_000 })
    await expect(
      page.getByText('別の週を選んでお試しください'),
      'member_no_slots_hint が表示されること',
    ).toBeVisible({ timeout: 10_000 })

    // 【最重要アサート】管理CTAが一切出ない
    await expect(
      page.getByRole('button', { name: '枠を管理する' }),
      'SUPPORTERには「枠を管理する」ボタンが出ないこと',
    ).toHaveCount(0)
    await expect(
      page.getByRole('button', { name: '予約対象の管理へ' }),
      'SUPPORTERには「予約対象の管理へ」ボタンが出ないこと',
    ).toHaveCount(0)
    // 「予約対象の管理」タブ自体が非表示（v-if="isAdmin"）
    await expect(
      page.getByRole('tab', { name: '予約対象の管理' }),
      'SUPPORTERには「予約対象の管理」タブ自体が出ないこと',
    ).toHaveCount(0)

    await page.screenshot({
      path: 'test-results/member-no-slots-supporter.png',
      fullPage: true,
    })

    // 【PR #2574 追随】旧「リスト表示/グリッド表示」の切替ボタンは撤去され、予約枠の表示は
    // マトリックス（SlotMatrixPicker）一本になった。よって「もう一方の表示でも管理CTAが出ない」
    // という旧AC-6の確認対象そのものが消滅している。検証の実質を落とさないよう、代わりに
    // 「枠ゼロ空状態が出ている間、ページ全体のどこにも管理CTAが存在しない」ことを
    // 明示アサートする（切替表示ぶんの網羅をページ全体走査で置き換える）。
    await expect(
      page.getByTestId('matrix-no-slots-empty'),
      '枠ゼロ空状態がマトリックス（唯一の表示）で出ていること',
    ).toBeVisible({ timeout: 15_000 })
    await expect(
      page.getByRole('button', { name: 'グリッド表示' }),
      'PR #2574 で表示切替ボタンは撤去済み（残っていれば撤去漏れの回帰）',
    ).toHaveCount(0)
  })

  test('AC-MEMBER: MEMBER視点は枠ゼロで管理CTA・管理タブが一切出ない', async ({ page, tokens }) => {
    await installApiBridge(page, tokens.member)
    await seedBrowserAuth(page, tokens.memberMe)

    await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    await expect(
      page.getByText('この週は空き枠がありません'),
      'member_no_slots（予約対象はあるが枠ゼロの非管理者向けメッセージ）が表示されること',
    ).toBeVisible({ timeout: 20_000 })
    await expect(
      page.getByText('別の週を選んでお試しください'),
      'member_no_slots_hint が表示されること',
    ).toBeVisible({ timeout: 10_000 })

    await expect(
      page.getByRole('button', { name: '枠を管理する' }),
      'MEMBERには「枠を管理する」ボタンが出ないこと',
    ).toHaveCount(0)
    await expect(
      page.getByRole('button', { name: '予約対象の管理へ' }),
      'MEMBERには「予約対象の管理へ」ボタンが出ないこと',
    ).toHaveCount(0)
    await expect(
      page.getByRole('tab', { name: '予約対象の管理' }),
      'MEMBERには「予約対象の管理」タブ自体が出ないこと',
    ).toHaveCount(0)

    await page.screenshot({
      path: 'test-results/member-no-slots-member.png',
      fullPage: true,
    })
  })

  test('AC-ADMIN-CONTRAST: 対照 - ADMIN視点は同じ枠ゼロ状態で「枠を管理する」CTAが出る', async ({
    page,
    tokens,
  }) => {
    await installApiBridge(page, tokens.admin)
    await seedBrowserAuth(page, tokens.adminMe)

    await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    await expect(
      page.getByText('この週は空き枠がありません'),
      'admin_no_slots（管理者向けメッセージ・文言はmemberと同一）が表示されること',
    ).toBeVisible({ timeout: 20_000 })

    await expect(
      page.getByRole('button', { name: '枠を管理する' }),
      '対照: ADMINには「枠を管理する」CTAが出ること',
    ).toBeVisible({ timeout: 10_000 })
    await expect(
      page.getByRole('tab', { name: '予約対象の管理' }),
      '対照: ADMINには「予約対象の管理」タブが出ること',
    ).toBeVisible({ timeout: 10_000 })

    await page.screenshot({
      path: 'test-results/admin-no-slots-contrast.png',
      fullPage: true,
    })
  })
})
