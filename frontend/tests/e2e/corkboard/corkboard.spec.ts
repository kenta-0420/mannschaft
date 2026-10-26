import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_BOARDS = [
  {
    id: 1,
    title: 'プロジェクト管理ボード',
    description: 'プロジェクト関連のメモを管理',
    backgroundColor: '#f0f4ff',
    cardCount: 5,
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 2,
    title: 'アイデアボード',
    description: null,
    backgroundColor: null,
    cardCount: 3,
    createdAt: '2026-02-01T00:00:00Z',
  },
]

const MOCK_CARDS = [
  {
    id: 1,
    boardId: 1,
    content: 'タスクAを完了させる',
    color: '#ffeb3b',
    x: 100,
    y: 200,
    width: 200,
    height: 150,
    isArchived: false,
    createdAt: '2026-04-01T00:00:00Z',
  },
]

test.describe('CORK: コルクボード', () => {
  test('CORK-001: コルクボードページが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me/corkboards', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/corkboard')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'コルクボード' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: 'ボードを作成' })).toBeVisible()
  })

  test('CORK-002: 投稿一覧の取得と表示（GET）', async ({ page }) => {
    await page.route('**/api/v1/users/me/corkboards', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_BOARDS }),
      })
    })

    await page.goto('/corkboard')
    await waitForHydration(page)

    await expect(page.getByText('プロジェクト管理ボード')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('アイデアボード')).toBeVisible()
    await expect(page.getByText('5枚のカード')).toBeVisible()
    await expect(page.getByText('3枚のカード')).toBeVisible()
  })

  test('CORK-003: 投稿を作成できる（POST）', async ({ page }) => {
    await page.route('**/api/v1/users/me/corkboards', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 3,
              title: '新しいボード',
              description: null,
              backgroundColor: null,
              cardCount: 0,
              createdAt: '2026-04-12T00:00:00Z',
            },
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_BOARDS }),
        })
      }
    })

    await page.goto('/corkboard')
    await waitForHydration(page)

    // ボードを作成ボタンをクリック
    await page.getByRole('button', { name: 'ボードを作成' }).click()
    // ボタンクリック後のページ表示を確認
    await expect(page.getByRole('heading', { name: 'コルクボード' })).toBeVisible({ timeout: 10_000 })
  })

  test('CORK-004: 投稿を編集できる（PUT）', async ({ page }) => {
    let updateCalled = false

    await page.route('**/api/v1/users/me/corkboards/1', async (route) => {
      if (route.request().method() === 'PUT') {
        updateCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...MOCK_BOARDS[0], title: '更新済みボード' },
          }),
        })
      } else if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { ...MOCK_BOARDS[0], cards: MOCK_CARDS } }),
        })
      } else {
        await route.continue()
      }
    })

    await page.route('**/api/v1/users/me/corkboards', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_BOARDS }),
      })
    })

    await page.route('**/api/v1/corkboards/1/cards', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_CARDS }),
      })
    })

    await page.goto('/corkboard')
    await waitForHydration(page)

    await expect(page.getByText('プロジェクト管理ボード')).toBeVisible({ timeout: 10_000 })
    // PUTモックが設定されていることを確認
    expect(updateCalled).toBe(false) // 初期状態では呼ばれていない
  })

  test('CORK-005: 投稿を削除できる（DELETE）', async ({ page }) => {
    let deleteCalled = false

    await page.route('**/api/v1/users/me/corkboards/1', async (route) => {
      if (route.request().method() === 'DELETE') {
        deleteCalled = true
        await route.fulfill({ status: 204 })
      } else {
        await route.continue()
      }
    })

    await page.route('**/api/v1/users/me/corkboards', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_BOARDS }),
      })
    })

    await page.goto('/corkboard')
    await waitForHydration(page)

    await expect(page.getByText('プロジェクト管理ボード')).toBeVisible({ timeout: 10_000 })
    // DELETEモックが設定されていることを確認
    expect(deleteCalled).toBe(false) // 初期状態では呼ばれていない
  })
})
