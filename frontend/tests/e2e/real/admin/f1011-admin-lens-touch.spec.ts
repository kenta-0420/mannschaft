/**
 * F10.1.1 管理者レンズ L1 トグル — emulated touch（タッチ）ジェスチャ補完 E2E（P4 要素3 補完）
 *
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3001（本ブランチ専用 dev）が
 * 起動済みの状態で実行してください（playwright-real.config.ts は webServer 無効＝既存サーバー前提）。
 *
 * 実行プロジェクト: chromium-real（baseURL=process.env.BASE_URL ?? 'http://localhost:3000'）+ test.use({ hasTouch: true }) でタッチ端末を擬似。
 *
 * 目的（DashboardScopeLensToggle.vue §1.3 のジェスチャ排他ロジックを実ブラウザで検証）:
 *   - **タップ（移動なし）** → トグルが 1 回だけ切り替わる（ghost click による二重発火がない）。
 *   - **横スワイプ（閾値超）** → トグルは切り替わらない（カルーセルへジェスチャ委譲）。
 *
 * これは実機 QA（iOS Safari / Android Chrome での実タップ・実スワイプ）の代替ではなく、
 * Chromium の emulated touch でロジックの正しさを補完検証するもの（コンポーネント側コメント §1.3 参照）。
 *
 * テストユーザー: e2e-admin@test.mannschaft.local（FC東京U-18 ADMIN）— トグルは ADMIN/DEPUTY のみ描画されるため。
 *
 * APIブリッジ（memory: feedback_e2e_wsl2_cors_apibridge）:
 *   本ブランチ専用 dev(:3001) で動かす場合、Nuxt アプリがブラウザから BE(:8080) へ
 *   クロスオリジン fetch するが BE の CORS 許可オリジンは :3000/:8080 のみで :3001 は弾かれる。
 *   loginViaApiBridge がセッションを API 経由で確立し、page.route で /api/v1/** を中継する。
 */

import { test as base, expect, request as pwRequest, type Page } from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

// 127.0.0.1 を明示（localhost だと IPv6 ::1 解決で間欠 ECONNREFUSED・memory: feedback_e2e_wsl2_cors_apibridge）
const BE_API = `${process.env.BE_ORIGIN ?? 'http://127.0.0.1:8080'}/api/v1`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

const test = base.extend({})
// storageState 非依存 + タッチ端末擬似（hasTouch）。
test.use({ storageState: { cookies: [], origins: [] }, hasTouch: true })
test.setTimeout(120_000)

// 検証対象の管理チーム slug（FC東京U-18 を優先・seed が rich データを投入済み）。beforeAll で解決。
let teamSlug = ''

test.beforeAll(async () => {
  // ログインは 1 回だけ（credCache に格納し各テストで使い回す）。
  const { token } = await resolveCreds(ADMIN_EMAIL, ADMIN_PASSWORD)
  const ctx = await pwRequest.newContext()
  const teamsRes = await ctx.get(`${BE_API}/me/teams`, { headers: { Authorization: `Bearer ${token}` } })
  const teams = (await teamsRes.json()).data as Array<{ slug: string | null; name: string | null; role: string }>
  const admin = teams.filter((t) => t.slug && (t.role === 'ADMIN' || t.role === 'DEPUTY'))
  const pick = admin.find((t) => (t.name ?? '').includes('FC東京U-18')) ?? admin[0]
  expect(pick, 'slug 解決済みの管理チームが存在すること').toBeTruthy()
  teamSlug = pick!.slug!
  await ctx.dispose()
})

/**
 * APIブリッジ（memory: feedback_e2e_wsl2_cors_apibridge）。
 * ブラウザの /api/v1/** 呼び出しを page.route で横取りして BE に中継する。
 */
