import { execFileSync } from 'node:child_process'
import { expect, test, type APIRequestContext } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'
import {
  authHeaders,
  createNoShowFixture,
  loginForNoShow,
  type NoShowFixture,
  type NoShowScope,
} from './helpers/cmp019-wave12-no-show-fixture'

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
const TEAM_OWNER = {
  email: process.env.TEST_TEAM_ADMIN_EMAIL ?? 'e2e-dummy-1@test.mannschaft.local',
  password: process.env.TEST_TEAM_ADMIN_PASSWORD ?? 'TestPass2026!',
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
const RUN_TAG = `CMP019_W16_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
const CONFIRMABLE_SOURCE = 'RECRUITMENT_PENALTY'
const APP_NOTIFICATION_TYPE = 'RECRUITMENT_PENALTY_APPLIED'
const APP_NOTIFICATION_SOURCE = 'CONFIRMABLE_NOTIFICATION'

type ApiEnvelope<T> = { data: T }
type ConfirmableNotification = {
  id: number
  scopeType: string
  scopeId: number
  title: string
  priority: string
  status: string
  totalRecipientCount: number
  confirmedCount: number
}
function mysql(sql: string): string {
  if (!MYSQL_USER || !MYSQL_PASSWORD)
    throw new Error('実機E2Eには E2E_MYSQL_USER / E2E_MYSQL_PASSWORD が必要です')
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

function mysqlUtcDateTimeForInstant(instant: string): string {
  const match = instant.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?Z$/)
  if (!match) throw new Error(`expiresAtがUTC Instant形式ではありません: ${instant}`)
  const fraction = match[2] ?? ''
  return `${match[1]!.replace('T', ' ')}.${fraction.padEnd(6, '0').slice(0, 6)}`
}

function rows(sql: string): string[] {
  return mysql(sql).trim().split(/\r?\n/).filter(Boolean)
}

async function notifications(request: APIRequestContext, token: string) {
  const response = await request.get(`${API}/notifications?page=0&size=100`, {
    headers: authHeaders(token),
  })
  expect(response.status(), 'アプリ内通知一覧').toBe(200)
  return (
    (await response.json()) as ApiEnvelope<
      Array<{
        id: number
        notificationType: string
        priority: string
        sourceType: string
        sourceId: number
        title: string
        body: string
        isRead: boolean
        actionUrl: string | null
      }>
    >
  ).data
}

async function createPublicListing(
  request: APIRequestContext,
  scope: NoShowScope,
  ownerToken: string,
  title: string,
): Promise<number> {
  const categoriesResponse = await request.get(`${API}/recruitment-categories`, {
    headers: authHeaders(ownerToken),
  })
  expect(categoriesResponse.status(), '別スコープ用の募集カテゴリ取得').toBe(200)
  const categories = ((await categoriesResponse.json()) as ApiEnvelope<Array<{ id: number }>>).data
  const category = categories[0]
  if (!category) throw new Error('別スコープ用の有効な募集カテゴリがありません')

  const futureLocalDateTime = (hoursFromNow: number) => {
    const parts = new Intl.DateTimeFormat('sv-SE', {
      timeZone: 'Asia/Tokyo',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).formatToParts(new Date(Date.now() + hoursFromNow * 60 * 60 * 1000))
    const part = (type: string) => parts.find((value) => value.type === type)!.value
    return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}:${part('second')}`
  }
  const path = scope.type === 'TEAM' ? 'teams' : 'organizations'
  const created = await request.post(`${API}/${path}/${scope.numericId}/recruitment-listings`, {
    headers: authHeaders(ownerToken),
    data: {
      categoryId: category.id,
      title,
      description: 'CMP-019 Wave16 cross-scope penalty fixture',
      participationType: 'INDIVIDUAL',
      startAt: futureLocalDateTime(48),
      endAt: futureLocalDateTime(50),
      applicationDeadline: futureLocalDateTime(24),
      autoCancelAt: futureLocalDateTime(23),
      capacity: 10,
      minCapacity: 1,
      paymentEnabled: false,
      visibility: 'PUBLIC',
      location: 'Wave16 E2E fixture',
    },
  })
  expect(created.status(), `別スコープ用の募集作成: ${await created.text()}`).toBe(201)
  const listing = ((await created.json()) as ApiEnvelope<{ id: number }>).data
  const distribution = await request.put(
    `${API}/recruitment-listings/${listing.id}/distribution-targets`,
    { headers: authHeaders(ownerToken), data: { targetTypes: ['PUBLIC_FEED'] } },
  )
  expect(distribution.status(), `別スコープ募集の公開先設定: ${await distribution.text()}`).toBe(
    200,
  )
  const published = await request.post(`${API}/recruitment-listings/${listing.id}/publish`, {
    headers: authHeaders(ownerToken),
  })
  expect(published.status(), `別スコープ募集の公開: ${await published.text()}`).toBe(200)
  return listing.id
}

