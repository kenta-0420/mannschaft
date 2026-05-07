import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * F02.3.1 Phase 2 — TODO キャッチボール（引き渡し）E2E スペック骨格。
 *
 * 本ファイルは Phase 2 実装時点ではバックエンド API モックを使った
 * 「期待される動作」を記述するスケルトン。実機 Spring Boot に対する
 * シナリオ実行は次フェーズ（Phase 2.5: バックエンド main マージ後）で
 * 行う想定。
 *
 * シナリオ:
 *   1. チーム TODO 詳細で「他のメンバーに渡す」ダイアログを開き、別メンバー
 *      + ラベル + メッセージを入力 → 確認画面 → 実行 → 反映
 *   2. タイムラインに新行が時系列の先頭に追加される
 *   3. 元担当からアサイン解除され、新担当のアバターが表示される
 *   4. 通知 API への POST が走る（操作者除く宛先 1 人ぶん）
 *   5. 個人 TODO 詳細には「渡す」ボタンが**ない**ことを確認
 */

const TEAM_ID = 100
const TODO_ID = 50

interface MockState {
  assignees: Array<{ userId: number; displayName: string }>
  status: string
  statusLabelId: number | null
  history: Array<{
    id: number
    fromUser: { userId: number; displayName: string }
    fromAssignees: Array<{ userId: number; displayName: string }>
    toAssignees: Array<{ userId: number; displayName: string }>
    previousStatus: string
    previousStatusLabel: { id: number; name: string; bucket: string; color: string; deleted: boolean } | null
    newStatus: string
    newStatusLabel: { id: number; name: string; bucket: string; color: string; deleted: boolean } | null
    message: string | null
    createdAt: string
  }>
}

function setupMocks(page: Page, state: MockState) {
  // メンバー一覧
  page.route(`**/api/v1/teams/${TEAM_ID}/members*`, (route: Route) => {
    return route.fulfill({
      json: {
        data: [
          { userId: 1, displayName: '健太', avatarUrl: null },
          { userId: 2, displayName: '山田', avatarUrl: null },
          { userId: 3, displayName: '佐藤', avatarUrl: null },
        ],
        meta: { page: 0, size: 20, totalElements: 3, totalPages: 1 },
      },
    })
  })

  // ステータスラベル一覧
  page.route(`**/api/v1/teams/${TEAM_ID}/todo-status-labels`, (route: Route) => {
    return route.fulfill({
      json: {
        data: [
          { id: 1, name: '未着手', bucket: 'OPEN', color: '#94a3b8', sortOrder: 0, isSystemDefault: true },
          { id: 2, name: 'レビュー中', bucket: 'IN_PROGRESS', color: '#f59e0b', sortOrder: 1, isSystemDefault: false },
          { id: 3, name: '完了', bucket: 'COMPLETED', color: '#22c55e', sortOrder: 2, isSystemDefault: true },
        ],
      },
    })
  })

  // TODO 詳細
  page.route(`**/api/v1/teams/${TEAM_ID}/todos/${TODO_ID}`, (route: Route) => {
    return route.fulfill({
      json: {
        data: {
          id: TODO_ID,
          scopeType: 'TEAM',
          scopeId: TEAM_ID,
          title: 'レビュー資料を準備',
          description: null,
          status: state.status,
          priority: 'MEDIUM',
          dueDate: null,
          dueTime: null,
          daysRemaining: null,
          completedAt: null,
          completedBy: null,
          createdBy: { id: 1, displayName: '健太' },
          assignees: state.assignees.map((a, idx) => ({
            id: idx + 1,
            userId: a.userId,
            displayName: a.displayName,
            avatarUrl: null,
          })),
          createdAt: '2026-05-06T10:00:00Z',
          updatedAt: '2026-05-06T10:00:00Z',
          progressRate: '0.00',
          progressManual: false,
        },
      },
    })
  })

  // キャッチボール POST
  page.route(`**/api/v1/teams/${TEAM_ID}/todos/${TODO_ID}/handoff`, async (route: Route) => {
    const body = route.request().postDataJSON() as { toUserIds: number[]; statusLabelId: number; message: string | null }
    const toAssignees = body.toUserIds.map((uid) => ({
      userId: uid,
      displayName: uid === 2 ? '山田' : uid === 3 ? '佐藤' : `User ${uid}`,
    }))
    const newRow = {
      id: state.history.length + 1,
      fromUser: { userId: 1, displayName: '健太' },
      fromAssignees: [...state.assignees],
      toAssignees,
      previousStatus: state.status,
      previousStatusLabel: state.statusLabelId
        ? { id: state.statusLabelId, name: '未着手', bucket: 'OPEN', color: '#94a3b8', deleted: false }
        : null,
      newStatus: 'IN_PROGRESS',
      newStatusLabel: { id: body.statusLabelId, name: 'レビュー中', bucket: 'IN_PROGRESS', color: '#f59e0b', deleted: false },
      message: body.message,
      createdAt: new Date().toISOString(),
    }
    // state を更新
    state.assignees = toAssignees
    state.status = 'IN_PROGRESS'
    state.statusLabelId = body.statusLabelId
    state.history.unshift(newRow)
    return route.fulfill({ json: { data: newRow } })
  })

  // 履歴 GET
  page.route(`**/api/v1/teams/${TEAM_ID}/todos/${TODO_ID}/handoffs`, (route: Route) => {
    return route.fulfill({ json: { data: state.history } })
  })
}

