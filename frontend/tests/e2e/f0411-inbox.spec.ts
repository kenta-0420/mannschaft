import { test, expect, type Page } from '@playwright/test'

/**
 * F04.11 統合通知インボックス — E2E スモーク
 *
 * 設計書: docs/features/F04.11_notification_inbox/
 *
 * 方針:
 *   - すべての API を page.route() でモック化し、バックエンド起動不要で動作させる。
 *   - 認証は addInitScript で localStorage に currentUser を注入（PR #1000 以降の方式）。
 *   - シナリオ: /inbox 表示 → 受信箱タブ確認 → snooze → スヌーズ中タブ移動 → archive → 保管庫タブ確認。
 *
 * 実行方法:
 *   npx playwright test tests/e2e/f0411-inbox.spec.ts
 *   （バックエンド稼働不要・CI 対応済み）
 */

// ──────────────────────────────────────────────────────────────────────────
// フィクスチャ
// ──────────────────────────────────────────────────────────────────────────

const SOURCE_TYPE = 'NOTIFICATION'
const SOURCE_ID = 1001

/** 受信箱アイテム（UNREAD） */
const INBOX_ITEM = {
  id: `${SOURCE_TYPE}:${SOURCE_ID}`,
  sourceType: SOURCE_TYPE,
  sourceId: SOURCE_ID,
  title: 'E2Eテスト通知',
  excerpt: 'インボックスE2Eテスト用のメッセージです',
  priority: 'NORMAL',
  scope: null,
  actionUrl: null,
  occurredAt: '2026-05-31T09:00:00Z',
  state: 'UNREAD',
  snoozedUntil: null as string | null,
  labels: [] as unknown[],
}

/** スヌーズ中アイテム（SNOOZED） */
const SNOOZED_ITEM = {
  ...INBOX_ITEM,
  state: 'SNOOZED',
  snoozedUntil: '2026-06-01T12:00:00Z',
}

/** 保管庫アイテム（ARCHIVED） */
const ARCHIVED_ITEM = {
  ...INBOX_ITEM,
  state: 'ARCHIVED',
}

/** 件数サマリ */
function makeSummary(overrides: Partial<{ inbox: number; snoozed: number; archived: number }> = {}) {
  return {
    data: {
      byState: {
        INBOX: overrides.inbox ?? 1,
        SNOOZED: overrides.snoozed ?? 0,
        ARCHIVED: overrides.archived ?? 0,
      },
      byPriority: { NORMAL: 1 },
      bySourceType: { NOTIFICATION: 1 },
    },
  }
}

/** ページリスト レスポンス */
function makeListResponse(item: typeof INBOX_ITEM) {
  return {
    data: {
      items: [item],
      page: 0,
      size: 20,
      totalEstimated: 1,
      hasMore: false,
    },
  }
}

/** triage レスポンス（snooze/archive） */
function makeTriageResponse(item: typeof INBOX_ITEM) {
  return { data: item }
}

// ──────────────────────────────────────────────────────────────────────────
// ヘルパー
// ──────────────────────────────────────────────────────────────────────────

/**
 * テスト用認証情報を localStorage に注入し、認証済み状態を作る。
 */
async function injectAuth(page: Page) {
  await page.addInitScript(() => {
    const user = {
      id: 9001,
      username: 'e2e_inbox_user',
      email: 'e2e-inbox@example.com',
      displayName: 'E2E テストユーザー',
      timezone: 'Asia/Tokyo',
      roles: ['USER'],
    }
    window.localStorage.setItem('currentUser', JSON.stringify(user))
  })
}

/**
 * 初期状態（受信箱に 1 件 UNREAD）の API ルートを設定する。
 * 各テストで必要に応じて個別ルートで上書きする。
 */
