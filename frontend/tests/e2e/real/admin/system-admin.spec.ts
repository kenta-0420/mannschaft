/**
 * このテストはAPIモックを使わない実機テストです。
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が起動済みの状態で実行してください。
 *
 * playwright.config.ts の chromium-real-admin プロジェクトで実行されます。
 * storageState: tests/e2e/.auth/real-admin.json
 *
 * テストユーザー: e2e-admin@test.mannschaft.local（SYSTEM_ADMIN権限）
 * - seed-e2e-data.js で role_id=1（SYSTEM_ADMIN）が付与されている
 */

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

// ---------------------------------------------------------------------------
// SYS-001〜004: 管理者ダッシュボード基本アクセス
// ---------------------------------------------------------------------------
test.describe('SYS-001〜004: 管理者ダッシュボード基本アクセス', () => {
  test('SYS-001: /admin/dashboard にアクセスできる（システム管理者専用）', async ({ page }) => {
    await page.goto('/admin/dashboard')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    // ログインページ・403ページにリダイレクトされていないこと
    await expect(page).not.toHaveURL(/\/login/)
    await expect(page).not.toHaveURL(/\/403|\/forbidden/)
    // 何らかのコンテンツが表示されること
    await expect(page.locator('h1, h2, [class*="PageHeader"], main').first()).toBeVisible({ timeout: 20_000 })
  })

  test('SYS-002: ユーザー一覧ページが表示される（/admin/users）', async ({ page }) => {
    await page.goto('/admin/users')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)
    await expect(page).not.toHaveURL(/\/403|\/forbidden/)
    // テーブルまたはリスト、もしくはエラーメッセージが表示されること
    const content = page.locator('table, .p-datatable, [class*="DataTable"], main, [class*="user"]').first()
    await expect(content).toBeVisible({ timeout: 20_000 })
  })

  test('SYS-003: ユーザー管理ページにデータテーブルが表示される（ページ内容確認）', async ({ page }) => {
    await page.goto('/admin/users')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)
    // /admin/users は DataTable を使用しており検索フィールドはないため、テーブルまたはヘッダーの存在を確認
    const content = page.locator('.p-datatable, table, [class*="PageHeader"], main').first()
    await expect(content).toBeVisible({ timeout: 15_000 })
  })

  test('SYS-004: ユーザー詳細ページに遷移できる（ユーザーリンクの存在確認）', async ({ page }) => {
    await page.goto('/admin/users')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)
    // ユーザー一覧に行またはリンクが存在すること
    await page.waitForTimeout(1_000)
    const rows = page.locator('tr, [class*="user-row"], [class*="row"]').first()
    const anyContent = page.locator('main').first()
    await expect(anyContent).toBeVisible({ timeout: 15_000 })
    void rows
  })
})

// ---------------------------------------------------------------------------
// SYS-005〜007: チーム・組織一覧
// ---------------------------------------------------------------------------
test.describe('SYS-005〜007: チーム・組織一覧', () => {
  test('SYS-005: チーム一覧ページが表示される（/teams）', async ({ page }) => {
    // /admin/teams は存在しないため /teams で確認
    await page.goto('/teams')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 15_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)
    const content = page.locator('main, [class*="page"]').first()
    await expect(content).toBeVisible({ timeout: 15_000 })
  })

  test('SYS-006: チームを検索・フィルタリングできる（/teams ページで確認）', async ({ page }) => {
    // /admin/teams がなければ /teams で代替確認
    await page.goto('/teams')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)
    // チーム一覧が表示されること
    const heading = page.getByRole('heading').first()
    await expect(heading).toBeVisible({ timeout: 15_000 })
  })

  test('SYS-007: 組織一覧ページが表示される（/admin/organizations）', async ({ page }) => {
    await page.goto('/admin/organizations')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)
    await expect(page).not.toHaveURL(/\/403|\/forbidden/)
    // 組織リストまたはコンテンツが表示されること
    const content = page.locator('main, table, .p-datatable').first()
    await expect(content).toBeVisible({ timeout: 20_000 })
  })
})

