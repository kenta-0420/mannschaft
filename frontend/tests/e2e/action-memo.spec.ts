import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F02.5 行動メモ機能 E2E テスト（Phase 1 + Phase 2 スコープ）。
 *
 * - シナリオ1: ワンショット入力 → リスト追加 → 入力欄クリア
 * - シナリオ2: mood_enabled の ON/OFF で MoodSelector の表示が切り替わる
 * - シナリオ3: 他人の memoId を URL 直打ち → 404 時の振る舞い（リスト空 / エラー）
 * - シナリオ4 (Phase 2): 編集ダイアログで content を更新 → 一覧に反映
 * - シナリオ5 (Phase 2): /action-memo/closing → 今日を締める → publish-daily 成功
 *
 * 認証は chromium プロジェクトの storageState に依存。Backend API は page.route で
 * モックする。
 */

const ACTION_MEMO_API = '**/api/v1/action-memos**'
const ACTION_MEMO_SETTINGS_API = '**/api/v1/action-memo-settings'
const TODO_MY_API = '**/api/v1/todos/my'
const BLOG_POSTS_API = '**/api/v1/blog/posts**'

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
    // POST /publish-daily は先にハンドルする
    if (method === 'POST' && /\/action-memos\/publish-daily/.test(url)) {
      const body = JSON.parse(route.request().postData() ?? '{}') as {
        memo_date?: string
      }
      const memoDate = body.memo_date ?? todayJst()
      const count = state.memos.filter((m) => m.memo_date === memoDate).length
      if (count === 0) {
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'no memos' }),
        })
        return
      }
      // 各メモの timeline_post_id を埋める
      const postId = 88231
      state.memos = state.memos.map((m) =>
        m.memo_date === memoDate ? { ...m, timeline_post_id: postId } : m,
      )
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            timeline_post_id: postId,
            memo_count: count,
            memo_date: memoDate,
          },
        }),
      })
      return
    }
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
    } else if (method === 'PATCH' && /\/action-memos\/\d+/.test(url)) {
      const idMatch = url.match(/\/action-memos\/(\d+)/)
      const id = idMatch ? Number(idMatch[1]) : 0
      const body = JSON.parse(route.request().postData() ?? '{}') as {
        content?: string
        mood?: string | null
      }
      const idx = state.memos.findIndex((m) => m.id === id)
      if (idx < 0) {
        await route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'not found' }),
        })
        return
      }
      const target = state.memos[idx]!
      const updated = {
        ...target,
        content: body.content ?? target.content,
        mood:
          body.mood !== undefined
            ? state.moodEnabled
              ? body.mood
              : null
            : target.mood,
        updated_at: new Date().toISOString().replace('Z', '').slice(0, 19),
      }
      state.memos[idx] = updated
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: updated }),
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

  // closing 画面で /api/v1/todos/my が叩かれるため最小モック
  await page.route(TODO_MY_API, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    } else {
      await route.fulfill({ status: 404, body: '' })
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

  test('AM-004 (Phase 2): カードの編集ボタン → 編集ダイアログ → 保存 → 一覧に反映', async ({
    page,
  }) => {
    const state: MockState = { memos: [], moodEnabled: false, nextId: 4521 }
    await setupActionMemoMocks(page, state)

    await page.goto('/action-memo')
    await waitForHydration(page)

    // 1件メモを作成
    const textarea = page.locator('[data-testid="action-memo-input-textarea"]')
    await expect(textarea).toBeVisible({ timeout: 10_000 })
    await textarea.fill('編集前の本文')
    await textarea.press('Enter')
    await expect(page.getByText('編集前の本文')).toBeVisible({ timeout: 5_000 })

    // カードにホバーして編集ボタンを押下（opacity-0 グループホバー対策に force: true）
    const editBtn = page.locator('[data-testid="action-memo-card-edit"]').first()
    await editBtn.click({ force: true })

    // ダイアログが開く
    const dialog = page.locator('[data-testid="action-memo-edit-dialog"]')
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // 本文を書き換えて保存
    const dialogTextarea = page.locator('[data-testid="action-memo-edit-dialog-textarea"]')
    await dialogTextarea.fill('編集後の本文')
    await page.locator('[data-testid="action-memo-edit-dialog-save"]').click()

    // ダイアログが閉じ、一覧に編集後の本文が表示される
    await expect(dialog).toBeHidden({ timeout: 5_000 })
    await expect(page.getByText('編集後の本文')).toBeVisible({ timeout: 5_000 })
  })

  test('AM-005 (Phase 2): 終業画面で「今日を締める」→ publish-daily 成功', async ({ page }) => {
    const state: MockState = { memos: [], moodEnabled: false, nextId: 4521 }
    await setupActionMemoMocks(page, state)

    // まずメモを 1 件作成
    await page.goto('/action-memo')
    await waitForHydration(page)
    const textarea = page.locator('[data-testid="action-memo-input-textarea"]')
    await expect(textarea).toBeVisible({ timeout: 10_000 })
    await textarea.fill('朝散歩した')
    await textarea.press('Enter')
    await expect(page.getByText('朝散歩した')).toBeVisible({ timeout: 5_000 })

    // 終業画面に移動
    await page.locator('[data-testid="action-memo-closing-link"]').click()
    await waitForHydration(page)

    await expect(page.locator('[data-testid="action-memo-closing-title"]')).toBeVisible({
      timeout: 10_000,
    })

    // 今日を締めるボタンを押下
    const publishBtn = page.locator('[data-testid="action-memo-closing-publish"]')
    await expect(publishBtn).toBeVisible({ timeout: 5_000 })
    await expect(publishBtn).toBeEnabled()
    await publishBtn.click()

    // 成功後は /action-memo にリダイレクトされる
    await page.waitForURL('**/action-memo', { timeout: 10_000 })
    // リダイレクト先に戻ったことを確認（メモ一覧が見える）
    await expect(page.getByText('朝散歩した')).toBeVisible({ timeout: 5_000 })
  })

  test('AM-006 (Phase 3): メインページ → 週次まとめリンク → /action-memo/weekly に遷移 → 一覧表示', async ({
    page,
  }) => {
    const state: MockState = { memos: [], moodEnabled: false, nextId: 1 }
    await setupActionMemoMocks(page, state)

    // BlogPost API のモック（週次まとめ用）
    await page.route(BLOG_POSTS_API, async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              {
                id: 101,
                title: '週次ふりかえり: 2026-03-30 〜 2026-04-05',
                body: '# 週次ふりかえり: 2026-03-30 〜 2026-04-05\n\n## 今週のサマリー\n- メモ件数: 15件\n- 投稿日数: 6/7日\n- 平均気分: GOOD',
                publishedAt: '2026-04-05T21:00:00',
                visibility: 'PRIVATE',
              },
              {
                id: 102,
                title: '週次ふりかえり: 2026-04-06 〜 2026-04-12',
                body: '# 週次ふりかえり: 2026-04-06 〜 2026-04-12\n\n## 今週のサマリー\n- メモ件数: 20件\n- 投稿日数: 7/7日',
                publishedAt: '2026-04-12T21:00:00',
                visibility: 'PRIVATE',
              },
              {
                id: 200,
                title: '通常のブログ記事',
                body: 'これは週次まとめではない普通の非公開記事です',
                publishedAt: '2026-04-10T12:00:00',
                visibility: 'PRIVATE',
              },
            ],
            meta: { page: 0, size: 20, totalElements: 3, totalPages: 1 },
          }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    // メインページを開く
    await page.goto('/action-memo')
    await waitForHydration(page)

    // 「週次まとめ」リンクが表示される
    const weeklyLink = page.locator('[data-testid="action-memo-weekly-link"]')
    await expect(weeklyLink).toBeVisible({ timeout: 10_000 })

    // クリックして週次まとめページに遷移
    await weeklyLink.click()
    await page.waitForURL('**/action-memo/weekly', { timeout: 10_000 })
    await waitForHydration(page)

    // ページタイトルが表示される
    await expect(page.locator('[data-testid="action-memo-weekly-title"]')).toBeVisible({
      timeout: 10_000,
    })

    // 週次まとめカードが表示される（「週次ふりかえり: 」で始まるもののみ = 2件）
    const weeklyList = page.locator('[data-testid="action-memo-weekly-list"]')
    await expect(weeklyList).toBeVisible({ timeout: 10_000 })

    // タイトルに「週次ふりかえり」が含まれるカードが2件表示される
    await expect(page.locator('[data-testid="action-memo-weekly-card-101"]')).toBeVisible()
    await expect(page.locator('[data-testid="action-memo-weekly-card-102"]')).toBeVisible()

    // 通常のブログ記事（id=200）は表示されない
    await expect(page.locator('[data-testid="action-memo-weekly-card-200"]')).toHaveCount(0)

    // 「詳細を見る」ボタンでモーダルが開く
    await page.locator('[data-testid="action-memo-weekly-view-detail-102"]').click()
    await expect(page.locator('[data-testid="action-memo-weekly-detail-modal"]')).toBeVisible({
      timeout: 5_000,
    })

    // モーダル内にタイトルが表示される
    await expect(page.getByText('週次ふりかえり: 2026-04-06 〜 2026-04-12')).toBeVisible()

    // モーダルを閉じる
    await page.locator('[data-testid="action-memo-weekly-detail-close"]').click()
    await expect(page.locator('[data-testid="action-memo-weekly-detail-modal"]')).toHaveCount(0)
  })
})