async function installApiBridge(page: Page, token: string): Promise<void> {
  // page.goto 前に設定するケースのため、origin を固定値で指定する（about:blank 対策）
  // BASE_URL 環境変数が設定されている場合はそちらを使う（本陣:3000 / 検証 worktree:3001 等）
  const pageOrigin = process.env.BASE_URL ?? 'http://localhost:3000'
  // 正規表現で /api/v1/ を含む全 URL をキャッチ
  // （NUXT_PUBLIC_API_BASE が絶対 URL 設定時にも確実に横取りできる）
  await page.route(/\/api\/v1\//, async (route) => {
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

type Me = {
  id: number
  email: string
  lastName: string
  firstName: string
  avatarUrl: string | null
  systemRole: string | null
  timezone: string | null
}

// role（email）ごとのログイン結果キャッシュ。同一ユーザーの高速連続ログインで BE が稀に 500 を
// 返す問題を避けるため、ログインは role ごとに 1 回だけ行う。
const credCache = new Map<string, { token: string; me: Me }>()

async function resolveCreds(email: string, password: string): Promise<{ token: string; me: Me }> {
  const cached = credCache.get(email)
  if (cached) return cached
  const ctx = await pwRequest.newContext()
  const loginRes = await ctx.post(`${BE_API}/auth/login`, {
    headers: { 'Content-Type': 'application/json' },
    data: { email, password },
  })
  expect(loginRes.status(), `BE ログイン(${email}) は 200`).toBe(200)
  const token = (await loginRes.json()).data.accessToken as string
  const meRes = await ctx.get(`${BE_API}/users/me`, { headers: { Authorization: `Bearer ${token}` } })
  expect(meRes.status(), '/users/me は 200').toBe(200)
  const me = (await meRes.json()).data as Me
  await ctx.dispose()
  const creds = { token, me }
  credCache.set(email, creds)
  return creds
}

/**
 * APIブリッジ＋localStorage(currentUser) を仕込み、role のセッションを確立する。
 * ログインは role ごとに 1 回だけ（{@link resolveCreds} がキャッシュ）。Cookie ログインは省略し
 * API ブリッジの Bearer 認証に依存する（同一ユーザーの連続ログインによる BE 間欠 500 を回避）。
 */
async function loginViaApiBridge(page: Page, email: string, password: string): Promise<void> {
  const { token, me } = await resolveCreds(email, password)

  const farFuture = Date.now() + 24 * 60 * 60 * 1000
  const currentUser = {
    id: me.id,
    email: me.email,
    fullName: `${me.lastName} ${me.firstName}`,
    profileImageUrl: me.avatarUrl,
    systemRole: me.systemRole ?? undefined,
    timezone: me.timezone ?? undefined,
  }
  await page.addInitScript(
    ({ user, expiresAt }) => {
      localStorage.setItem('currentUser', JSON.stringify(user))
      localStorage.setItem('tokenExpiresAt', String(expiresAt))
    },
    { user: currentUser, expiresAt: farFuture },
  )

  // APIブリッジを page.goto より前に設定して初回ナビゲーションからブリッジが有効になるようにする
  await installApiBridge(page, token)
  // addInitScript は次の page.goto で初めて実行されるため、ここでは goto しない。
}

// テスト終了後のインフライト API ブリッジルートによる
// "Target page context has been closed" エラーを抑止する
test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: 'ignoreErrors' })
})

/**
 * TEAM スコープの管理者レンズトグルが出るまでダッシュボードを開く。
 * 既定選択任せにせず「slug 解決済み かつ 管理ロール」のタグチップを明示選択する
 * （f1011-admin-console.spec.ts の gotoDashboardScope と同じ理由・作法）。
 */
async function openTeamLens(page: Page): Promise<void> {
  await page.goto('/dashboard')
  await waitForHydration(page)
  const segment = page.getByTestId('scope-segment-TEAM')
  await expect(segment).toBeVisible({ timeout: 20_000 })
  await segment.click()

  await expect(
    page.locator('[data-testid^="scope-tab-chip-TEAM-"]').first(),
    'TEAM のタグチップが描画されること',
  ).toBeVisible({ timeout: 20_000 })

  // 目的チーム(teamSlug)のチップが見つかるまでページ送りして選択する
  // （タグ一覧は joined_at 降順 6 件/ページで、最古参加の検証用チームは後方ページに出るため）。
  const chip = page.getByTestId(`scope-tab-chip-TEAM-${teamSlug}`)
  const nextBtn = page.getByTestId('scope-tab-nextpage-TEAM')
  for (let i = 0; i < 12; i++) {
    if (await chip.count()) break
    if ((await nextBtn.count()) === 0 || (await nextBtn.isDisabled())) break
    await nextBtn.click()
    await expect(page.locator('[data-testid^="scope-tab-chip-TEAM-"]').first()).toBeVisible({ timeout: 10_000 })
    await page.waitForTimeout(300)
  }
  await expect(chip, `タグ一覧に TEAM スコープ ${teamSlug} のチップが見つかること`).toBeVisible({ timeout: 10_000 })
  await chip.click()
  await expect(page.getByTestId('admin-lens-toggle-TEAM')).toBeVisible({ timeout: 20_000 })
}

/**
 * 指定要素の中心に対して touchstart→touchmove→touchend の Touch イベント列を dispatch する。
 * dx が大きいと「横スワイプ」、ほぼ 0 なら「タップ」を擬似する。
 * DashboardScopeLensToggle は @touchstart/@touchmove/@touchend ハンドラでこれを判定する（§1.3）。
 */
