import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

// ロール別パーミッションモック
// eslint-disable-next-line @typescript-eslint/no-unused-vars
const MOCK_ADMIN_PERMISSIONS = {
  roleName: 'ADMIN',
  permissions: [
    'schedule.create', 'schedule.edit', 'schedule.delete',
    'todo.create', 'todo.edit', 'todo.delete',
    'event.create', 'event.edit', 'event.delete',
    'member.manage', 'bulletin.create', 'bulletin.edit',
    'form.create', 'form.edit', 'survey.create', 'survey.edit',
  ],
}

const MOCK_MEMBER_PERMISSIONS = {
  roleName: 'MEMBER',
  permissions: ['todo.create'],
}

const MOCK_SUPPORTER_PERMISSIONS = {
  roleName: 'SUPPORTER',
  permissions: [],
}

// モジュールモックデータ（全有効）
const ALL_MODULES_ENABLED = [
  { moduleId: 1, moduleName: 'タイムライン', moduleSlug: 'timeline', isEnabled: true, enabledAt: '2026-01-01T00:00:00Z' },
  { moduleId: 2, moduleName: 'チャット', moduleSlug: 'chat', isEnabled: true, enabledAt: '2026-01-01T00:00:00Z' },
  { moduleId: 3, moduleName: '掲示板', moduleSlug: 'bulletin', isEnabled: true, enabledAt: '2026-01-01T00:00:00Z' },
  { moduleId: 4, moduleName: 'TODO', moduleSlug: 'todo', isEnabled: true, enabledAt: '2026-01-01T00:00:00Z' },
  { moduleId: 5, moduleName: 'スケジュール', moduleSlug: 'schedule', isEnabled: true, enabledAt: '2026-01-01T00:00:00Z' },
  { moduleId: 6, moduleName: 'ファイル共有', moduleSlug: 'file_sharing', isEnabled: true, enabledAt: '2026-01-01T00:00:00Z' },
  { moduleId: 7, moduleName: '支払い', moduleSlug: 'payment', isEnabled: true, enabledAt: '2026-01-01T00:00:00Z' },
  { moduleId: 8, moduleName: 'アンケート', moduleSlug: 'survey', isEnabled: true, enabledAt: '2026-01-01T00:00:00Z' },
]

// モジュールモックデータ（timeline OFF）
const TIMELINE_DISABLED_MODULES = ALL_MODULES_ENABLED.map(m =>
  m.moduleSlug === 'timeline' ? { ...m, isEnabled: false, enabledAt: null } : m,
)

/** モジュールAPIをモックする */
async function mockModules(page: import('@playwright/test').Page, modules = ALL_MODULES_ENABLED) {
  await page.route(`**/api/v1/organizations/${ORG_ID}/modules`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: modules }),
    })
  })
}

/** permissionsをオーバーライドするモック（goto前に設定すること） */
async function mockPermissions(
  page: import('@playwright/test').Page,
  permissions: typeof MOCK_ADMIN_PERMISSIONS,
) {
  await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: permissions }),
    })
  })
}

