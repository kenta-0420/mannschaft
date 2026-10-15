/**
 * F19.1 Phase 4 公開チーム・組織検索ページ E2E。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.1
 *
 * シナリオ:
 *  1. 未ログインで `/discover/teams` にアクセス → ページタイトルと検索フォームが表示される
 *  2. キーワードを入力して検索ボタンを押す → 結果が表示される（または「該当なし」メッセージ）
 *  3. 未ログインで `/discover/organizations` にアクセス → ページタイトルと検索フォームが表示される
 *  4. 検索結果カードの「詳細を見る」ボタンをクリック → 詳細ページに遷移する
 *  5. 都道府県フィルタを選択して検索 → フィルタが反映される
 *
 * 【実行前提】
 * 本 spec はバックエンド + フロントエンド統合環境で実行する:
 *   1. `docker-compose up -d` で Spring Boot 8080 + MySQL + Valkey を起動
 *   2. PUBLIC 状態のチームおよび組織レコードを seed
 *
 * 統合環境なしでは SSR fetch が失敗するため test.describe.skip でスキップする。
 */

import { test } from '@playwright/test'

// BE 統合環境必須のためスキップ
test.describe.skip('F19.1 Phase 4 公開検索ページ (BE 統合環境必須)', () => {
  test('DISC-001: /discover/teams にアクセスするとページタイトルと検索フォームが表示される', async ({ page }) => {
    await page.goto('/discover/teams')
    await page.waitForLoadState('networkidle')

    // ページタイトルが表示される
    await page.locator('h1').filter({ hasText: '公開チームを探す' }).waitFor()

    // キーワード入力フォームが存在する
    await page.locator('input[placeholder*="チーム名"]').waitFor()

    // 検索ボタンが存在する
    await page.getByRole('button', { name: '検索' }).waitFor()
  })

  test('DISC-002: キーワード検索で結果または「該当なし」メッセージが表示される', async ({ page }) => {
    await page.goto('/discover/teams')
    await page.waitForLoadState('networkidle')

    const input = page.locator('input[placeholder*="チーム名"]')
    await input.fill('テスト')
    await page.getByRole('button', { name: '検索' }).click()
    await page.waitForLoadState('networkidle')

    // カード一覧 OR 「該当なし」メッセージのいずれかが表示される
    const hasCards = await page.locator('[data-testid="discover-team-card"]').count() > 0
    const hasEmpty = await page.locator('p').filter({ hasText: '該当するチームが見つかりませんでした' }).count() > 0

    if (!hasCards && !hasEmpty) {
      throw new Error('検索結果もエンプティメッセージも表示されていない')
    }
  })

  test('DISC-003: /discover/organizations にアクセスするとページタイトルと検索フォームが表示される', async ({ page }) => {
    await page.goto('/discover/organizations')
    await page.waitForLoadState('networkidle')

    // ページタイトルが表示される
    await page.locator('h1').filter({ hasText: '公開組織を探す' }).waitFor()

    // キーワード入力フォームが存在する
    await page.locator('input[placeholder*="組織名"]').waitFor()
  })

  test('DISC-004: チームカードの「詳細を見る」をクリックすると詳細ページに遷移する', async ({ page }) => {
    await page.goto('/discover/teams')
    await page.waitForLoadState('networkidle')

    // 検索実行（空クエリで全件）
    await page.getByRole('button', { name: '検索' }).click()
    await page.waitForLoadState('networkidle')

    // 最初のカードの「詳細を見る」をクリック
    const firstDetailButton = page.getByRole('link', { name: '詳細を見る' }).first()
    await firstDetailButton.waitFor()
    await firstDetailButton.click()

    // /public/teams/{id} に遷移することを確認
    await page.waitForURL(/\/public\/teams\/\d+/)
  })

  test('DISC-005: 都道府県フィルタを選択して検索するとフィルタが反映される', async ({ page }) => {
    await page.goto('/discover/teams')
    await page.waitForLoadState('networkidle')

    // 都道府県セレクトを開いて「東京都」を選択
    await page.locator('[data-testid="prefecture-select"]').click()
    await page.getByText('東京都').click()
    await page.getByRole('button', { name: '検索' }).click()
    await page.waitForLoadState('networkidle')

    // 結果またはエンプティが表示される
    const hasCards = await page.locator('[data-testid="discover-team-card"]').count() > 0
    const hasEmpty = await page.locator('p').filter({ hasText: '該当するチームが見つかりませんでした' }).count() > 0

    if (!hasCards && !hasEmpty) {
      throw new Error('フィルタ適用後の結果表示が確認できない')
    }
  })
})
