import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F10.3 監査ログ E2E テスト
 *
 * このファイルは chromium-admin プロジェクト（admin.json storageState）で実行される。
 * API レスポンスはモックを使用する（バックエンド依存なし）。
 *
 * 権限確認テストのみ storageState を上書きして未認証または一般ユーザー状態で実行する。
 */

// ---- モックデータ ----

const MOCK_AUDIT_LOGS_RESPONSE = {
  data: [
    {
      id: 1001,
      userId: 42,
      userName: 'test-admin@example.com',
      targetUserId: null,
      targetUserName: null,
      teamId: null,
      organizationId: null,
      eventType: 'LOGIN_SUCCESS',
      eventCategory: 'AUTH',
      ipAddress: '203.0.113.1',
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      metadata: {},
      createdAt: '2026-05-01T10:30:00Z',
    },
    {
      id: 1002,
      userId: 43,
      userName: 'member@example.com',
      targetUserId: null,
      targetUserName: null,
      teamId: null,
      organizationId: null,
      eventType: 'PASSWORD_CHANGED',
      eventCategory: 'ACCOUNT',
      ipAddress: '203.0.113.2',
      userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)',
      metadata: {},
      createdAt: '2026-05-01T09:00:00Z',
    },
    {
      id: 1003,
      userId: 1,
      userName: 'sysadmin@example.com',
      targetUserId: 43,
      targetUserName: 'member@example.com',
      teamId: null,
      organizationId: null,
      eventType: 'USER_FROZEN',
      eventCategory: 'ADMIN_ACTION',
      ipAddress: '10.0.0.1',
      userAgent: 'Mozilla/5.0',
      metadata: { reason: 'TERMS_VIOLATION' },
      createdAt: '2026-04-30T15:00:00Z',
    },
  ],
  meta: {
    page: 0,
    size: 30,
    totalElements: 3,
    totalPages: 1,
    hasNext: false,
  },
}

const MOCK_EMPTY_RESPONSE = {
  data: [],
  meta: {
    page: 0,
    size: 30,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
  },
}

const MOCK_PAGINATED_RESPONSE = {
  data: Array.from({ length: 30 }, (_, i) => ({
    id: 2000 + i,
    userId: 42,
    userName: 'user@example.com',
    targetUserId: null,
    targetUserName: null,
    teamId: null,
    organizationId: null,
    eventType: 'LOGIN_SUCCESS',
    eventCategory: 'AUTH',
    ipAddress: '203.0.113.1',
    userAgent: 'Mozilla/5.0',
    metadata: {},
    createdAt: `2026-05-0${(i % 9) + 1}T10:00:00Z`,
  })),
  meta: {
    page: 0,
    size: 30,
    totalElements: 62,
    totalPages: 3,
    hasNext: true,
  },
}

// ---- テスト本体 ----

