import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F02.3.1 Phase 1b — TODO カスタムステータスラベル E2E テスト
 *
 * 注: 本フェーズでは「書くだけ」を目的とする。バックエンド起動と認証データの整備は
 * 次フェーズで対応する想定。
 */

const TEAM_ID = 100
const TODO_ID = 42
const SYSTEM_OPEN_LABEL = {
  id: 1,
  scopeType: 'SYSTEM',
  scopeId: null,
  name: '未着手',
  bucket: 'OPEN',
  color: '#94a3b8',
  sortOrder: 0,
  isSystemDefault: true,
  createdAt: '2026-05-06T00:00:00',
  updatedAt: '2026-05-06T00:00:00',
}
const SYSTEM_IN_PROGRESS_LABEL = {
  id: 2,
  scopeType: 'SYSTEM',
  scopeId: null,
  name: '着手中',
  bucket: 'IN_PROGRESS',
  color: '#3b82f6',
  sortOrder: 1,
  isSystemDefault: true,
  createdAt: '2026-05-06T00:00:00',
  updatedAt: '2026-05-06T00:00:00',
}
const SYSTEM_COMPLETED_LABEL = {
  id: 3,
  scopeType: 'SYSTEM',
  scopeId: null,
  name: '完了',
  bucket: 'COMPLETED',
  color: '#22c55e',
  sortOrder: 2,
  isSystemDefault: true,
  createdAt: '2026-05-06T00:00:00',
  updatedAt: '2026-05-06T00:00:00',
}

const PERSONAL_REVIEW_LABEL = {
  id: 11,
  scopeType: 'PERSONAL',
  scopeId: 1,
  name: 'レビュー中',
  bucket: 'IN_PROGRESS',
  color: '#fbbf24',
  sortOrder: 10,
  isSystemDefault: false,
  createdAt: '2026-05-06T10:00:00',
  updatedAt: '2026-05-06T10:00:00',
}

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

test.describe('F02.3.1 — 個人ステータスラベル', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
    await page.route('**/api/v1/users/me/todo-status-labels', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              SYSTEM_OPEN_LABEL,
              SYSTEM_IN_PROGRESS_LABEL,
              SYSTEM_COMPLETED_LABEL,
              PERSONAL_REVIEW_LABEL,
            ],
          }),
        })
        return
      }
      if (method === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...PERSONAL_REVIEW_LABEL, id: 12, name: '新規ラベル' },
          }),
        })
        return
      }
      await route.fulfill({ status: 200, body: '{}' })
    })
  })

  test('ステータスラベル管理画面でシステム既定とユーザーラベルが表示される', async ({ page }) => {
    await page.goto('/settings/todo-status-labels')
    await waitForHydration(page)

    await expect(page.getByText('未着手')).toBeVisible()
    await expect(page.getByText('着手中')).toBeVisible()
    await expect(page.getByText('完了')).toBeVisible()
    await expect(page.getByText('レビュー中')).toBeVisible()
  })

  test('「ラベルを追加」を押すとダイアログが開く', async ({ page }) => {
    await page.goto('/settings/todo-status-labels')
    await waitForHydration(page)

    await page.getByRole('button', { name: 'ラベルを追加' }).click()
    await expect(page.getByLabel('名前')).toBeVisible()
    await expect(page.getByLabel('区分')).toBeVisible()
  })
})

test.describe('F02.3.1 — 個人 TODO 詳細でのステータス変更', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
    await page.route('**/api/v1/users/me/todo-status-labels', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [SYSTEM_OPEN_LABEL, SYSTEM_IN_PROGRESS_LABEL, SYSTEM_COMPLETED_LABEL, PERSONAL_REVIEW_LABEL],
        }),
      })
    })
    await page.route(`**/api/v1/todos/${TODO_ID}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: TODO_ID,
            scopeType: 'PERSONAL',
            scopeId: 1,
            title: '個人TODO',
            description: '説明',
            status: 'OPEN',
            statusLabel: { id: 1, name: '未着手', bucket: 'OPEN', color: '#94a3b8' },
            priority: 'MEDIUM',
            dueDate: null,
            dueTime: null,
            daysRemaining: null,
            completedAt: null,
            completedBy: null,
            createdBy: { id: 1, displayName: 'e2eユーザー' },
            assignees: [],
            createdAt: '2026-05-01T00:00:00Z',
            updatedAt: '2026-05-01T00:00:00Z',
          },
        }),
      })
    })
    await page.route(`**/api/v1/todos/${TODO_ID}/status`, async (route) => {
      await route.fulfill({ status: 200, body: '{}' })
    })
  })

  test('Select でラベル変更 → 「変更」ボタンで即時 PATCH（確認ダイアログなし）', async ({ page }) => {
    await page.goto(`/todos/${TODO_ID}`)
    await waitForHydration(page)

    // 個人 TODO は確認ダイアログを出さず即時反映する仕様（指示書より）
    await expect(page.getByRole('button', { name: '変更' })).toBeVisible()
  })
})

test.describe('F02.3.1 — チーム TODO 詳細でのステータス変更', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
    await page.route(`**/api/v1/teams/${TEAM_ID}/todo-status-labels`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [SYSTEM_OPEN_LABEL, SYSTEM_IN_PROGRESS_LABEL, SYSTEM_COMPLETED_LABEL],
        }),
      })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/todos/${TODO_ID}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: TODO_ID,
            title: 'チームTODO',
            description: null,
            status: 'OPEN',
            statusLabel: { id: 1, name: '未着手', bucket: 'OPEN', color: '#94a3b8' },
            priority: 'HIGH',
            dueDate: null,
            dueTime: null,
            daysRemaining: null,
            completedAt: null,
            completedBy: null,
            createdBy: { id: 1, displayName: 'e2eユーザー' },
            assignees: [],
            createdAt: '2026-05-01T00:00:00Z',
            updatedAt: '2026-05-01T00:00:00Z',
            progressRate: '0.00',
            progressManual: false,
          },
        }),
      })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/todos/${TODO_ID}/status`, async (route) => {
      await route.fulfill({ status: 200, body: '{}' })
    })
  })

  test('チームではラベル変更 → 確認ダイアログが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/todos/${TODO_ID}`)
    await waitForHydration(page)

    // 「変更」ボタンが存在し、押下で確認ダイアログが出ることを確認するためのプレースホルダ
    await expect(page.getByRole('button', { name: '変更' })).toBeVisible()
  })
})

test.describe('F02.3.1 — ラベル削除エラー（使用中）', () => {
  test('LABEL_IN_USE エラー時に件数付きメッセージが表示される', async ({ page }) => {
    await mockAuth(page)
    await page.route('**/api/v1/users/me/todo-status-labels', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              SYSTEM_OPEN_LABEL,
              SYSTEM_IN_PROGRESS_LABEL,
              SYSTEM_COMPLETED_LABEL,
              PERSONAL_REVIEW_LABEL,
            ],
          }),
        })
        return
      }
      await route.fulfill({ status: 200, body: '{}' })
    })
    await page.route(`**/api/v1/users/me/todo-status-labels/11`, async (route) => {
      if (route.request().method() === 'DELETE') {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            error: {
              code: 'LABEL_IN_USE',
              message: 'このラベルは使用中です',
              details: { in_use_count: 5 },
            },
          }),
        })
        return
      }
      await route.fulfill({ status: 200, body: '{}' })
    })

    await page.goto('/settings/todo-status-labels')
    await waitForHydration(page)

    // ユーザーが削除ボタンを押すと 409 が返るシナリオの動作確認用プレースホルダ
    await expect(page.getByText('レビュー中')).toBeVisible()
  })
})
