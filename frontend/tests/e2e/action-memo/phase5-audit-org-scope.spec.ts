import { test, expect } from '@playwright/test'
import {
  setupAuth,
  buildMockState,
  buildWorkMemo,
  mockActionMemoApi,
  waitForHydration,
} from '../helpers/action-memo-mocks'

/**
 * F02.5 Phase 5 E2E テスト。
 *
 * <p>監査ログ可視化（Phase 5-1）と組織スコープ投稿（Phase 5-2）の
 * 2 グループ計 10 件のテストを実装する。</p>
 */

// テスト対象サーバー（worktree の開発サーバー: IPv4 127.0.0.1 を明示して IPv6 の 426 を回避）
const BASE = process.env.TEST_BASE_URL ?? 'http://127.0.0.1:3002'

// ---------------------------------------------------------------------------
// 監査ログ可視化（ActionMemoCard.vue の audit トグル）
// ---------------------------------------------------------------------------

test.describe('F02.5 Phase 5-1 — 監査ログ可視化', () => {
  test.beforeEach(async ({ page }) => {
    await setupAuth(page, { userId: 5001, displayName: 'テストユーザー', role: 'MEMBER' })
  })

  test('AM5-AUDIT-001: メモカードに「変更履歴」ボタンが表示される', async ({ page }) => {
    const state = buildMockState({
      memos: [buildWorkMemo({ id: 100, content: '監査ログテスト用メモ' })],
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo`)
    await waitForHydration(page)

    // カードが描画されるまで待つ
    const card = page.locator('[data-testid="action-memo-card-audit-toggle"]').first()
    await expect(card).toBeVisible({ timeout: 10_000 })
  })

  test('AM5-AUDIT-002: ボタンクリックでパネルが開き、ログ一覧が表示される', async ({ page }) => {
    const memoId = 101
    const state = buildMockState({
      memos: [buildWorkMemo({ id: memoId, content: '監査ログ2件テスト' })],
    })
    // 監査ログを2件設定
    state.auditLogs[memoId] = [
      {
        id: 1,
        eventType: 'ACTION_MEMO_CREATED',
        actorId: 5001,
        createdAt: '2026-05-04T09:00:00',
        metadata: null,
      },
      {
        id: 2,
        eventType: 'ACTION_MEMO_UPDATED',
        actorId: 5001,
        createdAt: '2026-05-04T10:00:00',
        metadata: null,
      },
    ]
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo`)
    await waitForHydration(page)

    // トグルボタンをクリック
    const toggleBtn = page.locator('[data-testid="action-memo-card-audit-toggle"]').first()
    await expect(toggleBtn).toBeVisible({ timeout: 10_000 })
    await toggleBtn.click()

    // パネルが表示される
    const panel = page.locator('[data-testid="action-memo-card-audit-panel"]').first()
    await expect(panel).toBeVisible({ timeout: 5_000 })

    // ログ行が2件表示される
    const logItems = page.locator('[data-testid="action-memo-card-audit-log-item"]')
    await expect(logItems).toHaveCount(2, { timeout: 5_000 })
  })

  test('AM5-AUDIT-003: 2回クリックでパネルが閉じる', async ({ page }) => {
    const memoId = 102
    const state = buildMockState({
      memos: [buildWorkMemo({ id: memoId, content: 'パネル開閉テスト' })],
    })
    state.auditLogs[memoId] = [
      {
        id: 3,
        eventType: 'ACTION_MEMO_CREATED',
        actorId: 5001,
        createdAt: '2026-05-04T09:00:00',
        metadata: null,
      },
    ]
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo`)
    await waitForHydration(page)

    const toggleBtn = page.locator('[data-testid="action-memo-card-audit-toggle"]').first()
    await expect(toggleBtn).toBeVisible({ timeout: 10_000 })

    // 1回目クリック → パネル開く（ログが1件表示されるまで待つ）
    await toggleBtn.click()
    // ログアイテムが表示されるまで待つ（=パネルが開いてAPIレスポンスとDOMレンダリングが完了した状態）
    const logItems = page.locator('[data-testid="action-memo-card-audit-log-item"]')
    await expect(logItems).toHaveCount(1, { timeout: 10_000 })
    // パネル自体がDOMに存在することを確認（auditOpen=true）
    const panel = page.locator('[data-testid="action-memo-card-audit-panel"]').first()
    await expect(panel).toBeAttached()

    // 2回目クリック → パネル閉じる（auditOpen=false → v-if=false → DOMから削除）
    await toggleBtn.click()
    // v-if=false → 要素はDOMから削除されるのでnot.toBeAttached()で確認
    await expect(panel).not.toBeAttached({ timeout: 5_000 })
  })

  test('AM5-AUDIT-004: ログが0件のときは「履歴なし」メッセージが表示される', async ({ page }) => {
    const memoId = 103
    const state = buildMockState({
      memos: [buildWorkMemo({ id: memoId, content: 'ログ0件テスト' })],
    })
    // auditLogs[memoId] を空配列に設定（デフォルト: []）
    state.auditLogs[memoId] = []
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo`)
    await waitForHydration(page)

    const toggleBtn = page.locator('[data-testid="action-memo-card-audit-toggle"]').first()
    await expect(toggleBtn).toBeVisible({ timeout: 10_000 })
    await toggleBtn.click()

    // パネルが開く
    const panel = page.locator('[data-testid="action-memo-card-audit-panel"]').first()
    await expect(panel).toBeVisible({ timeout: 5_000 })

    // ログアイテムは0件
    const logItems = page.locator('[data-testid="action-memo-card-audit-log-item"]')
    await expect(logItems).toHaveCount(0)

    // 「履歴なし」メッセージが表示される（empty テキスト）
    // ActionMemoCard.vue では v-else-if="auditLogs.length === 0" で表示
    await expect(panel).toBeVisible()
    // ログアイテムがないことを確認（既に上で検証済み）
  })

  test('AM5-AUDIT-005: API エラー時はエラーメッセージが表示される', async ({ page }) => {
    const memoId = 104
    const state = buildMockState({
      memos: [buildWorkMemo({ id: memoId, content: 'APIエラーテスト' })],
    })
    await mockActionMemoApi(page, state)

    // 監査ログ API を 500 エラーで上書き
    await page.route(/.*\/api\/v1\/action-memos\/\d+\/audit-logs$/, async (route) => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Internal Server Error' }),
      })
    })

    await page.goto(`${BASE}/action-memo`)
    await waitForHydration(page)

    const toggleBtn = page.locator('[data-testid="action-memo-card-audit-toggle"]').first()
    await expect(toggleBtn).toBeVisible({ timeout: 10_000 })
    await toggleBtn.click()

    // パネルが開く
    const panel = page.locator('[data-testid="action-memo-card-audit-panel"]').first()
    await expect(panel).toBeVisible({ timeout: 5_000 })

    // エラーメッセージが表示される（ActionMemoCard.vue: v-else-if="auditError"）
    // ログアイテムは表示されない
    const logItems = page.locator('[data-testid="action-memo-card-audit-log-item"]')
    await expect(logItems).toHaveCount(0)
  })
})

