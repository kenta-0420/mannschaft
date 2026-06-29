/**
 * このテストはAPIモックを使わない実機テストです。
 * バックエンド (http://localhost:8080) とフロントエンド (http://localhost:3000) が
 * 起動中であることを前提とし、実際のログインフォーム操作で認証フローを検証します。
 *
 * テストユーザー:
 *   一般ユーザー: e2e-user@test.mannschaft.local / TestPass2026!
 *   管理者:       e2e-admin@test.mannschaft.local / TestPass2026!
 */

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// ログイン試行はレート制限（10回/分/IP）があるため直列実行する
test.describe.configure({ mode: 'serial' })

const USER_EMAIL = 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = 'TestPass2026!'
const ADMIN_EMAIL = 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = 'TestPass2026!'

/** ログインフォームを操作してログインする（PrimeVue の v-model に対応） */
async function performLogin(
  page: import('@playwright/test').Page,
  email: string,
  password: string,
): Promise<void> {
  await page.goto('/login')
  await waitForHydration(page)

  const emailInput = page.locator('input#email')
  await emailInput.click()
  await emailInput.pressSequentially(email, { delay: 10 })

  const passwordInput = page.locator('input[type="password"]')
  await passwordInput.click()
  await passwordInput.pressSequentially(password, { delay: 10 })

  await page.getByRole('button', { name: 'ログイン' }).click()
}

// ----------------------------------------------------------------------------------
// AUTH-001〜005: ログイン成功フロー
// ----------------------------------------------------------------------------------
test.describe('AUTH-001〜005: ログイン成功フロー', () => {
  // 各テストは未認証状態から開始する
  test.use({ storageState: { cookies: [], origins: [] } })

  test('AUTH-001: 一般ユーザーでログイン → /my/ または /dashboard にリダイレクト', async ({
    page,
  }) => {
    await performLogin(page, USER_EMAIL, USER_PASSWORD)

    // ログイン後は '/' → '/dashboard' にリダイレクトされる（guest middleware → auth middleware）
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 })
    const url = page.url()
    expect(url).toMatch(/\/(dashboard|my)/)
  })

  test('AUTH-002: 管理者でログイン → /my/ または /dashboard にリダイレクト', async ({
    page,
  }) => {
    await performLogin(page, ADMIN_EMAIL, ADMIN_PASSWORD)

    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 })
    const url = page.url()
    // 管理者は systemRole によって /system-admin にリダイレクトされる場合もある
    expect(url).toMatch(/\/(dashboard|my|system-admin)/)
  })

  test('AUTH-003: ログイン後にページヘッダー/ナビゲーションが表示される', async ({ page }) => {
    await performLogin(page, USER_EMAIL, USER_PASSWORD)

    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 })

    // default layout のヘッダーが表示されていること
    await expect(page.locator('header')).toBeVisible({ timeout: 10_000 })
    // ロゴ「Mannschaft」がヘッダーに存在する
    await expect(page.locator('header').getByText('Mannschaft')).toBeVisible({ timeout: 5_000 })
  })

  test('AUTH-004: ログイン後にナビゲーション（ダッシュボードリンク）が表示される', async ({
    page,
  }) => {
    await performLogin(page, USER_EMAIL, USER_PASSWORD)

    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 })

    // ナビゲーションに「ダッシュボード」リンクが表示されること（APIからフェッチ後に描画される）
    await expect(page.getByRole('link', { name: 'ダッシュボード' }).first()).toBeVisible({
      timeout: 10_000,
    })
  })

  test('AUTH-005: 認証済みで /login にアクセスすると /dashboard にリダイレクト（二重ログイン防止）', async ({
    page,
  }) => {
    // まずログインする
    await performLogin(page, USER_EMAIL, USER_PASSWORD)
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 })

    // 認証済み状態で /login に直接アクセスすると guest middleware が /dashboard へリダイレクト
    await page.goto('/login')
    await page.waitForURL(/\/dashboard/, { timeout: 15_000 })
    expect(page.url()).toContain('/dashboard')
  })
})

// ----------------------------------------------------------------------------------
// AUTH-006〜009: ログイン失敗フロー
// ----------------------------------------------------------------------------------
test.describe('AUTH-006〜009: ログイン失敗フロー', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('AUTH-006: 存在しないメールアドレスでログイン → エラーメッセージ表示', async ({
    page,
  }) => {
    await performLogin(page, 'no-such-user@invalid.example.com', 'SomePassword123!')

    // バックエンドは AUTH_001 を返し、フロントは notification.error でトースト表示
    await expect(page.getByText('ログインに失敗しました')).toBeVisible({ timeout: 15_000 })
    // /login から遷移していないこと
    await expect(page).toHaveURL(/\/login/)
  })

  test('AUTH-007: 正しいメールアドレス + 誤ったパスワード → エラーメッセージ表示', async ({
    page,
  }) => {
    await performLogin(page, USER_EMAIL, 'WrongPassword999!')

    await expect(page.getByText('ログインに失敗しました')).toBeVisible({ timeout: 15_000 })
    await expect(page).toHaveURL(/\/login/)
  })

  test('AUTH-008: メールアドレス未入力でフォーム送信 → バリデーションエラー（ページ遷移しない）', async ({
    page,
  }) => {
    await page.goto('/login')
    await waitForHydration(page)

    // パスワードのみ入力してログインボタンをクリック
    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially(USER_PASSWORD, { delay: 10 })

    await page.getByRole('button', { name: 'ログイン' }).click()

    // HTML5 required バリデーションでフォーム送信がブロックされ、/login に留まること
    // 短時間待機してから URL を確認（ナビゲーションが起きていないことを検証）
    await page.waitForTimeout(2_000)
    await expect(page).toHaveURL(/\/login/)
  })

  test('AUTH-009: パスワード未入力でフォーム送信 → バリデーションエラー（ページ遷移しない）', async ({
    page,
  }) => {
    await page.goto('/login')
    await waitForHydration(page)

    // メールアドレスのみ入力してログインボタンをクリック
    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(USER_EMAIL, { delay: 10 })

    await page.getByRole('button', { name: 'ログイン' }).click()

    // HTML5 required バリデーションでフォーム送信がブロックされ、/login に留まること
    await page.waitForTimeout(2_000)
    await expect(page).toHaveURL(/\/login/)
  })
})

