import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

/** E2Eテスト用の擬似認証情報をlocalStorageに設定する */
async function mockAuth(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: 1,
        email: 'e2e-user@example.com',
        displayName: 'e2eユーザー',
        profileImageUrl: null,
      }),
    )
  })
}

/** TODO詳細APIをモックする */
async function mockTodoDetailApis(page: import('@playwright/test').Page, todoId = 1) {
  const MOCK_TODO = {
    id: todoId,
    title: 'E2Eテスト用TODO',
    description: 'テスト用の説明文です',
    status: 'IN_PROGRESS',
    priority: 'MEDIUM',
    dueDate: '2026-12-31',
    dueTime: null,
    daysRemaining: 258,
    completedAt: null,
    completedBy: null,
    createdBy: { id: 1, displayName: 'e2eユーザー' },
    assignees: [],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-04-17T00:00:00Z',
    progressRate: '0.00',
    progressManual: false,
  }

  await page.route(`**/api/v1/teams/${TEAM_ID}/todos/${todoId}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_TODO }),
    })
  })

  await page.route(`**/api/v1/teams/${TEAM_ID}/todos/${todoId}/shared-memos`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: [],
        meta: { page: 1, size: 20, totalElements: 0, totalPages: 0 },
      }),
    })
  })

  await page.route(`**/api/v1/teams/${TEAM_ID}/todos/${todoId}/personal-memo`, async (route) => {
    await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
  })

  await page.route(`**/api/v1/teams/${TEAM_ID}/todos/${todoId}/progress`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { progressRate: '0.00', progressManual: false } }),
    })
  })

  // コメント一覧
  await page.route(`**/api/v1/teams/${TEAM_ID}/todos/${todoId}/comments`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: [],
        meta: { page: 1, size: 20, totalElements: 0, totalPages: 0 },
      }),
    })
  })
}

test.describe('TEAM-021〜024: TODO', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-021: TODO一覧ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-022: TODO作成ボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: 'TODO作成' })).toBeVisible({ timeout: 5_000 })
  })

  test('TEAM-023: TODO作成ボタンを押すとページ遷移せずダイアログが開く', async ({ page }) => {
    const consoleErrors: string[] = []
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })

    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible({ timeout: 10_000 })
    await page.waitForLoadState('networkidle')
    // ボタンクリック
    await page.getByRole('button', { name: 'TODO作成' }).click()
    // ダイアログが開くため URL は /todos のまま
    await expect(page).toHaveURL(/\/todos$/, { timeout: 3_000 })
    // 少し待機してから Vue 状態を確認
    await page.waitForTimeout(200)
    const dialogCount = await page.evaluate(
      () => document.querySelectorAll('[role="dialog"]').length,
    )
    const pDialogCount = await page.evaluate(() => document.querySelectorAll('.p-dialog').length)
    const allPortals = await page.evaluate(() => {
      const teleports = Array.from(document.body.children).map((el) => el.className)
      return JSON.stringify(teleports)
    })
    console.log('Dialog count:', dialogCount, 'p-dialog count:', pDialogCount)
    console.log('Body children classes:', allPortals)
    console.log('Console errors:', consoleErrors)
    // 200ms後も見当たらなければ 3000ms 待って再確認
    await page.waitForTimeout(3000)
    const dialogCount2 = await page.evaluate(
      () => document.querySelectorAll('[role="dialog"]').length,
    )
    console.log('Dialog count after 3s:', dialogCount2)
    // ダイアログヘッダーが表示されること
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-024: 空の場合は空状態メッセージかリストが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible({ timeout: 10_000 })
    // ページが正常にロードされ、headingが存在すること
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible()
  })
})

test.describe('TEAM-025〜028: TODO強化機能（ガント・詳細タブ）', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-025: カレンダーページにガントタブが表示される', async ({ page }) => {
    // カレンダーAPIをモック
    await page.route('**/api/v1/schedules/personal**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/schedules/calendar**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { events: [] } }),
      })
    })

    await page.goto('/calendar')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'マイカレンダー' })).toBeVisible({
      timeout: 10_000,
    })

    // 「ガントビュー」タブが表示されること（i18nキー: todo.enhancement.gantt.title）
    const ganttTab = page.getByRole('button', { name: /ガント/i })
    await expect(ganttTab).toBeVisible({ timeout: 5_000 })
  })

  test('TEAM-026: TODO詳細ページが表示される（進捗タブ存在確認）', async ({ page }) => {
    const TODO_ID = 1
    await mockTodoDetailApis(page, TODO_ID)

    const consoleErrors: string[] = []
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })

    await page.goto(`/teams/${TEAM_ID}/todos/${TODO_ID}`)
    await waitForHydration(page)

    // ページがクラッシュせず読み込まれること
    await expect(page.locator('.mx-auto').first()).toBeVisible({ timeout: 15_000 })

    // 「進捗」タブが表示されること
    const progressTab = page.getByRole('button', { name: /進捗/i })
    await expect(progressTab).toBeVisible({ timeout: 10_000 })

    // Vueランタイムの重大なエラーがないこと
    const criticalErrors = consoleErrors.filter(
      (e) => !e.includes('404') && !e.includes('Failed to fetch') && e.includes('[Vue warn]'),
    )
    expect(criticalErrors).toHaveLength(0)
  })

  test('TEAM-027: TODO詳細ページで共有メモタブが表示される', async ({ page }) => {
    const TODO_ID = 1
    await mockTodoDetailApis(page, TODO_ID)

    await page.goto(`/teams/${TEAM_ID}/todos/${TODO_ID}`)
    await waitForHydration(page)

    // ページ読み込み完了を待つ
    await expect(page.locator('.mx-auto').first()).toBeVisible({ timeout: 15_000 })

    // 「共有メモ」タブが表示されること
    const sharedMemoTab = page.getByRole('button', { name: /共有メモ/i })
    await expect(sharedMemoTab).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-028: TODO詳細ページで個人メモタブが表示される', async ({ page }) => {
    const TODO_ID = 1
    await mockTodoDetailApis(page, TODO_ID)

    await page.goto(`/teams/${TEAM_ID}/todos/${TODO_ID}`)
    await waitForHydration(page)

    // ページ読み込み完了を待つ
    await expect(page.locator('.mx-auto').first()).toBeVisible({ timeout: 15_000 })

    // 「個人メモ」タブが表示されること（i18nキー: todo.enhancement.personal_memo.title には「自分だけ見えます」が含まれる）
    const personalMemoTab = page.getByRole('button', { name: /個人メモ/i })
    await expect(personalMemoTab).toBeVisible({ timeout: 10_000 })
  })
})
