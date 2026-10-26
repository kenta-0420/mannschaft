import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F14.2 チームメンバー定期更新フォーム E2E テスト。
 *
 * テストケース:
 *  MI-E2E-001: メンバー情報入力ページ — フィールド一覧が表示される
 *  MI-E2E-002: メンバー情報入力ページ — 期限切れフィールドが赤ハイライトされる
 *  MI-E2E-003: メンバー情報入力ページ — 値を入力して一括保存できる（200レスポンス）
 *  MI-E2E-004: メンバー情報入力ページ — フィールドなし時は EmptyState が表示される
 *  MI-E2E-005: 管理者設定ページ — フィールド一覧タブが表示される
 *  MI-E2E-006: 管理者設定ページ — 「フィールドを追加」ダイアログが開閉できる
 *  MI-E2E-007: 管理者設定ページ — フィールド作成 POST が送信される
 *  MI-E2E-008: 管理者設定ページ — ステータスタブでメンバー一覧が表示される
 *  MI-E2E-009: 管理者設定ページ — リマインド送信（200）で成功トーストが出る
 *  MI-E2E-010: 管理者設定ページ — リマインド送信（429）で警告トーストが出る
 *
 * 認証: chromium プロジェクトの storageState（一般ユーザー）を使用。
 * API: page.route() でモック。
 */

const TEAM_ID = 1
const USER_ID = 2
const FIELD_ID_1 = 10
const FIELD_ID_2 = 11

// ============================================================
// モックデータビルダー
// ============================================================

function buildField(overrides: Partial<{
  id: number
  fieldName: string
  fieldType: string
  isRequired: boolean
  isSensitive: boolean
  refreshIntervalMonths: number | null
  sortOrder: number
  isActive: boolean
}> = {}) {
  return {
    id: FIELD_ID_1,
    fieldName: '緊急連絡先電話番号',
    fieldType: 'PHONE',
    isRequired: true,
    isSensitive: true,
    refreshIntervalMonths: 36,
    sortOrder: 0,
    isActive: true,
    ...overrides,
  }
}

function buildResponse(overrides: Partial<{
  fieldId: number
  fieldName: string
  fieldType: string
  isRequired: boolean
  value: string | null
  confirmedAt: string | null
  isOverdue: boolean
  nextDueAt: string | null
}> = {}) {
  return {
    fieldId: FIELD_ID_1,
    fieldName: '緊急連絡先電話番号',
    fieldType: 'PHONE',
    isRequired: true,
    value: '090-1234-5678',
    confirmedAt: '2025-01-01T00:00:00',
    isOverdue: false,
    nextDueAt: '2028-01-01T00:00:00',
    ...overrides,
  }
}

function buildStatusResponse() {
  return {
    totalMembers: 3,
    completedCount: 1,
    overdueCount: 1,
    members: [
      {
        userId: USER_ID,
        displayName: '田中 太郎',
        responses: [
          {
            fieldId: FIELD_ID_1,
            fieldName: '緊急連絡先電話番号',
            value: '***',
            confirmedAt: '2025-01-01T00:00:00',
            isOverdue: true,
          },
        ],
      },
    ],
  }
}

// ============================================================
// セットアップヘルパー
// ============================================================

async function mockMemberInfoApis(page: Page, overrides: {
  myResponses?: object[]
  fields?: object[]
  createFieldStatus?: number
  remindStatus?: number
} = {}) {
  const fields = overrides.fields ?? [buildField()]
  const myResponses = overrides.myResponses ?? [buildResponse()]

  // GET /fields
  await page.route(`**/api/v1/teams/${TEAM_ID}/member-info/fields`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: fields }),
      })
    } else {
      await route.continue()
    }
  })

  // POST /fields
  await page.route(`**/api/v1/teams/${TEAM_ID}/member-info/fields`, async (route) => {
    if (route.request().method() === 'POST') {
      const status = overrides.createFieldStatus ?? 200
      await route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify({ data: buildField({ id: 99, fieldName: '新フィールド', fieldType: 'TEXT' }) }),
      })
    } else {
      await route.continue()
    }
  })

  // GET /responses/me
  await page.route(`**/api/v1/teams/${TEAM_ID}/member-info/responses/me`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: myResponses }),
      })
    } else if (route.request().method() === 'PUT') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({}),
      })
    } else {
      await route.continue()
    }
  })

  // GET /responses/status
  await page.route(`**/api/v1/teams/${TEAM_ID}/member-info/responses/status`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: buildStatusResponse() }),
    })
  })

  // POST /responses/{userId}/remind
  await page.route(`**/api/v1/teams/${TEAM_ID}/member-info/responses/${USER_ID}/remind`, async (route) => {
    const status = overrides.remindStatus ?? 200
    await route.fulfill({
      status,
      contentType: 'application/json',
      body: status === 429
        ? JSON.stringify({ message: 'リクエストが多すぎます' })
        : JSON.stringify({}),
    })
  })

  // PUT /fields/reorder
  await page.route(`**/api/v1/teams/${TEAM_ID}/member-info/fields/reorder`, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })
}