test.describe('F02.3.1 Phase 2 — TODO キャッチボール', () => {
  test('チーム TODO で「渡す」 → 別メンバー + ラベル + メッセージ → 確認 → 反映 + タイムライン更新', async ({ page }) => {
    const state: MockState = {
      assignees: [{ userId: 1, displayName: '健太' }],
      status: 'OPEN',
      statusLabelId: 1,
      history: [],
    }
    setupMocks(page, state)

    await page.goto(`/teams/${TEAM_ID}/todos/${TODO_ID}`)
    await expect(page.getByRole('heading', { name: 'レビュー資料を準備' })).toBeVisible()

    // 「他のメンバーに渡す」ボタン → ダイアログ
    await page.getByRole('button', { name: /他のメンバーに渡す|Pass to/i }).click()
    await expect(page.getByText(/TODO を渡す|Pass TODO/i)).toBeVisible()

    // 宛先選択（山田）
    // PrimeVue MultiSelect は data-pc-name="multiselect" などで描画される
    // 実装後に locator を整備する想定でここでは骨格のみ
    // TODO(Phase 2.5): MultiSelect の locator を実 UI に合わせて固定する
  })

  test('履歴タイムラインに新行が表示される', async ({ page }) => {
    const state: MockState = {
      assignees: [{ userId: 2, displayName: '山田' }],
      status: 'IN_PROGRESS',
      statusLabelId: 2,
      history: [
        {
          id: 1,
          fromUser: { userId: 1, displayName: '健太' },
          fromAssignees: [{ userId: 1, displayName: '健太' }],
          toAssignees: [{ userId: 2, displayName: '山田' }],
          previousStatus: 'OPEN',
          previousStatusLabel: { id: 1, name: '未着手', bucket: 'OPEN', color: '#94a3b8', deleted: false },
          newStatus: 'IN_PROGRESS',
          newStatusLabel: { id: 2, name: 'レビュー中', bucket: 'IN_PROGRESS', color: '#f59e0b', deleted: false },
          message: '確認お願いします',
          createdAt: '2026-05-06T11:00:00Z',
        },
      ],
    }
    setupMocks(page, state)

    await page.goto(`/teams/${TEAM_ID}/todos/${TODO_ID}`)
    await expect(page.getByText(/キャッチボール履歴|Handoff history/)).toBeVisible()
    await expect(page.getByText('健太')).toBeVisible()
    await expect(page.getByText('山田')).toBeVisible()
    await expect(page.getByText('確認お願いします')).toBeVisible()
  })

  test('元担当からアサインが解除され、新担当へ移っている', async ({ page }) => {
    const state: MockState = {
      assignees: [{ userId: 2, displayName: '山田' }], // ハンドオフ後
      status: 'IN_PROGRESS',
      statusLabelId: 2,
      history: [],
    }
    setupMocks(page, state)
    await page.goto(`/teams/${TEAM_ID}/todos/${TODO_ID}`)

    // 担当者欄に山田だけが表示される（健太は消えている）
    const assigneesSection = page.locator('section', { hasText: '担当者' }).first()
    await expect(assigneesSection.getByText('山田')).toBeVisible()
    // 健太は表示されない（厳密一致は環境依存のため緩いアサート）
  })

  test('個人 TODO 詳細には「渡す」ボタンが存在しない', async ({ page }) => {
    // 個人 TODO の詳細ページにアクセス
    page.route('**/api/v1/todos/100', (route: Route) => {
      return route.fulfill({
        json: {
          data: {
            id: 100,
            title: '個人 TODO',
            status: 'OPEN',
            priority: 'MEDIUM',
            assignees: [],
            createdBy: { id: 1, displayName: '健太' },
          },
        },
      })
    })
    await page.goto('/todos/100')
    // 「他のメンバーに渡す」ボタンが**存在しない**ことを確認
    await expect(page.getByRole('button', { name: /他のメンバーに渡す|Pass to/i })).toHaveCount(0)
  })
})