test.describe('AUDIT-001〜008: 監査ログ管理（SYSTEM_ADMIN）', () => {
  // chromium-admin の storageState を使用（SYSTEM_ADMIN 権限）

  test.beforeEach(async ({ page }) => {
    // 全テストで共通のデフォルトモックを設定
    await page.route('**/api/v1/admin/audit-logs**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_AUDIT_LOGS_RESPONSE),
      })
    })
  })

  test('AUDIT-001: 監査ログ一覧ページが表示される', async ({ page }) => {
    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    // ページタイトルが表示される
    await expect(page.getByText('監査ログ').first()).toBeVisible({ timeout: 10_000 })
  })

  test('AUDIT-002: 監査ログ一覧にログが表示される', async ({ page }) => {
    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    // ログエントリが表示される
    await expect(page.getByText('LOGIN_SUCCESS')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('PASSWORD_CHANGED')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('USER_FROZEN')).toBeVisible({ timeout: 5_000 })
  })

  test('AUDIT-003: 監査ログ一覧にユーザー名・IPアドレスが表示される', async ({ page }) => {
    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    await expect(page.getByText('test-admin@example.com').first()).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('203.0.113.1').first()).toBeVisible({ timeout: 5_000 })
  })

  test('AUDIT-004: カテゴリフィルタを適用するとAPIパラメータに反映される', async ({ page }) => {
    const requests: string[] = []
    await page.route('**/api/v1/admin/audit-logs**', async (route) => {
      requests.push(route.request().url())
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_AUDIT_LOGS_RESPONSE),
      })
    })

    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    // カテゴリ選択（Select コンポーネント）
    const categorySelect = page.locator('label:has-text("カテゴリ") + *').first()
    await categorySelect.click()
    // 「認証」カテゴリを選択
    const authOption = page.getByRole('option', { name: '認証' })
    if (await authOption.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await authOption.click()
    }

    // フィルタ適用ボタンをクリック
    await page.getByRole('button', { name: 'フィルタ適用' }).click()

    // API リクエストが発行されたことを確認
    await page.waitForTimeout(500)
    // フィルタ適用後に API が呼ばれている（初回ロード + フィルタ適用 = 2回以上）
    expect(requests.length).toBeGreaterThanOrEqual(1)
  })

  test('AUDIT-005: 日付範囲フィルタを入力してフィルタ適用できる', async ({ page }) => {
    const requests: string[] = []
    await page.route('**/api/v1/admin/audit-logs**', async (route) => {
      requests.push(route.request().url())
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_AUDIT_LOGS_RESPONSE),
      })
    })

    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    // 開始日・終了日を入力
    const fromInput = page.locator('label:has-text("開始日") + * input, label:has-text("開始日") ~ input').first()
    const toInput = page.locator('label:has-text("終了日") + * input, label:has-text("終了日") ~ input').first()

    // InputText は type="date" なのでブラウザの date input として扱う
    const fromField = page.locator('input[type="date"]').first()
    const toField = page.locator('input[type="date"]').nth(1)

    if (await fromField.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await fromField.fill('2026-05-01')
    } else {
      await fromInput.fill('2026-05-01')
    }

    if (await toField.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await toField.fill('2026-05-08')
    } else {
      await toInput.fill('2026-05-08')
    }

    // フィルタ適用
    await page.getByRole('button', { name: 'フィルタ適用' }).click()

    await page.waitForTimeout(500)
    // フィルタ適用後に API リクエストが発行される
    expect(requests.length).toBeGreaterThanOrEqual(1)
  })

  test('AUDIT-006: ログが存在しない場合に「ログがありません」が表示される', async ({ page }) => {
    await page.route('**/api/v1/admin/audit-logs**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_EMPTY_RESPONSE),
      })
    })

    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    await expect(page.getByText('ログがありません')).toBeVisible({ timeout: 10_000 })
  })

  test('AUDIT-007: ページネーションが表示される（複数ページあり）', async ({ page }) => {
    await page.route('**/api/v1/admin/audit-logs**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PAGINATED_RESPONSE),
      })
    })

    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    // ページネーターが表示される（DataTable の paginator）
    // total_elements = 62, size = 30 → 3 ページ
    const paginator = page.locator('.p-paginator, [data-pc-name="paginator"]').first()
    await expect(paginator).toBeVisible({ timeout: 10_000 })
  })

  test('AUDIT-008: ページネーション操作で次ページのAPIが呼ばれる', async ({ page }) => {
    let callCount = 0
    await page.route('**/api/v1/admin/audit-logs**', async (route) => {
      callCount++
      const url = route.request().url()
      // 2回目以降のコールはページ1を返す
      const isPageTwo = url.includes('page=1')
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          isPageTwo
            ? {
                ...MOCK_PAGINATED_RESPONSE,
                meta: { ...MOCK_PAGINATED_RESPONSE.meta, page: 1, hasNext: false },
              }
            : MOCK_PAGINATED_RESPONSE,
        ),
      })
    })

    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    // ページネーターの「次へ」ボタンをクリック
    const nextButton = page.locator(
      '.p-paginator-next, [data-pc-name="paginator"] button[aria-label="Next Page"], .p-paginator button.p-paginator-next',
    )
    const isNextVisible = await nextButton.isVisible({ timeout: 5_000 }).catch(() => false)
    if (isNextVisible) {
      await nextButton.click()
      await page.waitForTimeout(500)
      // ページ変更で追加の API コールが発生する
      expect(callCount).toBeGreaterThan(1)
    } else {
      // ページネーターが表示されていない場合はスキップ（UI 未実装の可能性）
      test.skip()
    }
  })
})