// ----------------------------------------------------------------------------------
// AUTH-010〜012: ログアウトフロー
// ----------------------------------------------------------------------------------
test.describe('AUTH-010〜012: ログアウトフロー', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('AUTH-010: ログイン後にログアウト → /login にリダイレクト', async ({ page }) => {
    // ログイン
    await performLogin(page, USER_EMAIL, USER_PASSWORD)
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 })

    // ヘッダーのログアウトボタン（デスクトップ: tooltip "ログアウト"、icon pi-sign-out）
    // v-tooltip は aria-label に紐付かないため、pi-sign-out アイコンを持つボタンで特定する
    const logoutBtn = page
      .locator('header button')
      .filter({ has: page.locator('.pi-sign-out') })
      .first()
    await expect(logoutBtn).toBeVisible({ timeout: 10_000 })
    await logoutBtn.click()

    // /login にリダイレクトされること
    await page.waitForURL(/\/login/, { timeout: 15_000 })
    expect(page.url()).toContain('/login')
  })

  test('AUTH-011: ログアウト後に認証必須ページ (/dashboard) にアクセスすると /login にリダイレクト', async ({
    page,
  }) => {
    // ログイン
    await performLogin(page, USER_EMAIL, USER_PASSWORD)
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 })

    // ログアウト
    const logoutBtn = page
      .locator('header button')
      .filter({ has: page.locator('.pi-sign-out') })
      .first()
    await expect(logoutBtn).toBeVisible({ timeout: 10_000 })
    await logoutBtn.click()
    await page.waitForURL(/\/login/, { timeout: 15_000 })

    // ログアウト後に /dashboard にアクセスすると /login にリダイレクトされること
    await page.goto('/dashboard')
    await page.waitForURL(/\/login/, { timeout: 10_000 })
    expect(page.url()).toContain('/login')
  })

  test('AUTH-012: ログアウト後に /login ページが表示される', async ({ page }) => {
    // ログイン
    await performLogin(page, USER_EMAIL, USER_PASSWORD)
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 })

    // ログアウト
    const logoutBtn = page
      .locator('header button')
      .filter({ has: page.locator('.pi-sign-out') })
      .first()
    await expect(logoutBtn).toBeVisible({ timeout: 10_000 })
    await logoutBtn.click()
    await page.waitForURL(/\/login/, { timeout: 15_000 })

    await waitForHydration(page)

    // ログインフォームが表示されていること
    await expect(page.locator('input#email')).toBeVisible({ timeout: 5_000 })
    await expect(page.locator('input[type="password"]')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByRole('button', { name: 'ログイン' })).toBeVisible()
  })
})

// ----------------------------------------------------------------------------------
// AUTH-013〜015: セッション
// ----------------------------------------------------------------------------------
test.describe('AUTH-013〜015: セッション', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('AUTH-013: ログイン後のページリロードで認証状態が維持される', async ({ page }) => {
    // ログイン
    await performLogin(page, USER_EMAIL, USER_PASSWORD)
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 })

    // ページリロード
    await page.reload()
    await waitForHydration(page)

    // リロード後も /login にリダイレクトされず、認証済みページに留まること
    await page.waitForTimeout(3_000)
    expect(page.url()).not.toContain('/login')
    // ヘッダーが表示されていること
    await expect(page.locator('header')).toBeVisible({ timeout: 10_000 })
  })

  test('AUTH-014: ログイン後に /settings ページが表示される（基本表示確認）', async ({
    page,
  }) => {
    // ログイン
    await performLogin(page, USER_EMAIL, USER_PASSWORD)
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 })

    // /settings に遷移
    await page.goto('/settings')
    await waitForHydration(page)

    // /login にリダイレクトされず、設定ページが表示されること
    await expect(page).not.toHaveURL(/\/login/, { timeout: 5_000 })
    // ヘッダーが引き続き表示されていること
    await expect(page.locator('header')).toBeVisible({ timeout: 10_000 })
  })

  test('AUTH-015: ログイン後に /notifications ページが表示される', async ({ page }) => {
    // ログイン
    await performLogin(page, USER_EMAIL, USER_PASSWORD)
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 })

    // /notifications に遷移
    await page.goto('/notifications')
    await waitForHydration(page)

    // /login にリダイレクトされず、通知ページが表示されること
    await expect(page).not.toHaveURL(/\/login/, { timeout: 5_000 })
    await expect(page.getByRole('heading', { name: '通知' })).toBeVisible({ timeout: 15_000 })
  })
})
