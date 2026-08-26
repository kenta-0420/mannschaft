// F16.1 組織サイドバーナビゲーション E2E テスト
// サイドバー撤去対応: 固定 <aside> → Drawer 方式に変更。aside nav → .p-drawer nav にセレクタ変更。
import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

interface PermissionsMock {
  roleName: string
  permissions: string[]
}

// ロール別パーミッションモック
const MOCK_MEMBER_PERMISSIONS: PermissionsMock = {
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
  permissions: PermissionsMock,
) {
  await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: permissions }),
    })
  })
}

/** ハンバーガーボタンをクリックして Drawer サイドバーを開く */
async function openSidebarDrawer(page: import('@playwright/test').Page) {
  const toggle = page.locator('[data-testid="scope-sidebar-toggle"]')
  await expect(toggle).toBeVisible({ timeout: 10_000 })
  await toggle.click()
  // Drawer が開くのを待つ（PrimeVue Drawer のアニメーション完了を待機）
  await expect(page.locator('.p-drawer')).toBeVisible({ timeout: 10_000 })
}

/** Drawer 内のサイドバー nav 要素を返す */
function sidebarNav(page: import('@playwright/test').Page) {
  return page.locator('.p-drawer nav')
}

test.describe('ORG-SB-001〜010: 組織サイドバーナビゲーション', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
    await mockModules(page)
  })

  // ORG-SB-001: ADMINロールでサイドバーが表示される（Drawer内）
  test('ORG-SB-001: ADMINロールでサイドバーが表示される（Drawer内nav要素が存在する）', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    // ハンバーガーボタンをクリックして Drawer を開く
    await openSidebarDrawer(page)
    // Drawer 内の nav が表示される
    await expect(sidebarNav(page)).toBeVisible({ timeout: 10_000 })
  })

  // ORG-SB-002: MEMBERロールでサイドバーが表示される（Drawer内）
  test('ORG-SB-002: MEMBERロールでサイドバーが表示される', async ({ page }) => {
    await mockPermissions(page, MOCK_MEMBER_PERMISSIONS)
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    await openSidebarDrawer(page)
    await expect(sidebarNav(page)).toBeVisible({ timeout: 10_000 })
  })

  // ORG-SB-003: SUPPORTERロールでサイドバーが表示されない
  test('ORG-SB-003: SUPPORTERロールでサイドバーが表示されない（Drawer内nav要素が存在しない）', async ({ page }) => {
    await mockPermissions(page, MOCK_SUPPORTER_PERMISSIONS)
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    // SUPPORTERはisMember=falseなのでDrawer内のnavが表示されない
    await page.waitForTimeout(2_000)
    await expect(page.locator('[data-testid="scope-sidebar-toggle"]')).toHaveCount(0)
  })

  // ORG-SB-004: ホームカテゴリがデフォルトで展開されている
  test('ORG-SB-004: ホームカテゴリがデフォルトで展開されている（タイムラインリンクが見える）', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    await openSidebarDrawer(page)
    const nav = sidebarNav(page)
    await expect(nav).toBeVisible({ timeout: 10_000 })
    await expect(nav.getByRole('link', { name: 'タイムライン' })).toBeVisible({ timeout: 10_000 })
  })

  // ORG-SB-005: メンバーカテゴリがデフォルトで展開されている
  test('ORG-SB-005: メンバーカテゴリがデフォルトで展開されている（メンバー一覧ボタンが見える）', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    await openSidebarDrawer(page)
    const nav = sidebarNav(page)
    await expect(nav).toBeVisible({ timeout: 10_000 })
    await expect(nav.getByText('メンバー一覧')).toBeVisible({ timeout: 10_000 })
  })

  // ORG-SB-006: カテゴリクリックで折り畳みができる
  test('ORG-SB-006: カテゴリクリックで折り畳みができる', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    await openSidebarDrawer(page)
    const nav = sidebarNav(page)
    await expect(nav).toBeVisible({ timeout: 10_000 })
    const homeButton = nav.locator('button').filter({ hasText: 'ホーム' }).first()
    await expect(homeButton).toBeVisible({ timeout: 10_000 })
    await expect(nav.getByRole('link', { name: 'タイムライン' })).toBeVisible({ timeout: 5_000 })
    await homeButton.click()
    await expect(nav.getByRole('link', { name: 'タイムライン' })).toBeHidden({ timeout: 5_000 })
  })

  // ORG-SB-007: ADMINにしか見えない項目がMEMBERには表示されない
  test('ORG-SB-007: ADMINにしか見えない項目（権限グループ）がMEMBERには表示されない', async ({ page }) => {
    await mockPermissions(page, MOCK_MEMBER_PERMISSIONS)
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    await openSidebarDrawer(page)
    await page.waitForTimeout(2_000)
    const nav = sidebarNav(page)
    const permGroupText = nav.getByText('権限グループ')
    const count = await permGroupText.count()
    expect(count).toBe(0)
  })

  // ORG-SB-008: モジュールOFFの場合、該当項目が表示されない
  test('ORG-SB-008: モジュールOFFの場合、該当項目が表示されない（timeline OFFでタイムラインリンク消える）', async ({ page }) => {
    await page.route(`**/api/v1/organizations/${ORG_ID}/modules`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: TIMELINE_DISABLED_MODULES }),
      })
    })
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    await openSidebarDrawer(page)
    await page.waitForTimeout(2_000)
    const nav = sidebarNav(page)
    const timelineLink = nav.getByRole('link', { name: 'タイムライン' })
    const count = await timelineLink.count()
    expect(count).toBe(0)
  })

  // ORG-SB-009: 全画面サイズでハンバーガーボタンが表示される
  test('ORG-SB-009: 全画面サイズでハンバーガーボタンが表示される（固定<aside>撤去・常時表示）', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    // 固定<aside>撤去により、ハンバーガーボタンは全画面サイズで常時表示される
    const toggle = page.locator('[data-testid="scope-sidebar-toggle"]')
    await expect(toggle).toBeVisible({ timeout: 10_000 })
  })

  // ORG-SB-010: タイムラインリンクをクリックするとタイムラインページへ遷移する
  test('ORG-SB-010: タイムラインリンクをクリックするとタイムラインページへ遷移する', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/schedule`)
    await waitForHydration(page)
    await openSidebarDrawer(page)
    const nav = sidebarNav(page)
    await expect(nav).toBeVisible({ timeout: 10_000 })
    const timelineLink = nav.getByRole('link', { name: 'タイムライン' })
    await expect(timelineLink).toBeVisible({ timeout: 10_000 })
    await timelineLink.click()
    await expect(page).toHaveURL(new RegExp(`/organizations/${ORG_ID}/timeline`), { timeout: 10_000 })
  })
})