async function setupBaseRoutes(page: Page) {
  // サマリ（初期: 受信箱1件）
  await page.route('**/api/v1/inbox/summary', (route) =>
    route.fulfill({ json: makeSummary({ inbox: 1 }) }),
  )

  // 受信箱一覧（INBOX）
  await page.route('**/api/v1/inbox?**', (route) => {
    const url = new URL(route.request().url())
    const state = url.searchParams.get('state')

    if (state === 'SNOOZED') {
      return route.fulfill({ json: { data: { items: [], page: 0, size: 20, totalEstimated: 0, hasMore: false } } })
    }
    if (state === 'ARCHIVED') {
      return route.fulfill({ json: { data: { items: [], page: 0, size: 20, totalEstimated: 0, hasMore: false } } })
    }
    return route.fulfill({ json: makeListResponse(INBOX_ITEM) })
  })

  // triage 操作（snooze）
  await page.route('**/api/v1/inbox/snooze', (route) =>
    route.fulfill({ json: makeTriageResponse(SNOOZED_ITEM) }),
  )

  // triage 操作（unsnooze）
  await page.route('**/api/v1/inbox/unsnooze', (route) =>
    route.fulfill({ json: makeTriageResponse(INBOX_ITEM) }),
  )

  // triage 操作（archive）
  await page.route('**/api/v1/inbox/archive', (route) =>
    route.fulfill({ json: makeTriageResponse(ARCHIVED_ITEM) }),
  )

  // triage 操作（unarchive）
  await page.route('**/api/v1/inbox/unarchive', (route) =>
    route.fulfill({ json: makeTriageResponse(INBOX_ITEM) }),
  )
}

// ──────────────────────────────────────────────────────────────────────────
// テスト
// ──────────────────────────────────────────────────────────────────────────

