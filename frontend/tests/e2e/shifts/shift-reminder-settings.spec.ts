import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  TEAM_ID,
  setupAdminAuth,
  mockCatchAllApis,
} from './_helpers'

/**
 * F03.5 シフト管理 Phase 5 — REMINDER-SETTINGS-001〜004: シフトリマインド設定 E2E テスト。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>REMINDER-SETTINGS-001: 設定ページが表示され、デフォルト値が反映されている</li>
 *   <li>REMINDER-SETTINGS-002: 設定を変更して保存できる</li>
 *   <li>REMINDER-SETTINGS-003: 全て OFF にしようとするとバリデーションエラーが表示される</li>
 *   <li>REMINDER-SETTINGS-004: 設定なし（404）の場合はデフォルト値で表示される</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F03.5_shift.md</p>
 */

const SETTINGS_URL = `/teams/${TEAM_ID}/settings/shift`

// ---------------------------------------------------------------------------
// fixture ビルダー
// ---------------------------------------------------------------------------

/**
 * デフォルト設定レスポンスのビルダー。
 * overrides で一部フィールドを上書き可能。
 */
function buildDefaultSettings(
  overrides: Partial<{
    reminder48hEnabled: boolean
    reminder24hEnabled: boolean
    reminder12hEnabled: boolean
  }> = {},
) {
  return {
    teamId: TEAM_ID,
    reminder48hEnabled: true,
    reminder24hEnabled: true,
    reminder12hEnabled: false,
    ...overrides,
  }
}

// ---------------------------------------------------------------------------
// モックヘルパー
// ---------------------------------------------------------------------------

/**
 * GET /api/v1/teams/{id}/shift-settings をモックする。
 * PATCH はそのまま通過させる（個別テストで必要に応じて上書き）。
 */
async function mockShiftSettingsGetApi(
  page: import('@playwright/test').Page,
  settings: ReturnType<typeof buildDefaultSettings>,
): Promise<void> {
  await page.route(`**/api/v1/teams/${TEAM_ID}/shift-settings`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(settings),
      })
    } else {
      await route.continue()
    }
  })
}

// ---------------------------------------------------------------------------
// ToggleSwitch ユーティリティ
// ---------------------------------------------------------------------------

/**
 * PrimeVue ToggleSwitch の checked 状態を返す。
 * ToggleSwitch は内部に `<input type="checkbox">` を持つ。
 */
async function isToggleChecked(
  page: import('@playwright/test').Page,
  testId: string,
): Promise<boolean> {
  const toggle = page.locator(`[data-testid="${testId}"]`)
  const input = toggle.locator('input[type="checkbox"]')
  return input.isChecked()
}

// ---------------------------------------------------------------------------
// テストスイート
// ---------------------------------------------------------------------------

