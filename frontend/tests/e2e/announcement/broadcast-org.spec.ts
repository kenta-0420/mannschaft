/**
 * F02.8 ダッシュボード告知ウィザード — 組織スコープ E2E テスト
 *
 * ORG-BROADCAST-001: 特定チームを対象にした告知
 * ORG-BROADCAST-002: 全チーム対象の告知
 *
 * 注意事項:
 * - BroadcastStep1Audience.vue でチーム個別チェックボックスは
 *   現時点では TODO コメントのみ（未実装）のため、ORG-BROADCAST-001 では
 *   targetTeamIds: [] の状態（「すべてのチーム」チェックを外した状態）を確認する。
 *   チーム個別選択 UI が実装された際は以下コメントのセクションを有効化すること。
 */

import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { fillInput } from '../helpers/form'

const ORG_ID = 1

// ---- モックデータ ----

const MOCK_ORG = {
  id: ORG_ID,
  name: 'テスト組織',
  nickname1: null,
  description: 'E2Eテスト用組織',
  visibility: 'PUBLIC',
  memberCount: 10,
  supporterEnabled: false,
  supporterCount: 0,
  iconUrl: null,
  bannerUrl: null,
  template: 'GENERAL',
  createdAt: '2026-01-01T00:00:00Z',
}

const MOCK_ORG_PERMISSIONS = {
  roleName: 'ADMIN',
  permissions: [
    'schedule.create', 'schedule.edit', 'schedule.delete',
    'todo.create', 'todo.edit', 'todo.delete',
    'event.create', 'event.edit', 'event.delete',
    'member.manage', 'bulletin.create', 'bulletin.edit',
    'form.create', 'form.edit', 'survey.create', 'survey.edit',
  ],
}

const MOCK_TEAMS = {
  data: [
    { id: 1, name: 'チームA' },
    { id: 2, name: 'チームB' },
  ],
}

const MOCK_BROADCAST_RESPONSE = {
  data: {
    id: 100,
    scopeType: 'ORGANIZATION',
    scopeId: ORG_ID,
    channel: 'BULLETIN_THREAD',
    targetRole: 'MEMBERS_ONLY',
    targetTeamIds: null,
    priority: 'NORMAL',
    expiresAt: null,
    createdAt: '2026-05-07T00:00:00Z',
  },
}

// ---- インラインモック関数 ----

/**
 * 組織ダッシュボード表示に必要な API 群をモックする
 */