function noShowIds(fixture: NoShowFixture): number[] {
  return fixture.participantIds.flatMap((participantId, index) =>
    rows(
      `SELECT id FROM recruitment_no_show_records WHERE listing_id=${fixture.listingIds[index]} ` +
        `AND participant_id=${participantId} AND user_id=${fixture.targetUserId}`,
    ).map(Number),
  )
}

async function markNoShow(
  request: APIRequestContext,
  adminToken: string,
  fixture: NoShowFixture,
  index: number,
): Promise<number> {
  const listingId = fixture.listingIds[index]
  const participantId = fixture.participantIds[index]
  if (!listingId || !participantId) throw new Error('NO_SHOW用fixtureのIDが不足しています')
  const response = await request.post(
    `${API}/scopes/${fixture.scopeType}/${fixture.scopeId}/recruitment-listings/${listingId}` +
      `/participants/${participantId}/no-show`,
    { headers: authHeaders(adminToken) },
  )
  expect(response.status(), `NO_SHOW仮記録: ${await response.text()}`).toBe(201)
  const data = ((await response.json()) as ApiEnvelope<{ id: number; confirmed: boolean }>).data
  expect(data.confirmed).toBe(false)
  return data.id
}

function confirmableRows(penaltyId: number): string[] {
  return rows(
    `SELECT id FROM confirmable_notifications WHERE source_type='${CONFIRMABLE_SOURCE}' ` +
      `AND source_id=${penaltyId} ORDER BY id`,
  )
}

function appNotificationRows(confirmableId: number, userId: number): string[] {
  return rows(
    `SELECT CONCAT_WS(CHAR(9), id, user_id, notification_type, priority, source_type, source_id, is_read) ` +
      `FROM notifications WHERE notification_type='${APP_NOTIFICATION_TYPE}' ` +
      `AND source_type='${APP_NOTIFICATION_SOURCE}' AND source_id=${confirmableId} ` +
      `AND user_id=${userId} ORDER BY id`,
  )
}

