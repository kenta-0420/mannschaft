import { test, expect } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F06.4 SNS シェア機能 E2E テスト
 *
 * `/activity/{id}` 公開ページの表示・404 エラー・シェアボタン動作を検証する。
 *
 * 実装対象:
 * - `frontend/app/pages/activity/[id].vue`    公開活動記録詳細ページ（auth: false）
 * - `frontend/app/components/activity/ActivitySharePanel.vue`  シェアパネル
 * - `frontend/app/composables/useActivityPublicApi.ts`         公開 API コンポーザブル
 *
 * APIモック戦略:
 * - `GET /api/v1/public/activities/{id}` を page.route でインターセプト
 * - 存在する PUBLIC 活動記録 (id=1) → 200 + データ返却
 * - 存在する MEMBERS_ONLY 活動記録 (id=2) → 404 返却
 * - 存在しない id (id=9999999) → 404 返却
 *
 * NOTE:
 * - `/activity/[id].vue` は `definePageMeta({ auth: false })` のため
 *   storageState（ログイン状態）の有無に関わらず動作する。
 * - URLコピーテストは navigator.clipboard が sandboxed なため、
 *   clipboard API をブラウザコンテキストでモックして検証する。
 * - `window.open` を使用するシェアボタンは href/属性の存在確認で検証する。
 */

/** 公開活動記録のモックデータ (id=1) */
const MOCK_PUBLIC_ACTIVITY = {
  data: {
    id: 1,
    scopeType: 'TEAM',
    scopeId: 1,
    title: '春季合宿2026',
    activityDate: '2026-03-20',
    location: '長野県・菅平高原',
    description: '3泊4日の強化合宿。フィジカル強化をメインとしたプログラムです。',
    participantCount: 15,
    customFields: [],
    imageUrl: null,
    organizationName: null,
    teamName: 'テストチーム',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
}

test.describe('SNS-001〜005: F06.4 SNS シェア機能', () => {
  /**
   * SNS-001: 公開活動記録ページが正常に表示される
   *
   * 未認証状態（storageState クリア）で PUBLIC な活動記録ページにアクセスし、
   * タイトル・シェアパネルが表示されることを確認する。
   */
  test('SNS-001: 公開活動記録ページが正常に表示される', async ({ page }) => {
    // 公開活動記録 API をモック
    await page.route('**/api/v1/public/activities/1', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PUBLIC_ACTIVITY),
      })
    })

    // 未認証状態でアクセス（auth: false ページ）
    await page.goto('/activity/1')
    await waitForHydration(page)

    // ローディングが完了するまで待機
    await expect(page.locator('.p-progress-spinner')).not.toBeVisible({ timeout: 10_000 })

    // タイトルが表示されること
    await expect(page.getByRole('heading', { name: '春季合宿2026' })).toBeVisible({
      timeout: 10_000,
    })

    // チーム名が表示されること
    await expect(page.getByText('テストチーム')).toBeVisible({ timeout: 5_000 })

    // 場所が表示されること
    await expect(page.getByText('長野県・菅平高原')).toBeVisible({ timeout: 5_000 })

    // シェアパネルが表示されること（シェアするセクション）
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
   * API が 404 を返した場合、ページは 404 エラー表示になることを確認する。
   */
  test('SNS-002: MEMBERS_ONLY 活動記録は 404 エラーページになる', async ({ page }) => {
    // MEMBERS_ONLY 活動記録 API → 404 を返す
    await page.route('**/api/v1/public/activities/2', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Not Found', message: 'Activity is not public' }),
      })
    })

    await page.goto('/activity/2')
    await waitForHydration(page)

    // 404 エラーが表示されること（Nuxt の createError で 404 を throw している）
    await page.waitForTimeout(3_000)
    const bodyText = await page.locator('body').textContent()
    expect(bodyText).toMatch(/404|見つかりません|Not Found/i)
  })

  /**
   * SNS-003: 存在しない ID では 404 エラーページになる
   *
   * 存在しない活動記録 ID にアクセスした場合も 404 エラー表示になることを確認する。
   */
  test('SNS-003: 存在しない ID は 404 エラーページになる', async ({ page }) => {
    // 存在しない活動記録 → 404
    await page.route('**/api/v1/public/activities/9999999', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Not Found', message: 'Activity not found' }),
      })
    })

    await page.goto('/activity/9999999')
    await waitForHydration(page)

    await page.waitForTimeout(3_000)
    const bodyText = await page.locator('body').textContent()
    expect(bodyText).toMatch(/404|見つかりません|Not Found/i)
  })

  /**
   * SNS-004: URLコピーボタンをクリックすると「コピーしました」が表示される
   *
   * navigator.clipboard をブラウザコンテキストでモックし、
   * コピーボタンクリック後のフィードバック表示を確認する。
   */
  test('SNS-004: URLコピーボタンで「コピーしました」フィードバックが表示される', async ({
    page,
  }) => {
    // 公開活動記録 API をモック
    await page.route('**/api/v1/public/activities/1', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PUBLIC_ACTIVITY),
      })
    })

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
    await expect(page.locator('.p-progress-spinner')).not.toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('heading', { name: '春季合宿2026' })).toBeVisible({
      timeout: 10_000,
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
    // 公開活動記録 API をモック
    await page.route('**/api/v1/public/activities/1', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PUBLIC_ACTIVITY),
      })
    })

    await page.goto('/activity/1')
    await waitForHydration(page)

    // ローディング完了を待機
    await expect(page.locator('.p-progress-spinner')).not.toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('heading', { name: '春季合宿2026' })).toBeVisible({
      timeout: 10_000,
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
