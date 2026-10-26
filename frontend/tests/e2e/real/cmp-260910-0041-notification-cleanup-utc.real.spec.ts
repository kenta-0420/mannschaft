import { test, expect, type Page } from '@playwright/test'
import { execSync } from 'node:child_process'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const ADMIN = {
  email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!',
}
const MEMBER = {
  email: process.env.TEST_MEMBER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_MEMBER_PASSWORD ?? 'TestPass2026!',
}
const MYSQL_USER = process.env.E2E_MYSQL_USER ?? ''
const MYSQL_PASSWORD = process.env.E2E_MYSQL_PASSWORD ?? ''
const RUN_TAG = `CMP0041_${Date.now().toString(36)}`
const KEEP_TITLE = `${RUN_TAG}_KEEP_90D_MINUS_1H`
const ARCHIVE_TITLE = `${RUN_TAG}_ARCHIVE_90D_PLUS_1H`

function sql(statement: string): void {
  if (!MYSQL_USER || !MYSQL_PASSWORD)
    throw new Error('E2E_MYSQL_USER/E2E_MYSQL_PASSWORD が必要です')
  const command = `docker exec mannschaft-mysql mysql -u${MYSQL_USER} -p${MYSQL_PASSWORD} mannschaft -e "${statement.replace(/"/g, '\\"')}"`
  execSync(process.platform === 'win32' ? `wsl.exe -e ${command}` : command, { stdio: 'pipe' })
}

function seedNotifications(): void {
  const userId = `SELECT id FROM users WHERE email='${MEMBER.email}' LIMIT 1`
  const common =
    'user_id, organization_id, notification_type, priority, title, body, source_type, source_id, scope_type, scope_id, action_url, actor_id, is_read, read_at, channels_sent, snoozed_until, created_at'
  sql(
    `DELETE FROM notifications_archive WHERE title LIKE '${RUN_TAG}%'; DELETE FROM notifications WHERE title LIKE '${RUN_TAG}%'; INSERT INTO notifications (${common}) SELECT (${userId}), NULL, 'SYSTEM', 'HIGH', '${KEEP_TITLE}', 'CMP-260910-0041 browser keep', 'SYSTEM', NULL, 'PERSONAL', NULL, NULL, NULL, TRUE, UTC_TIMESTAMP(), '[]', NULL, UTC_TIMESTAMP() - INTERVAL 90 DAY + INTERVAL 1 HOUR; INSERT INTO notifications (${common}) SELECT (${userId}), NULL, 'SYSTEM', 'HIGH', '${ARCHIVE_TITLE}', 'CMP-260910-0041 browser archive', 'SYSTEM', NULL, 'PERSONAL', NULL, NULL, NULL, TRUE, UTC_TIMESTAMP(), '[]', NULL, UTC_TIMESTAMP() - INTERVAL 90 DAY - INTERVAL 1 HOUR;`,
  )
}

function cleanupNotifications(): void {
  sql(
    `DELETE FROM notifications_archive WHERE title LIKE '${RUN_TAG}%'; DELETE FROM notifications WHERE title LIKE '${RUN_TAG}%';`,
  )
}

function promoteRetainedNotificationForUi(): void {
  sql(
    `UPDATE notifications SET created_at = UTC_TIMESTAMP() + INTERVAL 1 DAY WHERE title = '${KEEP_TITLE}';`,
  )
}

async function login(page: Page, credentials: typeof ADMIN): Promise<void> {
  if (!credentials.email || !credentials.password)
    throw new Error('TEST_ADMIN_*/TEST_MEMBER_* が必要です')
  await loginViaApi(page, credentials, { apiBaseUrl: API_BASE_URL })
  // 認証初期化に使ったルート画面の遅延ナビゲーションを後続の実機操作へ持ち越さない。
  await page.goto('about:blank')
}

test.describe('CMP-260910-0041: 通知保持期間UTC基準の実機導線', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ storageState: { cookies: [], origins: [] } })
  test.setTimeout(180_000)
  let notificationsSeeded = false

  test.afterEach(async ({ page: _page }, testInfo) => {
    if (!notificationsSeeded) return

    try {
      cleanupNotifications()
    } catch (error) {
      // Preserve the test failure when cleanup also fails.
      if (testInfo.status === testInfo.expectedStatus) throw error
      console.error('CMP-260910-0041: notification seed cleanup failed', error)
    } finally {
      notificationsSeeded = false
    }
  })

  test('SYSTEM_ADMINは画面から同期実行し、通知一覧でUTC境界を確認できる', async ({ page }) => {
    seedNotifications()
    notificationsSeeded = true
    await login(page, ADMIN)
    const batchListResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname === '/api/v1/system-admin/batch',
      { timeout: 90_000 },
    )
    await page.goto('/system-admin/batches', { waitUntil: 'commit' })
    expect((await batchListResponse).status()).toBe(200)
    await waitForHydration(page)
    const search = page.locator('input[placeholder="バッチ名で検索"]')
    await search.fill('notification-cleanup')
    const row = page
      .locator('[data-test="batch-table"] tr')
      .filter({ hasText: 'notification-cleanup' })
    await expect(row).toBeVisible({ timeout: 30_000 })
    const errorReport = page.locator('div.fixed').filter({ hasText: 'エラー報告' })
    if (await errorReport.isVisible()) {
      await errorReport.getByRole('button').first().click()
      await expect(errorReport).toBeHidden()
    }
    const batchReloadResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname === '/api/v1/system-admin/batch',
      { timeout: 90_000 },
    )
    await row.locator('[data-test="run-sync-notification-cleanup"]').click()
    await expect(page.getByText('バッチ実行が完了しました: notification-cleanup')).toBeVisible({
      timeout: 30_000,
    })
    expect((await batchReloadResponse).status()).toBe(200)

    // 保持された境界行だけを先頭ページへ移し、既存通知数に依存せず画面で確認する。
    // バッチが誤ってアーカイブしていれば更新対象は0件のままで、後続の表示検証が失敗する。
    promoteRetainedNotificationForUi()

    // バッチ操作後は管理者セッションを破棄し、通知の所有者として一覧を確認する。
    await page.context().clearCookies()
    await page.evaluate(() => localStorage.clear())
    await login(page, MEMBER)
    await page.goto('/notifications')
    await waitForHydration(page)
    await expect(page.getByText(KEEP_TITLE)).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText(ARCHIVE_TITLE)).toHaveCount(0)
  })

  test('MEMBERにはバッチ導線がなく、URL直打ちは拒否される', async ({ page }) => {
    await login(page, MEMBER)
    const batchListResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname === '/api/v1/system-admin/batch',
      { timeout: 90_000 },
    )
    await page.goto('/system-admin/batches', { waitUntil: 'commit' })
    // An authenticated MEMBER remains at this route after 403; batches.vue shows
    // the list-load failure toast and no action controls.
    expect((await batchListResponse).status()).toBe(403)
    await expect(page).toHaveURL(/\/system-admin\/batches$/)
    await expect(page.getByText('バッチ一覧の取得に失敗しました')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText('登録されているバッチがありません')).toBeVisible()
    await expect(page.locator('[data-test="run-sync-notification-cleanup"]')).toHaveCount(0)
  })
})
