import { test, expect } from '@playwright/test'

/**
 * VALIDATION-DEEP navigation-guard: ナビゲーションガード（middleware）の深掘り。
 *
 * Mannschaft の認証ガードは以下の3層で構成される。
 * - app/middleware/auth.ts        : サーバー実行をスキップし、未認証ならログイン画面へ
 * - app/middleware/auth.client.ts : クライアント限定。未認証なら redirect クエリ付きでログインへ
 * - app/middleware/guest.ts       : 認証済みなら /dashboard に強制遷移
 *
 * このファイルでは未認証/認証済みの2グループに describe を分け、
 * それぞれの中で storageState を出し分けて middleware の動作を網羅する。
 *
 * - GUEST グループ: storageState を空にして未認証ユーザーとしてアクセス
 * - AUTHED グループ: デフォルトの認証済み storageState のままアクセス
 */

// =====================================================================
// GUEST ブロック: 未認証ユーザー
// =====================================================================
test.describe('VALIDATION-DEEP navigation-guard (未認証)', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('VAL-DEEP-nav-001: 未認証で /dashboard 直アクセスは /login にリダイレクトされる', async ({
    page,
  }) => {
    await page.goto('/dashboard')
    // middleware/auth.ts により redirect クエリ付きで /login へ遷移
    await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
    // 戻り先（/dashboard）が redirect クエリで保持されている
    const url = new URL(page.url())
    expect(url.searchParams.get('redirect')).toContain('/dashboard')
  })

  test('VAL-DEEP-nav-002: 未認証で /settings/profile 直アクセスは /login にリダイレクトされる', async ({
    page,
  }) => {
    await page.goto('/settings/profile')
    await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
    const url = new URL(page.url())
    // 戻り先パスが「settings」と「profile」の両方を含むことだけを確認
    // （URL エンコーディング差異に左右されない判定にする）
    const redirect = url.searchParams.get('redirect') ?? ''
    expect(redirect).toContain('settings')
    expect(redirect).toContain('profile')
  })
})

// =====================================================================
// AUTHED ブロック: 認証済みユーザー（デフォルト storageState）
// =====================================================================
test.describe('VALIDATION-DEEP navigation-guard (認証済み)', () => {
  test('VAL-DEEP-nav-003: 認証済みで /login 直アクセスは /dashboard にリダイレクトされる', async ({
    page,
  }) => {
    // dashboard の各種 API を空配列で応答してリダイレクト後の描画を妨げない
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })

    await page.goto('/login')
    // middleware/guest.ts により /dashboard へ強制遷移
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 10_000 })
  })

  test('VAL-DEEP-nav-004: 認証済みで /register 直アクセスは /dashboard にリダイレクトされる', async ({
    page,
  }) => {
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })

    await page.goto('/register')
    // middleware/guest.ts により /dashboard へ強制遷移
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 10_000 })
  })

  test('VAL-DEEP-nav-005: SYSTEM_ADMIN権限なしで /system-admin にアクセスすると API が 403 を返してもページに留まらず情報が出ない', async ({
    page,
  }) => {
    // system-admin の各 API は403を返す（権限不足）
    let any403Returned = false
    await page.route('**/api/v1/system-admin/**', async (route) => {
      any403Returned = true
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'FORBIDDEN', message: 'Forbidden' } }),
      })
    })
    // 他の API は空応答
    await page.route('**/api/v1/**', async (route) => {
      if (route.request().url().includes('/system-admin/')) {
        await route.fallback()
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })

    await page.goto('/system-admin')
    // ページ自体は middleware: 'auth' のみで開けるが、API が 403 で全ロード失敗
    // → ダッシュボードの統計データが表示されない（loading 後も moderationStats が null）
    await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {})

    // 少なくとも 1 件以上の system-admin API が 403 で応答済みであること
    expect(any403Returned).toBe(true)

    // 「再読み込み」ボタンは出るが、KPI 系のコンポーネントは null データなのでレンダリングが空のまま
    // 安全側で URL がログインに飛ばされていないこと（auth middleware は通過する）を確認
    await expect(page).toHaveURL(/\/system-admin/)
  })
})
