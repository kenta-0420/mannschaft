/**
 * このテストはAPIモックを使わない実機テストです。
 *
 * Phase 1: 未認証・公開ページ E2E テスト（実機）
 *
 * 実際のバックエンド（localhost:8080）・フロントエンド（localhost:3000）に対して
 * リクエストを送信する。page.route によるモックは使用しない。
 * ブラウザの navigator.clipboard API のみブラウザコンテキストでモックする。
 *
 * テストデータ（seed-e2e-data.js 投入済み前提）:
 * - ID=1: seed で最初に作成される PUBLIC な活動記録
 *         title: '春季合宿2026', activityDate: '2026-03-25',
 *         所属スコープ: TEAM 'FC東京U-18（テスト）'（visibility=PUBLIC）
 * - ID=2: MEMBERS_ONLY な活動記録（バックエンドが未認証に 404 を返す）
 * - ID=9999999: 存在しない ID
 *
 * NOTE: seed は location='長野県・菅平高原' も投入しているが、公開 API
 * （PublicActivityDetail）は御裁可済み 8 項目のみを返し location は**禁則フィールド**である。
 * よって公開ページに開催場所は出ない。ここを assert してはならない
 * （出てしまったら公開 DTO の漏洩であり、BE 契約テスト側で落ちるべき事象）。
 *
 * 認証状態:
 * - 全テスト: storageState を空にして未認証状態で実行する
 */

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// 全テストを未認証状態で実行する
test.use({ storageState: { cookies: [], origins: [] } })

// ============================================================
// PUB-001〜008: /activity/[id] 公開活動記録ページ
// ============================================================

