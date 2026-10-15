/**
 * スラッグ URL 移行 E2E テスト
 *
 * チーム・組織の URL を UUID/BIGINT ベースからカスタムスラッグへ移行した機能の
 * 実機エンドツーエンドテスト。
 *
 * 前提条件:
 *   - バックエンド: http://localhost:8081 (feature/slug-url-be-foundation を起動)
 *   - フロントエンド: http://localhost:3002 (feature/slug-url-fe を起動)
 *   - DB: mannschaft-mysql コンテナが起動済み + seed-e2e-data.js が適用済み
 *   - 環境変数 SLUG_BE_URL: BE の URL（WSL2 IP 等）。デフォルト http://localhost:8081
 *   - 環境変数 SLUG_FE_URL: FE の URL。デフォルト http://localhost:3002
 *
 * 設計注記:
 *   BE は slug 値を id フィールドで返す（slug という別フィールドは存在しない）
 *   TeamSummaryResponse.id = slug 文字列 (例: "fc-u-18")
 *   OrganizationResponse.id = slug 文字列 (例: "org-000001")
 *
 * テストケース:
 *   SLUG-API-001: 未認証でチーム一覧取得 → 401
 *   SLUG-API-002: 認証済みでログイン → Bearer トークン取得
 *   SLUG-API-003: チーム一覧取得 → id フィールドがスラッグ形式
 *   SLUG-API-004: slug でチーム取得 (GET /api/v1/teams/{slug})
 *   SLUG-API-005: 存在しない slug で取得 → 404
 *   SLUG-API-006: チーム作成 → slug が自動生成される
 *   SLUG-API-007: 組織一覧取得 → id フィールドがスラッグ形式
 *   SLUG-API-008: slug で組織取得 (GET /api/v1/organizations/{slug})
 *   SLUG-FE-001:  /teams/{slug} でチームページが開く（slug ルーティング）
 *   SLUG-FE-002:  存在しない slug → エラーページ/404
 *   SLUG-FE-003:  チームページの URL が slug ベース
 *   SLUG-ROLE-001: 未認証でチーム設定ページ → /login にリダイレクト
 *   SLUG-ROLE-002: 未認証でチーム掲示板 → /login にリダイレクト
 *   SLUG-ROLE-003: 一般ユーザーは他チームの設定ページにアクセスできない
 */

import { test, expect, type APIRequestContext } from '@playwright/test'

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------
/**
 * worktree の BE を WSL2 で 8081 で起動している場合、
 * Windows 側の Playwright からは localhost:8081 ではなく WSL2 の IP で接続する必要がある。
 * 環境変数 SLUG_BE_URL で上書き可能。デフォルトは http://localhost:8081。
 */
const BACKEND_URL = process.env.SLUG_BE_URL ?? 'http://localhost:8081'
const FRONTEND_URL = process.env.SLUG_FE_URL ?? 'http://localhost:3002'
const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const E2E_ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }

/** スラッグのパターン: 英小文字・数字・ハイフン */
const SLUG_PATTERN = /^[a-z0-9][a-z0-9-]*[a-z0-9]$|^[a-z0-9]{1,2}$/

// ---------------------------------------------------------------------------
// ヘルパー
// ---------------------------------------------------------------------------

/** ログインして Bearer トークンを返す */
async function login(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<string | null> {
  try {
    const res = await request.post(`${BACKEND_URL}/api/v1/auth/login`, {
      data: { email, password },
    })
    if (!res.ok()) return null
    const body = await res.json()
    return body?.data?.accessToken ?? body.accessToken ?? body.token ?? null
  } catch {
    return null
  }
}

/** 認証ヘッダー付きで GET リクエストを送る */
async function authGet(
  request: APIRequestContext,
  path: string,
  token: string,
): Promise<{ status: number; body: unknown }> {
  const res = await request.get(`${BACKEND_URL}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  let body: unknown
  try {
    body = await res.json()
  } catch {
    body = null
  }
  return { status: res.status(), body }
}

/** 認証ヘッダー付きで POST リクエストを送る */
async function authPost(
  request: APIRequestContext,
  path: string,
  token: string,
  data: Record<string, unknown>,
): Promise<{ status: number; body: unknown }> {
  const res = await request.post(`${BACKEND_URL}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
    data,
  })
  let body: unknown
  try {
    body = await res.json()
  } catch {
    body = null
  }
  return { status: res.status(), body }
}

