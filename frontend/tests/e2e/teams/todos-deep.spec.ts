import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  fillInput,
  selectDropdown,
  pickDate,
  waitForDialog,
  clearAndFillInput,
} from '../helpers/form'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

/**
 * TODO作成ダイアログ深掘りテスト。
 * teams/[id]/todos/index.vue 配下の TodoForm.vue と TodoListTable.vue の操作系を網羅する。
 *
 * 既存の todos.spec.ts はページ表示・ボタン存在のみを検証するため、本ファイルは
 * フォーム入力・送信・バリデーション・ダイアログのキャンセル等を担当する。
 */
test.describe('TEAM-DEEP-todos: TODO作成ダイアログ深掘り', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    // 既存ヘルパーで一覧APIを空配列モックしておく（空状態で開始）
    await mockTeamFeatureApis(page)
  })

  test('TEAM-DEEP-todos-001: タイトル空のまま作成ボタンを押すとバリデーションエラーが表示され API は呼ばれない', async ({
    page,
  }) => {
    // POSTリクエストの監視（呼ばれないことの検証用）
    let postCalled = false
    page.on('request', (req) => {
      if (
        req.url().includes(`/api/v1/teams/${TEAM_ID}/todos`)
        && req.method() === 'POST'
      ) {
        postCalled = true
      }
    })

    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible({ timeout: 10_000 })

    // ダイアログを開く
    await page.getByRole('button', { name: 'TODO作成' }).click()
    const dialog = await waitForDialog(page)

    // タイトル未入力のまま「作成」ボタンを押下
    await dialog.getByRole('button', { name: '作成' }).click()

    // タイトル必須エラーが表示されること
    await expect(dialog.getByText('タイトルは必須です')).toBeVisible({ timeout: 5_000 })
    await page.waitForTimeout(300)
    expect(postCalled).toBe(false)
    // ダイアログは閉じていない
    await expect(dialog).toBeVisible()
  })

  test('TEAM-DEEP-todos-002: タイトルのみ入力して作成すると POST が成功する', async ({ page }) => {
    // 作成APIを成功で個別モック（catch-all より後に登録するため優先される）
    let postBody: Record<string, unknown> | null = null
    await page.route(`**/api/v1/teams/${TEAM_ID}/todos`, async (route) => {
      if (route.request().method() === 'POST') {
        postBody = route.request().postDataJSON() as Record<string, unknown>
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 999,
              scopeType: 'team',
              scopeId: TEAM_ID,
              title: postBody.title,
              description: null,
              status: 'OPEN',
              priority: 'MEDIUM',
              dueDate: null,
              dueTime: null,
              daysRemaining: null,
              completedAt: null,
              completedBy: null,
              createdBy: { id: 1, displayName: 'テストユーザー' },
              sortOrder: 0,
              assignees: [],
              createdAt: '2026-04-07T00:00:00Z',
              updatedAt: '2026-04-07T00:00:00Z',
            },
          }),
        })
      } else {
        // GET（一覧）は空配列
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [],
            meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
          }),
        })
      }
    })

    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'TODO作成' }).click()
    const dialog = await waitForDialog(page)

    // タイトルのみ入力（必須は title のみ）
    const titleInput = dialog.getByPlaceholder('TODOのタイトル')
    await fillInput(titleInput, 'Phase C 動作確認TODO')

    // 作成リクエストを待ちながら送信
    const respPromise = page.waitForResponse(
      (resp) =>
        resp.url().includes(`/api/v1/teams/${TEAM_ID}/todos`)
        && resp.request().method() === 'POST',
      { timeout: 10_000 },
    )
    await dialog.getByRole('button', { name: '作成' }).click()
    await respPromise

    // POST 本文に title が含まれていること
    expect(postBody).not.toBeNull()
    expect((postBody as unknown as { title: string }).title).toBe('Phase C 動作確認TODO')
  })

  test('TEAM-DEEP-todos-003: 優先度 Select で「高」を選択できる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'TODO作成' }).click()
    const dialog = await waitForDialog(page)

    // 優先度 Select のトリガーを取得して selectDropdown ヘルパーで「高」を選択
    const prioritySelect = dialog.locator('label:has-text("優先度") + .p-select')
    await selectDropdown(page, prioritySelect, '高')

    // 選択した値が表示されていること
    await expect(prioritySelect).toContainText('高')
  })

  test('TEAM-DEEP-todos-004: 期限 DatePicker に日付を直接入力できる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'TODO作成' }).click()
    const dialog = await waitForDialog(page)

    // 期限 DatePicker の input を取得（input-id="todo-due-date" で直接指定）
    // dialog.locator() の .last() 遅延評価でカレンダーパネル開放後に誤ダイアログを参照するため
    // page レベルで ID 直指定することでカレンダーダイアログの影響を回避する
    const dueDateInput = page.locator('#todo-due-date')
    await pickDate(dueDateInput, '2026/12/31')

    // 入力値が反映されていること
    await expect(dueDateInput).toHaveValue('2026/12/31')
  })

  test('TEAM-DEEP-todos-005: タイトル入力後にクリアして再入力できる（リセット動作確認）', async ({
    page,
  }) => {
    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'TODO作成' }).click()
    const dialog = await waitForDialog(page)

    const titleInput = dialog.getByPlaceholder('TODOのタイトル')
    await fillInput(titleInput, '最初の入力値')
    await expect(titleInput).toHaveValue('最初の入力値')

    // 一度クリアして別の値を入力
    await clearAndFillInput(titleInput, '修正後の値')
    await expect(titleInput).toHaveValue('修正後の値')
  })

  test('TEAM-DEEP-todos-006: ダイアログのキャンセルボタンで閉じても POST は呼ばれない', async ({
    page,
  }) => {
    let postCalled = false
    page.on('request', (req) => {
      if (
        req.url().includes(`/api/v1/teams/${TEAM_ID}/todos`)
        && req.method() === 'POST'
      ) {
        postCalled = true
      }
    })

    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'TODO作成' }).click()
    const dialog = await waitForDialog(page)

    // タイトルを入力したうえでキャンセル
    const titleInput = dialog.getByPlaceholder('TODOのタイトル')
    await fillInput(titleInput, 'キャンセル予定のTODO')

    await dialog.getByRole('button', { name: 'キャンセル' }).click()

    // ダイアログが閉じる
    await expect(dialog).toBeHidden({ timeout: 5_000 })
    await page.waitForTimeout(300)
    expect(postCalled).toBe(false)
  })
})
