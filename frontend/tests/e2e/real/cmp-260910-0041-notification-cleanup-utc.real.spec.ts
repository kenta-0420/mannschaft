import { test, expect, type Page } from '@playwright/test'
import { execSync } from 'node:child_process'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const ADMIN = { email: process.env.TEST_ADMIN_EMAIL ?? '', password: process.env.TEST_ADMIN_PASSWORD ?? '' }
const MEMBER = { email: process.env.TEST_MEMBER_EMAIL ?? '', password: process.env.TEST_MEMBER_PASSWORD ?? '' }
const MYSQL_USER = process.env.E2E_MYSQL_USER ?? ''
const MYSQL_PASSWORD = process.env.E2E_MYSQL_PASSWORD ?? ''
const RUN_TAG = `CMP0041_${Date.now().toString(36)}`
const KEEP_TITLE = `${RUN_TAG}_KEEP_89H`
const ARCHIVE_TITLE = `${RUN_TAG}_ARCHIVE_91H`

function sql(statement: string): void {
  if (!MYSQL_USER || !MYSQL_PASSWORD) throw new Error('E2E_MYSQL_USER/E2E_MYSQL_PASSWORD が必要です')
  const command = `docker exec mannschaft-mysql mysql -u${MYSQL_USER} -p${MYSQL_PASSWORD} mannschaft -e "${statement.replace(/"/g, '\\"')}"`
  execSync(process.platform === 'win32' ? `wsl.exe -e ${command}` : command, { stdio: 'pipe' })
}

function seedNotifications(): void {
  const userId = `SELECT id FROM users WHERE email='${MEMBER.email}' LIMIT 1`
  const common = "user_id, organization_id, notification_type, priority, title, body, source_type, source_id, scope_type, scope_id, action_url, actor_id, is_read, read_at, channels_sent, snoozed_until, created_at"
  sql(`DELETE FROM notifications_archive WHERE title LIKE '${RUN_TAG}%'; DELETE FROM notifications WHERE title LIKE '${RUN_TAG}%'; INSERT INTO notifications (${common}) SELECT (${userId}), NULL, 'SYSTEM', 'HIGH', '${KEEP_TITLE}', 'CMP-260910-0041 browser keep', 'SYSTEM', NULL, 'PERSONAL', NULL, NULL, NULL, TRUE, UTC_TIMESTAMP(), '[]', NULL, UTC_TIMESTAMP() - INTERVAL 90 DAY + INTERVAL 1 HOUR; INSERT INTO notifications (${common}) SELECT (${userId}), NULL, 'SYSTEM', 'HIGH', '${ARCHIVE_TITLE}', 'CMP-260910-0041 browser archive', 'SYSTEM', NULL, 'PERSONAL', NULL, NULL, NULL, TRUE, UTC_TIMESTAMP(), '[]', NULL, UTC_TIMESTAMP() - INTERVAL 90 DAY - INTERVAL 1 HOUR;`)
}

function cleanupNotifications(): void {
  sql(`DELETE FROM notifications_archive WHERE title LIKE '${RUN_TAG}%'; DELETE FROM notifications WHERE title LIKE '${RUN_TAG}%';`)
}

async function login(page: Page, credentials: typeof ADMIN): Promise<void> {
  if (!credentials.email || !credentials.password) throw new Error('TEST_ADMIN_*/TEST_MEMBER_* が必要です')
  await loginViaApi(page, credentials, { apiBaseUrl: API_BASE_URL })
}

test.describe('CMP-260910-0041: 通知保持期間UTC基準の実機導線', () => {
  test.setTimeout(120_000)

  test.afterEach(() => cleanupNotifications())

  test('SYSTEM_ADMINは画面から同期実行し、通知一覧でUTC境界を確認できる', async ({ page }) => {
    seedNotifications()
    await login(page, ADMIN)
    await page.goto('/system-admin/batches')
    await waitForHydration(page)
    const search = page.getByRole('textbox', { name: /検索|search/i })
    await search.fill('notification-cleanup')
    const row = page.locator('[data-test="batch-table"] tr').filter({ hasText: 'notification-cleanup' })
    await expect(row).toBeVisible({ timeout: 30_000 })
    await row.getByTestId('run-sync-notification-cleanup').click()
    await expect(page.getByText(/完了|completed/i)).toBeVisible({ timeout: 30_000 })

    await login(page, MEMBER)
    await page.goto('/notifications')
    await waitForHydration(page)
    await expect(page.getByText(KEEP_TITLE)).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText(ARCHIVE_TITLE)).toHaveCount(0)
  })

  test('MEMBERにはバッチ導線がなく、URL直打ちは拒否される', async ({ page }) => {
    await login(page, MEMBER)
    await page.goto('/system-admin/batches')
    await page.waitForURL(/\/login|\/403|\/forbidden/, { timeout: 30_000 })
    await expect(page.getByTestId('run-sync-notification-cleanup')).toHaveCount(0)
  })
})