test.describe('ORG-SB-001〜010: 組織サイドバーナビゲーション', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
    await mockModules(page)
  })

  // ORG-SB-001: ADMINロールでサイドバーが表示される
  test('ORG-SB-001: ADMINロールでサイドバーが表示される（nav要素が存在する）', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    // ADMINはisMember=trueなのでasideの中のnavが表示される
    await expect(page.locator('aside nav')).toBeVisible({ timeout: 10_000 })
  })

  // ORG-SB-002: MEMBERロールでサイドバーが表示される
  test('ORG-SB-002: MEMBERロールでサイドバーが表示される', async ({ page }) => {
    // goto前にpermissionsをMEMBERでオーバーライド
    await mockPermissions(page, MOCK_MEMBER_PERMISSIONS)
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    // MEMBERはisMember=trueなのでasideの中のnavが表示される
    await expect(page.locator('aside nav')).toBeVisible({ timeout: 10_000 })
  })

  // ORG-SB-003: SUPPORTERロールでサイドバーが表示されない
  test('ORG-SB-003: SUPPORTERロールでサイドバーが表示されない（nav要素が存在しない）', async ({ page }) => {
    // goto前にpermissionsをSUPPORTERでオーバーライド
    await mockPermissions(page, MOCK_SUPPORTER_PERMISSIONS)
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    // SUPPORTERはisMember=falseなのでasideの中のnavが表示されない
    await page.waitForTimeout(2_000)
    const navCount = await page.locator('aside nav').count()
    expect(navCount).toBe(0)
  })

  // ORG-SB-004: ホームカテゴリがデフォルトで展開されている
  test('ORG-SB-004: ホームカテゴリがデフォルトで展開されている（タイムラインリンクが見える）', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    // asideのサイドバーnavを特定（ヘッダーのnavと区別）
    const sidebarNav = page.locator('aside nav')
    await expect(sidebarNav).toBeVisible({ timeout: 10_000 })
    // 'ホーム'カテゴリがデフォルト展開されているので'タイムライン'リンクが見える
    await expect(sidebarNav.getByRole('link', { name: 'タイムライン' })).toBeVisible({ timeout: 10_000 })
  })

  // ORG-SB-005: メンバーカテゴリがデフォルトで展開されている
  test('ORG-SB-005: メンバーカテゴリがデフォルトで展開されている（メンバー一覧ボタンが見える）', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    // asideのサイドバーnavを特定
    const sidebarNav = page.locator('aside nav')
    await expect(sidebarNav).toBeVisible({ timeout: 10_000 })
    // 'メンバー'カテゴリがデフォルト展開されているので'メンバー一覧'ボタンが見える
    await expect(sidebarNav.getByText('メンバー一覧')).toBeVisible({ timeout: 10_000 })
  })

  // ORG-SB-006: カテゴリクリックで折り畳みができる
  test('ORG-SB-006: カテゴリクリックで折り畳みができる', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    // サイドバーのnavを特定（asideの中にあるcomplementaryロール）
    const sidebarNav = page.locator('aside nav')
    await expect(sidebarNav).toBeVisible({ timeout: 10_000 })
    // ホームカテゴリのボタンを特定（'ホーム'テキストを持つbutton）
    const homeButton = sidebarNav.locator('button').filter({ hasText: 'ホーム' }).first()
    await expect(homeButton).toBeVisible({ timeout: 10_000 })
    // 展開状態でタイムラインリンクが見えている
    await expect(sidebarNav.getByRole('link', { name: 'タイムライン' })).toBeVisible({ timeout: 5_000 })
    // ホームカテゴリをクリックして折り畳む
    await homeButton.click()
    // タイムラインリンクが非表示になる（v-show）
    await expect(sidebarNav.getByRole('link', { name: 'タイムライン' })).toBeHidden({ timeout: 5_000 })
  })

  // ORG-SB-007: ADMINにしか見えない項目がMEMBERには表示されない
  test('ORG-SB-007: ADMINにしか見えない項目（権限グループ）がMEMBERには表示されない', async ({ page }) => {
    // goto前にpermissionsをMEMBERでオーバーライド
    await mockPermissions(page, MOCK_MEMBER_PERMISSIONS)
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    await page.waitForTimeout(2_000)
    // MEMBERには「権限グループ」（requiredRole: 'ADMIN'）が表示されない
    // asideのサイドバーnavでスコープ
    const sidebarNav = page.locator('aside nav')
    const permGroupText = sidebarNav.getByText('権限グループ')
    const count = await permGroupText.count()
    expect(count).toBe(0)
  })

  // ORG-SB-008: モジュールOFFの場合、該当項目が表示されない
  test('ORG-SB-008: モジュールOFFの場合、該当項目が表示されない（timeline OFFでタイムラインリンク消える）', async ({ page }) => {
    // timeline無効なモジュールリストでモックし直す（beforeEachのmockModulesをオーバーライド）
    await page.route(`**/api/v1/organizations/${ORG_ID}/modules`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: TIMELINE_DISABLED_MODULES }),
      })
    })
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    await page.waitForTimeout(2_000)
    // asideのサイドバーnavでスコープ（ヘッダーのタイムラインと区別）
    const sidebarNav = page.locator('aside nav')
    // timeline moduleが無効なのでサイドバーのタイムラインリンクが表示されない
    const timelineLink = sidebarNav.getByRole('link', { name: 'タイムライン' })
    const count = await timelineLink.count()
    expect(count).toBe(0)
  })

  // ORG-SB-009: モバイルサイズでハンバーガーボタンが表示される
  test('ORG-SB-009: モバイルサイズでハンバーガーボタンが表示される（lgブレークポイント未満）', async ({ page }) => {
    // lgブレークポイント未満（Tailwindのlgは1024px以上）にgoto前に設定
    await page.setViewportSize({ width: 1023, height: 768 })
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    // organization.vueの <div class="lg:hidden px-4 pt-3"> 内のボタンが表示されることを確認
    // mainコンテンツ内のlg:hidden divを特定（asideのサイドバーではなくサイドバー開閉ボタン）
    // Playwrightではclass名のコロンをエスケープして .lg\:hidden と書く
    const mobileMenuArea = page.locator('main .lg\\:hidden')
    await expect(mobileMenuArea).toBeVisible({ timeout: 10_000 })
    // その中のpi-barsボタンが表示される
    const barsButton = mobileMenuArea.locator('button').first()
    await expect(barsButton).toBeVisible({ timeout: 5_000 })
  })

  // ORG-SB-010: タイムラインリンクをクリックするとタイムラインページへ遷移する
  test('ORG-SB-010: タイムラインリンクをクリックするとタイムラインページへ遷移する', async ({ page }) => {
    // 最初は別ページに移動しておく
    await page.goto(`/organizations/${ORG_ID}/schedule`)
    await waitForHydration(page)
    // asideのサイドバーnavのタイムラインリンクを特定（ヘッダーのリンクと区別するためasideでスコープ）
    const sidebarNav = page.locator('aside nav')
    await expect(sidebarNav).toBeVisible({ timeout: 10_000 })
    const timelineLink = sidebarNav.getByRole('link', { name: 'タイムライン' })
    await expect(timelineLink).toBeVisible({ timeout: 10_000 })
    // クリック
    await timelineLink.click()
    // タイムラインページへ遷移する
    await expect(page).toHaveURL(new RegExp(`/organizations/${ORG_ID}/timeline`), { timeout: 10_000 })
  })
})