// useRoleAccess が内部で呼ぶ権限チェック API をモック
async function mockRoleAccess(page: Page, role: 'ADMIN' | 'MEMBER' = 'MEMBER') {
  await page.route(`**/api/v1/memberships/my**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { roleKind: role, scopeType: 'TEAM', scopeId: TEAM_ID },
      }),
    })
  })
}

// ============================================================
// MI-E2E-001: メンバー情報入力ページ — フィールド一覧が表示される
// ============================================================

test('MI-E2E-001: メンバー情報入力ページ — フィールド一覧が表示される', async ({ page }) => {
  await mockMemberInfoApis(page, {
    myResponses: [buildResponse({ value: '090-1234-5678', isOverdue: false })],
  })
  await mockRoleAccess(page, 'MEMBER')

  await page.goto(`/teams/${TEAM_ID}/member-info`)
  await waitForHydration(page)

  // フィールド名が表示されること
  await expect(page.getByText('緊急連絡先電話番号')).toBeVisible({ timeout: 10000 })
  // 入力フォームが存在すること（TEL タイプ）
  await expect(page.locator('input[type="tel"]')).toBeVisible()
})

// ============================================================
// MI-E2E-002: メンバー情報入力ページ — 期限切れフィールドが赤ハイライトされる
// ============================================================

test('MI-E2E-002: メンバー情報入力ページ — 期限切れフィールドが赤ハイライトされる', async ({ page }) => {
  await mockMemberInfoApis(page, {
    myResponses: [buildResponse({ isOverdue: true })],
  })
  await mockRoleAccess(page, 'MEMBER')

  await page.goto(`/teams/${TEAM_ID}/member-info`)
  await waitForHydration(page)

  // 期限切れタグが表示されること
  await expect(page.getByText('更新期限切れ')).toBeVisible({ timeout: 10000 })
  // カードが赤ボーダーを持つこと（border-red-400 クラス）
  const card = page.locator('.border-red-400').first()
  await expect(card).toBeVisible()
})

// ============================================================
// MI-E2E-003: メンバー情報入力ページ — 値を入力して一括保存できる
// ============================================================

test('MI-E2E-003: メンバー情報入力ページ — 値を入力して一括保存できる', async ({ page }) => {
  let putCalled = false
  await mockMemberInfoApis(page, {
    myResponses: [buildResponse({ value: null, confirmedAt: null, isOverdue: false })],
  })
  // PUT をキャプチャ
  await page.route(`**/api/v1/teams/${TEAM_ID}/member-info/responses/me`, async (route) => {
    if (route.request().method() === 'PUT') {
      putCalled = true
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({}) })
    } else {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [buildResponse({ value: null, confirmedAt: null })] }),
      })
    }
  })
  await mockRoleAccess(page, 'MEMBER')

  await page.goto(`/teams/${TEAM_ID}/member-info`)
  await waitForHydration(page)

  // TEL 入力に値を入れる
  const telInput = page.locator('input[type="tel"]').first()
  await telInput.fill('080-9999-0000')

  // 「すべて保存」ボタンをクリック
  await page.getByRole('button', { name: 'すべて保存' }).click()

  await expect(() => expect(putCalled).toBe(true)).toPass({ timeout: 5000 })
})

// ============================================================
// MI-E2E-004: メンバー情報入力ページ — フィールドなし時は EmptyState が表示される
// ============================================================

test('MI-E2E-004: メンバー情報入力ページ — フィールドなし時は EmptyState が表示される', async ({ page }) => {
  await mockMemberInfoApis(page, { myResponses: [] })
  await mockRoleAccess(page, 'MEMBER')

  await page.goto(`/teams/${TEAM_ID}/member-info`)
  await waitForHydration(page)

  // フィールドリストが空なので EmptyState が表示される
  await expect(page.getByText('自分の情報')).toBeVisible({ timeout: 10000 })
  // 保存ボタンが表示されないこと
  await expect(page.getByRole('button', { name: 'すべて保存' })).not.toBeVisible()
})

// ============================================================
// MI-E2E-005: 管理者設定ページ — フィールド一覧タブが表示される
// ============================================================

test('MI-E2E-005: 管理者設定ページ — フィールド一覧タブが表示される', async ({ page }) => {
  await mockMemberInfoApis(page, {
    fields: [
      buildField({ id: FIELD_ID_1, fieldName: '緊急連絡先電話番号', fieldType: 'PHONE' }),
      buildField({ id: FIELD_ID_2, fieldName: 'メールアドレス', fieldType: 'EMAIL', sortOrder: 1 }),
    ],
  })
  await mockRoleAccess(page, 'ADMIN')

  await page.goto(`/teams/${TEAM_ID}/settings/member-info`)
  await waitForHydration(page)

  // フィールド名が表示されること
  await expect(page.getByText('緊急連絡先電話番号')).toBeVisible({ timeout: 10000 })
  await expect(page.getByText('メールアドレス')).toBeVisible()
})

// ============================================================
// MI-E2E-006: 管理者設定ページ — 「フィールドを追加」ダイアログが開閉できる
// ============================================================

test('MI-E2E-006: 管理者設定ページ — 「フィールドを追加」ダイアログが開閉できる', async ({ page }) => {
  await mockMemberInfoApis(page)
  await mockRoleAccess(page, 'ADMIN')

  await page.goto(`/teams/${TEAM_ID}/settings/member-info`)
  await waitForHydration(page)

  // 追加ボタンをクリック
  await page.getByRole('button', { name: 'フィールドを追加' }).click()

  // ダイアログが表示されること
  await expect(page.getByRole('dialog')).toBeVisible({ timeout: 5000 })
  await expect(page.getByText('フィールド名')).toBeVisible()

  // キャンセルで閉じること
  await page.getByRole('button', { name: 'キャンセル' }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
})

// ============================================================
// MI-E2E-007: 管理者設定ページ — フィールド作成 POST が送信される
// ============================================================

test('MI-E2E-007: 管理者設定ページ — フィールド作成 POST が送信される', async ({ page }) => {
  let postBody: unknown = null
  await mockRoleAccess(page, 'ADMIN')

  await page.route(`**/api/v1/teams/${TEAM_ID}/member-info/fields`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    } else if (route.request().method() === 'POST') {
      postBody = JSON.parse(route.request().postData() ?? '{}')
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: buildField({ fieldName: '新しいフィールド', fieldType: 'TEXT' }) }),
      })
    } else {
      await route.continue()
    }
  })
  // 再取得用
  await page.route(`**/api/v1/teams/${TEAM_ID}/member-info/responses/**`, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }) })
  })

  await page.goto(`/teams/${TEAM_ID}/settings/member-info`)
  await waitForHydration(page)

  // 追加ダイアログを開く
  await page.getByRole('button', { name: 'フィールドを追加' }).click()
  await expect(page.getByRole('dialog')).toBeVisible({ timeout: 5000 })

  // フィールド名を入力
  await page.getByRole('dialog').locator('input').first().fill('新しいフィールド')

  // 保存ボタンをクリック
  await page.getByRole('dialog').getByRole('button', { name: '保存' }).click()

  // POST が呼ばれたことを確認
  await expect(() => expect(postBody).not.toBeNull()).toPass({ timeout: 5000 })
  const body = postBody as Record<string, unknown>
  expect(body.fieldName).toBe('新しいフィールド')
})

// ============================================================
// MI-E2E-008: 管理者設定ページ — ステータスタブでメンバー一覧が表示される
// ============================================================

test('MI-E2E-008: 管理者設定ページ — ステータスタブでメンバー一覧が表示される', async ({ page }) => {
  await mockMemberInfoApis(page)
  await mockRoleAccess(page, 'ADMIN')

  await page.goto(`/teams/${TEAM_ID}/settings/member-info`)
  await waitForHydration(page)

  // 「回答状況」タブをクリック
  await page.getByRole('tab', { name: '回答状況' }).click()

  // メンバー名が表示されること
  await expect(page.getByText('田中 太郎')).toBeVisible({ timeout: 10000 })
  // サマリーが表示されること（総メンバー数 3）
  await expect(page.getByText('3')).toBeVisible()
})

// ============================================================
// MI-E2E-009: 管理者設定ページ — リマインド送信（200）で成功トーストが出る
// ============================================================

test('MI-E2E-009: 管理者設定ページ — リマインド送信（200）で成功トーストが出る', async ({ page }) => {
  await mockMemberInfoApis(page, { remindStatus: 200 })
  await mockRoleAccess(page, 'ADMIN')

  await page.goto(`/teams/${TEAM_ID}/settings/member-info`)
  await waitForHydration(page)

  // ステータスタブへ移動
  await page.getByRole('tab', { name: '回答状況' }).click()
  await expect(page.getByText('田中 太郎')).toBeVisible({ timeout: 10000 })

  // リマインドボタンをクリック
  await page.getByRole('button', { name: 'リマインド送信' }).first().click()

  // 成功トーストが表示されること
  await expect(page.getByText('リマインドを送信しました')).toBeVisible({ timeout: 5000 })
})

// ============================================================
// MI-E2E-010: 管理者設定ページ — リマインド送信（429）で警告トーストが出る
// ============================================================

test('MI-E2E-010: 管理者設定ページ — リマインド送信（429）で警告トーストが出る', async ({ page }) => {
  await mockMemberInfoApis(page, { remindStatus: 429 })
  await mockRoleAccess(page, 'ADMIN')

  await page.goto(`/teams/${TEAM_ID}/settings/member-info`)
  await waitForHydration(page)

  // ステータスタブへ移動
  await page.getByRole('tab', { name: '回答状況' }).click()
  await expect(page.getByText('田中 太郎')).toBeVisible({ timeout: 10000 })

  // リマインドボタンをクリック
  await page.getByRole('button', { name: 'リマインド送信' }).first().click()

  // 警告トーストが表示されること
  await expect(page.getByText('24時間以内に送信済みです')).toBeVisible({ timeout: 5000 })
})