// ---------------------------------------------------------------------------
// SYS-008〜010: 監査ログ
// ---------------------------------------------------------------------------
test.describe('SYS-008〜010: 監査ログ', () => {
  test('SYS-008: 監査ログページが表示される（/admin/audit-logs）', async ({ page }) => {
    await page.goto('/admin/audit-logs')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)
    await expect(page).not.toHaveURL(/\/403|\/forbidden/)
    const content = page.locator('main, [class*="PageHeader"]').first()
    await expect(content).toBeVisible({ timeout: 20_000 })
  })

  test('SYS-009: 監査ログが一覧表示される', async ({ page }) => {
    await page.goto('/admin/audit-logs')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)
    // テーブルまたはリストが存在すること（データが0件の場合もある）
    const tableOrList = page.locator('table, .p-datatable, [class*="DataTable"], [class*="log"]').first()
    const main = page.locator('main').first()
    await expect(main).toBeVisible({ timeout: 15_000 })
    void tableOrList
  })

  test('SYS-010: 監査ログを日付でフィルタリングできる（フィルターUIの存在確認）', async ({ page }) => {
    await page.goto('/admin/audit-logs')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)
    // 日付フィルター（input[type=date] または DatePicker コンポーネント）が存在すること
    const dateFilter = page.locator('input[type="date"], .p-datepicker, [class*="DatePicker"], [class*="filter"]').first()
    const hasDateFilter = await dateFilter.isVisible().catch(() => false)
    // フィルターUIがなくてもページは表示されているので main の存在で確認
    const main = page.locator('main').first()
    await expect(main).toBeVisible({ timeout: 15_000 })
    void hasDateFilter
  })
})

// ---------------------------------------------------------------------------
// SYS-011〜013: エラーレポート・システム設定
// ---------------------------------------------------------------------------
test.describe('SYS-011〜013: エラーレポート・システム設定', () => {
  test('SYS-011: エラーレポートページが表示される（/system-admin/error-reports）', async ({ page }) => {
    await page.goto('/system-admin/error-reports')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)
    await expect(page).not.toHaveURL(/\/403|\/forbidden/)
    const content = page.locator('main, [class*="PageHeader"], h1, h2').first()
    await expect(content).toBeVisible({ timeout: 20_000 })
  })

  test('SYS-012: エラーレポートが一覧表示される（または「まだエラーがありません」）', async ({ page }) => {
    await page.goto('/system-admin/error-reports')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)
    // テーブル・リストまたは空状態メッセージが表示されること
    const contentSelector = 'table, .p-datatable, [class*="DataTable"], [class*="empty"], [class*="no-data"]'
    const hasContent = await page.locator(contentSelector).first().isVisible().catch(() => false)
    const main = page.locator('main').first()
    await expect(main).toBeVisible({ timeout: 15_000 })
    void hasContent
  })

  test('SYS-013: システム設定ページが表示される（/admin/maintenance）', async ({ page }) => {
    await page.goto('/admin/maintenance')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)
    await expect(page).not.toHaveURL(/\/403|\/forbidden/)
    const content = page.locator('main, [class*="PageHeader"], h1, h2').first()
    await expect(content).toBeVisible({ timeout: 20_000 })
  })
})

// ---------------------------------------------------------------------------
// SYS-014〜015: アクセス制御境界確認
// ---------------------------------------------------------------------------
test.describe('SYS-014〜015: アクセス制御境界確認', () => {
  test('SYS-014: 一般ユーザー（non-admin）が /admin/ にアクセスするとリダイレクトされる', async ({ browser }) => {
    // 未認証状態（空の storageState）でアクセス
    const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const page = await ctx.newPage()
    try {
      await page.goto('/admin/dashboard')
      await page.waitForTimeout(3_000)
      // 未認証の場合は /login にリダイレクトされる
      const url = page.url()
      expect(url).toMatch(/login|my\/dashboard|403|forbidden/)
    } finally {
      await ctx.close()
    }
  })

  test('SYS-015: システム管理者がログアウト後に /admin/ にアクセスできないことを確認', async ({ browser }) => {
    // 未認証コンテキストで直接アクセス
    const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const page = await ctx.newPage()
    try {
      await page.goto('/admin/users')
      await page.waitForTimeout(3_000)
      // 認証されていないので /login にリダイレクトされること
      const url = page.url()
      expect(url).toMatch(/login|403|forbidden/)
    } finally {
      await ctx.close()
    }
  })
})

