import { execSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { test, expect, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const TARGET_USER = {
  email: process.env.TEST_OUTSIDER_EMAIL ?? 'f087-outsider@test.mannschaft.local',
  password: process.env.TEST_OUTSIDER_PASSWORD ?? 'TestPass2026!',
}
const OTHER_USER = {
  email: process.env.TEST_MEMBER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_MEMBER_PASSWORD ?? 'TestPass2026!',
}
const MYSQL_USER = process.env.E2E_MYSQL_USER ?? ''
const MYSQL_PASSWORD = process.env.E2E_MYSQL_PASSWORD ?? ''
const RUN_TAG = `CMP0042_${Date.now().toString(36)}`
const TITLE = `${RUN_TAG}_UTC_BOUNDARY_RESERVATION`
const COMPANY = `${RUN_TAG}_ADVERTISER`
const MESSAGING_CAMPAIGN_ID = randomUUID()
const DELIVERY_ID = randomUUID()

function mysql(statement: string, batch = false): string {
  if (!MYSQL_USER || !MYSQL_PASSWORD)
    throw new Error('E2E_MYSQL_USER/E2E_MYSQL_PASSWORD が必要です')
  const flags = batch ? '-N -B ' : ''
  const escaped = statement.replace(/"/g, '\\"')
  const command = `docker exec mannschaft-mysql mysql -u${MYSQL_USER} -p${MYSQL_PASSWORD} mannschaft ${flags}-e "${escaped}"`
  return execSync(process.platform === 'win32' ? `wsl.exe -e ${command}` : command, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim()
}

function seedReservation(): void {
  mysql(
    `SET @viewer = (SELECT id FROM users WHERE email = '${TARGET_USER.email}' LIMIT 1); SET @org = (SELECT id FROM organizations ORDER BY id LIMIT 1); INSERT INTO advertiser_accounts (scope_type, scope_id, status, company_name, contact_email, billing_method, credit_limit, created_at, updated_at) VALUES ('ORGANIZATION', @org, 'ACTIVE', '${COMPANY}', 'cmp0042@test.mannschaft.local', 'STRIPE', 100000, UTC_TIMESTAMP(), UTC_TIMESTAMP()); SET @advertiser = LAST_INSERT_ID(); INSERT INTO ad_campaigns (advertiser_account_id, name, status, pricing_model, daily_budget, start_date, end_date, unit_price_snapshot, created_at, updated_at) VALUES (@advertiser, '${TITLE}', 'DRAFT', 'CPM', 3000, UTC_DATE() - INTERVAL 1 DAY, UTC_DATE() + INTERVAL 30 DAY, 500, UTC_TIMESTAMP(), UTC_TIMESTAMP()); SET @operational_campaign = LAST_INSERT_ID(); INSERT INTO ads (campaign_id, title, image_url, destination_url, placement, width, height, alt_text, status, created_at, updated_at) VALUES (@operational_campaign, '${TITLE}', NULL, '${API_BASE_URL}/dashboard?cmp0042=${RUN_TAG}', 'DASHBOARD_TILE', 300, 250, '${TITLE}', 'ACTIVE', UTC_TIMESTAMP(), UTC_TIMESTAMP()); SET @creative = LAST_INSERT_ID(); INSERT INTO ad_messaging_campaigns (id, advertiser_account_id, scope_type, scope_id, name, status, total_budget_yen, consumed_budget_yen, starts_at, ends_at, scheduled_timezone, moderation_status, created_by_user_id, created_at, updated_at) VALUES (UUID_TO_BIN('${MESSAGING_CAMPAIGN_ID}'), @advertiser, 'ORGANIZATION', @org, '${TITLE}', 'DELIVERING', 100000, 0, UTC_TIMESTAMP() - INTERVAL 1 DAY, UTC_TIMESTAMP() + INTERVAL 7 DAY, 'Asia/Tokyo', 'APPROVED', @viewer, UTC_TIMESTAMP(), UTC_TIMESTAMP()); INSERT INTO ad_messaging_campaign_channels (id, campaign_id, channel_type, locale, body_markdown, banner_creative_id, placement, created_at, updated_at) VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN('${MESSAGING_CAMPAIGN_ID}'), 'BANNER', 'ja', '${TITLE}', @creative, 'DASHBOARD_TILE', UTC_TIMESTAMP(), UTC_TIMESTAMP()); INSERT INTO ad_banner_deliveries (id, campaign_id, user_id, ad_impression_id, served_at, clicked_at, month_key, created_at) VALUES (UUID_TO_BIN('${DELIVERY_ID}'), UUID_TO_BIN('${MESSAGING_CAMPAIGN_ID}'), @viewer, NULL, NULL, NULL, DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m'), UTC_TIMESTAMP() - INTERVAL 14 DAY + INTERVAL 1 HOUR);`,
  )
}

function cleanupReservation(): void {
  mysql(
    `DELETE c FROM ad_clicks c JOIN ads a ON a.id = c.ad_id WHERE a.title = '${TITLE}'; DELETE i FROM ad_impressions i JOIN ads a ON a.id = i.ad_id WHERE a.title = '${TITLE}'; DELETE FROM ad_messaging_campaigns WHERE id = UUID_TO_BIN('${MESSAGING_CAMPAIGN_ID}'); DELETE FROM ads WHERE title = '${TITLE}'; DELETE FROM ad_campaigns WHERE name = '${TITLE}'; DELETE FROM advertiser_accounts WHERE company_name = '${COMPANY}';`,
  )
}

function utcMeasurementDeltaSeconds(): { served: number; clicked: number } {
  const output = mysql(
    `SELECT ABS(TIMESTAMPDIFF(SECOND, served_at, UTC_TIMESTAMP())), ABS(TIMESTAMPDIFF(SECOND, clicked_at, UTC_TIMESTAMP())) FROM ad_banner_deliveries WHERE id = UUID_TO_BIN('${DELIVERY_ID}');`,
    true,
  )
  const [served, clicked] = output.split('\t').map(Number)
  if (!Number.isFinite(served) || !Number.isFinite(clicked))
    throw new Error(`UTC計測時刻を取得できませんでした: ${JSON.stringify(output)}`)
  return { served, clicked }
}

async function login(page: Page, credentials: typeof TARGET_USER): Promise<void> {
  await loginViaApi(page, credentials, { apiBaseUrl: API_BASE_URL })
  await page.goto('about:blank')
}

test.describe('CMP-260910-0042: Spotlight UTC配信・計測の実機導線', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ storageState: { cookies: [], origins: [] } })
  test.setTimeout(180_000)
  let seeded = false

  test.beforeAll(() => {
    seeded = true
    seedReservation()
  })

  test.afterAll(() => {
    if (seeded) cleanupReservation()
  })

  test('予約対象外の認証済みユーザーには対象Spotlightを配信しない', async ({ page }) => {
    await login(page, OTHER_USER)
    const contentResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname === '/api/v1/spotlight/content',
    )
    await page.goto('/dashboard')
    expect((await contentResponse).status()).toBe(200)
    await waitForHydration(page)
    await expect(page.getByText(TITLE, { exact: true })).toHaveCount(0)
  })

  test('14日境界内の予約を画面表示し、閲覧・クリック時刻をUTCで記録する', async ({ page }) => {
    await login(page, TARGET_USER)
    const contentResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname === '/api/v1/spotlight/content',
    )
    const viewResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        /\/api\/v1\/spotlight\/\d+\/view$/.test(new URL(response.url()).pathname),
      { timeout: 30_000 },
    )
    await page.goto('/dashboard')
    expect((await contentResponse).status()).toBe(200)
    await waitForHydration(page)

    const house = page.getByTestId('spotlight-house').filter({ hasText: TITLE })
    await expect(house).toBeVisible({ timeout: 30_000 })
    expect((await viewResponse).status()).toBe(200)

    const visitResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        /\/api\/v1\/spotlight\/\d+\/visit$/.test(new URL(response.url()).pathname),
    )
    await house.click()
    expect((await visitResponse).status()).toBe(200)

    const delta = utcMeasurementDeltaSeconds()
    expect(delta.served).toBeLessThanOrEqual(60)
    expect(delta.clicked).toBeLessThanOrEqual(60)
  })
})