async function cleanup(
  request: APIRequestContext,
  teamOwnerToken: string,
  createdTeamSlugs: string[],
  scope: NoShowScope | undefined,
  teamScopeIds: number[],
  fixture: NoShowFixture | undefined,
  crossScopeListingId: number | undefined,
  appliedSettingId: number | undefined,
  penaltyId: number | undefined,
  confirmableId: number | undefined,
  userId: number | undefined,
  ownerUserId: number | undefined,
) {
  const penaltyIds = new Set<number>(penaltyId === undefined ? [] : [penaltyId])
  if (appliedSettingId !== undefined && userId !== undefined) {
    rows(
      `SELECT id FROM recruitment_user_penalties WHERE triggered_by_setting_id=${appliedSettingId} ` +
        `AND user_id=${userId}`,
    ).forEach((id) => penaltyIds.add(Number(id)))
  }
  const confirmableIds = new Set<number>(confirmableId === undefined ? [] : [confirmableId])
  for (const id of penaltyIds) {
    confirmableRows(id).forEach((cnId) => confirmableIds.add(Number(cnId)))
  }
  for (const id of confirmableIds) {
    mysql(
      `DELETE FROM notifications WHERE notification_type='${APP_NOTIFICATION_TYPE}' ` +
        `AND source_type='${APP_NOTIFICATION_SOURCE}' AND source_id=${id}` +
        (userId === undefined ? '' : ` AND user_id=${userId}`),
    )
    mysql(`DELETE FROM confirmable_notification_recipients WHERE confirmable_notification_id=${id}`)
    mysql(
      `DELETE FROM confirmable_notifications WHERE id=${id} ` +
        `AND source_type='${CONFIRMABLE_SOURCE}'`,
    )
  }
  for (const id of penaltyIds) {
    mysql(`DELETE FROM recruitment_user_penalties WHERE id=${id}`)
  }

  const ownedListingIds = rows(
    `SELECT id FROM recruitment_listings WHERE title IN (` +
      `'${RUN_TAG}_TEAM_A','${RUN_TAG}_TEAM_B','${RUN_TAG}_CROSS_SCOPE_LISTING')`,
  ).map(Number)
  const listings = [
    ...new Set([
      ...(fixture?.listingIds ?? []),
      ...(crossScopeListingId === undefined ? [] : [crossScopeListingId]),
      ...ownedListingIds,
    ]),
  ]
  const listingIds = listings.filter(Number.isInteger).join(',')
  if (listingIds) {
    const showIds = fixture
      ? noShowIds(fixture)
      : rows(
          `SELECT id FROM recruitment_no_show_records WHERE listing_id IN (${listingIds}) ` +
            `AND user_id=${userId ?? 0}`,
        ).map(Number)
    if (showIds.length) {
      const ids = showIds.join(',')
      mysql(
        `DELETE FROM notifications WHERE notification_type='RECRUITMENT_NO_SHOW_RECORDED' ` +
          `AND source_type='RECRUITMENT_LISTING' AND source_id IN (${listingIds})`,
      )
      mysql(`DELETE FROM recruitment_no_show_records WHERE id IN (${ids})`)
    }
    for (const listingId of listings) {
      const archived = await request.post(`${API}/recruitment-listings/${listingId}/archive`, {
        headers: authHeaders(teamOwnerToken),
      })
      const archiveBody = await archived.text()
      expect(
        archived.status(),
        `試験募集 ${listingId} のアーカイブ: status=${archived.status()}, body=${archiveBody}`,
      ).toBe(204)
    }
    mysql(
      `DELETE FROM notifications WHERE source_type='RECRUITMENT_LISTING' ` +
        `AND source_id IN (${listingIds})`,
    )
    mysql(`DELETE FROM recruitment_listings WHERE id IN (${listingIds})`)
  }

  if (appliedSettingId !== undefined && scope !== undefined) {
    mysql(
      `DELETE FROM recruitment_penalty_settings WHERE id=${appliedSettingId} ` +
        `AND scope_type='TEAM' AND scope_id=${scope.numericId}`,
    )
  }
  for (const teamScopeId of teamScopeIds) {
    const scopeSettingIds = rows(
      `SELECT id FROM recruitment_penalty_settings WHERE scope_type='TEAM' AND scope_id=${teamScopeId}`,
    )
    for (const id of scopeSettingIds) {
      mysql(
        `DELETE FROM recruitment_penalty_settings WHERE id=${Number(id)} AND scope_type='TEAM' AND scope_id=${teamScopeId}`,
      )
    }
    mysql(
      `DELETE FROM confirmable_notification_settings WHERE scope_type='TEAM' AND scope_id=${teamScopeId}`,
    )
    if (userId !== undefined && ownerUserId !== undefined) {
      mysql(
        `DELETE FROM notifications WHERE notification_type IN ('JOIN_REQUEST_RECEIVED','JOIN_REQUEST_APPROVED') ` +
          `AND source_type='USER' AND scope_type='TEAM' AND scope_id=${teamScopeId} ` +
          `AND user_id IN (${userId},${ownerUserId})`,
      )
    }
  }
  for (const teamSlug of [...createdTeamSlugs].reverse()) {
    const deletedTeam = await request.delete(`${API}/teams/${teamSlug}`, {
      headers: authHeaders(teamOwnerToken),
    })
    const deleteBody = await deletedTeam.text()
    expect(
      deletedTeam.status(),
      `試験チーム ${teamSlug} の削除: status=${deletedTeam.status()}, body=${deleteBody}`,
    ).toBe(204)
  }
}