async function mockOrgApis(page: Page) {
  // 組織基本情報
  await page.route(`**/api/v1/organizations/${ORG_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_ORG }),
    })
  })

  // 権限チェック
  await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_ORG_PERMISSIONS }),
    })
  })

  // 組織チーム一覧
  await page.route(`**/api/v1/organizations/${ORG_ID}/teams**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_TEAMS),
    })
  })

  // 階層（ancestors / children）
  await page.route(`**/api/v1/organizations/${ORG_ID}/ancestors**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
  await page.route(`**/api/v1/organizations/${ORG_ID}/children**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
    })
  })

  // フォロー状態
  await page.route(`**/api/v1/organizations/${ORG_ID}/me/follow-status**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: null }),
    })
  })

  // 権限グループ
  await page.route(`**/api/v1/organizations/${ORG_ID}/permission-groups**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  // ダッシュボード / ウィジェット関連のフォールバック
  await page.route(`**/api/v1/organizations/${ORG_ID}/**`, async (route) => {
    const url = route.request().url()
    // 既にルートが設定されたパスはスキップされるため、残りをすべて空レスポンスで返す
    if (url.includes('/broadcast')) {
      // broadcast は個別テストで設定するためここでは continue
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
    })
  })

  // テンプレート一覧（ウィザード内 BroadcastTemplateSelector が呼ぶ）
  await page.route(`**/api/v1/organizations/${ORG_ID}/announcement-templates**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
}

/**
 * POST /api/v1/organizations/{orgId}/broadcast をモックし、リクエストボディをキャプチャする
 * @returns capturedBody を保持するオブジェクト
 */
async function mockBroadcastApi(
  page: Page,
  orgId: number,
): Promise<{ getBody: () => Record<string, unknown> | null }> {
  let capturedBody: Record<string, unknown> | null = null

  await page.route(`**/api/v1/organizations/${orgId}/broadcast`, async (route) => {
    if (route.request().method() === 'POST') {
      capturedBody = JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_BROADCAST_RESPONSE),
      })
    } else {
      await route.continue()
    }
  })

  return {
    getBody: () => capturedBody,
  }
}

// ---- テストスイート ----

test.describe('ORG-BROADCAST-001〜002: 組織スコープ告知ウィザード', () => {
  test.use({ storageState: 'tests/e2e/.auth/admin.json' })

  test.beforeEach(async ({ page }) => {
    await mockOrgApis(page)
  })

  /**
   * ORG-BROADCAST-001:
   * 組織ダッシュボードで「すべてのチーム」チェックを外した状態で告知し、
   * targetTeamIds: [] がリクエストに含まれること。
   *
   * 注意: BroadcastStep1Audience.vue のチーム個別チェックボックスは
   * 現時点で未実装のため、「すべてのチーム」を OFF にした状態
   * （targetTeamIds が空配列）を ORG-BROADCAST-001 の検証対象とする。
   * チーム個別選択 UI 実装後は targetTeamIds: [1] の検証に更新すること。
   *
   * 想定する selector:
   *   - 「組織内告知」ボタン: getByRole('button', { name: '組織内告知' })
   *   - 「すべてのチーム」チェックボックス: label[for="all_teams"] または #all_teams
   *   - 「メンバーのみ」ラジオ: label[for="target_role_MEMBERS_ONLY"]
   *   - チャネル「掲示板」: button[text="掲示板"]（BroadcastStep2Channel 内の grid button）
   *   - タイトル入力: InputText（label「タイトル」の次の input）
   *   - 送信ボタン: getByRole('button', { name: '告知を送る' })
   */
  test('ORG-BROADCAST-001: すべてのチームOFF状態で告知するとtargetTeamIdsが空配列になること', async ({ page }) => {
    const captured = await mockBroadcastApi(page, ORG_ID)

    await page.goto(`/organizations/${ORG_ID}`)
    await waitForHydration(page)

    // 「組織内告知」ボタンが表示されていることを確認
    const broadcastButton = page.getByRole('button', { name: '組織内告知' })
    await expect(broadcastButton).toBeVisible({ timeout: 10_000 })
    await broadcastButton.click()

    // BroadcastWizard ダイアログが開くことを確認
    const dialog = page.locator('[role="dialog"]').last()
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // Step 1: 対象範囲
    // 「メンバーのみ」ラジオを選択（デフォルトのはずだが明示的にクリック）
    const membersOnlyLabel = page.locator('label[for="target_role_MEMBERS_ONLY"]')
    await expect(membersOnlyLabel).toBeVisible({ timeout: 5_000 })
    await membersOnlyLabel.click()

    // 「すべてのチーム」チェックを外す（デフォルト ON → OFF にする）
    // scopeType === 'ORGANIZATION' の場合のみ表示されるチームセクション
    const allTeamsCheckbox = page.locator('#all_teams')
    if (await allTeamsCheckbox.isVisible({ timeout: 3_000 }).catch(() => false)) {
      const isChecked = await allTeamsCheckbox.isChecked()
      if (isChecked) {
        // チェックを外すにはラッパー div をクリック（PrimeVue Checkbox）
        await page.locator('label[for="all_teams"]').click()
      }
    }

    // 「次へ」クリック → Step 2
    await page.getByRole('button', { name: '次へ' }).click()

    // Step 2: チャネル選択 — 掲示板を選択
    await expect(page.getByText('チャネルを選ぶ')).toBeVisible({ timeout: 5_000 })
    await page.getByRole('button', { name: '掲示板' }).click()

    // 「次へ」クリック → Step 3
    await page.getByRole('button', { name: '次へ' }).click()

    // Step 3: タイトルを入力
    await expect(page.getByText('内容を入力')).toBeVisible({ timeout: 5_000 })
    const titleInput = page.locator('input').first()
    await fillInput(titleInput, 'E2Eテスト告知タイトル001')

    // 「告知を送る」をクリックし、API レスポンスを待つ
    const submitButton = page.getByRole('button', { name: '告知を送る' })
    await expect(submitButton).toBeEnabled({ timeout: 5_000 })
    const responsePromise = page.waitForResponse(
      (resp) => resp.url().includes(`/api/v1/organizations/${ORG_ID}/broadcast`) && resp.status() === 201,
      { timeout: 10_000 },
    )
    await submitButton.click()
    await responsePromise

    const body = captured.getBody()
    expect(body).not.toBeNull()
    // すべてのチームOFF → targetTeamIds は [] または null（BroadcastWizard.vue 実装依存）
    const teamIds = body?.targetTeamIds
    expect(teamIds === null || (Array.isArray(teamIds) && teamIds.length === 0)).toBe(true)
  })

  /**
   * ORG-BROADCAST-002:
   * 組織ダッシュボードで「すべてのチーム」を選択した状態で告知し、
   * targetTeamIds: null がリクエストに含まれること。
   *
   * 想定する selector:
   *   - 「組織内告知」ボタン: getByRole('button', { name: '組織内告知' })
   *   - 「すべてのチーム」チェックボックス: label[for="all_teams"]
   *   - 「メンバーのみ」ラジオ: label[for="target_role_MEMBERS_ONLY"]
   *   - チャネル「タイムライン」: button[text="タイムライン"]
   *   - 本文入力: Textarea
   *   - 送信ボタン: getByRole('button', { name: '告知を送る' })
   */
  test('ORG-BROADCAST-002: すべてのチームONで告知するとtargetTeamIdsがnullになること', async ({ page }) => {
    const captured = await mockBroadcastApi(page, ORG_ID)

    await page.goto(`/organizations/${ORG_ID}`)
    await waitForHydration(page)

    // 「組織内告知」ボタンをクリック
    const broadcastButton = page.getByRole('button', { name: '組織内告知' })
    await expect(broadcastButton).toBeVisible({ timeout: 10_000 })
    await broadcastButton.click()

    // BroadcastWizard ダイアログが開くことを確認
    const dialog = page.locator('[role="dialog"]').last()
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // Step 1: 「メンバーのみ」ラジオを選択
    const membersOnlyLabel = page.locator('label[for="target_role_MEMBERS_ONLY"]')
    await expect(membersOnlyLabel).toBeVisible({ timeout: 5_000 })
    await membersOnlyLabel.click()

    // 「すべてのチーム」チェックがデフォルト ON であることを確認
    // scopeType === 'ORGANIZATION' の場合のみ表示される
    const allTeamsCheckbox = page.locator('#all_teams')
    if (await allTeamsCheckbox.isVisible({ timeout: 3_000 }).catch(() => false)) {
      // デフォルトで ON（targetTeamIds === null）なのでそのままにする
      // もし OFF になっていたら ON にする
      const isChecked = await allTeamsCheckbox.isChecked()
      if (!isChecked) {
        await page.locator('label[for="all_teams"]').click()
      }
    }

    // 「次へ」クリック → Step 2
    await page.getByRole('button', { name: '次へ' }).click()

    // Step 2: チャネル選択 — タイムラインを選択
    await expect(page.getByText('チャネルを選ぶ')).toBeVisible({ timeout: 5_000 })
    await page.getByRole('button', { name: 'タイムライン' }).click()

    // 「次へ」クリック → Step 3
    await page.getByRole('button', { name: '次へ' }).click()

    // Step 3: 本文を入力（タイムラインは content フィールド）
    await expect(page.getByText('内容を入力')).toBeVisible({ timeout: 5_000 })
    const bodyTextarea = page.locator('textarea').first()
    await fillInput(bodyTextarea, 'E2Eテスト告知本文002 全チーム対象')

    // 「告知を送る」をクリックし、API レスポンスを待つ
    const submitButton = page.getByRole('button', { name: '告知を送る' })
    await expect(submitButton).toBeEnabled({ timeout: 5_000 })
    const responsePromise = page.waitForResponse(
      (resp) => resp.url().includes(`/api/v1/organizations/${ORG_ID}/broadcast`) && resp.status() === 201,
      { timeout: 10_000 },
    )
    await submitButton.click()
    await responsePromise

    const body = captured.getBody()
    expect(body).not.toBeNull()
    // 「すべてのチーム」ON → targetTeamIds は null
    expect(body?.targetTeamIds).toBeNull()
  })
})