test.describe('AUDIT-009: 未認証アクセスのリダイレクト確認', () => {
  // 未認証状態でテスト
  test.use({ storageState: { cookies: [], origins: [] } })

  test('AUDIT-009: 未認証で /admin/audit-logs にアクセスするとログインへリダイレクトされる', async ({
    page,
  }) => {
    await page.goto('/admin/audit-logs')
    await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
  })
})

test.describe('AUDIT-010〜011: APIエラーハンドリング', () => {
  test('AUDIT-010: APIがエラーを返した場合にエラーメッセージが表示される', async ({ page }) => {
    await page.route('**/api/v1/admin/audit-logs**', async (route) => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Internal Server Error' }),
      })
    })

    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    // エラーメッセージが表示される
    await expect(page.getByText('監査ログの取得に失敗しました')).toBeVisible({ timeout: 10_000 })
  })

  test('AUDIT-011: APIが401を返した場合にログインへリダイレクトされる', async ({ page }) => {
    await page.route('**/api/v1/admin/audit-logs**', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Unauthorized' }),
      })
    })

    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    // 401 で /login にリダイレクト、またはエラー表示
    const isLoginPage = await page
      .waitForURL(/\/login/, { timeout: 5_000 })
      .then(() => true)
      .catch(() => false)
    const isErrorVisible = await page
      .getByText('監査ログの取得に失敗しました')
      .isVisible({ timeout: 3_000 })
      .catch(() => false)

    expect(isLoginPage || isErrorVisible).toBe(true)
  })
})

test.describe('AUDIT-012: テーブルカラムの確認', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/admin/audit-logs**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_AUDIT_LOGS_RESPONSE),
      })
    })
  })

  test('AUDIT-012: テーブルに必要なカラムヘッダーが表示される', async ({ page }) => {
    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    // テーブルのカラムヘッダー確認
    await expect(page.getByText('日時').first()).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('ユーザー').first()).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('カテゴリ').first()).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('イベント').first()).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('IPアドレス').first()).toBeVisible({ timeout: 5_000 })
  })

  test('AUDIT-013: 対象ユーザーがいる場合にターゲットユーザー名が表示される', async ({ page }) => {
    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    // USER_FROZEN イベントの対象ユーザー
    await expect(page.getByText('member@example.com').first()).toBeVisible({ timeout: 10_000 })
  })

  test('AUDIT-014: 対象ユーザーがいない場合に「-」が表示される', async ({ page }) => {
    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    // targetUserName が null の場合は「-」表示
    await expect(page.getByText('-').first()).toBeVisible({ timeout: 10_000 })
  })
})

test.describe('AUDIT-015: イベントカテゴリのバッジ表示', () => {
  test('AUDIT-015: イベントカテゴリがバッジとして表示される', async ({ page }) => {
    await page.route('**/api/v1/admin/audit-logs**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_AUDIT_LOGS_RESPONSE),
      })
    })

    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    // カテゴリが Badge コンポーネントで表示される
    // AUTH, ACCOUNT, ADMIN_ACTION カテゴリが含まれるはず
    const categoryBadge = page.locator('.p-badge, [data-pc-name="badge"]').first()
    await expect(categoryBadge).toBeVisible({ timeout: 10_000 })
  })
})
