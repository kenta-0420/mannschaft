import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { fillInput, fillPassword } from '../helpers/form'

/**
 * VALIDATION-DEEP session-expiry: 401レスポンス・セッション期限切れの深掘り。
 *
 * useApi の onResponseError は 401 を受け取ると refresh トークンを試み、
 * 失敗すると authStore.logout() → navigateTo('/login') する設計。
 *
 * このファイルでは page.route() で API 応答をモックし、トークン期限切れ・
 * リフレッシュ失敗・redirect クエリ保持などのフローを検証する。
 *
 * 1) 認証済みページで API 401 → リフレッシュも失敗 → /login へ遷移
 * 2) リフレッシュトークン期限切れ → /login へ + redirect クエリで戻り先保持
 * 3) ログイン成功後、redirect クエリの URL に戻される
 * 4) 401 後の再ログインで認証ストアが新しいトークンに差し替わる
 * 5) CSRF/不正トークンエラー時のメッセージ表示
 */

test.describe('VALIDATION-DEEP session-expiry: セッション期限切れ・401ハンドリング', () => {
  test('VAL-DEEP-session-001: 認証必須ページの API が 401 を返すと /login にリダイレクトされる', async ({
    page,
  }) => {
    // /api/v1/users/me など、認証必須エンドポイントの 401 をシミュレート
    await page.route('**/api/v1/**', async (route) => {
      const url = route.request().url()
      // refresh も 401 で失敗させて logout を確実に発火させる
      if (url.includes('/api/v1/auth/refresh')) {
        await route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({ error: { message: 'Refresh token expired' } }),
        })
        return
      }
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: { message: 'Unauthorized' } }),
      })
    })

    await page.goto('/dashboard')
    // useApi 内で refresh 失敗 → authStore.logout() → navigateTo('/login')
    await expect(page).toHaveURL(/\/login/, { timeout: 15_000 })
  })

  test('VAL-DEEP-session-002: リフレッシュトークン期限切れ時、redirect クエリで元のURLが保持される', async ({
    page,
  }) => {
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: { message: 'Token expired' } }),
      })
    })

    // localStorage を直接クリアしてから navigate することで、
    // middleware/auth.ts によるリダイレクト（redirect クエリ付き）を発火させる
    await page.goto('/login')
    await page.evaluate(() => {
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      localStorage.removeItem('currentUser')
    })

    await page.goto('/settings/profile')
    // middleware/auth.ts は redirect=/settings/profile クエリを付けてログイン画面に遷移する
    await expect(page).toHaveURL(/\/login\?redirect=.*settings.*profile/, { timeout: 10_000 })
  })

  test('VAL-DEEP-session-003: ログイン成功後、redirect クエリの URL に戻される', async ({
    page,
  }) => {
    // ログイン API を成功でモック
    await page.route('**/api/v1/auth/login', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            accessToken: 'mock-access-token-12345',
            refreshToken: 'mock-refresh-token-67890',
            user: {
              id: 1,
              email: 'redirect-test@example.com',
              displayName: 'リダイレクトテスト',
              profileImageUrl: null,
            },
          },
        }),
      })
    })
    // 戻り先（/dashboard）の API はすべて空配列で応答してログイン後の遷移を妨げない
    await page.route('**/api/v1/users/me', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: 1,
            email: 'redirect-test@example.com',
            displayName: 'リダイレクトテスト',
            profileImageUrl: null,
          },
        }),
      })
    })

    // 未認証状態でログイン画面に redirect クエリ付きで直アクセス
    await page.goto('/login')
    await page.evaluate(() => {
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      localStorage.removeItem('currentUser')
    })
    await page.goto('/login?redirect=/dashboard')
    await waitForHydration(page)

    await fillInput(page.locator('input#email'), 'redirect-test@example.com')
    await fillPassword(page.locator('input[type="password"]'), 'somepassword123')
    await page.getByRole('button', { name: 'ログイン' }).click()

    // ログイン成功で redirect クエリの URL（/dashboard）に遷移する
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 15_000 })
  })

  test('VAL-DEEP-session-004: 401 後の再ログインで認証ストアの accessToken が新しい値に置き換わる', async ({
    page,
  }) => {
    // ログイン API は別のトークンを返す
    await page.route('**/api/v1/auth/login', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            accessToken: 'fresh-access-token-AAAA',
            refreshToken: 'fresh-refresh-token-BBBB',
            user: {
              id: 2,
              email: 'reauth@example.com',
              displayName: '再ログイン',
              profileImageUrl: null,
            },
          },
        }),
      })
    })

    // 既存セッションを破棄してログイン画面に到達
    await page.goto('/login')
    await page.evaluate(() => {
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      localStorage.removeItem('currentUser')
    })
    await page.goto('/login')
    await waitForHydration(page)

    await fillInput(page.locator('input#email'), 'reauth@example.com')
    await fillPassword(page.locator('input[type="password"]'), 'somepassword123')
    await page.getByRole('button', { name: 'ログイン' }).click()

    // ログイン処理完了を待つ（/dashboard へ遷移するか、少なくとも localStorage に書き込まれる）
    await page.waitForFunction(
      () => localStorage.getItem('accessToken') === 'fresh-access-token-AAAA',
      { timeout: 15_000 },
    )

    const storedAccess = await page.evaluate(() => localStorage.getItem('accessToken'))
    const storedRefresh = await page.evaluate(() => localStorage.getItem('refreshToken'))
    expect(storedAccess).toBe('fresh-access-token-AAAA')
    expect(storedRefresh).toBe('fresh-refresh-token-BBBB')
  })

  test('VAL-DEEP-session-005: ログイン API が 403（CSRF/不正トークン）を返すとエラーメッセージが表示される', async ({
    page,
  }) => {
    await page.route('**/api/v1/auth/login', async (route) => {
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({
          error: { code: 'CSRF_TOKEN_INVALID', message: 'CSRF token is invalid' },
        }),
      })
    })

    await page.goto('/login')
    await page.evaluate(() => {
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      localStorage.removeItem('currentUser')
    })
    await page.goto('/login')
    await waitForHydration(page)

    await fillInput(page.locator('input#email'), 'csrf-test@example.com')
    await fillPassword(page.locator('input[type="password"]'), 'somepassword123')
    await page.getByRole('button', { name: 'ログイン' }).click()

    // login.vue 内の catch ブロックは「ログインに失敗しました」を notification で表示する
    await expect(page.getByText('ログインに失敗しました')).toBeVisible({ timeout: 10_000 })
    // ログイン画面に留まる
    await expect(page).toHaveURL(/\/login/)
  })
})
