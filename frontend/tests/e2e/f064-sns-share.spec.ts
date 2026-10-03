import { test, expect } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F06.4 SNS シェア機能 E2E テスト（実機）
 *
 * `/activity/{id}` 公開ページの表示・404 エラー・シェアボタン動作を検証する。
 * APIモックは使用しない。実際のバックエンド（localhost:8080）に対してリクエストを送信する。
 *
 * テストデータ:
 * - ID=1: PUBLIC な活動記録（テスト環境DBに投入済み）
 *         title: '春季合宿2026' / 所属スコープ: TEAM 'FC東京U-18（テスト）'
 * - ID=2: MEMBERS_ONLY な活動記録（バックエンドが 404 を返す）
 * - ID=9999999: 存在しない ID
 *
 * NOTE: 公開 API（PublicActivityDetail）は御裁可済み 8 項目のみを返す。
 * 開催場所（location）は禁則フィールドで返らないため、画面にも出ない。
 *
 * 認証:
 * - `/activity/[id].vue` は `definePageMeta({ auth: false })` のため
 *   storageState（ログイン状態）の有無に関わらず動作する。
 *   このテストは未認証状態で実行する。
 */

// 未認証状態で実行（storageState を使わない）
test.use({ storageState: { cookies: [], origins: [] } })

test.describe('SNS-001〜005: F06.4 SNS シェア機能（実機）', () => {
  /**
   * SNS-001: 公開活動記録ページが正常に表示される
   *
   * 未認証状態で PUBLIC な活動記録ページにアクセスし、
   * タイトル・シェアパネルが表示されることを確認する。
   */
  test('SNS-001: 公開活動記録ページが正常に表示される', async ({ page }) => {
    // 未認証状態でアクセス（auth: false ページ）
    await page.goto('/activity/1')
    await waitForHydration(page)

    // ローディングが完了するまで待機
    await expect(page.locator('.p-progress-spinner')).not.toBeVisible({ timeout: 15_000 })

    // タイトルが表示されること（DBに投入したタイトル）
    await expect(page.getByRole('heading', { name: '春季合宿2026' })).toBeVisible({
      timeout: 15_000,
    })

    // 所属スコープ名が表示されること（BE: PublicScopeRef.scopeName）
    // 旧版は開催場所を assert していたが、公開 DTO の禁則フィールドとなり返らなくなったため、
    // 公開してよい項目のうち「記録の帰属が分かる」要素で守り直す。
    await expect(page.getByText('FC東京U-18（テスト）')).toBeVisible({ timeout: 5_000 })

    // 本文が表示されること（seed の description）
    await expect(page.getByText('菅平高原での春季合宿。', { exact: false })).toBeVisible({
      timeout: 5_000,
    })

    // シェアパネルが表示されること（「シェアする」セクション）
    await expect(page.getByText('シェアする')).toBeVisible({ timeout: 5_000 })

    // X でシェアボタンが存在すること
    await expect(page.getByRole('button', { name: 'X でシェア' })).toBeVisible({ timeout: 5_000 })

    // LINE でシェアボタンが存在すること
    await expect(page.getByRole('button', { name: 'LINE でシェア' })).toBeVisible({
      timeout: 5_000,
    })

    // URLコピーボタンが存在すること
    await expect(page.getByRole('button', { name: 'リンクをコピー' })).toBeVisible({
      timeout: 5_000,
    })
  })

  /**
   * SNS-002: MEMBERS_ONLY 活動記録へのアクセスは 404 エラーページになる
   *
   * バックエンドが 404 を返した場合、ページは 404 エラー表示になることを確認する。
   */
  test('SNS-002: MEMBERS_ONLY 活動記録は 404 エラーページになる', async ({ page }) => {
    await page.goto('/activity/2')
    await waitForHydration(page)

    // 404 エラーが表示されること（Nuxt の createError で 404 を throw している）
    await page.waitForTimeout(5_000)
    const bodyText = await page.locator('body').textContent()
    expect(bodyText).toMatch(/404|見つかりません|Not Found/i)
  })

  /**
   * SNS-003: 存在しない ID では 404 エラーページになる
   *
   * 存在しない活動記録 ID にアクセスした場合も 404 エラー表示になることを確認する。
   */
  test('SNS-003: 存在しない ID は 404 エラーページになる', async ({ page }) => {
    await page.goto('/activity/9999999')
    await waitForHydration(page)

    await page.waitForTimeout(5_000)
    const bodyText = await page.locator('body').textContent()
    expect(bodyText).toMatch(/404|見つかりません|Not Found/i)
  })

  /**
   * SNS-004: URLコピーボタンをクリックすると「コピーしました」が表示される
   *
   * navigator.clipboard をブラウザコンテキストでモックし（API制限のため）、
   * コピーボタンクリック後のフィードバック表示を確認する。
   */
  test('SNS-004: URLコピーボタンで「コピーしました」フィードバックが表示される', async ({
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

    // ローディング完了を待機
    await expect(page.locator('.p-progress-spinner')).not.toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('heading', { name: '春季合宿2026' })).toBeVisible({
      timeout: 15_000,
    })

    // URLコピーボタンをクリック
    const copyButton = page.getByRole('button', { name: 'リンクをコピー' })
    await expect(copyButton).toBeVisible({ timeout: 5_000 })
    await copyButton.click()

    // 「コピーしました」テキストが一時的に表示されること
    await expect(page.getByRole('button', { name: 'コピーしました' })).toBeVisible({
      timeout: 3_000,
    })
  })

  /**
   * SNS-005: シェアボタンの存在確認（X・LINE・Threads・メール）
   *
   * 公開ページにアクセスしてすべてのシェアボタンが存在することを確認する。
   */
  test('SNS-005: シェアボタン（X・LINE・Threads・メール）がすべて存在する', async ({ page }) => {
    await page.goto('/activity/1')
    await waitForHydration(page)

    // ローディング完了を待機
    await expect(page.locator('.p-progress-spinner')).not.toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('heading', { name: '春季合宿2026' })).toBeVisible({
      timeout: 15_000,
    })

    // X でシェアボタンが存在すること
    await expect(page.getByRole('button', { name: 'X でシェア' })).toBeVisible({ timeout: 5_000 })

    // LINE でシェアボタンが存在すること
    await expect(page.getByRole('button', { name: 'LINE でシェア' })).toBeVisible({
      timeout: 5_000,
    })

    // Threads でシェアボタンが存在すること
    await expect(page.getByRole('button', { name: 'Threads でシェア' })).toBeVisible({
      timeout: 5_000,
    })

    // メールでシェアボタンが存在すること
    await expect(page.getByRole('button', { name: 'メールで送る' })).toBeVisible({
      timeout: 5_000,
    })

    // URLコピーボタンが存在すること
    await expect(page.getByRole('button', { name: 'リンクをコピー' })).toBeVisible({
      timeout: 5_000,
    })
  })
})