async function dispatchTouchGesture(
  page: Page,
  testId: string,
  dx: number,
): Promise<void> {
  await page.evaluate(
    ({ tid, deltaX }) => {
      const el = document.querySelector(`[data-testid="${tid}"]`) as HTMLElement | null
      if (!el) throw new Error(`toggle element not found: ${tid}`)
      const rect = el.getBoundingClientRect()
      const startX = rect.left + rect.width / 2
      const startY = rect.top + rect.height / 2

      const makeTouch = (x: number, y: number): Touch =>
        new Touch({ identifier: 1, target: el, clientX: x, clientY: y })

      const fire = (type: string, x: number, y: number) => {
        const touch = makeTouch(x, y)
        const ev = new TouchEvent(type, {
          bubbles: true,
          cancelable: true,
          touches: type === 'touchend' ? [] : [touch],
          targetTouches: type === 'touchend' ? [] : [touch],
          changedTouches: [touch],
        })
        el.dispatchEvent(ev)
      }

      fire('touchstart', startX, startY)
      if (deltaX !== 0) {
        // 横移動を段階的に発火（閾値 8px 超を確実に跨ぐ）
        fire('touchmove', startX + deltaX / 2, startY)
        fire('touchmove', startX + deltaX, startY)
      }
      fire('touchend', startX + deltaX, startY)
    },
    { tid: testId, deltaX: dx },
  )
}

test.describe('F10.1.1 管理者レンズ L1 トグル — emulated touch ジェスチャ', () => {
  test('TOUCH-001: タップ（移動なし）でトグルが確定し ghost click 二重発火がない', async ({
    page,
  }) => {
    await loginViaApiBridge(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await openTeamLens(page)

    const adminBtn = page.getByTestId('admin-lens-toggle-TEAM')
    const memberBtn = page.getByTestId('admin-lens-member-TEAM')

    // 管理者ボタンをタップ（dx=0）→ ON になる（setLens(true)）
    await dispatchTouchGesture(page, 'admin-lens-toggle-TEAM', 0)
    await expect(adminBtn, 'タップ後 管理者ボタンが aria-pressed=true').toHaveAttribute(
      'aria-pressed',
      'true',
      { timeout: 8_000 },
    )
    await expect(page.getByTestId('admin-widget-grid-TEAM'), '管理者グリッドが出現').toBeVisible({
      timeout: 10_000,
    })

    // 同じ管理者ボタンを再タップ → ghost click 二重発火なら一旦 false に振れる
    // 冪等（aria-pressed='true' のまま）であることを検証する
    await dispatchTouchGesture(page, 'admin-lens-toggle-TEAM', 0)
    await page.waitForTimeout(800)
    await expect(adminBtn, '再タップでも aria-pressed は true のまま（ghost 二重発火なし）').toHaveAttribute(
      'aria-pressed',
      'true',
    )
    await expect(page.getByTestId('admin-widget-grid-TEAM'), 'グリッドは visible のまま').toBeVisible()

    // メンバーボタンをタップ → 管理者ボタンが false になりグリッドが消える
    await dispatchTouchGesture(page, 'admin-lens-member-TEAM', 0)
    await expect(adminBtn, 'メンバータップ後 管理者ボタンが aria-pressed=false').toHaveAttribute(
      'aria-pressed',
      'false',
      { timeout: 8_000 },
    )
    await expect(memberBtn, 'メンバーボタンが aria-pressed=true').toHaveAttribute(
      'aria-pressed',
      'true',
    )
    await expect(page.getByTestId('admin-widget-grid-TEAM'), 'グリッドが非表示').toBeHidden({
      timeout: 10_000,
    })
  })

  test('TOUCH-002: 横スワイプ（閾値超）ではトグルしない（カルーセル委譲）', async ({ page }) => {
    await loginViaApiBridge(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await openTeamLens(page)

    const adminBtn = page.getByTestId('admin-lens-toggle-TEAM')
    const before = await adminBtn.getAttribute('aria-pressed')

    // 横スワイプ（dx=80px、|Δx| > |Δy|*1.5 を満たす）: touchMoved=true → setLens を呼ばない
    await dispatchTouchGesture(page, 'admin-lens-toggle-TEAM', 80)

    // しばらく待っても aria-pressed が変化しないこと（スワイプではトグルしない §1.3）
    await page.waitForTimeout(800)
    await expect(
      adminBtn,
      'スワイプでは aria-pressed が変化しない（トグル発火しない）',
    ).toHaveAttribute('aria-pressed', before ?? 'false')
  })
})
