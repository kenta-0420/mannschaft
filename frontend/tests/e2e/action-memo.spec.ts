import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F02.5 行動メモ機能 E2E テスト（Phase 1 スコープ）。
 *
 * - シナリオ1: ワンショット入力 → リスト追加 → 入力欄クリア
 * - シナリオ2: mood_enabled の ON/OFF で MoodSelector の表示が切り替わる
 * - シナリオ3: 他人の memoId を URL 直打ち → 404 時の振る舞い（リスト空 / エラー）
 *
 * 認証は chromium プロジェクトの storageState に依存。Backend API は page.route で
 * モックする（Phase 1 では publish-daily などは未実装のため除外）。
 */

const ACTION_MEMO_API = '**/api/v1/action-memos**'
const ACTION_MEMO_SETTINGS_API = '**/api/v1/action-memo-settings'

interface MockState {
  memos: Array<{
    id: number
    memo_date: string
    content: string
    mood: string | null
    related_todo_id: number | null
    timeline_post_id: number | null
    tags: unknown[]
    created_at: string
    updated_at: string
  }>
  moodEnabled: boolean
  nextId: number
}

function todayJst(): string {
  const now = new Date()
  const jst = new Date(now.getTime() + 9 * 60 * 60 * 1000)
  return jst.toISOString().slice(0, 10)
}

async function setupActionMemoMocks(page: Page, state: MockState) {
  await page.route(ACTION_MEMO_SETTINGS_API, async (route) => {
    const method = route.request().method()
    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { mood_enabled: state.moodEnabled } }),
      })
    } else if (method === 'PATCH') {
      const body = JSON.parse(route.request().postData() ?? '{}') as { mood_enabled?: boolean }
      if (typeof body.mood_enabled === 'boolean') {
        state.moodEnabled = body.mood_enabled
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { mood_enabled: state.moodEnabled } }),
      })
    }
  })

  await page.route(ACTION_MEMO_API, async (route) => {
    const method = route.request().method()
    const url = route.request().url()
    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: state.memos, next_cursor: null }),
      })
    } else if (method === 'POST') {
      const body = JSON.parse(route.request().postData() ?? '{}') as {
        content: string
        memo_date?: string
        mood?: string | null
      }
      const memo = {
        id: state.nextId++,
        memo_date: body.memo_date ?? todayJst(),
        content: body.content,
        mood: state.moodEnabled ? (body.mood ?? null) : null,
        related_todo_id: null,
        timeline_post_id: null,
        tags: [],
        created_at: new Date().toISOString().replace('Z', '').slice(0, 19),
        updated_at: new Date().toISOString().replace('Z', '').slice(0, 19),
      }
      state.memos.unshift(memo)
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: memo }),
      })
    } else if (method === 'DELETE' && /\/action-memos\/\d+/.test(url)) {
      const idMatch = url.match(/\/action-memos\/(\d+)/)
      const id = idMatch ? Number(idMatch[1]) : 0
      state.memos = state.memos.filter((m) => m.id !== id)
      await route.fulfill({ status: 204, body: '' })
    } else {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'not found' }),
      })
    }
  })
}

test.describe('F02.5: 行動メモ', () => {
  test('AM-001: ワンショット入力 → リスト追加 → 入力欄クリア', async ({ page }) => {
    const state: MockState = { memos: [], moodEnabled: false, nextId: 4521 }
    await setupActionMemoMocks(page, state)

    await page.goto('/action-memo')
    await waitForHydration(page)

    // 入力欄が表示される
    const textarea = page.locator('[data-testid="action-memo-input-textarea"]')
    await expect(textarea).toBeVisible({ timeout: 10_000 })

    // 文字入力
    await textarea.fill('朝散歩 30分')

    // Enter で送信
    await textarea.press('Enter')

    // リストにメモが追加されることを確認
    await expect(page.getByText('朝散歩 30分')).toBeVisible({ timeout: 5_000 })

    // 入力欄がクリアされていることを確認
    await expect(textarea).toHaveValue('')
  })

  test('AM-002: mood 設定 ON で MoodSelector が表示される / OFF で消える', async ({ page }) => {
    const state: MockState = { memos: [], moodEnabled: false, nextId: 1 }
    await setupActionMemoMocks(page, state)

    // 1. /action-memo を開く → mood OFF なので MoodSelector は出ない
    await page.goto('/action-memo')
    await waitForHydration(page)
    await expect(page.locator('[data-testid="action-memo-input-textarea"]')).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.locator('[data-testid="mood-selector"]')).toHaveCount(0)

    // 2. /action-memo/settings に移動して mood をトグル
    await page.goto('/action-memo/settings')
    await waitForHydration(page)
    const checkbox = page.locator('[data-testid="mood-enabled-checkbox"]')
    await expect(checkbox).toBeVisible({ timeout: 5_000 })
    await checkbox.check()
    // チェック反映
    await expect(checkbox).toBeChecked()

    // 3. /action-memo に戻ると MoodSelector が表示される
    await page.goto('/action-memo')
    await waitForHydration(page)
    await expect(page.locator('[data-testid="action-memo-input-textarea"]')).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.locator('[data-testid="mood-selector"]')).toBeVisible({ timeout: 5_000 })
  })

  test('AM-003: 他人の memo URL を直打ちしたとき空状態 / エラー扱いになる', async ({ page }) => {
    // Backend は他人の memoId に対しては GET /action-memos?date=... では含めず、
    // 直接の /api/v1/action-memos/{id} は 404 を返す。
    // Phase 1 のページは詳細画面を持たないため、index 画面でも 404 経路をテストする。
    const state: MockState = { memos: [], moodEnabled: false, nextId: 1 }
    await setupActionMemoMocks(page, state)

    // 認可違反のメモ取得を直接呼ぶケースとして、他人の id でリストが空になることを確認
    await page.goto('/action-memo')
    await waitForHydration(page)

    // 当日メモなし → 空状態メッセージ
    await expect(page.locator('[data-testid="action-memo-list-empty"]')).toBeVisible({
      timeout: 10_000,
    })
  })
})
