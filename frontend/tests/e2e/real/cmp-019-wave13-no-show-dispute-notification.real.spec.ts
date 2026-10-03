import { execFileSync } from 'node:child_process'
import { expect, test, type APIRequestContext } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'
import {
  authHeaders,
  createNoShowFixture,
  loginForNoShow,
  type NoShowCredentials,
  type NoShowScope,
} from './helpers/cmp019-wave12-no-show-fixture'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(600_000)

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const API = `${API_BASE}/api/v1`
const TEAM_ADMIN: NoShowCredentials = {
  email: process.env.TEST_TEAM_ADMIN_EMAIL ?? 'e2e-dummy-1@test.mannschaft.local',
  password: process.env.TEST_TEAM_ADMIN_PASSWORD ?? 'TestPass2026!',
}
const MEMBER: NoShowCredentials = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}
const OUTSIDER: NoShowCredentials = {
  email: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local',
  password: process.env.TEST_OUTSIDER_PASSWORD ?? 'TestPass2026!',
}
const MYSQL_USER = process.env.E2E_MYSQL_USER ?? ''
const MYSQL_PASSWORD = process.env.E2E_MYSQL_PASSWORD ?? ''
const RUN_TAG = `CMP019_W13_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
const NOTIFICATION_TYPE = 'RECRUITMENT_NO_SHOW_DISPUTE_RAISED'
const SCOPE: NoShowScope = { type: 'TEAM', slug: 'fc-u-18', numericId: 1 }

type Notification = {
  id: number
  userId: number
  notificationType: string
  priority: string
  sourceType: string
  sourceId: number
  scopeType: string
  scopeId: string | number | null
  actionUrl: string | null
  title: string
  body: string | null
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
    '--raw',
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

async function getNotifications(request: APIRequestContext, token: string): Promise<Notification[]> {
  const response = await request.get(`${API}/notifications?page=0&size=100`, {
    headers: authHeaders(token),
  })
  expect(response.status(), '通知一覧 API').toBe(200)
  return ((await response.json()) as { data: Notification[] }).data
}

function notificationRows(listingId: number, userId: number): string[] {
  return mysql(
    `SELECT CONCAT_WS(CHAR(9), id, user_id, notification_type, priority, source_type, source_id, scope_type, scope_id, action_url) ` +
      `FROM notifications WHERE notification_type='${NOTIFICATION_TYPE}' ` +
      `AND source_type='RECRUITMENT_LISTING' AND source_id=${listingId} AND user_id=${userId} ORDER BY id`,
  )
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
}

/** 自分の RUN_TAG が付いた2件だけを特定して片付ける。共有seedは対象にしない。 */
async function cleanupFixture(request: APIRequestContext, adminToken: string) {
  const titles = [`${RUN_TAG}_TEAM_A`, `${RUN_TAG}_TEAM_B`]
  const titleSql = titles.map(title => `'${title}'`).join(',')
  const listingIds = mysql(
    `SELECT id FROM recruitment_listings WHERE title IN (${titleSql}) ORDER BY id`,
  )
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
    .map(Number)
  if (!listingIds.length) return

  for (const listingId of listingIds) {
    const archive = await request.post(`${API}/recruitment-listings/${listingId}/archive`, {
      headers: authHeaders(adminToken),
    })
    expect(archive.status(), `作成した募集 ${listingId} のアーカイブ`).toBe(204)
  }
  const ids = listingIds.join(',')
  mysql(
    `DELETE FROM notifications WHERE source_type='RECRUITMENT_LISTING' AND source_id IN (${ids})`,
  )
  // ID は RUN_TAG と完全一致するタイトルから得たものだけ。募集配下の試験データは cascade で消える。
  mysql(`DELETE FROM recruitment_listings WHERE id IN (${ids})`)
}

test('CMP-019 Wave13: 本人の異議申立から主催者通知と裁定画面への遷移までを実 DB で確認する', async ({
  page,
  request,
}) => {
  test.skip(
    !MYSQL_USER || !MYSQL_PASSWORD,
    '実 DB 照合には E2E_MYSQL_USER / E2E_MYSQL_PASSWORD が必要です',
  )

  const organizer = await loginForNoShow(request, TEAM_ADMIN)
  const member = await loginForNoShow(request, MEMBER)
  const outsider = await loginForNoShow(request, OUTSIDER)
  try {
    const fixture = await createNoShowFixture(
      request,
      SCOPE,
      organizer.token,
      member.token,
      member.userId,
      RUN_TAG,
    )
    const markUrl =
      `${API}/scopes/${fixture.scopeType}/${fixture.scopeId}` +
      `/recruitment-listings/${fixture.listingId}/participants/${fixture.participantId}/no-show`
    const marked = await request.post(markUrl, { headers: authHeaders(organizer.token) })
    expect(marked.status(), `主催者の仮マーク: ${await marked.text()}`).toBe(201)
    const record = ((await marked.json()) as { data: { id: number } }).data

    // 別ユーザーによる ID 推測では申立も通知も発生しない。
    const outsiderDispute = await request.post(`${API}/recruitment/no-shows/${record.id}/dispute`, {
      headers: authHeaders(outsider.token),
      data: { reason: 'not mine' },
    })
    expect(outsiderDispute.status(), '他人の NO_SHOW 記録への申立拒否').toBe(404)
    expect(notificationRows(fixture.listingId, organizer.userId)).toHaveLength(0)

    const reason = `${RUN_TAG}: 主催者通知 E2E の異議理由`
    const dispute = await request.post(`${API}/recruitment/no-shows/${record.id}/dispute`, {
      headers: authHeaders(member.token),
      data: { reason },
    })
    expect(dispute.status(), `本人の異議申立: ${await dispute.text()}`).toBe(200)
    expect(((await dispute.json()) as { data: { disputed: boolean; disputeReason: string } }).data)
      .toMatchObject({ disputed: true, disputeReason: reason })

    let organizerNotice: Notification | undefined
    await expect
      .poll(
        async () => {
          organizerNotice = (await getNotifications(request, organizer.token)).find(
            notification =>
              notification.notificationType === NOTIFICATION_TYPE &&
              notification.sourceId === fixture.listingId,
          )
          return organizerNotice?.id ?? 0
        },
        { timeout: 30_000, intervals: [500, 1_000, 2_000] },
      )
      .toBeGreaterThan(0)
    if (!organizerNotice) throw new Error('主催者向け異議申立通知が作成されませんでした')

    const expectedActionUrl = `/scopes/team/${fixture.scopeId}/no-shows`
    expect(organizerNotice.userId, '通知先は募集の主催者').toBe(organizer.userId)
    expect(organizerNotice.priority).toBe('NORMAL')
    expect(organizerNotice.sourceType).toBe('RECRUITMENT_LISTING')
    expect(organizerNotice.scopeType).toBe('TEAM')
    expect(String(organizerNotice.scopeId)).toBe(String(fixture.scopeId))
    expect(organizerNotice.actionUrl).toBe(expectedActionUrl)
    expect(organizerNotice.body).toContain(`募集枠 #${fixture.listingId}`)
    expect(notificationRows(fixture.listingId, organizer.userId)).toEqual([
      `${organizerNotice.id}\t${organizer.userId}\t${NOTIFICATION_TYPE}\tNORMAL\tRECRUITMENT_LISTING\t${fixture.listingId}\tTEAM\t${fixture.scopeId}\t${expectedActionUrl}`,
    ])

    // 本人・無関係なアカウントには裁定待ち通知もスコープ内記録も見せない。
    for (const credentials of [MEMBER, OUTSIDER]) {
      const account = credentials === MEMBER ? member : outsider
      const notices = (await getNotifications(request, account.token)).filter(
        notification =>
          notification.notificationType === NOTIFICATION_TYPE &&
          notification.sourceId === fixture.listingId,
      )
      expect(notices, `${credentials.email} は主催者通知を受け取らない`).toHaveLength(0)
      const forbidden = await request.get(
        `${API}/scopes/${fixture.scopeType}/${fixture.scopeId}/no-shows?page=0&size=20`,
        { headers: authHeaders(account.token) },
      )
      expect(forbidden.status(), `${credentials.email} の裁定一覧`).toBeGreaterThanOrEqual(400)
      expect(forbidden.status()).toBeLessThan(500)
    }

    // 2回目の申立は409で拒否され、同一主催者向け通知も増えない。
    const duplicate = await request.post(`${API}/recruitment/no-shows/${record.id}/dispute`, {
      headers: authHeaders(member.token),
      data: { reason: `${RUN_TAG}: duplicate` },
    })
    expect(duplicate.status()).toBe(409)
    expect(notificationRows(fixture.listingId, organizer.userId)).toHaveLength(1)

    // 別募集の NO_SHOW 記録へ同時に2回申立し、悲観ロック下で通知が二重作成されないことを確認する。
    const concurrentListingId = fixture.listingIds[1]
    const concurrentParticipantId = fixture.participantIds[1]
    if (concurrentListingId === undefined || concurrentParticipantId === undefined)
      throw new Error('並列申立用の2件目 fixture がありません')
    const concurrentMark = await request.post(
      `${API}/scopes/${fixture.scopeType}/${fixture.scopeId}` +
        `/recruitment-listings/${concurrentListingId}/participants/${concurrentParticipantId}/no-show`,
      { headers: authHeaders(organizer.token) },
    )
    expect(concurrentMark.status(), `並列申立用 NO_SHOW 仮マーク: ${await concurrentMark.text()}`).toBe(
      201,
    )
    const concurrentRecord = ((await concurrentMark.json()) as { data: { id: number } }).data
    const concurrentResults = await Promise.all([
      request.post(`${API}/recruitment/no-shows/${concurrentRecord.id}/dispute`, {
        headers: authHeaders(member.token),
        data: { reason: `${RUN_TAG}: concurrent A` },
      }),
      request.post(`${API}/recruitment/no-shows/${concurrentRecord.id}/dispute`, {
        headers: authHeaders(member.token),
        data: { reason: `${RUN_TAG}: concurrent B` },
      }),
    ])
    const concurrentStatuses = concurrentResults
      .map(response => response.status())
      .sort((left, right) => left - right)
    expect(concurrentStatuses, '同一記録への並列申立は成功1件・重複拒否1件').toEqual([200, 409])
    await expect
      .poll(
        async () => {
          const notices = (await getNotifications(request, organizer.token)).filter(
            notification =>
              notification.notificationType === NOTIFICATION_TYPE &&
              notification.sourceId === concurrentListingId,
          )
          return notices.length
        },
        { timeout: 30_000, intervals: [500, 1_000, 2_000] },
      )
      .toBe(1)
    expect(notificationRows(concurrentListingId, organizer.userId)).toHaveLength(1)
    expect(notificationRows(concurrentListingId, member.userId)).toHaveLength(0)

    // 実 UI で通知行を選び、actionUrl の裁定一覧で申立理由を確認する。
    await loginViaApi(page, TEAM_ADMIN, { apiBaseUrl: API_BASE })
    await page.goto('/notifications')
    await waitForHydration(page)
    const noticeRow = page
      .getByRole('button')
      .filter({ hasText: organizerNotice.title })
      .filter({ hasText: `募集枠 #${fixture.listingId}` })
    await expect(noticeRow).toHaveCount(1)
    await expect(noticeRow, '新しい通知が通知一覧に表示される').toBeVisible({ timeout: 30_000 })
    await noticeRow.click()
    await expect(page).toHaveURL(new RegExp(`${expectedActionUrl.replaceAll('/', '\\/')}$`))
    await expect(page.getByText(`listing #${fixture.listingId} / participant #${fixture.participantId}`, {
      exact: true,
    })).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText(`申立理由: ${reason}`)).toBeVisible()
  }
  finally {
    await cleanupFixture(request, organizer.token)
  }
})