test.describe('PUB-001〜008: /activity/[id] 公開活動記録ページ', () => {
  /**
   * PUB-001: PUBLIC な活動記録ページ（ID=1）が未認証で表示される
   *
   * auth: false のページであるため、storageState がなくてもアクセスできる。
   */
  test('PUB-001: PUBLIC な活動記録ページ（ID=1）が未認証で表示される', async ({ page }) => {
    await page.goto('/activity/1')
    await waitForHydration(page)

    // ローディングスピナーが消えるまで待機（実バックエンドのレスポンス待ち）
    await expect(page.locator('.p-progress-spinner')).not.toBeVisible({ timeout: 15_000 })

    // ページ本体（main コンテンツ）が表示されること
    await expect(page.locator('main').first()).toBeVisible({ timeout: 15_000 })

    // 404 や エラーページではないこと
    const bodyText = await page.locator('body').textContent()
    expect(bodyText).not.toMatch(/^404$/)
  })

  /**
   * PUB-002: タイトル「春季合宿2026」が表示される
   */
  test('PUB-002: タイトル「春季合宿2026」が表示される', async ({ page }) => {
    await page.goto('/activity/1')
    await waitForHydration(page)

    await expect(page.locator('.p-progress-spinner')).not.toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('heading', { name: '春季合宿2026' })).toBeVisible({
      timeout: 15_000,
    })
  })

  /**
   * PUB-003: 記録の中身（スコープ名・活動日・本文）が表示される
   *
   * 「公開ページに記録の内容が表示される」ことを守るテスト。
   * 旧版は開催場所（location）を assert していたが、公開 API の禁則フィールドとなり
   * 返らなくなったため、公開してよい 8 項目のうち画面に出る要素で守り直す。
   * - scopeRef.scopeName（所属チーム名）
   * - activityDate（活動日）
   * - description（本文）
   */
  test('PUB-003: 記録の中身（スコープ名・活動日・本文）が表示される', async ({ page }) => {
    await page.goto('/activity/1')
    await waitForHydration(page)

    await expect(page.locator('.p-progress-spinner')).not.toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('heading', { name: '春季合宿2026' })).toBeVisible({
      timeout: 15_000,
    })

    // スコープ名（BE: PublicScopeRef.scopeName）
    await expect(page.getByText('FC東京U-18（テスト）')).toBeVisible({ timeout: 5_000 })
    // 活動日
    await expect(page.getByText('2026-03-25')).toBeVisible({ timeout: 5_000 })
    // 本文（seed の description）
    await expect(page.getByText('菅平高原での春季合宿。', { exact: false })).toBeVisible({
      timeout: 5_000,
    })

    // 開催場所は公開 DTO の禁則フィールド。表示されていたら漏洩である。
    await expect(page.getByText('長野県・菅平高原')).toHaveCount(0)
  })

  /**
   * PUB-004: 「シェアする」パネルが表示される
   */
  test('PUB-004: 「シェアする」パネルが表示される', async ({ page }) => {
    await page.goto('/activity/1')
    await waitForHydration(page)

    await expect(page.locator('.p-progress-spinner')).not.toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('heading', { name: '春季合宿2026' })).toBeVisible({
      timeout: 15_000,
    })
    await expect(page.getByText('シェアする')).toBeVisible({ timeout: 5_000 })
  })

  /**
   * PUB-005: X・LINE・Threads・メールのシェアボタンが全て存在する
   */
  test('PUB-005: X/LINE/Threads/メールのシェアボタンが全て存在する', async ({ page }) => {
    await page.goto('/activity/1')
    await waitForHydration(page)

    await expect(page.locator('.p-progress-spinner')).not.toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('heading', { name: '春季合宿2026' })).toBeVisible({
      timeout: 15_000,
    })

    await expect(page.getByRole('button', { name: 'X でシェア' })).toBeVisible({ timeout: 5_000 })
    await expect(page.getByRole('button', { name: 'LINE でシェア' })).toBeVisible({
      timeout: 5_000,
    })
    await expect(page.getByRole('button', { name: 'Threads でシェア' })).toBeVisible({
      timeout: 5_000,
    })
    await expect(page.getByRole('button', { name: 'メールで送る' })).toBeVisible({
      timeout: 5_000,
    })
  })

  /**
   * PUB-006: URLコピーボタンで「コピーしました」フィードバックが表示される
   *
   * navigator.clipboard はブラウザコンテキストでのみモックする（テスト環境の制限）。
   * API モック（page.route）は使用しない。
   */
  test('PUB-006: URLコピーボタンで「コピーしました」フィードバックが表示される', async ({
    page,
  }) => {
    // navigator.clipboard.writeText をモック（テスト環境では clipboard API が制限される）
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'clipboard', {
        value: {
          writeText: () => Promise.resolve(),
          readText: () => Promise.resolve(''),
        },
        writable: true,
        configurable: true,
      })
    })

    await page.goto('/activity/1')
    await waitForHydration(page)

    await expect(page.locator('.p-progress-spinner')).not.toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('heading', { name: '春季合宿2026' })).toBeVisible({
      timeout: 15_000,
    })

    // URLコピーボタンをクリック
    const copyButton = page.getByRole('button', { name: 'リンクをコピー' })
    await expect(copyButton).toBeVisible({ timeout: 5_000 })
    await copyButton.click()

    // 「コピーしました」フィードバックが一時的に表示されること
    await expect(page.getByRole('button', { name: 'コピーしました' })).toBeVisible({
      timeout: 3_000,
    })
  })

  /**
   * PUB-007: 存在しないID（9999999）は404エラーページになる
   */
  test('PUB-007: 存在しないID（9999999）は404エラーページになる', async ({ page }) => {
    await page.goto('/activity/9999999')
    await waitForHydration(page)

    await page.waitForTimeout(5_000)
    const bodyText = await page.locator('body').textContent()
    expect(bodyText).toMatch(/404|見つかりません|Not Found/i)
  })

  /**
   * PUB-008: MEMBERS_ONLY（ID=2）な活動記録は未認証で404エラーページになる
   *
   * seed データで ID=2 が存在しない場合はスキップする。
   */
  test('PUB-008: MEMBERS_ONLY（ID=2）な活動記録は未認証で404エラーページになる', async ({
    page,
  }) => {
    await page.goto('/activity/2')
    await waitForHydration(page)

    await page.waitForTimeout(5_000)
    const bodyText = await page.locator('body').textContent()

    // ID=2 が存在しないか、MEMBERS_ONLY で 404 が返ることを確認
    // seed によってはこのレコードが存在しない可能性があるため、
    // 404 または Not Found いずれかのパターンでパスとする
    expect(bodyText).toMatch(/404|見つかりません|Not Found/i)
  })
})