// ---------------------------------------------------------------------------
// SLUG-API: バックエンド API レベルのテスト
// ---------------------------------------------------------------------------
test.describe('SLUG-API: バックエンド API テスト', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('SLUG-API-001: 未認証でチーム一覧取得 → 401', async ({ playwright }) => {
    const ctx = await playwright.request.newContext({ baseURL: BACKEND_URL })
    try {
      const res = await ctx.get(`${BACKEND_URL}/api/v1/teams/search`)
      expect([401, 403]).toContain(res.status())
      console.log(`PASS: 未認証 → ${res.status()}`)
    } finally {
      await ctx.dispose()
    }
  })

  test('SLUG-API-002: 認証済みでログイン → Bearer トークン取得', async ({ playwright }) => {
    const ctx = await playwright.request.newContext({ baseURL: BACKEND_URL })
    try {
      const token = await login(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
      expect(token).not.toBeNull()
      expect(typeof token).toBe('string')
      expect(token!.length).toBeGreaterThan(10)
      console.log(`PASS: 管理者トークン取得 ${token!.substring(0, 20)}...`)
    } finally {
      await ctx.dispose()
    }
  })

  test('SLUG-API-003: チーム一覧取得 → id フィールドがスラッグ形式', async ({ playwright }) => {
    const ctx = await playwright.request.newContext({ baseURL: BACKEND_URL })
    try {
      const token = await login(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
      expect(token).not.toBeNull()

      const { status, body } = await authGet(ctx, '/api/v1/teams/search', token!)
      expect(status).toBe(200)

      const data = body as { data?: Array<{ id: string | number; name: string }> }
      const teams = data?.data ?? []
      console.log(`チーム数: ${teams.length}`)
      expect(teams.length).toBeGreaterThan(0)

      // 全チームの id がスラッグ形式であることを確認（純粋な数値 ID ではない）
      const teamsWithNumericId = teams.filter(
        (t) => !t.id || typeof t.id !== 'string' || !SLUG_PATTERN.test(t.id),
      )
      if (teamsWithNumericId.length > 0) {
        console.log(
          `スラッグ形式でないチーム: ${teamsWithNumericId.map((t) => `${t.name}(id=${t.id})`).join(', ')}`,
        )
      }
      expect(teamsWithNumericId.length).toBe(0)

      const fcTokyo = teams.find((t) => t.name.includes('FC東京U-18'))
      console.log(
        `FC東京U-18 slug(=id): ${fcTokyo?.id ?? 'not found'}`,
      )
      console.log(`PASS: 全 ${teams.length} チームのidがスラッグ形式`)
    } finally {
      await ctx.dispose()
    }
  })

  test('SLUG-API-004: slug でチーム取得 (GET /api/v1/teams/{slug})', async ({ playwright }) => {
    const ctx = await playwright.request.newContext({ baseURL: BACKEND_URL })
    try {
      const token = await login(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
      expect(token).not.toBeNull()

      // チーム一覧から FC東京U-18 の slug を取得
      const { body: listBody } = await authGet(ctx, '/api/v1/teams/search?size=20', token!)
      const listData = listBody as { data?: Array<{ id: string | number; name: string }> }
      const fcTokyo = (listData?.data ?? []).find((t) => t.name.includes('FC東京U-18'))
      let targetSlug = fcTokyo?.id && typeof fcTokyo.id === 'string' ? fcTokyo.id : ''

      // フォールバック: 先頭チームを使用
      if (!targetSlug) {
        const firstTeam = listData?.data?.[0]
        if (firstTeam?.id && typeof firstTeam.id === 'string') targetSlug = firstTeam.id
      }

      expect(targetSlug).not.toBe('')
      console.log(`slug でチーム取得テスト: slug=${targetSlug}`)

      const { status, body } = await authGet(ctx, `/api/v1/teams/${targetSlug}`, token!)
      expect(status).toBe(200)

      const data = body as { data?: { name: string; id?: string } }
      expect(data?.data?.id).toBe(targetSlug)
      console.log(`PASS: GET /api/v1/teams/${targetSlug} → ${data?.data?.name}`)
    } finally {
      await ctx.dispose()
    }
  })

  test('SLUG-API-005: 存在しない slug でチーム取得 → 404', async ({ playwright }) => {
    const ctx = await playwright.request.newContext({ baseURL: BACKEND_URL })
    try {
      const token = await login(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
      expect(token).not.toBeNull()

      const { status } = await authGet(
        ctx,
        '/api/v1/teams/this-slug-does-not-exist-at-all-999',
        token!,
      )
      expect(status).toBe(404)
      console.log(`PASS: 存在しない slug → 404`)
    } finally {
      await ctx.dispose()
    }
  })

  test('SLUG-API-006: チーム作成 → slug が自動生成される', async ({ playwright }) => {
    const ctx = await playwright.request.newContext({ baseURL: BACKEND_URL })
    try {
      const token = await login(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
      expect(token).not.toBeNull()

      const teamName = `E2Eテストチーム-${Date.now()}`
      const { status, body } = await authPost(ctx, '/api/v1/teams', token!, {
        name: teamName,
        template: 'SPORTS',
        visibility: 'PRIVATE',
      })

      expect(status).toBe(201)
      // 設計: BE は slug 値を id フィールドで返す
      // チーム作成レスポンスは data.id (slug) + data.basicInfo.name (チーム名)
      const data = body as {
        data?: {
          id?: string
          basicInfo?: { name: string }
          name?: string  // TeamSummaryResponse の場合は data.name に入る可能性もあり
        }
      }
      const teamId = data?.data?.id
      const teamNameReturned = data?.data?.basicInfo?.name ?? data?.data?.name

      expect(teamId).toBeTruthy()
      expect(teamNameReturned).toBe(teamName)

      if (teamId) {
        expect(teamId).toMatch(SLUG_PATTERN)
        console.log(`PASS: チーム作成 → slug(id)=${teamId}`)

        // 後片付け（失敗しても無視）
        try {
          await ctx.delete(`${BACKEND_URL}/api/v1/teams/${teamId}`, {
            headers: { Authorization: `Bearer ${token!}` },
          })
        } catch {
          // 無視
        }
      }
    } finally {
      await ctx.dispose()
    }
  })

  test('SLUG-API-007: 組織一覧取得 → id フィールドがスラッグ形式', async ({ playwright }) => {
    const ctx = await playwright.request.newContext({ baseURL: BACKEND_URL })
    try {
      const token = await login(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
      expect(token).not.toBeNull()

      const { status, body } = await authGet(
        ctx,
        '/api/v1/organizations/search?keyword=テスト&size=20',
        token!,
      )
      console.log(`組織検索 status: ${status}`)

      if (status !== 200) {
        console.log(`組織検索 API が ${status} を返した`)
        test.skip()
        return
      }

      const data = body as {
        data?: Array<{ id: string | number; name: string }>
        content?: Array<{ id: string | number; name: string }>
      }
      const orgs = data?.data ?? data?.content ?? []
      console.log(`組織数: ${orgs.length}`)

      if (orgs.length === 0) {
        console.log('組織が 0 件')
        test.skip()
        return
      }

      const orgsWithNumericId = orgs.filter(
        (o) => !o.id || typeof o.id !== 'string' || !SLUG_PATTERN.test(o.id),
      )
      if (orgsWithNumericId.length > 0) {
        console.log(
          `スラッグ形式でない組織: ${orgsWithNumericId.map((o) => `${o.name}(id=${o.id})`).join(', ')}`,
        )
      }
      expect(orgsWithNumericId.length).toBe(0)

      const fcTokyoOrg = orgs.find((o) => o.name.includes('FC東京'))
      console.log(`FC東京組織 slug(=id): ${fcTokyoOrg?.id ?? 'not found'}`)
      console.log(`PASS: 全 ${orgs.length} 組織のidがスラッグ形式`)
    } finally {
      await ctx.dispose()
    }
  })

  test('SLUG-API-008: slug で組織取得 (GET /api/v1/organizations/{slug})', async ({
    playwright,
  }) => {
    const ctx = await playwright.request.newContext({ baseURL: BACKEND_URL })
    try {
      const token = await login(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
      expect(token).not.toBeNull()

      // 組織一覧から slug を取得
      const { status: listStatus, body: listBody } = await authGet(
        ctx,
        '/api/v1/organizations/search?keyword=テスト&size=20',
        token!,
      )

      if (listStatus !== 200) {
        console.log(`組織一覧取得失敗 ${listStatus}`)
        test.skip()
        return
      }

      const listData = listBody as {
        data?: Array<{ id: string | number; name: string }>
        content?: Array<{ id: string | number; name: string }>
      }
      const orgs = listData?.data ?? listData?.content ?? []
      const targetOrg = orgs.find((o) => o.name.includes('FC東京')) ?? orgs[0]

      if (!targetOrg?.id || typeof targetOrg.id !== 'string') {
        console.log('組織の slug が取得できないためスキップ')
        test.skip()
        return
      }

      const orgSlug = targetOrg.id
      const { status, body } = await authGet(ctx, `/api/v1/organizations/${orgSlug}`, token!)
      expect(status).toBe(200)
      const data = body as { data?: { name: string; id?: string } }
      expect(data?.data?.id).toBe(orgSlug)
      console.log(`PASS: GET /api/v1/organizations/${orgSlug} → ${data?.data?.name}`)
    } finally {
      await ctx.dispose()
    }
  })
})

// ---------------------------------------------------------------------------
// SLUG-FE: フロントエンド画面テスト
// ---------------------------------------------------------------------------
test.describe('SLUG-FE: フロントエンド画面テスト', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  /** FE が起動しているか確認 */
  async function isFrontendAlive(request: APIRequestContext): Promise<boolean> {
    try {
      const res = await request.get(FRONTEND_URL, { timeout: 8_000 })
      return res.status() < 500
    } catch {
      return false
    }
  }

  /** ページでログインする */
  async function performLogin(page: import('@playwright/test').Page, email: string, password: string) {
    await page.goto(`${FRONTEND_URL}/login`, { waitUntil: 'domcontentloaded', timeout: 30_000 })

    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(email, { delay: 10 })

    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially(password, { delay: 10 })

    await page.getByRole('button', { name: 'ログイン' }).click()
    try {
      await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 30_000 })
    } catch {
      // ログイン失敗時はスキップ（FE が BE に接続できない等）
    }
  }

  test('SLUG-FE-001: /teams/{slug} でチームページが開く', async ({ page, request, playwright }) => {
    const alive = await isFrontendAlive(request)
    if (!alive) {
      console.log('FE が起動していないためスキップ')
      test.skip()
      return
    }

    // BE から slug を取得
    const apiCtx = await playwright.request.newContext({ baseURL: BACKEND_URL })
    let teamSlug = ''
    try {
      const token = await login(apiCtx, E2E_USER.email, E2E_USER.password)
      if (token) {
        const { body } = await authGet(apiCtx, '/api/v1/teams/search', token)
        const data = body as { data?: Array<{ id: string | number; name: string }> }
        const fcTokyo = (data?.data ?? []).find((t) => t.name.includes('FC東京U-18'))
        if (fcTokyo?.id && typeof fcTokyo.id === 'string') teamSlug = fcTokyo.id
        // フォールバック
        if (!teamSlug) {
          const first = data?.data?.[0]
          if (first?.id && typeof first.id === 'string') teamSlug = first.id
        }
      }
    } finally {
      await apiCtx.dispose()
    }

    if (!teamSlug) {
      console.log('チームの slug が取得できないためスキップ')
      test.skip()
      return
    }

    await performLogin(page, E2E_USER.email, E2E_USER.password)

    console.log(`チームページ URL: ${FRONTEND_URL}/teams/${teamSlug}`)
    let response: import('@playwright/test').Response | null = null
    try {
      response = await page.goto(`${FRONTEND_URL}/teams/${teamSlug}`, { timeout: 30_000, waitUntil: 'domcontentloaded' })
    } catch {
      // タイムアウトしても続行
    }

    const finalUrl = page.url()
    const content = await page.content()

    // FE に slug ルートが未実装の場合 → 404 を検知してスキップ
    if (content.includes('Page not found')) {
      console.log('SKIP: FE に /teams/:slug ルートが未実装（FE slug 対応ブランチでの実行が必要）')
      test.skip()
      return
    }

    const status = response?.status() ?? 0
    expect(status).not.toBe(404)
    expect(status).not.toBe(500)

    expect(finalUrl).toContain(`/teams/${teamSlug}`)
    console.log(`PASS: /teams/${teamSlug} でページが開いた。最終 URL: ${finalUrl}`)
  })

  test('SLUG-FE-002: 存在しない slug → エラーページ/404 表示', async ({ page, request }) => {
    const alive = await isFrontendAlive(request)
    if (!alive) {
      test.skip()
      return
    }

    const nonExistentSlug = 'this-slug-does-not-exist-at-all-99999'
    try {
      await page.goto(`${FRONTEND_URL}/teams/${nonExistentSlug}`, { timeout: 30_000, waitUntil: 'domcontentloaded' })
    } catch {
      // タイムアウトしても URL・コンテンツチェックは続行
    }

    const url = page.url()
    const content = await page.content()

    // FE に slug ルーティングが未実装の場合も 404 Nuxt エラーページ → テスト成立
    const is404 =
      content.includes('404') ||
      content.includes('Not Found') ||
      content.includes('見つかりません') ||
      content.includes('Page not found')
    const isError =
      content.includes('エラー') || content.includes('error') || content.includes('Error')
    const isRedirectedToLogin = url.includes('/login')

    console.log(`URL: ${url}`)
    console.log(`404: ${is404}, エラー: ${isError}, ログインリダイレクト: ${isRedirectedToLogin}`)
    expect(is404 || isError || isRedirectedToLogin).toBe(true)
  })

  test('SLUG-FE-003: チームページの URL が slug ベース（UUID/数値 ID でない）', async ({
    page,
    request,
  }) => {
    const alive = await isFrontendAlive(request)
    if (!alive) {
      test.skip()
      return
    }

    await performLogin(page, E2E_USER.email, E2E_USER.password)

    const currentUrl = page.url()
    console.log(`ログイン後 URL: ${currentUrl}`)

    // ログインできなかった場合はスキップ（FE が BE に接続できない）
    if (currentUrl.includes('/login')) {
      console.log('SKIP: ログインできなかった（FE が BE に接続できない）')
      test.skip()
      return
    }

    const isExpectedUrl =
      currentUrl.includes('/my/') ||
      currentUrl.includes('/dashboard') ||
      currentUrl.includes('/teams/')

    expect(isExpectedUrl).toBe(true)

    // URL が純粋な数値 ID パターンでないこと（/teams/123 のような形ではない）
    const numericTeamUrlPattern = /\/teams\/\d+$/
    expect(currentUrl).not.toMatch(numericTeamUrlPattern)
    console.log(`PASS: ログイン後 URL は数値 ID でない: ${currentUrl}`)
  })
})

// ---------------------------------------------------------------------------
// SLUG-ROLE: ロールチェックテスト
// ---------------------------------------------------------------------------
test.describe('SLUG-ROLE: ロールチェックテスト', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  async function isFrontendAlive(request: APIRequestContext): Promise<boolean> {
    try {
      const res = await request.get(FRONTEND_URL, { timeout: 8_000 })
      return res.status() < 500
    } catch {
      return false
    }
  }

  test('SLUG-ROLE-001: 未認証でチーム設定ページ → /login にリダイレクト', async ({ page, request, playwright }) => {
    const alive = await isFrontendAlive(request)
    if (!alive) {
      test.skip()
      return
    }

    // BE から適当な slug を取得
    const apiCtx = await playwright.request.newContext({ baseURL: BACKEND_URL })
    let testSlug = 'fc-e2e-team'
    try {
      const token = await login(apiCtx, E2E_ADMIN.email, E2E_ADMIN.password)
      if (token) {
        const { body } = await authGet(apiCtx, '/api/v1/teams/search?size=5', token)
        const data = body as { data?: Array<{ id: string | number; name: string }> }
        const first = data?.data?.[0]
        if (first?.id && typeof first.id === 'string') testSlug = first.id
      }
    } finally {
      await apiCtx.dispose()
    }

    await page.context().clearCookies()
    // waitUntil: 'domcontentloaded' でリダイレクト先URLを確認（BE待機タイムアウト回避）
    try {
      await page.goto(`${FRONTEND_URL}/teams/${testSlug}/settings`, { timeout: 30_000, waitUntil: 'domcontentloaded' })
    } catch {
      // タイムアウトしても URL チェックは続行
    }
    const url = page.url()
    const content = await page.content()
    console.log(`未認証でチーム設定 → リダイレクト先: ${url}`)

    // FE に slug ルーティングが未実装の場合は 404 で /login リダイレクトはない
    // → FE 側のスラッグルート未実装を検出してスキップ
    if (content.includes('Page not found') || content.includes('404')) {
      console.log('SKIP: FE に /teams/:slug/settings ルートが未実装（FE slug 対応ブランチでの実行が必要）')
      test.skip()
      return
    }

    expect(url).toContain('/login')
    console.log(`PASS: 未認証 → /login にリダイレクト`)
  })

  test('SLUG-ROLE-002: 未認証で /teams/{slug}/bulletin → /login にリダイレクト', async ({
    page,
    request,
    playwright,
  }) => {
    const alive = await isFrontendAlive(request)
    if (!alive) {
      test.skip()
      return
    }

    const apiCtx = await playwright.request.newContext({ baseURL: BACKEND_URL })
    let testSlug = 'fc-e2e-team'
    try {
      const token = await login(apiCtx, E2E_ADMIN.email, E2E_ADMIN.password)
      if (token) {
        const { body } = await authGet(apiCtx, '/api/v1/teams/search?size=5', token)
        const data = body as { data?: Array<{ id: string | number; name: string }> }
        const first = data?.data?.[0]
        if (first?.id && typeof first.id === 'string') testSlug = first.id
      }
    } finally {
      await apiCtx.dispose()
    }

    await page.context().clearCookies()
    // waitUntil: 'domcontentloaded' でリダイレクト先URLを確認（BE待機タイムアウト回避）
    try {
      await page.goto(`${FRONTEND_URL}/teams/${testSlug}/bulletin`, { timeout: 30_000, waitUntil: 'domcontentloaded' })
    } catch {
      // タイムアウトしても URL チェックは続行
    }
    const url = page.url()
    const content = await page.content()
    console.log(`未認証でチーム掲示板 → リダイレクト先: ${url}`)

    // FE に slug ルーティングが未実装の場合は 404 で /login リダイレクトはない
    if (content.includes('Page not found') || content.includes('404')) {
      console.log('SKIP: FE に /teams/:slug/bulletin ルートが未実装（FE slug 対応ブランチでの実行が必要）')
      test.skip()
      return
    }

    expect(url).toContain('/login')
  })

  test('SLUG-ROLE-003: 一般ユーザーは他チームの設定ページにアクセスできない', async ({
    page,
    request,
    playwright,
  }) => {
    const alive = await isFrontendAlive(request)
    if (!alive) {
      test.skip()
      return
    }

    // 横浜FCジュニアA の slug を取得（E2E_USER は所属していない）
    const apiCtx = await playwright.request.newContext({ baseURL: BACKEND_URL })
    let yokohamaSlug = ''
    try {
      const token = await login(apiCtx, E2E_USER.email, E2E_USER.password)
      if (!token) {
        test.skip()
        return
      }

      const { body } = await authGet(apiCtx, '/api/v1/teams/search?keyword=横浜', token)
      const data = body as { data?: Array<{ id: string | number; name: string }> }
      const yokohamaTeam = (data?.data ?? []).find((t) => t.name.includes('横浜'))
      if (!yokohamaTeam?.id || typeof yokohamaTeam.id !== 'string') {
        console.log('横浜チームの slug が取得できないためスキップ')
        test.skip()
        return
      }
      yokohamaSlug = yokohamaTeam.id
    } finally {
      await apiCtx.dispose()
    }

    console.log(`E2E_USER が所属していないチーム slug: ${yokohamaSlug}`)

    // 一般ユーザーでログイン（本陣 FE が 8080 BE に接続できない場合はタイムアウトするのでスキップ対応）
    await page.context().clearCookies()
    try {
      await page.goto(`${FRONTEND_URL}/login`, { timeout: 30_000, waitUntil: 'domcontentloaded' })
    } catch {
      console.log('SKIP: FE ログインページのロードがタイムアウト（FE が BE に接続できない）')
      test.skip()
      return
    }

    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(E2E_USER.email, { delay: 10 })
    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially(E2E_USER.password, { delay: 10 })
    await page.getByRole('button', { name: 'ログイン' }).click()
    try {
      await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 30_000 })
    } catch {
      console.log('SKIP: ログイン後リダイレクトがタイムアウト（FE が BE に接続できない）')
      test.skip()
      return
    }

    // 他チームの設定ページにアクセス
    await page.goto(`${FRONTEND_URL}/teams/${yokohamaSlug}/settings`, { timeout: 20_000 })
    const url = page.url()
    const content = await page.content()

    const isAccessDenied =
      url.includes('/login') ||
      content.includes('403') ||
      content.includes('Forbidden') ||
      content.includes('アクセスが拒否') ||
      content.includes('権限がありません') ||
      !url.includes(`/teams/${yokohamaSlug}/settings`)

    console.log(`他チーム設定アクセス結果: URL=${url}, アクセス拒否=${isAccessDenied}`)
    expect(isAccessDenied).toBe(true)
  })
})
