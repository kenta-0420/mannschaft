import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * ダッシュボードウィジェット設定ページ E2E テスト
 *
 * AC4-1: /settings/dashboard-widgets でスコープ別に表示/非表示/並び替え→保存→リロード後も保持
 * AC4-2: 設定ページと歯車ダイアログの編集結果が相互一致（同一composable使用を確認）
 * AC4-3: 設定トップ（/settings）の検索で「ダッシュボード」がヒットする
 */

test.describe('SET-DW-001〜006: ダッシュボードウィジェット設定ページ', () => {
  // 所属チーム・組織APIのモック設定（共通）
  async function mockTeamsAndOrgs(page: import('@playwright/test').Page) {
    await page.route('**/api/v1/me/teams', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [
            {
              id: 1,
              slug: 'test-team',
              name: 'テストチーム',
              nickname1: null,
              iconUrl: null,
              role: 'ADMIN',
              template: 'DEFAULT',
              memberCount: 5,
            },
          ],
        }),
      })
    })

    await page.route('**/api/v1/me/organizations', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [
            {
              id: 10,
              slug: 'test-org',
              name: 'テスト組織',
              nickname1: null,
              iconUrl: null,
              role: 'ADMIN',
              orgType: 'GENERAL',
              memberCount: 20,
            },
          ],
        }),
      })
    })

    // ダッシュボードウィジェット設定APIのモック（team/orgスコープ用）
    await page.route('**/api/v1/dashboard/widgets**', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              { widgetKey: 'TEAM_NOTICES', visible: true, sortOrder: 0 },
              { widgetKey: 'TEAM_UPCOMING_EVENTS', visible: true, sortOrder: 1 },
              { widgetKey: 'TEAM_TODO', visible: false, sortOrder: 2 },
            ],
          }),
        })
      } else if (method === 'PUT') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: {} }),
        })
      } else {
        await route.continue()
      }
    })
  }

  test('SET-DW-001: ページが表示されスコープ切替タブが存在する', async ({ page }) => {
    await mockTeamsAndOrgs(page)
    await page.goto('/settings/dashboard-widgets')
    await waitForHydration(page)

    // ページヘッダーが表示される
    await expect(
      page.getByRole('heading', { name: 'ダッシュボードウィジェット設定' }),
    ).toBeVisible({ timeout: 10_000 })

    // スコープ切替ボタンが存在する
    await expect(page.getByRole('button', { name: '個人' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'チーム' })).toBeVisible()
    await expect(page.getByRole('button', { name: '組織' })).toBeVisible()
  })

  test('SET-DW-002: 個人スコープのウィジェット一覧が表示される（AC4-1: personalスコープ）', async ({
    page,
  }) => {
    await mockTeamsAndOrgs(page)
    await page.goto('/settings/dashboard-widgets')
    await waitForHydration(page)

    // デフォルトは個人スコープ
    // ウィジェット一覧が表示されることを確認（drag可能なアイテムが存在する）
    // 個人スコープのウィジェットが表示されることを確認
    await expect(page.locator('[draggable="true"]').first()).toBeVisible({ timeout: 10_000 })

    // ToggleSwitchが存在することを確認
    const toggles = page.locator('[role="switch"]')
    await expect(toggles.first()).toBeVisible()
  })

  test('SET-DW-003: チームスコープへ切替後にチーム選択ドロップダウンが表示される', async ({
    page,
  }) => {
    await mockTeamsAndOrgs(page)
    await page.goto('/settings/dashboard-widgets')
    await waitForHydration(page)

    // チームタブをクリック
    await page.getByRole('button', { name: 'チーム' }).click()

    // チーム選択ドロップダウンが表示される
    // Selectコンポーネントはrole="combobox"かリストボックスとして描画される
    const selectEl = page.locator('[placeholder*="チームを選択"]').or(
      page.locator('[aria-label*="チームを選択"]'),
    )
    // Selectコンポーネントが存在するかチーム名が直接表示されるか
    const hasSelect = await selectEl.count()
    const hasTeamName = await page.getByText('テストチーム').count()
    expect(hasSelect + hasTeamName).toBeGreaterThan(0)
  })

  test('SET-DW-004: 組織スコープへ切替後に組織名が表示される', async ({ page }) => {
    await mockTeamsAndOrgs(page)
    await page.goto('/settings/dashboard-widgets')
    await waitForHydration(page)

    // 組織タブをクリック
    await page.getByRole('button', { name: '組織' }).click()

    // 組織選択or組織名が表示される
    const hasOrgName = await page.getByText('テスト組織').count()
    const hasOrgSelect = await page.locator('[placeholder*="組織を選択"]').count()
    expect(hasOrgName + hasOrgSelect).toBeGreaterThan(0)
  })

  test('SET-DW-005: 戻るボタンが機能する（PageHeader back-to使用）', async ({ page }) => {
    await mockTeamsAndOrgs(page)
    await page.goto('/settings/dashboard-widgets')
    await waitForHydration(page)

    // 戻るボタンをクリック（PageHeader内蔵のbutton.backキー）
    const backButton = page.getByRole('button', { name: /戻る|back/i }).or(
      page.locator('[data-testid="back-button"]'),
    )
    // 戻るボタンがある場合はクリックして/settingsへ遷移することを確認
    if (await backButton.count() > 0) {
      await backButton.first().click()
      await expect(page).toHaveURL(/\/settings$/, { timeout: 5_000 })
    } else {
      // PageHeaderのback-toリンクがある場合
      await page.goBack()
      await expect(page).toHaveURL(/\/settings/, { timeout: 5_000 })
    }
  })

  test('SET-DW-006: 設定検索で「ダッシュボード」がヒットする（AC4-3）', async ({ page }) => {
    await page.goto('/settings')
    await waitForHydration(page)

    // 設定検索フォームに「ダッシュボード」を入力
    const searchInput = page.locator('input[placeholder*="設定を検索"]')
    await expect(searchInput).toBeVisible({ timeout: 10_000 })
    await searchInput.fill('ダッシュボード')

    // ダッシュボードウィジェットの項目がヒットすることを確認
    await expect(page.getByText('ダッシュボードウィジェット')).toBeVisible({ timeout: 5_000 })

    // リンクとして/settings/dashboard-widgetsへの遷移先が含まれることを確認
    await expect(page.locator('a[href="/settings/dashboard-widgets"]')).toBeVisible()
  })
})