// ============================================================
// PUB-009〜011: 未認証時のリダイレクト確認
// ============================================================

test.describe('PUB-009〜011: 未認証時の /login リダイレクト', () => {
  /**
   * PUB-009: 未認証で /my/dashboard にアクセスすると /login にリダイレクトされる
   *
   * auth ミドルウェアが isAuthenticated=false を検出し、
   * /login?redirect=... にリダイレクトすることを確認する。
   */
  test('PUB-009: 未認証で /my/dashboard にアクセスすると /login にリダイレクトされる', async ({
    page,
  }) => {
    await page.goto('/my/')
    await waitForHydration(page)

    // auth ミドルウェアによってリダイレクトされること
    await expect(page).toHaveURL(/\/login/, { timeout: 15_000 })
  })

  /**
   * PUB-010: 未認証で /teams にアクセスすると /login にリダイレクトされる
   */
  test('PUB-010: 未認証で /teams にアクセスすると /login にリダイレクトされる', async ({
    page,
  }) => {
    await page.goto('/teams')
    await waitForHydration(page)

    await expect(page).toHaveURL(/\/login/, { timeout: 15_000 })
  })

  /**
   * PUB-011: 未認証で /notifications にアクセスすると /login にリダイレクトされる
   */
  test('PUB-011: 未認証で /notifications にアクセスすると /login にリダイレクトされる', async ({
    page,
  }) => {
    await page.goto('/notifications')
    await waitForHydration(page)

    await expect(page).toHaveURL(/\/login/, { timeout: 15_000 })
  })
})

// ============================================================
// PUB-012〜015: /login ページの基本要素確認
// ============================================================

test.describe('PUB-012〜015: /login ページの基本要素', () => {
  /**
   * PUB-012: /login ページが表示される（メールアドレス・パスワード入力欄あり）
   */
  test('PUB-012: /login ページが表示される（メールアドレス・パスワード入力欄あり）', async ({
    page,
  }) => {
    await page.goto('/login')
    await waitForHydration(page)

    // メールアドレス入力欄が存在すること
    await expect(page.locator('input#email')).toBeVisible({ timeout: 15_000 })

    // パスワード入力欄が存在すること
    await expect(page.locator('input[type="password"]')).toBeVisible({ timeout: 5_000 })
  })

  /**
   * PUB-013: ログインボタンが存在する
   */
  test('PUB-013: ログインボタンが存在する', async ({ page }) => {
    await page.goto('/login')
    await waitForHydration(page)

    await expect(page.getByRole('button', { name: 'ログイン' })).toBeVisible({ timeout: 15_000 })
  })

  /**
   * PUB-014: 「パスワードをお忘れですか？」リンクが存在する
   */
  test('PUB-014: 「パスワードをお忘れですか？」リンクが存在する', async ({ page }) => {
    await page.goto('/login')
    await waitForHydration(page)

    await expect(page.getByRole('link', { name: 'パスワードをお忘れですか？' })).toBeVisible({
      timeout: 15_000,
    })
  })

  /**
   * PUB-015: 「新規アカウント作成」リンクが存在する
   */
  test('PUB-015: 「新規アカウント作成」リンクが存在する', async ({ page }) => {
    await page.goto('/login')
    await waitForHydration(page)

    await expect(page.getByRole('link', { name: '新規アカウント作成' })).toBeVisible({
      timeout: 15_000,
    })
  })
})