test.describe('F04.11 統合通知インボックス', () => {
  test.beforeEach(async ({ page }) => {
    await injectAuth(page)
    await setupBaseRoutes(page)
  })

  // ──────────────────────────────────────────────────────────────────────
  // INBOX-E2E-001: /inbox で受信箱タブが表示される
  // ──────────────────────────────────────────────────────────────────────

  test('INBOX-E2E-001: /inbox を開くと受信箱タブが selected になり通知が表示される', async ({
    page,
  }) => {
    await page.goto('/inbox')

    // 受信箱タブが選択済み
    const inboxTab = page.getByTestId('inbox-tab-inbox')
    await expect(inboxTab).toBeVisible()
    await expect(inboxTab).toHaveAttribute('aria-selected', 'true')

    // 通知タイトルが見える
    await expect(page.getByText('E2Eテスト通知')).toBeVisible()
  })

  // ──────────────────────────────────────────────────────────────────────
  // INBOX-E2E-002: snooze → スヌーズ中タブへ移動して確認
  // ──────────────────────────────────────────────────────────────────────

  test('INBOX-E2E-002: アイテムを snooze するとスヌーズ中タブで確認できる', async ({ page }) => {
    const itemId = `${SOURCE_TYPE}:${SOURCE_ID}`

    // snooze 後のサマリ・スヌーズ一覧を準備
    await page.route('**/api/v1/inbox/summary', (route) =>
      route.fulfill({ json: makeSummary({ inbox: 0, snoozed: 1 }) }),
    )
    // スヌーズ中一覧
    let snoozedCallCount = 0
    await page.route('**/api/v1/inbox?**', (route) => {
      const url = new URL(route.request().url())
      const state = url.searchParams.get('state')
      if (state === 'SNOOZED') {
        snoozedCallCount++
        return route.fulfill({ json: makeListResponse(SNOOZED_ITEM) })
      }
      return route.fulfill({ json: makeListResponse(INBOX_ITEM) })
    })

    await page.goto('/inbox')
    await expect(page.getByText('E2Eテスト通知')).toBeVisible()

    // snooze ボタンをクリックしてプリセットパネルを開く
    const snoozeBtn = page.getByTestId(`inbox-snooze-btn-${itemId}`)
    await snoozeBtn.click()

    // 「3時間後」プリセットを選択
    await page.getByText('3時間後').click()

    // スヌーズ中タブに切替
    await page.getByTestId('inbox-tab-snoozed').click()

    // スヌーズ中アイテムが表示される
    await expect(page.getByText('E2Eテスト通知')).toBeVisible()
    // スヌーズ中タブ切替で state=SNOOZED の一覧取得が発火していること
    expect(snoozedCallCount).toBeGreaterThan(0)
  })

  // ──────────────────────────────────────────────────────────────────────
  // INBOX-E2E-003: archive → 保管庫タブで確認
  // ──────────────────────────────────────────────────────────────────────

  test('INBOX-E2E-003: アイテムを archive すると保管庫タブで確認できる', async ({ page }) => {
    const itemId = `${SOURCE_TYPE}:${SOURCE_ID}`

    // archive 後のサマリ・保管庫一覧を準備
    await page.route('**/api/v1/inbox/summary', (route) =>
      route.fulfill({ json: makeSummary({ inbox: 0, archived: 1 }) }),
    )
    await page.route('**/api/v1/inbox?**', (route) => {
      const url = new URL(route.request().url())
      const state = url.searchParams.get('state')
      if (state === 'ARCHIVED') {
        return route.fulfill({ json: makeListResponse(ARCHIVED_ITEM) })
      }
      return route.fulfill({ json: makeListResponse(INBOX_ITEM) })
    })

    await page.goto('/inbox')
    await expect(page.getByText('E2Eテスト通知')).toBeVisible()

    // archive ボタンをクリック
    const archiveBtn = page.getByTestId(`inbox-archive-btn-${itemId}`)
    await archiveBtn.click()

    // 保管庫タブへ移動
    await page.getByTestId('inbox-tab-archived').click()

    // 保管庫アイテムが表示される
    await expect(page.getByText('E2Eテスト通知')).toBeVisible()
  })

  // ──────────────────────────────────────────────────────────────────────
  // INBOX-E2E-004: 空状態の表示
  // ──────────────────────────────────────────────────────────────────────

  test('INBOX-E2E-004: アイテムがない場合は空状態が表示される', async ({ page }) => {
    await page.route('**/api/v1/inbox?**', (route) =>
      route.fulfill({
        json: { data: { items: [], page: 0, size: 20, totalEstimated: 0, hasMore: false } },
      }),
    )
    await page.route('**/api/v1/inbox/summary', (route) =>
      route.fulfill({ json: makeSummary({ inbox: 0 }) }),
    )

    await page.goto('/inbox')

    // 空状態コンポーネントが表示される
    const emptyState = page.getByTestId('inbox-empty-state')
    await expect(emptyState).toBeVisible()
  })

  // ──────────────────────────────────────────────────────────────────────
  // INBOX-E2E-005: スヌーズ中タブ → unsnooze → 受信箱に戻る
  // ──────────────────────────────────────────────────────────────────────

  test('INBOX-E2E-005: スヌーズ中アイテムを unsnooze すると受信箱に戻る', async ({ page }) => {
    const itemId = `${SOURCE_TYPE}:${SOURCE_ID}`

    // スヌーズ中の状態から開始
    await page.route('**/api/v1/inbox/summary', (route) =>
      route.fulfill({ json: makeSummary({ inbox: 0, snoozed: 1 }) }),
    )
    await page.route('**/api/v1/inbox?**', (route) => {
      const url = new URL(route.request().url())
      const state = url.searchParams.get('state')
      if (state === 'SNOOZED') {
        return route.fulfill({ json: makeListResponse(SNOOZED_ITEM) })
      }
      return route.fulfill({ json: makeListResponse(INBOX_ITEM) })
    })

    await page.goto('/inbox')

    // スヌーズ中タブへ移動
    await page.getByTestId('inbox-tab-snoozed').click()
    await expect(page.getByText('E2Eテスト通知')).toBeVisible()

    // unsnooze ボタンをクリック
    const unsnoozeBtn = page.getByTestId(`inbox-unsnooze-btn-${itemId}`)
    await unsnoozeBtn.click()

    // 受信箱タブへ移動して確認
    await page.getByTestId('inbox-tab-inbox').click()
    await expect(page.getByText('E2Eテスト通知')).toBeVisible()
  })
})
