import { execFileSync } from 'node:child_process'
import { expect, test, type APIRequestContext } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'
import { authHeaders, loginForNoShow } from './helpers/cmp019-wave12-no-show-fixture'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(300_000)

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const API = `${API_BASE}/api/v1`
const APP_BASE = process.env.BASE_URL ?? 'http://localhost:3000'
const SYSTEM_ADMIN = {
  email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!',
}
const MEMBER = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}
const OUTSIDER = {
  email: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local',
  password: process.env.TEST_OUTSIDER_PASSWORD ?? 'TestPass2026!',
}
const MYSQL_USER = process.env.E2E_MYSQL_USER ?? ''
const MYSQL_PASSWORD = process.env.E2E_MYSQL_PASSWORD ?? ''
const NOTIFICATION_TYPE = 'RECRUITMENT_PENALTY_LIFTED'
const SOURCE_TYPE = 'RECRUITMENT_PENALTY'
const RUN_TAG = `CMP019_W14_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`

type Notification = {
  id: number
  userId: number
  notificationType: string
  priority: string
  title: string
  body: string
  sourceType: string
  sourceId: number
  scopeType: string | null
  scopeId: number | null
  actionUrl: string | null
}

type PenaltyFixture = {
  penaltyId: number
  settingId: number
  createdSetting: boolean
  userId: number
}

function mysql(sql: string): string {
  if (!MYSQL_USER || !MYSQL_PASSWORD)
    throw new Error('実機 E2E には E2E_MYSQL_USER / E2E_MYSQL_PASSWORD が必要です')
  const jdbcJar = process.env.E2E_MYSQL_JDBC_JAR
  if (jdbcJar) {
    return execFileSync('java', ['--class-path', jdbcJar, 'tests/e2e/real/MysqlExec.java', sql], {
      cwd: process.cwd(),
      env: process.env,
      encoding: 'utf8',
    })
  }
  const dockerArgs = [
    'exec',
    'mannschaft-mysql',
    'mysql',
    '--batch',
    '--skip-column-names',
    '--raw',
    `-u${MYSQL_USER}`,
    `-p${MYSQL_PASSWORD}`,
    'mannschaft',
    `--execute=${sql}`,
  ]
  return execFileSync(
    process.platform === 'win32' ? 'wsl.exe' : 'docker',
    process.platform === 'win32' ? ['-e', 'docker', ...dockerArgs] : dockerArgs,
    { encoding: 'utf8' },
  )
}

function scalar(sql: string): string {
  return mysql(sql).trim()
}

function insertReturningId(insert: string): number {
  const output = scalar(`${insert}; SELECT LAST_INSERT_ID()`)
  const id = Number(output.split(/\r?\n/).at(-1))
  expect(id, `${RUN_TAG}: INSERT ID`).toBeGreaterThan(0)
  return id
}

function createExpiredPenalty(userId: number): PenaltyFixture {
  const existingSettingId = scalar(
    "SELECT id FROM recruitment_penalty_settings WHERE scope_type='TEAM' AND scope_id=1 LIMIT 1",
  )
  const createdSetting = !existingSettingId
  let settingId = Number(existingSettingId)
  if (createdSetting) {
    settingId = insertReturningId(
      "INSERT INTO recruitment_penalty_settings " +
        "(scope_type, scope_id, is_enabled, threshold_count, threshold_period_days, penalty_duration_days, apply_scope, auto_no_show_detection, dispute_allowed_days) " +
        "VALUES ('TEAM', 1, 0, 3, 180, 30, 'THIS_SCOPE_ONLY', 0, 14)",
    )
  }
  expect(settingId, '試験用ペナルティ設定').toBeGreaterThan(0)

  try {
    const penaltyId = insertReturningId(
      "INSERT INTO recruitment_user_penalties " +
        "(user_id, scope_type, scope_id, penalty_type, triggered_by_setting_id, triggered_no_show_count, started_at, expires_at) " +
        `VALUES (${userId}, 'TEAM', 1, 'NO_SHOW', ${settingId}, 3, UTC_TIMESTAMP() - INTERVAL 31 DAY, UTC_TIMESTAMP() - INTERVAL 1 MINUTE)`,
    )
    expect(penaltyId, `${RUN_TAG}: 試験用ペナルティ ID`).toBeGreaterThan(0)
    return { penaltyId, settingId, createdSetting, userId }
  }
  catch (error) {
    if (createdSetting)
      mysql(`DELETE FROM recruitment_penalty_settings WHERE id=${settingId}`)
    throw error
  }
}