test('CMP-019 Wave16: 24時間経過したNO_SHOWを確定し、GLOBALペナルティと本人確認通知を1件ずつ作成する', async ({
  page,
  request,
}) => {
  test.skip(
    !MYSQL_USER || !MYSQL_PASSWORD,
    '実機DB照合には E2E_MYSQL_USER / E2E_MYSQL_PASSWORD が必要です',
  )

  const systemAdmin = await loginForNoShow(request, SYSTEM_ADMIN)
  const teamOwner = await loginForNoShow(request, TEAM_OWNER)
  const member = await loginForNoShow(request, MEMBER)
  const outsider = await loginForNoShow(request, OUTSIDER)
  const staleBefore = Number(
    scalar(
      'SELECT COUNT(*) FROM recruitment_no_show_records ' +
        'WHERE confirmed=FALSE AND recorded_at <= UTC_TIMESTAMP(6) - INTERVAL 24 HOUR',
    ),
  )
  test.skip(
    staleBefore > 0,
    '共有DBに既存の24時間超過NO_SHOWがあるため、全体バッチの影響を避けてスキップします',
  )

  const activeBefore = Number(
    scalar(
      `SELECT COUNT(*) FROM recruitment_user_penalties WHERE user_id=${member.userId} ` +
        'AND lifted_at IS NULL AND expires_at > UTC_TIMESTAMP(6)',
    ),
  )
  test.skip(
    activeBefore > 0,
    '対象会員に既存の有効ペナルティがあるため専用シナリオをスキップします',
  )
  const batchLocked = Number(
    scalar(
      "SELECT COUNT(*) FROM shedlock WHERE name='recruitment-no-show-confirm-batch' " +
        'AND lock_until > UTC_TIMESTAMP(6)',
    ),
  )
  test.skip(batchLocked > 0, 'NO_SHOW確定バッチが既にロック中のため専用シナリオをスキップします')

  const confirmedBefore = Number(
    scalar(
      `SELECT COUNT(*) FROM recruitment_no_show_records WHERE user_id=${member.userId} ` +
        'AND confirmed=TRUE AND recorded_at >= UTC_TIMESTAMP(6) - INTERVAL 180 DAY ' +
        "AND (dispute_resolution IS NULL OR dispute_resolution <> 'REVOKED')",
    ),
  )
  test.skip(
    confirmedBefore > 8,
    '対象会員の既存NO_SHOW件数が閾値上限に近く、専用条件を安全に作れません',
  )
  const thresholdCount = confirmedBefore + 2

  let scope: NoShowScope | undefined
  const createdTeamSlugs: string[] = []
  const teamScopeIds: number[] = []
  let crossScope: NoShowScope | undefined
  let crossScopeListingId: number | undefined
  let fixture: NoShowFixture | undefined
  let settingAppliedId: number | undefined
  let penaltyId: number | undefined
  let confirmableId: number | undefined
  let scenarioFailure: unknown
  let hasScenarioFailure = false
  try {
    const createTeam = await request.post(`${API}/teams`, {
      headers: authHeaders(teamOwner.token),
      data: { name: RUN_TAG, template: 'SPORTS', visibility: 'PUBLIC' },
    })
    expect(createTeam.status(), `専用試験チーム作成: ${await createTeam.text()}`).toBe(201)
    const createdSlug = ((await createTeam.json()) as ApiEnvelope<{ slug: string }>).data.slug
    createdTeamSlugs.push(createdSlug)
    const myTeams = await request.get(`${API}/me/teams?limit=200`, {
      headers: authHeaders(teamOwner.token),
    })
    expect(myTeams.status(), '作成者のチーム一覧からID解決').toBe(200)
    const team = (
      (await myTeams.json()) as ApiEnvelope<Array<{ id: number; slug: string }>>
    ).data.find((row) => row.slug === createdSlug)
    expect(team, 'TEAM_OWNERのチーム一覧に専用チームがある').toBeTruthy()
    scope = { type: 'TEAM', slug: createdSlug, numericId: team!.id }
    teamScopeIds.push(scope.numericId)
    const joinRequest = await request.post(`${API}/teams/${scope.numericId}/join-requests`, {
      headers: authHeaders(member.token),
      data: { message: RUN_TAG },
    })
    expect(joinRequest.status(), `会員の専用チーム参加申請: ${await joinRequest.text()}`).toBe(201)
    const joinRequestId = ((await joinRequest.json()) as ApiEnvelope<{ id: string }>).data.id
    const approveJoin = await request.post(
      `${API}/teams/${scope.numericId}/join-requests/${joinRequestId}/approve`,
      { headers: authHeaders(teamOwner.token), data: {} },
    )
    expect(approveJoin.status(), `専用チーム参加承認: ${await approveJoin.text()}`).toBe(200)
    const memberTeams = await request.get(`${API}/me/teams?limit=200`, {
      headers: authHeaders(member.token),
    })
    expect(memberTeams.status(), '参加後の会員チーム一覧').toBe(200)
    expect(
      ((await memberTeams.json()) as ApiEnvelope<Array<{ id: number }>>).data.some(
        (row) => row.id === scope!.numericId,
      ),
    ).toBe(true)

    const crossTeamCreate = await request.post(`${API}/teams`, {
      headers: authHeaders(teamOwner.token),
      data: { name: `${RUN_TAG}_CROSS_SCOPE`, template: 'SPORTS', visibility: 'PUBLIC' },
    })
    expect(crossTeamCreate.status(), `別スコープ用TEAM作成: ${await crossTeamCreate.text()}`).toBe(
      201,
    )
    const crossTeamSlug = ((await crossTeamCreate.json()) as ApiEnvelope<{ slug: string }>).data
      .slug
    createdTeamSlugs.push(crossTeamSlug)
    const refreshOwnerTeams = await request.get(`${API}/me/teams?limit=200`, {
      headers: authHeaders(teamOwner.token),
    })
    expect(refreshOwnerTeams.status()).toBe(200)
    const crossTeam = (
      (await refreshOwnerTeams.json()) as ApiEnvelope<Array<{ id: number; slug: string }>>
    ).data.find((row) => row.slug === crossTeamSlug)
    expect(crossTeam, 'TEAM_OWNERが別スコープTEAMを所有する').toBeTruthy()
    crossScope = { type: 'TEAM', slug: crossTeamSlug, numericId: crossTeam!.id }
    teamScopeIds.push(crossScope.numericId)
    const crossJoin = await request.post(`${API}/teams/${crossScope.numericId}/join-requests`, {
      headers: authHeaders(member.token),
      data: { message: `${RUN_TAG}_CROSS_SCOPE` },
    })
    expect(crossJoin.status(), `別スコープTEAMの参加申請: ${await crossJoin.text()}`).toBe(201)
    const crossJoinId = ((await crossJoin.json()) as ApiEnvelope<{ id: string }>).data.id
    const crossApproval = await request.post(
      `${API}/teams/${crossScope.numericId}/join-requests/${crossJoinId}/approve`,
      { headers: authHeaders(teamOwner.token), data: {} },
    )
    expect(crossApproval.status(), `別スコープTEAMの参加承認: ${await crossApproval.text()}`).toBe(
      200,
    )
    const refreshedMemberTeams = await request.get(`${API}/me/teams?limit=200`, {
      headers: authHeaders(member.token),
    })
    expect(refreshedMemberTeams.status()).toBe(200)
    expect(
      ((await refreshedMemberTeams.json()) as ApiEnvelope<Array<{ id: number }>>).data.some(
        (row) => row.id === crossScope!.numericId,
      ),
      'MEMBERは別スコープ募集にも応募資格のあるTEAMメンバー',
    ).toBe(true)
    crossScopeListingId = await createPublicListing(
      request,
      crossScope,
      teamOwner.token,
      `${RUN_TAG}_CROSS_SCOPE_LISTING`,
    )
    const visibleCrossListing = await request.get(
      `${API}/public/market/listings/${crossScopeListingId}`,
      { headers: authHeaders(member.token) },
    )
    expect(visibleCrossListing.status(), '別スコープの公開募集をMEMBERが閲覧できる').toBe(200)
    expect(
      scalar(
        `SELECT COUNT(*) FROM recruitment_participants WHERE listing_id=${crossScopeListingId} ` +
          `AND user_id=${member.userId}`,
      ),
      '応募前のためMEMBERの申込がまだ存在しない',
    ).toBe('0')
    // 期限切れの許可は追加fixtureを作らず、RecruitmentUserPenaltyRepository の expiresAt > :now
    // 条件と RecruitmentNoShowConfirmPenaltyIT の実Repository照合に追跡する。
    expect(
      Number(
        scalar(
          `SELECT COUNT(*) FROM recruitment_penalty_settings WHERE scope_type='TEAM' AND scope_id=${scope.numericId}`,
        ),
      ),
      '作成した専用チームにペナルティ設定がない',
    ).toBe(0)

    const setting = await request.put(
      `${API}/scopes/${scope.type}/${scope.numericId}/penalty-settings`,
      {
        headers: authHeaders(teamOwner.token),
        data: {
          isEnabled: true,
          thresholdCount,
          thresholdPeriodDays: 180,
          penaltyDurationDays: 30,
          applyScope: 'ALL_SCOPES',
          autoNoShowDetection: false,
          disputeAllowedDays: 14,
        },
      },
    )
    expect(setting.status(), `専用スコープの試験設定: ${await setting.text()}`).toBe(200)
    settingAppliedId = ((await setting.json()) as ApiEnvelope<{ id: number }>).data.id

    fixture = await createNoShowFixture(
      request,
      scope,
      teamOwner.token,
      member.token,
      member.userId,
      RUN_TAG,
    )
    const recordIds = [
      await markNoShow(request, teamOwner.token, fixture, 0),
      await markNoShow(request, teamOwner.token, fixture, 1),
    ]
    expect(recordIds).toHaveLength(2)
    const recordIdList = recordIds.join(',')
    mysql(
      `UPDATE recruitment_no_show_records SET recorded_at=UTC_TIMESTAMP(6) - INTERVAL 48 HOUR ` +
        `WHERE id IN (${recordIdList}) AND user_id=${member.userId} AND confirmed=FALSE`,
    )
    expect(
      Number(
        scalar(
          `SELECT COUNT(*) FROM recruitment_no_show_records WHERE id IN (${recordIdList}) ` +
            `AND user_id=${member.userId} AND confirmed=FALSE ` +
            'AND recorded_at <= UTC_TIMESTAMP(6) - INTERVAL 24 HOUR',
        ),
      ),
    ).toBe(2)

    const trigger = await request.post(
      `${API}/system-admin/batch/recruitment-no-show-confirm-hourly/trigger?sync=true`,
      { headers: authHeaders(systemAdmin.token) },
    )
    expect(trigger.status(), `24h確認バッチ: ${await trigger.text()}`).toBe(200)
    expect(((await trigger.json()) as ApiEnvelope<{ status: string }>).data.status).toBe(
      'COMPLETED',
    )
    await expect
      .poll(() =>
        Number(
          scalar(
            `SELECT COUNT(*) FROM recruitment_no_show_records WHERE id IN (${recordIdList}) AND confirmed=TRUE`,
          ),
        ),
      )
      .toBe(2)

    const penalties = rows(
      `SELECT id FROM recruitment_user_penalties WHERE user_id=${member.userId} ` +
        `AND triggered_by_setting_id=${settingAppliedId} AND scope_type='GLOBAL' AND scope_id IS NULL ` +
        'AND lifted_at IS NULL AND expires_at > UTC_TIMESTAMP(6)',
    )
    expect(penalties, '2件の確定NO_SHOWからGLOBALペナルティは1件').toHaveLength(1)
    penaltyId = Number(penalties[0])
    expect(
      scalar(
        `SELECT triggered_no_show_count FROM recruitment_user_penalties WHERE id=${penaltyId}`,
      ),
    ).toBe(String(thresholdCount))

    await expect.poll(() => confirmableRows(penaltyId!)).toHaveLength(1)
    confirmableId = Number(confirmableRows(penaltyId)[0])
    const confirmableQuery = await request.get(`${API}/me/confirmable-notifications/pending`, {
      headers: authHeaders(member.token),
    })
    expect(confirmableQuery.status(), '本人の確認待ち一覧').toBe(200)
    const recipientId = Number(
      scalar(
        `SELECT id FROM confirmable_notification_recipients ` +
          `WHERE confirmable_notification_id=${confirmableId} AND user_id=${member.userId}`,
      ),
    )
    const pending = (
      (await confirmableQuery.json()) as ApiEnvelope<Array<{ id: number; isConfirmed: boolean }>>
    ).data
    expect(pending).toEqual(
      expect.arrayContaining([expect.objectContaining({ id: recipientId, isConfirmed: false })]),
    )
    const detailResponse = await request.get(
      `${API}/teams/${scope.numericId}/confirmable-notifications/${confirmableId}`,
      { headers: authHeaders(member.token) },
    )
    expect(detailResponse.status(), '会員の確認通知詳細取得').toBe(200)
    const detail = ((await detailResponse.json()) as ApiEnvelope<ConfirmableNotification>).data
    expect(detail.scopeType).toBe('TEAM')
    expect(detail.scopeId).toBe(scope.numericId)
    expect(detail.priority).toBe('URGENT')
    expect(detail.totalRecipientCount).toBe(1)

    const cnRows = confirmableRows(penaltyId)
    expect(cnRows).toEqual([String(confirmableId)])
    await expect.poll(() => appNotificationRows(confirmableId!, member.userId)).toHaveLength(1)
    expect(appNotificationRows(confirmableId, member.userId)[0]).toContain(
      `${member.userId}\t${APP_NOTIFICATION_TYPE}\tURGENT\t${APP_NOTIFICATION_SOURCE}\t${confirmableId}\t0`,
    )
    const memberNotifications = await notifications(request, member.token)
    expect(
      memberNotifications.filter(
        (row) =>
          row.notificationType === APP_NOTIFICATION_TYPE &&
          row.sourceType === APP_NOTIFICATION_SOURCE &&
          row.sourceId === confirmableId,
      ),
    ).toHaveLength(1)
    const memberNotification = memberNotifications.find(
      (row) =>
        row.notificationType === APP_NOTIFICATION_TYPE &&
        row.sourceType === APP_NOTIFICATION_SOURCE &&
        row.sourceId === confirmableId,
    )
    if (!memberNotification) throw new Error('本人のアプリ内確認通知が見つかりません')
    expect(memberNotification.actionUrl).toBe('/notifications')

    const crossScopeApply = await request.post(
      `${API}/recruitment-listings/${crossScopeListingId}/applications`,
      { headers: authHeaders(member.token), data: { participantType: 'USER' } },
    )
    expect(crossScopeApply.status(), 'GLOBALペナルティによる別TEAM募集への応募拒否').toBe(403)
    const penaltyError = (
      (await crossScopeApply.json()) as {
        error: { code: string; details: { expiresAt: string } }
      }
    ).error
    expect(penaltyError.code).toBe('RECRUITMENT_300')
    expect(new Date(penaltyError.details.expiresAt).getTime()).toBeGreaterThan(Date.now())
    expect(
      scalar(
        `SELECT DATE_FORMAT(expires_at, '%Y-%m-%d %H:%i:%s.%f') ` +
          `FROM recruitment_user_penalties WHERE id=${penaltyId}`,
      ),
      '応募拒否のexpiresAtとUTC保存の専用ペナルティ期限がマイクロ秒まで一致',
    ).toBe(mysqlUtcDateTimeForInstant(penaltyError.details.expiresAt))
    expect(
      scalar(
        `SELECT COUNT(*) FROM recruitment_participants WHERE listing_id=${crossScopeListingId} ` +
          `AND user_id=${member.userId}`,
      ),
      'ペナルティ拒否後に別スコープの応募レコードが作られていない',
    ).toBe('0')

    const outsiderNotifications = await notifications(request, outsider.token)
    expect(
      outsiderNotifications.some(
        (row) =>
          row.notificationType === APP_NOTIFICATION_TYPE &&
          row.sourceType === APP_NOTIFICATION_SOURCE &&
          row.sourceId === confirmableId,
      ),
    ).toBe(false)

    const outsiderPending = await request.get(`${API}/me/confirmable-notifications/pending`, {
      headers: authHeaders(outsider.token),
    })
    expect(outsiderPending.status()).toBe(200)
    expect(
      ((await outsiderPending.json()) as ApiEnvelope<Array<{ id: number }>>).data.some(
        (row) => row.id === recipientId,
      ),
    ).toBe(false)
    const outsiderDetail = await request.get(
      `${API}/teams/${scope.numericId}/confirmable-notifications/${confirmableId}`,
      { headers: authHeaders(outsider.token) },
    )
    expect(outsiderDetail.status(), '他アカウントの確認通知ID直指定').toBe(404)
    const outsiderConfirm = await request.post(
      `${API}/me/confirmable-notifications/${confirmableId}/confirm`,
      { headers: authHeaders(outsider.token) },
    )
    expect(outsiderConfirm.status()).toBe(404)
    expect(
      scalar(
        `SELECT is_confirmed FROM confirmable_notification_recipients ` +
          `WHERE confirmable_notification_id=${confirmableId} AND user_id=${member.userId}`,
      ),
    ).toBe('0')

    await loginViaApi(page, OUTSIDER, { apiBaseUrl: API_BASE })
    await page.goto('/notifications')
    await waitForHydration(page)
    await expect(page).toHaveURL(/\/notifications$/)
    await expect(page.getByText(memberNotification.body, { exact: true })).toHaveCount(0)

    await loginViaApi(page, MEMBER, { apiBaseUrl: API_BASE })
    await page.goto(`/market/listings/${crossScopeListingId}`)
    await waitForHydration(page)
    await expect(page.getByTestId('market-detail-page')).toBeVisible()
    const applyButton = page.getByTestId('market-apply-btn')
    await expect(applyButton, '事前条件を満たす別スコープ募集では応募ボタンが有効').toBeEnabled()
    const uiApplyResponse = page.waitForResponse(
      (response) =>
        response
          .url()
          .endsWith(`/api/v1/recruitment-listings/${crossScopeListingId}/applications`) &&
        response.request().method() === 'POST',
    )
    await applyButton.click()
    expect((await uiApplyResponse).status(), '画面からの別スコープ応募は実BEで拒否').toBe(403)
    await expect(
      page.getByText('ペナルティ期間中のため申込できません', { exact: true }),
      '応募画面にペナルティ拒否のtoastが表示される',
    ).toBeVisible()
    await expect(page.getByTestId('market-detail-page')).toBeVisible()
    expect(page.url()).toContain(`/market/listings/${crossScopeListingId}`)

    await page.goto('/notifications')
    await waitForHydration(page)
    const notificationRow = page
      .getByRole('button')
      .filter({ hasText: memberNotification.body })
      .first()
    await expect(notificationRow).toBeVisible({ timeout: 30_000 })
    await notificationRow.getByRole('button', { name: '確認する' }).click()
    await expect
      .poll(() =>
        scalar(
          `SELECT is_confirmed FROM confirmable_notification_recipients ` +
            `WHERE confirmable_notification_id=${confirmableId} AND user_id=${member.userId}`,
        ),
      )
      .toBe('1')
    await expect.poll(() => appNotificationRows(confirmableId!, member.userId)).toHaveLength(1)
    expect(appNotificationRows(confirmableId, member.userId)[0]).toContain('\t1')
    await expect(page.getByText('確認しました', { exact: true })).toBeVisible()

    const repeated = await request.post(
      `${API}/system-admin/batch/recruitment-no-show-confirm-hourly/trigger?sync=true`,
      { headers: authHeaders(systemAdmin.token) },
    )
    // e2e プロファイルは自動スケジューラと ShedLock を止めるため、実際の再実行も検証できる。
    expect([200, 409]).toContain(repeated.status())
    expect(((await repeated.json()) as ApiEnvelope<{ status: string }>).data.status).toBe(
      repeated.status() === 409 ? 'LOCKED' : 'COMPLETED',
    )
    expect(confirmableRows(penaltyId)).toEqual([String(confirmableId)])
    expect(appNotificationRows(confirmableId, member.userId)).toHaveLength(1)

    // 期限切れ後に通常応募へ戻る契約: 専用penalty行の2時刻列だけUTCで過去へ進める。
    mysql(
      `UPDATE recruitment_user_penalties SET started_at=UTC_TIMESTAMP(6) - INTERVAL 2 DAY, ` +
        `expires_at=UTC_TIMESTAMP(6) - INTERVAL 1 DAY ` +
        `WHERE id=${penaltyId} AND user_id=${member.userId} ` +
        `AND triggered_by_setting_id=${settingAppliedId}`,
    )
    expect(
      scalar(
        `SELECT IF(started_at < expires_at AND expires_at < UTC_TIMESTAMP(6), 1, 0) ` +
          `FROM recruitment_user_penalties WHERE id=${penaltyId} AND user_id=${member.userId} ` +
          `AND triggered_by_setting_id=${settingAppliedId}`,
      ),
      '専用ペナルティのstarted_at/expires_atだけが整合するUTC過去値になった',
    ).toBe('1')
    await page.goto(`/market/listings/${crossScopeListingId}`)
    await waitForHydration(page)
    await expect(page.getByTestId('market-detail-page')).toBeVisible()
    const allowedApplyButton = page.getByTestId('market-apply-btn')
    await expect(allowedApplyButton, '期限切れ後に同じ有資格会員の応募ボタンが使える').toBeEnabled()
    const allowedApplyResponse = page.waitForResponse(
      (response) =>
        response
          .url()
          .endsWith(`/api/v1/recruitment-listings/${crossScopeListingId}/applications`) &&
        response.request().method() === 'POST',
    )
    await allowedApplyButton.click()
    const allowedResponse = await allowedApplyResponse
    expect(allowedResponse.status(), '期限切れ後の応募は実BEで受理').toBe(201)
    const allowedParticipant = (
      (await allowedResponse.json()) as ApiEnvelope<{ id: number; status: string }>
    ).data
    expect(allowedParticipant.id).toBeGreaterThan(0)
    expect(['APPLIED', 'CONFIRMED']).toContain(allowedParticipant.status)
    expect(
      scalar(
        `SELECT COUNT(*) FROM recruitment_participants WHERE listing_id=${crossScopeListingId} ` +
          `AND user_id=${member.userId}`,
      ),
      '期限切れ後は専用別スコープ募集への応募が1件だけ永続化される',
    ).toBe('1')
    await expect(page.getByText('申込済み', { exact: true })).toBeVisible()
    await page.goto('/notifications')
    await waitForHydration(page)
    await expect(page.getByText(memberNotification.body, { exact: true })).toBeVisible()
  } catch (error) {
    hasScenarioFailure = true
    scenarioFailure = error
  }

  let cleanupFailure: unknown
  try {
    await cleanup(
      request,
      teamOwner.token,
      createdTeamSlugs,
      scope,
      teamScopeIds,
      fixture,
      crossScopeListingId,
      settingAppliedId,
      penaltyId,
      confirmableId,
      member.userId,
      teamOwner.userId,
    )
  } catch (error) {
    cleanupFailure = error
  }
  if (cleanupFailure !== undefined) {
    if (hasScenarioFailure) {
      throw new AggregateError(
        [scenarioFailure, cleanupFailure],
        'Wave16 scenario failed and fixture cleanup also failed; see both errors for the primary cause and cleanup response body.',
      )
    }
    throw cleanupFailure
  }
  if (hasScenarioFailure) throw scenarioFailure

  const finalUrl = new URL(page.url())
  expect(finalUrl.origin).toBe(APP_BASE)
  expect(finalUrl.pathname).toBe('/notifications')
})