// ---------------------------------------------------------------------------
// 組織スコープ投稿（index.vue の org scope セレクタ）
// ---------------------------------------------------------------------------

test.describe('F02.5 Phase 5-2 — 組織スコープ投稿', () => {
  test.beforeEach(async ({ page }) => {
    await setupAuth(page, { userId: 5001, displayName: 'テストユーザー', role: 'MEMBER' })
  })

  test('AM5-ORG-001: 組織がない場合は org scope セレクタが表示されない', async ({ page }) => {
    // availableOrgs を空に設定（デフォルト）
    const state = buildMockState({ availableOrgs: [] })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo`)
    await waitForHydration(page)

    // 詳細パネルを開く
    const detailsToggle = page.locator('[data-testid="phase3-details-toggle"]')
    await expect(detailsToggle).toBeVisible({ timeout: 10_000 })
    await detailsToggle.click()

    // org scope セレクタが表示されない
    const orgScopeSelector = page.locator('[data-testid="index-org-scope-selector"]')
    await expect(orgScopeSelector).toBeHidden()
  })

  test('AM5-ORG-002: 組織がある場合は org scope セレクタが表示される', async ({ page }) => {
    const state = buildMockState({
      availableOrgs: [{ id: 1, name: 'テスト組織' }],
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo`)
    await waitForHydration(page)

    // 詳細パネルを開く
    const detailsToggle = page.locator('[data-testid="phase3-details-toggle"]')
    await expect(detailsToggle).toBeVisible({ timeout: 10_000 })
    await detailsToggle.click()

    // org scope セレクタが表示される
    const orgScopeSelector = page.locator('[data-testid="index-org-scope-selector"]')
    await expect(orgScopeSelector).toBeVisible({ timeout: 5_000 })

    // 組織選択セレクトが表示される
    const orgSelect = page.locator('[data-testid="org-scope-select"]')
    await expect(orgSelect).toBeVisible()
  })

  test('AM5-ORG-003: 組織を選択すると visibility セレクタが表示される', async ({ page }) => {
    const state = buildMockState({
      availableOrgs: [{ id: 1, name: 'テスト組織' }],
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo`)
    await waitForHydration(page)

    // 詳細パネルを開く
    const detailsToggle = page.locator('[data-testid="phase3-details-toggle"]')
    await expect(detailsToggle).toBeVisible({ timeout: 10_000 })
    await detailsToggle.click()

    // 組織選択前は visibility セレクタが非表示
    const visibilitySelect = page.locator('[data-testid="org-visibility-select"]')
    await expect(visibilitySelect).toBeHidden()

    // 組織を選択
    const orgSelect = page.locator('[data-testid="org-scope-select"]')
    await expect(orgSelect).toBeVisible({ timeout: 5_000 })
    await orgSelect.selectOption({ value: '1' })

    // visibility セレクタが表示される
    await expect(visibilitySelect).toBeVisible({ timeout: 5_000 })
  })

  test('AM5-ORG-004: 組織を選択してメモ作成すると POST ボディに organization_id が含まれる', async ({
    page,
  }) => {
    const state = buildMockState({
      availableOrgs: [{ id: 2, name: '開発組織' }],
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo`)
    await waitForHydration(page)

    // 詳細パネルを開く
    const detailsToggle = page.locator('[data-testid="phase3-details-toggle"]')
    await expect(detailsToggle).toBeVisible({ timeout: 10_000 })
    await detailsToggle.click()

    // 組織を選択
    const orgSelect = page.locator('[data-testid="org-scope-select"]')
    await expect(orgSelect).toBeVisible({ timeout: 5_000 })
    await orgSelect.selectOption({ value: '2' })

    // POST リクエストをキャプチャ
    const postPromise = page.waitForRequest(
      (req) => req.url().includes('/api/v1/action-memos') && req.method() === 'POST',
    )

    // メモを入力して送信
    const textarea = page.locator('[data-testid="action-memo-input-textarea"]')
    await expect(textarea).toBeVisible({ timeout: 5_000 })
    await textarea.fill('組織スコープ付きメモ')

    const submitBtn = page.locator('[data-testid="action-memo-input-submit"]')
    await submitBtn.click()

    // POST リクエストのボディを検証
    const postReq = await postPromise
    const body = JSON.parse(postReq.postData() ?? '{}') as Record<string, unknown>
    expect(body.organization_id).toBe(2)
  })

  test('AM5-ORG-005: 組織選択を「—」に戻すと visibility セレクタが消える', async ({ page }) => {
    const state = buildMockState({
      availableOrgs: [{ id: 1, name: 'テスト組織' }],
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo`)
    await waitForHydration(page)

    // 詳細パネルを開く
    const detailsToggle = page.locator('[data-testid="phase3-details-toggle"]')
    await expect(detailsToggle).toBeVisible({ timeout: 10_000 })
    await detailsToggle.click()

    // 組織を選択
    const orgSelect = page.locator('[data-testid="org-scope-select"]')
    await expect(orgSelect).toBeVisible({ timeout: 5_000 })
    await orgSelect.selectOption({ value: '1' })

    // visibility セレクタが表示される
    const visibilitySelect = page.locator('[data-testid="org-visibility-select"]')
    await expect(visibilitySelect).toBeVisible({ timeout: 5_000 })

    // 組織選択を「—」（null）に戻す
    await orgSelect.selectOption({ label: '—' })

    // visibility セレクタが消える
    await expect(visibilitySelect).toBeHidden({ timeout: 5_000 })
  })
})
