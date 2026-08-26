/**
 * チーム詳細ページ（/teams/[slug]）の「予約」タブ — 実機フルスタックE2Eテスト
 *
 * バックエンド :8080 / フロントエンド :3000(or :3001) 起動済みで実行する。
 * chromium-real-admin プロジェクト（real/admin/ 配下）で動く。--workers=1 推奨。
 *
 * 前提: 対象チーム（fc-u-18）で reservation モジュールが有効であること
 *       （PATCH /teams/{slug}/modules/{moduleId}/toggle で事前に有効化しておく）。
 *
 * 検証目的:
 *   PR #1984 で追加したチーム詳細ページの「予約」タブが、予約モジュール有効時に
 *   実ブラウザで表示され、クリックで予約パネルが開くことを実証する。
 *
 *   【根本バグ（このテストが守る回帰）】
 *   GET /teams/{id}/modules の BE レスポンスは moduleSlug を返すが、FE の手書き
 *   interface TeamModuleItem が slug と誤宣言（型アサーションで typecheck 素通り）し、
 *   `m.slug === 'reservation'` が常に false → 予約タブ/チームサイドバーのモジュール連動
 *   項目/予約ウィジェット導線が全て非表示だった。fix(#2005) で moduleSlug に是正。
 *
 * テストユーザー: e2e-admin@test.mannschaft.local（fc-u-18 ADMIN / SYSTEM_ADMIN）
 */

import { test as base, expect, request as playwrightRequest } from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const BE = 'http://localhost:8080'
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
const TEAM_SLUG = 'fc-u-18'

interface MeProfile {
  id: number
  email: string
  lastName: string
  firstName: string
  avatarUrl: string | null
  systemRole: string | null
  timezone: string | null
}

const test = base.extend<
  { authToken: string; adminInit: boolean },
  { workerToken: { token: string; me: MeProfile } }
>({
  // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
  storageState: async ({}, use) => {
    await use(undefined)
  },
  workerToken: [
    // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
    async ({}, use) => {
      const ctx = await playwrightRequest.newContext()
      const loginRes = await ctx.post(`${BE}/api/v1/auth/login`, {
        headers: { 'Content-Type': 'application/json' },
        data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
      })
      if (!loginRes.ok()) {
        throw new Error(`管理者ログイン失敗: ${loginRes.status()} ${await loginRes.text()}`)
      }
      const token = (await loginRes.json()).data.accessToken as string
      const meRes = await ctx.get(`${BE}/api/v1/users/me`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!meRes.ok()) throw new Error(`/users/me 失敗: ${meRes.status()}`)
      const me = (await meRes.json()).data as MeProfile
      await ctx.dispose()
      await use({ token, me })
    },
    { scope: 'worker' },
  ],
  authToken: async ({ workerToken }, use) => {
    await use(workerToken.token)
  },
  adminInit: [
    async ({ page, workerToken }, use) => {
      await page.request.post(`${BE}/api/v1/auth/login`, {
        headers: { 'Content-Type': 'application/json' },
        data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
      })
      const me = workerToken.me
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
      await page.goto('/')
      await use(true)
    },
    { auto: true },
  ],
})

test.setTimeout(120_000)

/**
 * APIブリッジ（memory: feedback_e2e_wsl2_cors_apibridge）。:3001 では必須、:3000 でも無害。
 * ブラウザの /api/v1 fetch を横取りし、Playwright の APIRequestContext で BE(8080) へ中継する。
 */
async function installApiBridge(
  page: import('@playwright/test').Page,
  token: string,
): Promise<void> {
  const pageOrigin = new URL(page.url() || 'http://localhost:3001').origin
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const target = `http://127.0.0.1:8080${url.pathname}${url.search}`
    const headers: Record<string, string> = {
      ...req.headers(),
      origin: 'http://localhost:3000',
      referer: 'http://localhost:3000/',
      authorization: `Bearer ${token}`,
    }
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
  })
}

// ---------------------------------------------------------------------------
// TEAMTAB-001: チーム詳細ページに「予約」タブが表示され、クリックで開く
// ---------------------------------------------------------------------------

test.describe('TEAMTAB-001: チーム詳細ページの予約タブ', () => {
  test('TEAMTAB-001-01: /teams/fc-u-18 に「予約」タブが描画される', async ({ page, authToken }) => {
    await installApiBridge(page, authToken)
    const consoleErrors: string[] = []
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })

    await page.goto(`/teams/${TEAM_SLUG}`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    const reservationTab = page.getByRole('tab', { name: '予約', exact: true })
    await expect(
      reservationTab,
      '予約タブが表示されない（moduleSlug 判定の回帰の可能性）',
    ).toBeVisible({ timeout: 20_000 })

    await page.screenshot({ path: 'test-results/reservation-team-tab-visible.png', fullPage: true })

    const fatal = consoleErrors.filter((e) => /Cannot read|undefined is not|Hydration|TypeError/i.test(e))
    expect(fatal.length, `致命的コンソールエラー: ${fatal.join(' / ')}`).toBe(0)
  })

  test('TEAMTAB-001-02: 「予約」タブをクリックすると予約パネル（予約する/予約一覧）が開く', async ({
    page,
    authToken,
  }) => {
    await installApiBridge(page, authToken)
    await page.goto(`/teams/${TEAM_SLUG}`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    const reservationTab = page.getByRole('tab', { name: '予約', exact: true })
    await reservationTab.waitFor({ state: 'visible', timeout: 20_000 })
    await reservationTab.click()

    await expect(page.getByRole('tab', { name: '予約する' })).toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('tab', { name: '予約一覧' })).toBeVisible({ timeout: 5_000 })

    await page.screenshot({ path: 'test-results/reservation-team-tab-opened.png', fullPage: true })
  })
})

// ---------------------------------------------------------------------------
// TEAMTAB-002（回帰）: スタンドアロン予約ページが引き続き機能する
// ---------------------------------------------------------------------------

test.describe('TEAMTAB-002: スタンドアロン予約ページ回帰', () => {
  test('TEAMTAB-002-01: /teams/fc-u-18/reservations が予約UIで描画される', async ({ page, authToken }) => {
    await installApiBridge(page, authToken)
    await page.goto(`/teams/${TEAM_SLUG}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    expect(page.url()).toContain(`/teams/${TEAM_SLUG}/reservations`)
    const hasReservationUi =
      ((await page.locator('body').textContent()) ?? '').includes('予約') ||
      (await page.getByRole('tab').count()) > 0
    expect(hasReservationUi).toBe(true)
  })
})