function penaltyDbRow(fixture: PenaltyFixture): string {
  return scalar(
    `SELECT CONCAT_WS(CHAR(9), id, user_id, lifted_at IS NOT NULL, lift_reason) ` +
      `FROM recruitment_user_penalties WHERE id=${fixture.penaltyId}`,
  )
}

function notificationDbRows(fixture: PenaltyFixture): string[] {
  return mysql(
    `SELECT CONCAT_WS(CHAR(9), id, user_id, notification_type, priority, source_type, source_id, scope_type, scope_id, action_url) ` +
      `FROM notifications WHERE notification_type='${NOTIFICATION_TYPE}' ` +
      `AND source_type='${SOURCE_TYPE}' AND source_id=${fixture.penaltyId} ORDER BY id`,
  )
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
}

async function notifications(request: APIRequestContext, token: string): Promise<Notification[]> {
  const response = await request.get(`${API}/notifications?page=0&size=100`, {
    headers: authHeaders(token),
  })
  expect(response.status(), '通知 API').toBe(200)
  return ((await response.json()) as { data: Notification[] }).data
}

async function expectCreatedNotification(
  request: APIRequestContext,
  token: string,
  fixture: PenaltyFixture,
): Promise<Notification> {
  let matching: Notification[] = []
  await expect
    .poll(
      async () => {
        matching = (await notifications(request, token)).filter(
          (row) => row.notificationType === NOTIFICATION_TYPE && row.sourceId === fixture.penaltyId,
        )
        return matching.length
      },
      { timeout: 30_000, intervals: [500, 1_000, 2_000] },
    )
    .toBe(1)
  const notification = matching[0]
  if (!notification) throw new Error('期限切れペナルティ解除通知が見つかりません')
  return notification
}

function cleanupFixture(fixture: PenaltyFixture | undefined): void {
  if (!fixture) return
  // source ID はこの spec が INSERT で取得した penaltyId に限定する。
  mysql(
    `DELETE FROM notifications WHERE notification_type='${NOTIFICATION_TYPE}' ` +
      `AND source_type='${SOURCE_TYPE}' AND source_id=${fixture.penaltyId} AND user_id=${fixture.userId}`,
  )
  mysql(`DELETE FROM recruitment_user_penalties WHERE id=${fixture.penaltyId} AND user_id=${fixture.userId}`)
  if (fixture.createdSetting) {
    mysql(`DELETE FROM recruitment_penalty_settings WHERE id=${fixture.settingId}`)
  }
}

