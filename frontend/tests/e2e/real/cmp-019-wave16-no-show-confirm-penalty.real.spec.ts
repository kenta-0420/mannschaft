import { execFileSync } from 'node:child_process'
import { appendFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
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
const RUN_TAG = `CMP019_W16_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
const STAGE_LEDGER_PATH = resolve(process.cwd(), '.nuxt/wave16-e2e-stage-ledger.jsonl')
let stageLedgerFailure: unknown
const CONFIRMABLE_SOURCE = 'RECRUITMENT_PENALTY'
const APP_NOTIFICATION_TYPE = 'RECRUITMENT_PENALTY_APPLIED'
const APP_NOTIFICATION_SOURCE = 'CONFIRMABLE_NOTIFICATION'

function redactDiagnosticText(value: string): string {
  return value
    .replace(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi, '[email]')
    .replace(/\bBearer\s+[^\s,;]+/gi, 'Bearer [redacted]')
    .replace(
      /(["']?(?:password|token|cookie|authorization|secret)["']?\s*[:=]\s*["']?)[^\s,"'}]+["']?/gi,
      '$1[redacted]',
    )
    .replace(/\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '[jwt]')
    .replace(/Response text:.*$/gim, 'Response text: [omitted]')
    .slice(0, 12_000)
}

function diagnosticError(error: unknown): Record<string, unknown> {
  if (error instanceof AggregateError) {
    return {
      name: error.name,
      message: redactDiagnosticText(error.message),
      errors: [...error.errors].map(diagnosticError),
      locations: error.stack
        ?.split(/\r?\n/)
        .filter((line) => /cmp-019-wave16-no-show-confirm-penalty\.real\.spec\.ts:\d+:\d+/.test(line))
        .slice(0, 8),
    }
  }
  if (error instanceof Error) {
    return {
      name: error.name,
      message: redactDiagnosticText(error.message),
      locations: error.stack
        ?.split(/\r?\n/)
        .filter((line) => /cmp-019-wave16-no-show-confirm-penalty\.real\.spec\.ts:\d+:\d+/.test(line))
        .slice(0, 8),
    }
  }
  return { name: typeof error, message: '非Error値の詳細は記録しません' }
}

function recordStage(
  scenario: 'TEAM' | 'ORGANIZATION',
  stage: string,
  owned: Record<string, unknown> = {},
  error?: unknown,
) {
  const entry = {
    recordedAt: new Date().toISOString(),
    runTag: RUN_TAG,
    scenario,
    stage,
    owned,
    ...(error === undefined ? {} : { error: diagnosticError(error) }),
  }
  try {
    mkdirSync(dirname(STAGE_LEDGER_PATH), { recursive: true })
    appendFileSync(STAGE_LEDGER_PATH, `${JSON.stringify(entry)}\n`, 'utf8')
  } catch (ledgerError) {
    stageLedgerFailure = ledgerError
  }
}

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

function isNoShowConfirmBatchLocked(): boolean {
  return Number(
    scalar(
      "SELECT COUNT(*) FROM shedlock WHERE name='recruitment-no-show-confirm-batch' " +
        'AND lock_until > UTC_TIMESTAMP(6)',
    ),
  ) > 0
}

async function waitForNoShowConfirmBatchUnlock(maxWaitMs = 180_000): Promise<boolean> {
  const deadline = Date.now() + maxWaitMs
  while (isNoShowConfirmBatchLocked() && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, Math.min(5_000, deadline - Date.now())))
  }
  return isNoShowConfirmBatchLocked()
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

async function findNotificationRow(page: Page, notificationId: number) {
  const row = page.locator(`[data-notification-id="${notificationId}"]`)
  const loadMore = page.getByRole('button', { name: '\u3082\u3063\u3068\u8aad\u3080' })
  const markAllRead = page.getByRole('button', {
    name: '\u3059\u3079\u3066\u65e2\u8aad\u306b\u3059\u308b',
  })
  const emptyState = page.getByText('\u901a\u77e5\u306f\u3042\u308a\u307e\u305b\u3093', {
    exact: true,
  })
  await expect(markAllRead).toBeVisible({ timeout: 30_000 })
  await expect
    .poll(
      async () =>
        (await row.count()) > 0 || (await loadMore.isVisible()) || (await emptyState.isVisible()),
    )
    .toBe(true)
  for (let pageIndex = 0; pageIndex < 50; pageIndex++) {
    if (await row.count()) {
      await expect(row).toBeVisible({ timeout: 30_000 })
      return row
    }
    if (!(await loadMore.isVisible())) break
    const nextPageResponse = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/notifications' &&
        url.searchParams.get('page') !== '0' &&
        response.request().method() === 'GET'
      )
    })
    await loadMore.click()
    const response = await nextPageResponse
    expect(response.status()).toBe(200)
  }
  throw new Error('対象の確認通知が通知一覧の読み込み範囲内にありません')
}

async function expectNotificationAbsentFromLoadedPages(
  page: Page,
  body: string,
  scenario: 'TEAM' | 'ORGANIZATION',
) {
  const row = page.getByRole('button').filter({ hasText: body })
  const loadMore = page.getByRole('button', { name: '\u3082\u3063\u3068\u8aad\u3080' })
  for (let pageIndex = 0; pageIndex < 50; pageIndex++) {
    await expect(row).toHaveCount(0)
    if (!(await loadMore.isVisible())) break
    recordStage(scenario, 'outsider-load-more-clicked', { pageIndex: pageIndex + 1 })
    const nextPageResponse = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/notifications' &&
        url.searchParams.get('page') !== '0' &&
        response.request().method() === 'GET'
      )
    })
    await loadMore.click()
    const response = await nextPageResponse
    expect(response.status()).toBe(200)
    recordStage(scenario, 'outsider-next-page-loaded', {
      pageIndex: pageIndex + 1,
      status: response.status(),
    })
  }
  if (await loadMore.isVisible()) {
    throw new Error('50ページを超えたため全通知の非表示を確認できません')
  }
  await expect(row).toHaveCount(0)
}

async function cleanup(
  request: APIRequestContext,
  ownerToken: string,
  createdScopes: NoShowScope[],
  scope: NoShowScope | undefined,
  ownedScopes: NoShowScope[],
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
      `'${RUN_TAG}_TEAM_A','${RUN_TAG}_TEAM_B','${RUN_TAG}_ORGANIZATION_A',` +
      `'${RUN_TAG}_ORGANIZATION_B','${RUN_TAG}_CROSS_SCOPE_LISTING')`,
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
        headers: authHeaders(ownerToken),
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
        `AND scope_type='${scope.type}' AND scope_id=${scope.numericId}`,
    )
  }
  for (const ownedScope of ownedScopes) {
    const scopeSettingIds = rows(
      `SELECT id FROM recruitment_penalty_settings WHERE scope_type='${ownedScope.type}' ` +
        `AND scope_id=${ownedScope.numericId}`,
    )
    for (const id of scopeSettingIds) {
      mysql(
        `DELETE FROM recruitment_penalty_settings WHERE id=${Number(id)} ` +
          `AND scope_type='${ownedScope.type}' AND scope_id=${ownedScope.numericId}`,
      )
    }
    mysql(
      `DELETE FROM confirmable_notification_settings WHERE scope_type='${ownedScope.type}' ` +
        `AND scope_id=${ownedScope.numericId}`,
    )
    if (userId !== undefined && ownerUserId !== undefined) {
      mysql(
        `DELETE FROM notifications WHERE notification_type IN ('JOIN_REQUEST_RECEIVED','JOIN_REQUEST_APPROVED') ` +
          `AND source_type='USER' AND scope_type='${ownedScope.type}' ` +
          `AND scope_id=${ownedScope.numericId} ` +
          `AND user_id IN (${userId},${ownerUserId})`,
      )
    }
  }
  for (const createdScope of [...createdScopes].reverse()) {
    const path = createdScope.type === 'TEAM' ? 'teams' : 'organizations'
    const deletedScope = await request.delete(`${API}/${path}/${createdScope.slug}`, {
      headers: authHeaders(ownerToken),
    })
    const deleteBody = await deletedScope.text()
    expect(
      deletedScope.status(),
      `試験スコープ ${createdScope.slug} の削除: ` +
        `status=${deletedScope.status()}, body=${deleteBody}`,
    ).toBe(204)
  }
}

test('CMP-019 Wave16: 24時間経過したNO_SHOWを確定し、GLOBALペナルティと本人確認通知を1件ずつ作成する', async ({
  page,
  browser,
  request,
}) => {
  recordStage('TEAM', 'scenario-start')
  test.setTimeout(600_000)
  test.skip(
    !MYSQL_USER || !MYSQL_PASSWORD,
    '実機DB照合には E2E_MYSQL_USER / E2E_MYSQL_PASSWORD が必要です',
  )

  const systemAdmin = await loginForNoShow(request, SYSTEM_ADMIN)
  const teamOwner = await loginForNoShow(request, TEAM_OWNER)
  const member = await loginForNoShow(request, MEMBER)
  const outsider = await loginForNoShow(request, OUTSIDER)
  const batchLocked = await waitForNoShowConfirmBatchUnlock()
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
  test.skip(batchLocked, 'NO_SHOW確定バッチが既にロック中のため専用シナリオをスキップします')

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
  const createdScopes: NoShowScope[] = []
  const ownedScopes: NoShowScope[] = []
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
    createdScopes.push({ type: 'TEAM', slug: createdSlug, numericId: 0 })
    recordStage('TEAM', 'team-api-created', { scopes: createdScopes })
    const myTeams = await request.get(`${API}/me/teams?limit=200`, {
      headers: authHeaders(teamOwner.token),
    })
    expect(myTeams.status(), '作成者のチーム一覧からID解決').toBe(200)
    const team = (
      (await myTeams.json()) as ApiEnvelope<Array<{ id: number; slug: string }>>
    ).data.find((row) => row.slug === createdSlug)
    expect(team, 'TEAM_OWNERのチーム一覧に専用チームがある').toBeTruthy()
    scope = { type: 'TEAM', slug: createdSlug, numericId: team!.id }
    ownedScopes.push(scope)
    recordStage('TEAM', 'owned-team-created', { scopes: ownedScopes })
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
    createdScopes.push({ type: 'TEAM', slug: crossTeamSlug, numericId: 0 })
    recordStage('TEAM', 'cross-scope-team-api-created', { scopes: createdScopes })
    const refreshOwnerTeams = await request.get(`${API}/me/teams?limit=200`, {
      headers: authHeaders(teamOwner.token),
    })
    expect(refreshOwnerTeams.status()).toBe(200)
    const crossTeam = (
      (await refreshOwnerTeams.json()) as ApiEnvelope<Array<{ id: number; slug: string }>>
    ).data.find((row) => row.slug === crossTeamSlug)
    expect(crossTeam, 'TEAM_OWNERが別スコープTEAMを所有する').toBeTruthy()
    crossScope = { type: 'TEAM', slug: crossTeamSlug, numericId: crossTeam!.id }
    ownedScopes.push(crossScope)
    recordStage('TEAM', 'cross-scope-team-created', { scopes: ownedScopes })
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
    recordStage('TEAM', 'cross-scope-listing-created', {
      scopes: ownedScopes,
      listingIds: [crossScopeListingId],
    })
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
    recordStage('TEAM', 'penalty-setting-created', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
    })

    fixture = await createNoShowFixture(
      request,
      scope,
      teamOwner.token,
      member.token,
      member.userId,
      RUN_TAG,
    )
    recordStage('TEAM', 'no-show-fixture-created', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
      listingIds: fixture.listingIds,
      participantIds: fixture.participantIds,
    })
    const recordIds = [
      await markNoShow(request, teamOwner.token, fixture, 0),
      await markNoShow(request, teamOwner.token, fixture, 1),
    ]
    expect(recordIds).toHaveLength(2)
    recordStage('TEAM', 'no-show-records-created', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
      listingIds: fixture.listingIds,
      noShowIds: recordIds,
    })
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
    recordStage('TEAM', 'penalty-created', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
      listingIds: fixture.listingIds,
      noShowIds: recordIds,
      penaltyId,
    })
    expect(
      scalar(
        `SELECT triggered_no_show_count FROM recruitment_user_penalties WHERE id=${penaltyId}`,
      ),
    ).toBe(String(thresholdCount))

    await expect.poll(() => confirmableRows(penaltyId!)).toHaveLength(1)
    confirmableId = Number(confirmableRows(penaltyId)[0])
    recordStage('TEAM', 'confirmable-created', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
      listingIds: fixture.listingIds,
      noShowIds: recordIds,
      penaltyId,
      confirmableId,
    })
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
    expect(outsiderDetail.status(), '他アカウントの確認通知ID直指定').toBe(403)
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

    recordStage('TEAM', 'outsider-ui-start', {
      confirmableId,
      appNotificationIds: appNotificationRows(confirmableId, member.userId).map((row) =>
        Number(row.split('\t')[0]),
      ),
    })
    const outsiderContext = await browser.newContext({
      baseURL: APP_BASE,
      locale: 'ja-JP',
      timezoneId: 'Asia/Tokyo',
    })
    try {
      const outsiderPage = await outsiderContext.newPage()
      await loginViaApi(outsiderPage, OUTSIDER, { apiBaseUrl: API_BASE })
      const outsiderListResponse = outsiderPage.waitForResponse(
        (response) =>
          response.request().method() === 'GET' &&
          new URL(response.url()).pathname.endsWith('/api/v1/notifications'),
      )
      await outsiderPage.goto('/notifications')
      await waitForHydration(outsiderPage)
      await expect(outsiderPage).toHaveURL(/\/notifications$/)
      expect((await outsiderListResponse).status(), '対象外画面の通知一覧APIが正常応答').toBe(200)
      recordStage('TEAM', 'outsider-list-api-200')
      await expect(
        outsiderPage.getByRole('button', { name: 'すべて既読にする' }),
        '通知一覧ツールバーが表示される',
      ).toBeVisible()
      recordStage('TEAM', 'outsider-list-rendered')
      if (outsiderNotifications.length > 0) {
        const firstOwnBody = outsiderNotifications[0]?.body
        expect(firstOwnBody, '対象外自身の既存通知に本文がある').toBeTruthy()
        await expect(
          outsiderPage.getByRole('button').filter({ hasText: firstOwnBody! }).first(),
        ).toBeVisible()
      } else {
        await expect(outsiderPage.getByText('通知はありません', { exact: true })).toBeVisible()
      }
      await expectNotificationAbsentFromLoadedPages(
        outsiderPage,
        memberNotification.body,
        'TEAM',
      )
      recordStage('TEAM', 'outsider-target-notification-absent')
    } finally {
      await outsiderContext.close()
    }

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

    const initialNotifications = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/notifications' &&
        url.searchParams.get('page') === '0' &&
        response.request().method() === 'GET'
      )
    })
    await page.goto('/notifications')
    await waitForHydration(page)
    expect((await initialNotifications).status()).toBe(200)
    const appNotificationId = Number(
      appNotificationRows(confirmableId!, member.userId)[0]?.split('\t')[0],
    )
    const notificationRow = await findNotificationRow(page, appNotificationId)
    const notificationContent = notificationRow.locator('.min-w-0.flex-1')
    recordStage('TEAM', 'member-notification-ui-start', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
      listingIds: fixture.listingIds,
      penaltyId,
      confirmableId,
      appNotificationId,
    })
    expect(appNotificationId).toBeGreaterThan(0)
    const readResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/v1/notifications/${appNotificationId}/read`) &&
        response.request().method() === 'POST',
    )
    await notificationRow.getByText(memberNotification.body, { exact: true }).click()
    expect((await readResponse).status()).toBe(200)
    await expect
      .poll(() => appNotificationRows(confirmableId!, member.userId)[0]?.endsWith('\t1'))
      .toBe(true)
    await expect
      .poll(() =>
        scalar(
          `SELECT is_confirmed FROM confirmable_notification_recipients ` +
            `WHERE confirmable_notification_id=${confirmableId} AND user_id=${member.userId}`,
        ),
      )
      .toBe('0')
    const confirmButton = notificationContent.getByRole('button')
    await expect(confirmButton).toBeVisible()
    await confirmButton.click()
    await expect(page.getByText('確認しました', { exact: true })).toBeVisible()
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
    await expect(
      notificationContent.getByText('\u78ba\u8a8d\u6e08\u307f', { exact: true }),
    ).toBeVisible()
    await expect(notificationContent.getByRole('button')).toHaveCount(0)

    const reloadedNotifications = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/notifications' &&
        url.searchParams.get('page') === '0' &&
        response.request().method() === 'GET'
      )
    })
    await page.reload()
    await waitForHydration(page)
    expect((await reloadedNotifications).status()).toBe(200)
    const persistedNotificationRow = await findNotificationRow(page, appNotificationId)
    const persistedNotificationContent = persistedNotificationRow.locator('.min-w-0.flex-1')
    await expect(
      persistedNotificationContent.getByText('\u78ba\u8a8d\u6e08\u307f', { exact: true }),
    ).toBeVisible()
    await expect(persistedNotificationContent.getByRole('button')).toHaveCount(0)
    recordStage('TEAM', 'member-notification-ui-confirmed', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
      listingIds: fixture.listingIds,
      penaltyId,
      confirmableId,
      appNotificationId,
    })

    const unreadResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/v1/notifications/${appNotificationId}/unread`) &&
        response.request().method() === 'POST',
    )
    await persistedNotificationRow.locator('button:has(i.pi-envelope)').click()
    expect((await unreadResponse).status()).toBe(200)
    await expect
      .poll(() => appNotificationRows(confirmableId!, member.userId)[0]?.endsWith('\t0'))
      .toBe(true)
    await expect
      .poll(() =>
        scalar(
          `SELECT is_confirmed FROM confirmable_notification_recipients ` +
            `WHERE confirmable_notification_id=${confirmableId} AND user_id=${member.userId}`,
        ),
      )
      .toBe('1')
    await expect(
      persistedNotificationContent.getByText('\u78ba\u8a8d\u6e08\u307f', { exact: true }),
    ).toBeVisible()
    await expect(persistedNotificationContent.getByRole('button')).toHaveCount(0)

    const repeated = await request.post(
      `${API}/system-admin/batch/recruitment-no-show-confirm-hourly/trigger?sync=true`,
      { headers: authHeaders(systemAdmin.token) },
    )
    // e2e プロファイルは自動スケジューラと ShedLock を止めるため、実際の再実行も検証できる。
    expect(repeated.status()).toBe(200)
    expect(((await repeated.json()) as ApiEnvelope<{ status: string }>).data.status).toBe(
      'COMPLETED',
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
    const expectedParticipantStatusLabel =
      allowedParticipant.status === 'CONFIRMED' ? '確定' : '申込済み'
    await expect(
      page
        .getByTestId('market-detail-apply-area')
        .getByText(expectedParticipantStatusLabel, { exact: true }),
    ).toBeVisible()
    await page.goto('/notifications')
    await waitForHydration(page)
    const returnedNotificationRow = await findNotificationRow(page, appNotificationId)
    await expect(
      returnedNotificationRow
        .locator('.min-w-0.flex-1')
        .getByText(memberNotification.body, { exact: true }),
    ).toBeVisible()
  } catch (error) {
    hasScenarioFailure = true
    scenarioFailure = error
    recordStage(
      'TEAM',
      'scenario-failed',
      { scopes: ownedScopes, fixtureListingIds: fixture?.listingIds, crossScopeListingId, settingId: settingAppliedId, penaltyId, confirmableId },
      error,
    )
  }

  let cleanupFailure: unknown
  try {
    recordStage('TEAM', 'cleanup-started', {
      scopes: [...createdScopes],
      ownedScopes,
      fixtureListingIds: fixture?.listingIds,
      crossScopeListingId,
      settingId: settingAppliedId,
      penaltyId,
      confirmableId,
    })
    await cleanup(
      request,
      teamOwner.token,
      createdScopes,
      scope,
      ownedScopes,
      fixture,
      crossScopeListingId,
      settingAppliedId,
      penaltyId,
      confirmableId,
      member.userId,
      teamOwner.userId,
    )
    recordStage('TEAM', 'cleanup-completed')
  } catch (error) {
    cleanupFailure = error
    recordStage(
      'TEAM',
      'cleanup-failed',
      { scopes: [...createdScopes], ownedScopes, fixtureListingIds: fixture?.listingIds, crossScopeListingId, settingId: settingAppliedId, penaltyId, confirmableId },
      error,
    )
  }
  const failures: unknown[] = []
  if (hasScenarioFailure) failures.push(scenarioFailure)
  if (cleanupFailure !== undefined) failures.push(cleanupFailure)
  if (stageLedgerFailure !== undefined) failures.push(stageLedgerFailure)
  if (failures.length === 1) throw failures[0]
  if (failures.length > 1) {
    throw new AggregateError(failures, 'Wave16のシナリオ・cleanup・診断記録で複数の失敗が発生しました。')
  }

  const finalUrl = new URL(page.url())
  expect(finalUrl.origin).toBe(APP_BASE)
  expect(finalUrl.pathname).toBe('/notifications')
})

test('CMP-019 Wave16: 専用組織のTHIS_SCOPE_ONLYで本人確認と対象外境界を実証する', async ({
  page,
  browser,
  request,
}) => {
  recordStage('ORGANIZATION', 'scenario-start')
  test.setTimeout(600_000)
  test.skip(
    !MYSQL_USER || !MYSQL_PASSWORD,
    '実DB照合には E2E_MYSQL_USER / E2E_MYSQL_PASSWORD が必要です',
  )

  const systemAdmin = await loginForNoShow(request, SYSTEM_ADMIN)
  const owner = await loginForNoShow(request, TEAM_OWNER)
  const member = await loginForNoShow(request, ORG_MEMBER)
  const outsider = await loginForNoShow(request, OUTSIDER)
  const batchLocked = await waitForNoShowConfirmBatchUnlock()
  const staleBefore = Number(
    scalar(
      'SELECT COUNT(*) FROM recruitment_no_show_records ' +
        'WHERE confirmed=FALSE AND recorded_at <= UTC_TIMESTAMP(6) - INTERVAL 24 HOUR',
    ),
  )
  test.skip(
    staleBefore > 0,
    '共有DBに既存の24時間経過NO_SHOWがあり、全体バッチへの影響を避けるためスキップします',
  )
  const activeBefore = Number(
    scalar(
      `SELECT COUNT(*) FROM recruitment_user_penalties WHERE user_id=${member.userId} ` +
        'AND lifted_at IS NULL AND expires_at > UTC_TIMESTAMP(6)',
    ),
  )
  test.skip(activeBefore > 0, '対象会員に有効なペナルティがあり専用シナリオを分離できません')
  test.skip(batchLocked, 'NO_SHOW確定バッチがロック中のためスキップします')
  const confirmedBefore = Number(
    scalar(
      `SELECT COUNT(*) FROM recruitment_no_show_records WHERE user_id=${member.userId} ` +
        'AND confirmed=TRUE AND recorded_at >= UTC_TIMESTAMP(6) - INTERVAL 180 DAY ' +
        "AND (dispute_resolution IS NULL OR dispute_resolution <> 'REVOKED')",
    ),
  )
  test.skip(
    confirmedBefore > 8,
    '対象会員の既存NO_SHOW数が閾値上限に近く試験条件を安全に作れません',
  )
  const thresholdCount = confirmedBefore + 2

  let scope: NoShowScope | undefined
  const createdScopes: NoShowScope[] = []
  const ownedScopes: NoShowScope[] = []
  let fixture: NoShowFixture | undefined
  let settingAppliedId: number | undefined
  let penaltyId: number | undefined
  let confirmableId: number | undefined
  let scenarioFailure: unknown
  let hasScenarioFailure = false
  try {
    const createOrganization = await request.post(`${API}/organizations`, {
      headers: authHeaders(owner.token),
      data: {
        name: `${RUN_TAG}_ORGANIZATION`,
        orgType: 'OTHER',
        visibility: 'PUBLIC',
      },
    })
    expect(createOrganization.status(), `専用組織の作成: ${await createOrganization.text()}`).toBe(
      201,
    )
    const createdOrg = (
      (await createOrganization.json()) as ApiEnvelope<{
        numericId: number
        slug: string
      }>
    ).data
    scope = {
      type: 'ORGANIZATION',
      slug: createdOrg.slug,
      numericId: createdOrg.numericId,
    }
    createdScopes.push(scope)
    ownedScopes.push(scope)
    recordStage('ORGANIZATION', 'owned-organization-created', { scopes: ownedScopes })

    expect(
      Number(
        scalar(
          `SELECT COUNT(*) FROM recruitment_penalty_settings ` +
            `WHERE scope_type='ORGANIZATION' AND scope_id=${scope.numericId}`,
        ),
      ),
      '専用組織に既存設定がないこと（既存設定は変更しない）',
    ).toBe(0)
    const setting = await request.put(
      `${API}/scopes/ORGANIZATION/${scope.numericId}/penalty-settings`,
      {
        headers: authHeaders(owner.token),
        data: {
          isEnabled: true,
          thresholdCount,
          thresholdPeriodDays: 180,
          penaltyDurationDays: 30,
          applyScope: 'THIS_SCOPE_ONLY',
          autoNoShowDetection: false,
          disputeAllowedDays: 14,
        },
      },
    )
    expect(setting.status(), `組織専用設定: ${await setting.text()}`).toBe(200)
    settingAppliedId = ((await setting.json()) as ApiEnvelope<{ id: number }>).data.id
    recordStage('ORGANIZATION', 'penalty-setting-created', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
    })

    fixture = await createNoShowFixture(
      request,
      scope,
      owner.token,
      member.token,
      member.userId,
      RUN_TAG,
    )
    recordStage('ORGANIZATION', 'no-show-fixture-created', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
      listingIds: fixture.listingIds,
      participantIds: fixture.participantIds,
    })
    const recordIds = [
      await markNoShow(request, owner.token, fixture, 0),
      await markNoShow(request, owner.token, fixture, 1),
    ]
    const recordIdList = recordIds.join(',')
    recordStage('ORGANIZATION', 'no-show-records-created', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
      listingIds: fixture.listingIds,
      noShowIds: recordIds,
    })
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
      '組織募集由来の2件を24時間経過状態にする',
    ).toBe(2)

    const trigger = await request.post(
      `${API}/system-admin/batch/recruitment-no-show-confirm-hourly/trigger?sync=true`,
      { headers: authHeaders(systemAdmin.token) },
    )
    expect(trigger.status(), `24時間確定バッチ: ${await trigger.text()}`).toBe(200)
    expect(((await trigger.json()) as ApiEnvelope<{ status: string }>).data.status).toBe(
      'COMPLETED',
    )
    expect(
      Number(
        scalar(
          `SELECT COUNT(*) FROM recruitment_no_show_records WHERE id IN (${recordIdList}) ` +
            'AND confirmed=TRUE',
        ),
      ),
    ).toBe(2)

    const penalties = rows(
      `SELECT id FROM recruitment_user_penalties WHERE user_id=${member.userId} ` +
        `AND triggered_by_setting_id=${settingAppliedId} ` +
        `AND scope_type='ORGANIZATION' AND scope_id=${scope.numericId} ` +
        'AND lifted_at IS NULL AND expires_at > UTC_TIMESTAMP(6)',
    )
    expect(penalties, '組織スコープのペナルティが1件だけ作成される').toHaveLength(1)
    penaltyId = Number(penalties[0])
    recordStage('ORGANIZATION', 'penalty-created', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
      listingIds: fixture.listingIds,
      noShowIds: recordIds,
      penaltyId,
    })
    expect(
      scalar(
        `SELECT triggered_no_show_count FROM recruitment_user_penalties WHERE id=${penaltyId}`,
      ),
    ).toBe(String(thresholdCount))
    await expect.poll(() => confirmableRows(penaltyId!)).toHaveLength(1)
    confirmableId = Number(confirmableRows(penaltyId)[0])
    recordStage('ORGANIZATION', 'confirmable-created', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
      listingIds: fixture.listingIds,
      noShowIds: recordIds,
      penaltyId,
      confirmableId,
    })

    const pendingResponse = await request.get(`${API}/me/confirmable-notifications/pending`, {
      headers: authHeaders(member.token),
    })
    expect(pendingResponse.status(), '本人の確認待ち一覧').toBe(200)
    const recipientId = Number(
      scalar(
        `SELECT id FROM confirmable_notification_recipients ` +
          `WHERE confirmable_notification_id=${confirmableId} AND user_id=${member.userId}`,
      ),
    )
    const pending = (
      (await pendingResponse.json()) as ApiEnvelope<Array<{ id: number; isConfirmed: boolean }>>
    ).data
    expect(pending).toEqual(
      expect.arrayContaining([expect.objectContaining({ id: recipientId, isConfirmed: false })]),
    )
    const organizationDetail = await request.get(
      `${API}/organizations/${scope.numericId}/confirmable-notifications/${confirmableId}`,
      { headers: authHeaders(owner.token) },
    )
    expect(
      organizationDetail.status(),
      `組織管理者による専用通知の確認: ${await organizationDetail.text()}`,
    ).toBe(200)
    const detail = ((await organizationDetail.json()) as ApiEnvelope<ConfirmableNotification>).data
    expect(detail.scopeType).toBe('ORGANIZATION')
    expect(detail.scopeId).toBe(scope.numericId)
    expect(detail.priority).toBe('URGENT')
    expect(detail.totalRecipientCount).toBe(1)
    expect(confirmableRows(penaltyId), 'RECRUITMENT_PENALTY由来の確認通知が重複しない').toEqual([
      String(confirmableId),
    ])
    await expect.poll(() => appNotificationRows(confirmableId!, member.userId)).toHaveLength(1)
    expect(appNotificationRows(confirmableId, member.userId)[0]).toContain(
      `${member.userId}\t${APP_NOTIFICATION_TYPE}\tURGENT\t${APP_NOTIFICATION_SOURCE}\t${confirmableId}\t0`,
    )

    const memberNotifications = await notifications(request, member.token)
    const memberNotification = memberNotifications.find(
      (row) =>
        row.notificationType === APP_NOTIFICATION_TYPE &&
        row.sourceType === APP_NOTIFICATION_SOURCE &&
        row.sourceId === confirmableId,
    )
    expect(memberNotification, '本人の通知一覧にURGENT通知が1件表示される').toBeTruthy()
    expect(
      memberNotifications.filter(
        (row) =>
          row.notificationType === APP_NOTIFICATION_TYPE &&
          row.sourceType === APP_NOTIFICATION_SOURCE &&
          row.sourceId === confirmableId,
      ),
    ).toHaveLength(1)

    const outsiderNotifications = await notifications(request, outsider.token)
    expect(
      outsiderNotifications.some(
        (row) =>
          row.notificationType === APP_NOTIFICATION_TYPE &&
          row.sourceType === APP_NOTIFICATION_SOURCE &&
          row.sourceId === confirmableId,
      ),
      '対象外会員の通知一覧に専用通知が出ない',
    ).toBe(false)
    const outsiderDetail = await request.get(
      `${API}/organizations/${scope.numericId}/confirmable-notifications/${confirmableId}`,
      { headers: authHeaders(outsider.token) },
    )
    expect(outsiderDetail.status(), '対象外会員による通知ID直指定').toBe(403)
    const outsiderConfirm = await request.post(
      `${API}/me/confirmable-notifications/${confirmableId}/confirm`,
      { headers: authHeaders(outsider.token) },
    )
    expect(outsiderConfirm.status(), '対象外会員による確認API直指定').toBe(404)
    expect(
      scalar(
        `SELECT is_confirmed FROM confirmable_notification_recipients ` +
          `WHERE confirmable_notification_id=${confirmableId} AND user_id=${member.userId}`,
      ),
    ).toBe('0')

    recordStage('ORGANIZATION', 'outsider-ui-start', {
      confirmableId,
      appNotificationIds: appNotificationRows(confirmableId, member.userId).map((row) =>
        Number(row.split('\t')[0]),
      ),
    })
    const outsiderContext = await browser.newContext({
      baseURL: APP_BASE,
      locale: 'ja-JP',
      timezoneId: 'Asia/Tokyo',
    })
    try {
      const outsiderPage = await outsiderContext.newPage()
      await loginViaApi(outsiderPage, OUTSIDER, { apiBaseUrl: API_BASE })
      const outsiderListResponse = outsiderPage.waitForResponse(
        (response) =>
          response.request().method() === 'GET' &&
          new URL(response.url()).pathname.endsWith('/api/v1/notifications'),
      )
      await outsiderPage.goto('/notifications')
      await waitForHydration(outsiderPage)
      await expect(outsiderPage).toHaveURL(/\/notifications$/)
      expect((await outsiderListResponse).status(), '対象外画面の通知一覧APIが正常応答').toBe(200)
      recordStage('ORGANIZATION', 'outsider-list-api-200')
      await expect(
        outsiderPage.getByRole('button', { name: 'すべて既読にする' }),
        '通知一覧ツールバーが表示される',
      ).toBeVisible()
      recordStage('ORGANIZATION', 'outsider-list-rendered')
      if (outsiderNotifications.length > 0) {
        const firstOwnBody = outsiderNotifications[0]?.body
        expect(firstOwnBody, '対象外自身の既存通知に本文がある').toBeTruthy()
        await expect(
          outsiderPage.getByRole('button').filter({ hasText: firstOwnBody! }).first(),
        ).toBeVisible()
      } else {
        await expect(outsiderPage.getByText('通知はありません', { exact: true })).toBeVisible()
      }
      if (memberNotification) {
        await expectNotificationAbsentFromLoadedPages(
          outsiderPage,
          memberNotification.body,
          'ORGANIZATION',
        )
      }
      recordStage('ORGANIZATION', 'outsider-target-notification-absent')
    } finally {
      await outsiderContext.close()
    }

    await loginViaApi(page, ORG_MEMBER, { apiBaseUrl: API_BASE })
    const initialNotifications = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/notifications' &&
        url.searchParams.get('page') === '0' &&
        response.request().method() === 'GET'
      )
    })
    await page.goto('/notifications')
    await waitForHydration(page)
    expect((await initialNotifications).status()).toBe(200)
    if (!memberNotification) throw new Error('??????????')
    const appNotificationId = Number(
      appNotificationRows(confirmableId!, member.userId)[0]?.split('\t')[0],
    )
    const notificationRow = await findNotificationRow(page, appNotificationId)
    const notificationContent = notificationRow.locator('.min-w-0.flex-1')
    recordStage('ORGANIZATION', 'member-notification-ui-start', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
      listingIds: fixture.listingIds,
      penaltyId,
      confirmableId,
      appNotificationId,
    })
    expect(appNotificationId).toBeGreaterThan(0)
    const readResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/v1/notifications/${appNotificationId}/read`) &&
        response.request().method() === 'POST',
    )
    await notificationRow.getByText(memberNotification.body, { exact: true }).click()
    expect((await readResponse).status()).toBe(200)
    await expect
      .poll(() => appNotificationRows(confirmableId!, member.userId)[0]?.endsWith('\t1'))
      .toBe(true)
    await expect
      .poll(() =>
        scalar(
          `SELECT is_confirmed FROM confirmable_notification_recipients ` +
            `WHERE confirmable_notification_id=${confirmableId} AND user_id=${member.userId}`,
        ),
      )
      .toBe('0')
    const confirmButton = notificationContent.getByRole('button')
    await expect(confirmButton).toBeVisible()
    await confirmButton.click()
    await expect(page.getByText('確認しました', { exact: true })).toBeVisible()
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
    await expect(
      notificationContent.getByText('\u78ba\u8a8d\u6e08\u307f', { exact: true }),
    ).toBeVisible()
    await expect(notificationContent.getByRole('button')).toHaveCount(0)

    const reloadedNotifications = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/notifications' &&
        url.searchParams.get('page') === '0' &&
        response.request().method() === 'GET'
      )
    })
    await page.reload()
    await waitForHydration(page)
    expect((await reloadedNotifications).status()).toBe(200)
    const persistedNotificationRow = await findNotificationRow(page, appNotificationId)
    const persistedNotificationContent = persistedNotificationRow.locator('.min-w-0.flex-1')
    await expect(
      persistedNotificationContent.getByText('\u78ba\u8a8d\u6e08\u307f', { exact: true }),
    ).toBeVisible()
    await expect(persistedNotificationContent.getByRole('button')).toHaveCount(0)
    recordStage('ORGANIZATION', 'member-notification-ui-confirmed', {
      scopes: ownedScopes,
      settingId: settingAppliedId,
      listingIds: fixture.listingIds,
      penaltyId,
      confirmableId,
      appNotificationId,
    })

    const unreadResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/v1/notifications/${appNotificationId}/unread`) &&
        response.request().method() === 'POST',
    )
    await persistedNotificationRow.locator('button:has(i.pi-envelope)').click()
    expect((await unreadResponse).status()).toBe(200)
    await expect
      .poll(() => appNotificationRows(confirmableId!, member.userId)[0]?.endsWith('\t0'))
      .toBe(true)
    await expect
      .poll(() =>
        scalar(
          `SELECT is_confirmed FROM confirmable_notification_recipients ` +
            `WHERE confirmable_notification_id=${confirmableId} AND user_id=${member.userId}`,
        ),
      )
      .toBe('1')
    await expect(
      persistedNotificationContent.getByText('\u78ba\u8a8d\u6e08\u307f', { exact: true }),
    ).toBeVisible()
    await expect(persistedNotificationContent.getByRole('button')).toHaveCount(0)

    const repeated = await request.post(
      `${API}/system-admin/batch/recruitment-no-show-confirm-hourly/trigger?sync=true`,
      { headers: authHeaders(systemAdmin.token) },
    )
    expect(repeated.status()).toBe(200)
    expect(((await repeated.json()) as ApiEnvelope<{ status: string }>).data.status).toBe(
      'COMPLETED',
    )
    expect(confirmableRows(penaltyId)).toEqual([String(confirmableId)])
    expect(appNotificationRows(confirmableId, member.userId)).toHaveLength(1)
  } catch (error) {
    hasScenarioFailure = true
    scenarioFailure = error
    recordStage(
      'ORGANIZATION',
      'scenario-failed',
      { scopes: ownedScopes, fixtureListingIds: fixture?.listingIds, settingId: settingAppliedId, penaltyId, confirmableId },
      error,
    )
  }

  let cleanupFailure: unknown
  try {
    recordStage('ORGANIZATION', 'cleanup-started', {
      scopes: [...createdScopes],
      ownedScopes,
      fixtureListingIds: fixture?.listingIds,
      settingId: settingAppliedId,
      penaltyId,
      confirmableId,
    })
    await cleanup(
      request,
      owner.token,
      createdScopes,
      scope,
      ownedScopes,
      fixture,
      undefined,
      settingAppliedId,
      penaltyId,
      confirmableId,
      member.userId,
      owner.userId,
    )
    recordStage('ORGANIZATION', 'cleanup-completed')
  } catch (error) {
    cleanupFailure = error
    recordStage(
      'ORGANIZATION',
      'cleanup-failed',
      { scopes: [...createdScopes], ownedScopes, fixtureListingIds: fixture?.listingIds, settingId: settingAppliedId, penaltyId, confirmableId },
      error,
    )
  }
  const failures: unknown[] = []
  if (hasScenarioFailure) failures.push(scenarioFailure)
  if (cleanupFailure !== undefined) failures.push(cleanupFailure)
  if (stageLedgerFailure !== undefined) failures.push(stageLedgerFailure)
  if (failures.length === 1) throw failures[0]
  if (failures.length > 1) {
    throw new AggregateError(failures, 'Wave16のシナリオ・cleanup・診断記録で複数の失敗が発生しました。')
  }

  const finalUrl = new URL(page.url())
  expect(finalUrl.origin).toBe(APP_BASE)
  expect(finalUrl.pathname).toBe('/notifications')
})