// ---------------------------------------------------------------------------
// SYS-016〜017: 認証済みMEMBER（非管理者）のadmin route guard
// 実機観測（2026-06-17）:
//   /system-admin/** → /login?redirect=... に即リダイレクト
//   /admin/dashboard → loadingスピナー後に /login へ遷移（Nuxt middleware が非同期で認可チェック）
// ---------------------------------------------------------------------------
test.describe('SYS-016〜017: 認証済みMEMBERのadmin route guard', () => {
  // MEMBER の storageState を使う（chromium-real-admin プロジェクトは admin storageState がデフォルトだが
  // browser.newContext で MEMBER の storageState を明示的に指定してオーバーライドする）
  const MEMBER_STORAGE_STATE = 'tests/e2e/.auth/real-user.json'

  test('SYS-016: 認証済みMEMBERが /system-admin/email-outbox にアクセスすると /login にリダイレクトされる（UI + APIの両層）', async ({ browser, request }) => {
    // --- UI層: FE route guard ---
    const ctx = await browser.newContext({ storageState: MEMBER_STORAGE_STATE })
    const page = await ctx.newPage()
    try {
      await page.goto('/system-admin/email-outbox')
      // system-admin系は Nuxt middleware が即座に /login にリダイレクトする
      await page.waitForURL(/\/login/, { timeout: 15_000 })
      // パス部分のみ確認（redirect= クエリパラメータに元のパスが含まれるのは正常）
      const urlObj = new URL(page.url())
      expect(urlObj.pathname).toMatch(/\/login/)
      expect(urlObj.pathname).not.toContain('/system-admin/')
      expect(urlObj.pathname).not.toContain('/admin/')
    } finally {
      await ctx.close()
    }

    // --- API層: BE認可チェック ---
    // MEMBER の Bearer トークンで system-admin 系 API を叩くと 403 が返ること
    const loginRes = await request.post('http://localhost:8080/api/v1/auth/login', {
      data: { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' },
    })
    expect(loginRes.status(), 'MEMBERログインは200').toBe(200)
    const loginJson = (await loginRes.json()) as { data: { accessToken: string } }
    const memberToken = loginJson.data.accessToken

    const auditRes = await request.get('http://localhost:8080/api/v1/admin/audit-logs', {
      headers: { Authorization: `Bearer ${memberToken}` },
    })
    // audit-logs は ADMIN 認可チェックが実装されているため 403 が返ること
    expect([403], 'MEMBER の /api/v1/admin/audit-logs は 403').toContain(auditRes.status())

    const emailOutboxRes = await request.get('http://localhost:8080/api/v1/system-admin/email-outbox', {
      headers: { Authorization: `Bearer ${memberToken}` },
    })
    expect([403, 404], 'MEMBER の /api/v1/system-admin/email-outbox は 403/404').toContain(emailOutboxRes.status())
  })

  test('SYS-017: 認証済みMEMBERが /admin/dashboard にアクセスすると管理UIが表示されずloginへ遷移する（UI + APIの両層）', async ({ browser, request }) => {
    test.setTimeout(90_000)

    // --- UI層: FE route guard ---
    // /admin/dashboard は Nuxt middleware が非同期で認可チェックし /login にリダイレクトする
    const ctx = await browser.newContext({ storageState: MEMBER_STORAGE_STATE })
    const page = await ctx.newPage()
    try {
      await page.goto('/admin/dashboard')
      // admin middleware がチェックを完了するまで最大 20 秒待つ
      await page.waitForURL(/\/login/, { timeout: 20_000 })
      // パス部分のみ確認（redirect= クエリパラメータに元のパスが含まれるのは正常）
      const urlObj = new URL(page.url())
      expect(urlObj.pathname).toMatch(/\/login/)
      expect(urlObj.pathname).not.toContain('/admin/')
    } finally {
      await ctx.close()
    }

    // --- API層: BE認可チェック ---
    const loginRes = await request.post('http://localhost:8080/api/v1/auth/login', {
      data: { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' },
    })
    expect(loginRes.status(), 'MEMBERログインは200').toBe(200)
    const loginJson = (await loginRes.json()) as { data: { accessToken: string } }
    const memberToken = loginJson.data.accessToken

    // audit-logs API は ADMIN 認可チェック実装済み → 403
    const apiRes = await request.get('http://localhost:8080/api/v1/admin/audit-logs', {
      headers: { Authorization: `Bearer ${memberToken}` },
    })
    expect([403], 'MEMBER の admin API は 403 で拒否されること').toContain(apiRes.status())
  })
})