test('CMP-019 Wave14: 期限切れペナルティを解除して本人だけへ NORMAL 通知を1回作成する', async ({
  page,
  request,
}) => {
  test.skip(
    !MYSQL_USER || !MYSQL_PASSWORD,
    '実機 DB 照合には E2E_MYSQL_USER / E2E_MYSQL_PASSWORD が必要です',
  )

  const systemAdmin = await loginForNoShow(request, SYSTEM_ADMIN)
  const member = await loginForNoShow(request, MEMBER)
  const outsider = await loginForNoShow(request, OUTSIDER)
  let fixture: PenaltyFixture | undefined
  try {
    const preexistingExpiredCount = Number(
      // バッチは JVM の LocalDateTime.now() を使う。JVM が JST でも既存行を更新しないよう、
      // UTC より最大1日先までを保守的に検出して共有 DB への副作用を防ぐ。
      scalar(
        'SELECT COUNT(*) FROM recruitment_user_penalties ' +
          'WHERE lifted_at IS NULL AND expires_at <= UTC_TIMESTAMP() + INTERVAL 1 DAY',
      ),
    )
    test.skip(
      preexistingExpiredCount > 0,
      '既存の未解除ペナルティをバッチが更新しないよう、実機 DB を先に片付けてから実行してください',
    )

    const batches = await request.get(`${API}/system-admin/batch`, {
      headers: authHeaders(systemAdmin.token),
    })
    expect(batches.status(), 'SYSTEM_ADMIN のバッチ一覧').toBe(200)
    expect(
      ((await batches.json()) as { data: Array<{ name: string }> }).data.some(
        (batch) => batch.name === 'recruitment-penalty-lift-daily',
      ),
      '期限切れペナルティ解除バッチが登録されている',
    ).toBe(true)

    fixture = createExpiredPenalty(member.userId)
    expect(penaltyDbRow(fixture)).toBe(`${fixture.penaltyId}\t${member.userId}\t0`)
    expect(notificationDbRows(fixture), '実行前の通知').toHaveLength(0)

    const trigger = await request.post(
      `${API}/system-admin/batch/recruitment-penalty-lift-daily/trigger?sync=true`,
      { headers: authHeaders(systemAdmin.token) },
    )
    expect(trigger.status(), `解除バッチの同期起動: ${await trigger.text()}`).toBe(200)

    await expect.poll(() => penaltyDbRow(fixture!)).toMatch(
      new RegExp(`^${fixture.penaltyId}\\t${member.userId}\\t1\\tAUTO_EXPIRED$`),
    )
    const notification = await expectCreatedNotification(request, member.token, fixture)
    expect(notification.userId).toBe(member.userId)
    expect(notification.priority).toBe('NORMAL')
    expect(notification.sourceType).toBe(SOURCE_TYPE)
    expect(notification.sourceId).toBe(fixture.penaltyId)
    expect(notification.scopeType).toBe('TEAM')
    expect(notification.scopeId).toBe(1)
    expect(notification.actionUrl, '本人向け専用画面は未提供のため action URL は持たない').toBeNull()
    expect(`${notification.title}\n${notification.body}`).toContain('AUTO_EXPIRED')

    const dbNotifications = notificationDbRows(fixture)
    expect(dbNotifications).toHaveLength(1)
    expect(dbNotifications[0]).toBe(
      `${notification.id}\t${member.userId}\t${NOTIFICATION_TYPE}\tNORMAL\t${SOURCE_TYPE}\t${fixture.penaltyId}\tTEAM\t1`,
    )

    const outsiderNotifications = (await notifications(request, outsider.token)).filter(
      (row) => row.notificationType === NOTIFICATION_TYPE && row.sourceId === fixture!.penaltyId,
    )
    expect(outsiderNotifications, '他ユーザーの通知一覧には表示しない').toHaveLength(0)

    const repeated = await request.post(
      `${API}/system-admin/batch/recruitment-penalty-lift-daily/trigger?sync=true`,
      { headers: authHeaders(systemAdmin.token) },
    )
    // @SchedulerLock の lockAtLeastFor=5m により、直後の手動再実行は業務処理へ入らず 409 になる。
    // 実機ではユーザー資産の shedlock 行を変更せず、通知が増えないことだけを確認する。
    const repeatedBody = (await repeated.json()) as { data: { status: string } }
    expect(repeated.status(), '直後の再実行').toBe(409)
    expect(repeatedBody.data.status).toBe('LOCKED')
    expect(penaltyDbRow(fixture)).toMatch(
      new RegExp(`^${fixture.penaltyId}\\t${member.userId}\\t1\\tAUTO_EXPIRED$`),
    )
    expect(notificationDbRows(fixture), 'ロック中の再実行で通知を重複作成しない').toEqual(dbNotifications)

    await loginViaApi(page, MEMBER, { apiBaseUrl: API_BASE })
    await page.goto('/notifications')
    await waitForHydration(page)
    await expect(page.getByText(notification.title, { exact: true })).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText('AUTO_EXPIRED', { exact: false })).toBeVisible()

    const currentUrl = new URL(page.url())
    expect(currentUrl.origin).toBe(APP_BASE)
    expect(currentUrl.pathname).toBe('/notifications')
  } finally {
    cleanupFixture(fixture)
  }
})
