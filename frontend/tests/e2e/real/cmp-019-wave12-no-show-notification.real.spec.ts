import { execFileSync } from 'node:child_process'
import { expect, test, type APIRequestContext, type Browser } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'
import {
  authHeaders,
  createNoShowFixture,
  loginForNoShow,
  type NoShowFixture,
  type NoShowCredentials,
  type NoShowScope,
} from './helpers/cmp019-wave12-no-show-fixture'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(300_000)

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const API = `${API_BASE}/api/v1`
const APP_BASE = process.env.BASE_URL ?? 'http://localhost:8081'
const TEAM_ADMIN = {
  email: process.env.TEST_TEAM_ADMIN_EMAIL ?? 'e2e-dummy-1@test.mannschaft.local',
  password: process.env.TEST_TEAM_ADMIN_PASSWORD ?? 'TestPass2026!',
}
const MEMBER = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}
const ORG_MEMBER = {
  email: process.env.TEST_ORG_MEMBER_EMAIL ?? 'e2e-dummy-6@test.mannschaft.local',
  password: process.env.TEST_ORG_MEMBER_PASSWORD ?? 'TestPass2026!',
}
const OUTSIDER = {
  email: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local',
  password: process.env.TEST_OUTSIDER_PASSWORD ?? 'TestPass2026!',
}
const MYSQL_USER = process.env.E2E_MYSQL_USER ?? ''
const MYSQL_PASSWORD = process.env.E2E_MYSQL_PASSWORD ?? ''
const RUN_TAG = `CMP019_W12_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
const NOTIFICATION_TYPE = 'RECRUITMENT_NO_SHOW_RECORDED'

type Notification = {
  id: number
  userId: number
  notificationType: string
  priority: string
  sourceType: string
  sourceId: number
  actionUrl: string | null
  channelsSent: string | null
}

function mysql(sql: string): string {
  if (!MYSQL_USER || !MYSQL_PASSWORD)
    throw new Error('E2E_MYSQL_USER / E2E_MYSQL_PASSWORD が必要です')
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

async function notifications(request: APIRequestContext, token: string): Promise<Notification[]> {
  const response = await request.get(`${API}/notifications?page=0&size=100`, {
    headers: authHeaders(token),
  })
  expect(response.status(), '本人の通知一覧').toBe(200)
  return ((await response.json()) as { data: Notification[] }).data
}

function readNoShowDb(fixture: NoShowFixture): string {
  return mysql(
    `SELECT CONCAT_WS(CHAR(9), id, user_id, participant_id, listing_id, confirmed, disputed) ` +
      `FROM recruitment_no_show_records WHERE id IN ` +
      `(SELECT id FROM recruitment_no_show_records WHERE listing_id=${fixture.listingId} ` +
      `AND participant_id=${fixture.participantId})`,
  ).trim()
}

function readNotificationDb(fixture: NoShowFixture): string[] {
  return mysql(
    `SELECT CONCAT_WS(CHAR(9), id, user_id, notification_type, priority, source_type, source_id, action_url) ` +
      `FROM notifications WHERE notification_type='${NOTIFICATION_TYPE}' ` +
      `AND source_type='RECRUITMENT_LISTING' AND source_id=${fixture.listingId} ` +
      `AND user_id=${fixture.targetUserId} ORDER BY id`,
  )
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
}

async function expectStableNotification(
  request: APIRequestContext,
  token: string,
  fixture: NoShowFixture,
): Promise<Notification> {
  let matching: Notification[] = []
  await expect
    .poll(
      async () => {
        matching = (await notifications(request, token)).filter(
          (row) => row.notificationType === NOTIFICATION_TYPE && row.sourceId === fixture.listingId,
        )
        return matching.length
      },
      { timeout: 30_000, intervals: [500, 1_000, 2_000] },
    )
    .toBe(1)
  const notification = matching[0]
  if (!notification) throw new Error('NO_SHOW 通知が見つかりません')
  return notification
}

async function cleanupFixture(request: APIRequestContext, adminToken: string, scope: NoShowScope) {
  const createdIds = mysql(
    `SELECT id FROM recruitment_listings WHERE title IN ` +
      `('${RUN_TAG}_${scope.type}_A','${RUN_TAG}_${scope.type}_B')`,
  )
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
    .map(Number)
  if (!createdIds.length) return
  const listingIds = createdIds.join(',')
  for (const listingId of createdIds) {
    const archive = await request.post(`${API}/recruitment-listings/${listingId}/archive`, {
      headers: authHeaders(adminToken),
    })
    expect(archive.status(), `作成した募集 ${listingId} をアーカイブ`).toBe(204)
  }
  const notificationIds = mysql(
    `SELECT id FROM notifications WHERE source_type='RECRUITMENT_LISTING' AND source_id IN (${listingIds})`,
  )
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
    .map(Number)
  if (notificationIds.length) {
    mysql(`DELETE FROM notifications WHERE id IN (${notificationIds.join(',')})`)
  }
  // The exact created listing IDs drive this delete; all recruitment child rows cascade from them.
  mysql(`DELETE FROM recruitment_listings WHERE id IN (${listingIds})`)
}

async function assertOtherAccountCannotSee(
  browser: Browser,
  request: APIRequestContext,
  outsiderToken: string,
  fixture: NoShowFixture,
) {
  const ownHistory = await request.get(`${API}/recruitment/no-shows/me`, {
    headers: authHeaders(outsiderToken),
  })
  expect(ownHistory.status(), '別アカウントの本人履歴').toBe(200)
  const rows = ((await ownHistory.json()) as { data: Array<{ id: number; listingId: number }> })
    .data
  expect(
    rows.some((row) => row.listingId === fixture.listingId),
    '別アカウントには NO_SHOW 履歴が見えない',
  ).toBe(false)

  const outsiderContext = await browser.newContext({
    baseURL: APP_BASE,
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
  })
  try {
    const outsiderPage = await outsiderContext.newPage()
    await loginViaApi(outsiderPage, OUTSIDER, { apiBaseUrl: API_BASE })
    await outsiderPage.goto('/my/no-shows')
    await waitForHydration(outsiderPage)
    await expect(outsiderPage.locator('body')).not.toContainText(`listing #${fixture.listingId}`)
  } finally {
    await outsiderContext.close()
  }
}