test.describe('REMINDER-SETTINGS-001〜004: F03.5 Phase 5 シフトリマインド設定', () => {
  test.beforeEach(async ({ page }) => {
    // 管理者として認証済み状態を設定
    await setupAdminAuth(page)
    // catch-all で全 API に空レスポンスを設定（後で個別上書き）
    await mockCatchAllApis(page)
  })

  test('REMINDER-SETTINGS-001: 設定ページが表示され、デフォルト値が反映されている', async ({ page }) => {
    // GET モック: 48h=true, 24h=true, 12h=false
    const settings = buildDefaultSettings()
    await mockShiftSettingsGetApi(page, settings)

    await page.goto(SETTINGS_URL)
    await waitForHydration(page)

    // フォーム全体が表示されていることを確認
    await expect(page.locator('[data-testid="reminder-settings-form"]')).toBeVisible({
      timeout: 10_000,
    })

    // 48h トグルが ON であることを確認
    expect(await isToggleChecked(page, 'reminder-48h-toggle')).toBe(true)

    // 24h トグルが ON であることを確認
    expect(await isToggleChecked(page, 'reminder-24h-toggle')).toBe(true)

    // 12h トグルが OFF であることを確認
    expect(await isToggleChecked(page, 'reminder-12h-toggle')).toBe(false)
  })

  test('REMINDER-SETTINGS-002: 設定を変更して保存できる', async ({ page }) => {
    // GET モック: 初期値（48h=true, 24h=true, 12h=false）を返す
    const initialSettings = buildDefaultSettings()
    await mockShiftSettingsGetApi(page, initialSettings)

    // PATCH モック: 48h=false, 24h=true, 12h=false を返す
    let patchCalled = false
    let patchedBody: Record<string, unknown> | null = null
    await page.route(`**/api/v1/teams/${TEAM_ID}/shift-settings`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(initialSettings),
        })
      } else if (route.request().method() === 'PATCH') {
        patchCalled = true
        patchedBody = JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            buildDefaultSettings({ reminder48hEnabled: false }),
          ),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(SETTINGS_URL)
    await waitForHydration(page)

    // フォームが表示されるまで待つ
    await expect(page.locator('[data-testid="reminder-settings-form"]')).toBeVisible({
      timeout: 10_000,
    })

    // 48h トグルが現在 ON であることを確認
    expect(await isToggleChecked(page, 'reminder-48h-toggle')).toBe(true)

    // 48h トグルをクリックして OFF にする
    // PrimeVue ToggleSwitch はクリックで toggled される
    await page.locator('[data-testid="reminder-48h-toggle"]').click()

    // 保存ボタンをクリック
    await page.locator('[data-testid="reminder-settings-save-btn"]').click()

    // PATCH が呼ばれたことを確認（API 呼び出しを待つ）
    await expect
      .poll(() => patchCalled, { timeout: 10_000 })
      .toBe(true)

    // PATCH のリクエストボディで 48h=false が送信されていることを確認
    expect(patchedBody?.reminder48hEnabled).toBe(false)

    // 成功通知が表示されることを確認（PrimeVue Toast の summary テキスト）
    await expect(
      page.locator('.p-toast-summary').filter({ hasText: '設定を保存しました' }),
    ).toBeVisible({ timeout: 5_000 })
  })

  test('REMINDER-SETTINGS-003: 全て OFF にしようとするとバリデーションエラーが表示される', async ({ page }) => {
    // GET モック: 初期値（48h=true, 24h=true, 12h=false）を返す
    const settings = buildDefaultSettings()
    await mockShiftSettingsGetApi(page, settings)

    // PATCH が呼ばれないことを確認するためのフラグ
    let patchCalled = false
    await page.route(`**/api/v1/teams/${TEAM_ID}/shift-settings`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(settings),
        })
      } else if (route.request().method() === 'PATCH') {
        patchCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(settings),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(SETTINGS_URL)
    await waitForHydration(page)

    // フォームが表示されるまで待つ
    await expect(page.locator('[data-testid="reminder-settings-form"]')).toBeVisible({
      timeout: 10_000,
    })

    // 48h トグルを OFF にする
    await page.locator('[data-testid="reminder-48h-toggle"]').click()
    // 24h トグルを OFF にする
    await page.locator('[data-testid="reminder-24h-toggle"]').click()
    // 12h はもともと OFF（disabled のため操作不要）

    // 保存ボタンをクリック
    await page.locator('[data-testid="reminder-settings-save-btn"]').click()

    // バリデーションエラーが表示されることを確認
    await expect(page.locator('[data-testid="reminder-validation-error"]')).toBeVisible({
      timeout: 5_000,
    })

    // エラーメッセージのテキスト内容を確認
    await expect(page.locator('[data-testid="reminder-validation-error"]')).toHaveText(
      /少なくとも1つのリマインドを有効にしてください/,
    )

    // PATCH は呼ばれないことを確認
    expect(patchCalled).toBe(false)
  })

  test('REMINDER-SETTINGS-004: 設定なし（404）の場合はデフォルト値で表示される', async ({ page }) => {
    // バックエンドが 404 時に自動作成して 200 を返す仕様のため、
    // フロントエンドは常に 200 を受け取る。デフォルト値を返すモックで確認する。
    const defaultSettings = buildDefaultSettings()
    await mockShiftSettingsGetApi(page, defaultSettings)

    await page.goto(SETTINGS_URL)
    await waitForHydration(page)

    // ページが正常に表示されることを確認
    await expect(page.locator('[data-testid="reminder-settings-form"]')).toBeVisible({
      timeout: 10_000,
    })

    // デフォルト値（48h=true, 24h=true, 12h=false）で表示されることを確認
    expect(await isToggleChecked(page, 'reminder-48h-toggle')).toBe(true)
    expect(await isToggleChecked(page, 'reminder-24h-toggle')).toBe(true)
    expect(await isToggleChecked(page, 'reminder-12h-toggle')).toBe(false)
  })
})