const scopes: Array<NoShowScope & { admin: NoShowCredentials; target: NoShowCredentials }> = [
  { type: 'TEAM', slug: 'fc-u-18', numericId: 1, admin: TEAM_ADMIN, target: MEMBER },
  { type: 'ORGANIZATION', slug: 'org-000009', numericId: 9, admin: MEMBER, target: ORG_MEMBER },
]

for (const scope of scopes) {
  test(`CMP-019 Wave12 ${scope.type}: 仮マーク通知から本人の異議申立までを実DBで確認する`, async ({
    page,
    browser,
    request,
  }) => {
    test.skip(
      !MYSQL_USER || !MYSQL_PASSWORD,
      '実DB照合には E2E_MYSQL_USER / E2E_MYSQL_PASSWORD が必要です',
    )

    const admin = await loginForNoShow(request, scope.admin)
    const member = await loginForNoShow(request, scope.target)
    const outsider = await loginForNoShow(request, OUTSIDER)
    try {
      const fixture = await createNoShowFixture(
        request,
        scope,
        admin.token,
        member.token,
        member.userId,
        RUN_TAG,
      )
      const preMarkDb = readNoShowDb(fixture)
      expect(preMarkDb, '試験用参加者にはまだ NO_SHOW がない').toBe('')
      const notifyBefore = readNotificationDb(fixture)
      expect(notifyBefore, 'NO_SHOW通知の初期状態').toHaveLength(0)

      const markUrl =
        `${API}/scopes/${fixture.scopeType}/${fixture.scopeId}` +
        `/recruitment-listings/${fixture.listingId}/participants/${fixture.participantId}/no-show`
      const denied = await request.post(markUrl, { headers: authHeaders(member.token) })
      expect(denied.status(), '一般会員による仮マークは拒否').toBeGreaterThanOrEqual(400)
      expect(denied.status()).toBeLessThan(500)
      expect(readNoShowDb(fixture), '権限拒否で記録は増えない').toBe('')
      expect(readNotificationDb(fixture), '権限拒否で通知は増えない').toHaveLength(0)

      const otherScopeType = fixture.scopeType === 'TEAM' ? 'ORGANIZATION' : 'TEAM'
      const wrongScope = await request.post(
        `${API}/scopes/${otherScopeType}/${fixture.scopeId}` +
          `/recruitment-listings/${fixture.listingId}/participants/${fixture.participantId}/no-show`,
        { headers: authHeaders(admin.token) },
      )
      expect(wrongScope.status(), '募集と異なる scope の ID を拒否').toBe(403)

      const wrongParticipant = await request.post(
        `${API}/scopes/${fixture.scopeType}/${fixture.scopeId}` +
          `/recruitment-listings/${fixture.listingId}/participants/${fixture.participantIds[1]}/no-show`,
        { headers: authHeaders(admin.token) },
      )
      expect(wrongParticipant.status(), '別募集の participant ID を拒否').toBe(404)
      const wrongListing = await request.post(
        `${API}/scopes/${fixture.scopeType}/${fixture.scopeId}` +
          `/recruitment-listings/${fixture.listingIds[1]}/participants/${fixture.participantId}/no-show`,
        { headers: authHeaders(admin.token) },
      )
      expect(wrongListing.status(), '別募集に属する participant ID の混入を拒否').toBe(404)
      expect(readNoShowDb(fixture), '越境ID拒否後も記録なし').toBe('')
      expect(readNotificationDb(fixture), '越境ID拒否で通知なし').toHaveLength(0)

      const marked = await request.post(markUrl, { headers: authHeaders(admin.token) })
      expect(marked.status(), `管理者の NO_SHOW 仮マーク: ${await marked.text()}`).toBe(201)
      const record = (
        (await marked.json()) as {
          data: {
            id: number
            listingId: number
            participantId: number
            userId: number
            confirmed: boolean
            disputed: boolean
          }
        }
      ).data
      expect(record.listingId).toBe(fixture.listingId)
      expect(record.participantId).toBe(fixture.participantId)
      expect(record.userId).toBe(member.userId)
      expect(record.confirmed, '管理者の仮マークは未確定').toBe(false)
      expect(record.disputed).toBe(false)
      expect(readNoShowDb(fixture)).toBe(
        `${record.id}\t${member.userId}\t${fixture.participantId}\t${fixture.listingId}\t0\t0`,
      )

      const notification = await expectStableNotification(request, member.token, fixture)
      expect(notification.userId, '対象本人だけに通知').toBe(member.userId)
      expect(notification.priority).toBe('HIGH')
      expect(notification.sourceType).toBe('RECRUITMENT_LISTING')
      expect(notification.actionUrl).toBe('/my/no-shows')
      const dbNotifications = readNotificationDb(fixture)
      expect(dbNotifications).toHaveLength(1)
      expect(dbNotifications[0]).toBe(
        `${notification.id}\t${member.userId}\t${NOTIFICATION_TYPE}\tHIGH\tRECRUITMENT_LISTING\t${fixture.listingId}\t/my/no-shows`,
      )

      // A repeated mark is rejected as a duplicate and cannot enqueue a second notification.
      const duplicate = await request.post(markUrl, { headers: authHeaders(admin.token) })
      expect(duplicate.status()).toBe(409)
      expect(readNoShowDb(fixture)).toBe(
        `${record.id}\t${member.userId}\t${fixture.participantId}\t${fixture.listingId}\t0\t0`,
      )
      expect(readNotificationDb(fixture)).toEqual(dbNotifications)
      const outsiderHistory = await request.get(`${API}/recruitment/no-shows/me`, {
        headers: authHeaders(outsider.token),
      })
      expect(outsiderHistory.status()).toBe(200)
      expect(
        ((await outsiderHistory.json()) as { data: Array<{ id: number }> }).data.some(
          (row) => row.id === record.id,
        ),
      ).toBe(false)
      const outsiderDispute = await request.post(
        `${API}/recruitment/no-shows/${record.id}/dispute`,
        {
          headers: authHeaders(outsider.token),
          data: { reason: 'not mine' },
        },
      )
      expect(outsiderDispute.status(), '他アカウントからの記録参照は存在を隠す').toBe(404)
      expect(readNoShowDb(fixture)).toContain('\t0\t0')
      expect(readNotificationDb(fixture)).toEqual(dbNotifications)
      const outsiderNotifications = (await notifications(request, outsider.token)).filter(
        (row) => row.notificationType === NOTIFICATION_TYPE && row.sourceId === fixture.listingId,
      )
      expect(outsiderNotifications, '通知は対象者以外へ届かない').toHaveLength(0)

      const memberHistory = await request.get(`${API}/recruitment/no-shows/me`, {
        headers: authHeaders(member.token),
      })
      expect(memberHistory.status(), '本人の履歴 API').toBe(200)
      expect(
        ((await memberHistory.json()) as { data: Array<{ id: number }> }).data.some(
          (row) => row.id === record.id,
        ),
        '本人の履歴 API に仮マークが含まれる',
      ).toBe(true)
      await loginViaApi(page, scope.target, { apiBaseUrl: API_BASE })
      await page.goto('/my/no-shows')
      await waitForHydration(page)
      await expect(page.getByText(`listing #${fixture.listingId}`, { exact: true })).toBeVisible({
        timeout: 30_000,
      })
      const disputeButton = page.getByRole('button', { name: '異議を申し立てる', exact: true })
      await expect(disputeButton).toBeVisible()
      await disputeButton.click()
      const dialog = page.getByRole('dialog')
      await dialog.getByRole('textbox').fill('Wave12 実機 E2E の異議申立')
      await dialog.getByRole('button').last().click()
      await expect(page.getByRole('button', { name: '異議を申し立てる', exact: true })).toHaveCount(
        0,
      )
      await expect.poll(() => readNoShowDb(fixture)).toContain('\t0\t1')
      const duplicateDispute = await request.post(
        `${API}/recruitment/no-shows/${record.id}/dispute`,
        {
          headers: authHeaders(member.token),
          data: { reason: 'Wave12 repeated dispute attempt' },
        },
      )
      expect(duplicateDispute.status(), '本人も異議申立を重ねて送信できない').toBe(409)
      expect(readNoShowDb(fixture)).toContain('\t0\t1')

      const memberNotifications = (await notifications(request, member.token)).filter(
        (row) => row.notificationType === NOTIFICATION_TYPE && row.sourceId === fixture.listingId,
      )
      expect(memberNotifications).toHaveLength(1)
      await assertOtherAccountCannotSee(browser, request, outsider.token, fixture)
    } finally {
      await cleanupFixture(request, admin.token, scope)
    }
  })
}
