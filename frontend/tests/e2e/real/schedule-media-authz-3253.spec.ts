/**
 * #3253 Schedule media の更新認可を実HTTPで検証するローカル専用spec。
 * AWS/R2 本番環境には接続しない。API_BASE_URL はローカルBE、upload-url はローカルMinIO前提。
 */
import { test, expect, type APIRequestContext } from '@playwright/test'

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const TEAM_SLUG = 'fc-u-18'
const PASSWORD = 'TestPass2026!'
// SYSTEM_ADMIN を兼ねる e2e-admin ではなく、fc-u-18 の純粋な TEAM ADMIN を使う。
const ADMIN = 'e2e-dummy-1@test.mannschaft.local'
const MEMBER = 'e2e-user@test.mannschaft.local'
const OUTSIDER = 'e2e-outsider@test.mannschaft.local'

type ApiBody<T> = { data: T }
type Schedule = { id: number }
type Media = { id: string; caption: string | null }

const UUID_V7_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

async function login(ctx: APIRequestContext, email: string): Promise<string> {
  const res = await ctx.post(`${API_BASE}/api/v1/auth/login`, { data: { email, password: PASSWORD } })
  expect(res.ok(), `${email} のログイン: ${res.status()} ${await res.text()}`).toBeTruthy()
  return ((await res.json()) as ApiBody<{ accessToken: string }>).data.accessToken
}

async function api(
  ctx: APIRequestContext,
  token: string,
  method: 'GET' | 'POST' | 'PATCH' | 'DELETE',
  path: string,
  data?: unknown,
) {
  return ctx.fetch(`${API_BASE}${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data,
  })
}

test.describe('#3253 Schedule media PATCH authorization (local MinIO)', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(90_000)

  let adminCtx: APIRequestContext
  let memberCtx: APIRequestContext
  let outsiderCtx: APIRequestContext
  let anonymousCtx: APIRequestContext
  let adminToken = ''
  let memberToken = ''
  let outsiderToken = ''
  let scheduleId: number | undefined
  let mediaId: string | undefined

  test.beforeAll(async ({ playwright }) => {
    // single-session 制約を回避するため、操作者ごとに独立した APIRequestContext を使う。
    adminCtx = await playwright.request.newContext()
    memberCtx = await playwright.request.newContext()
    outsiderCtx = await playwright.request.newContext()
    anonymousCtx = await playwright.request.newContext()
    adminToken = await login(adminCtx, ADMIN)
    memberToken = await login(memberCtx, MEMBER)
    outsiderToken = await login(outsiderCtx, OUTSIDER)

    const stamp = Date.now()
    const create = await api(adminCtx, adminToken, 'POST', `/api/v1/teams/${TEAM_SLUG}/schedules`, {
      title: `CMP019-media-authz-${stamp}`,
      startAt: '2028-12-15T10:00:00+09:00',
      endAt: '2028-12-15T11:00:00+09:00',
      allDay: false,
      eventType: 'OTHER',
      attendanceRequired: false,
    })
    expect(create.ok(), `使い捨てTEAM予定作成: ${create.status()} ${await create.text()}`).toBeTruthy()
    scheduleId = ((await create.json()) as ApiBody<Schedule>).data.id

    // upload-url の発行時に media row が作られる。PATCH はオブジェクト存在を検証するため、
    // ローカル MinIO へ最小の実バイトを PUT して本番同等の状態にする。
    const upload = await api(memberCtx, memberToken, 'POST', `/api/v1/schedules/${scheduleId}/media/upload-url`, {
      mediaType: 'IMAGE',
      contentType: 'image/jpeg',
      fileSize: 1,
      fileName: `cmp019-${stamp}.jpg`,
    })
    expect(upload.ok(), `MEMBERのmedia fixture作成: ${upload.status()} ${await upload.text()}`).toBeTruthy()
    const uploadData = ((await upload.json()) as ApiBody<{ mediaId: string; uploadUrl: string }>).data
    mediaId = uploadData.mediaId
    expect(mediaId, 'upload-url レスポンスの mediaId は UUIDv7').toMatch(UUID_V7_PATTERN)
    const put = await memberCtx.put(uploadData.uploadUrl, {
      headers: { 'Content-Type': 'image/jpeg' },
      data: Buffer.from([0xff]),
    })
    expect(put.ok(), `ローカルMinIOへのfixture PUT: ${put.status()} ${await put.text()}`).toBeTruthy()
    const complete = await api(memberCtx, memberToken, 'POST',
      `/api/v1/schedules/${scheduleId}/media/${mediaId}/complete`)
    expect(complete.ok(), `MEMBERのmedia fixture確定: ${complete.status()} ${await complete.text()}`).toBeTruthy()
  })

  test.afterAll(async () => {
    // 失敗途中でもローカルMinIOのオブジェクトと使い捨て予定を最善努力で後始末する。
    if (scheduleId && mediaId) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末失敗で本体の検証結果を上書きしない
      await api(adminCtx, adminToken, 'DELETE', `/api/v1/schedules/${scheduleId}/media/${mediaId}`).catch(() => {})
    }
    if (scheduleId) {
      // eslint-disable-next-line no-restricted-syntax -- 後始末失敗で本体の検証結果を上書きしない
      await api(adminCtx, adminToken, 'DELETE', `/api/v1/teams/${TEAM_SLUG}/schedules/${scheduleId}`).catch(() => {})
    }
    await Promise.all([adminCtx?.dispose(), memberCtx?.dispose(), outsiderCtx?.dispose(), anonymousCtx?.dispose()])
  })

  test('MEMBER本人とTEAM ADMINはPATCHでき、外部ユーザーは403かつ値不変', async () => {
    expect(scheduleId).toBeTruthy()
    expect(mediaId).toBeTruthy()

    const memberCaption = 'member-owned-caption'
    const memberPatch = await api(memberCtx, memberToken, 'PATCH', `/api/v1/schedules/${scheduleId}/media/${mediaId}`, {
      caption: memberCaption,
    })
    expect(memberPatch.ok(), `MEMBER本人PATCH: ${memberPatch.status()} ${await memberPatch.text()}`).toBeTruthy()

    const adminCaption = 'team-admin-caption'
    const adminPatch = await api(adminCtx, adminToken, 'PATCH', `/api/v1/schedules/${scheduleId}/media/${mediaId}`, {
      caption: adminCaption,
    })
    expect(adminPatch.ok(), `TEAM ADMIN PATCH: ${adminPatch.status()} ${await adminPatch.text()}`).toBeTruthy()

    const deniedCaption = 'outsider-must-not-write'
    const outsiderPatch = await api(outsiderCtx, outsiderToken, 'PATCH', `/api/v1/schedules/${scheduleId}/media/${mediaId}`, {
      caption: deniedCaption,
    })
    expect(outsiderPatch.status(), `外部ユーザーPATCHは403: ${await outsiderPatch.text()}`).toBe(403)

    // GETで保存済み値を再取得し、拒否リクエストがDB状態を変えていないことを実HTTPで確認する。
    const list = await api(adminCtx, adminToken, 'GET', `/api/v1/schedules/${scheduleId}/media`)
    expect(list.ok(), `media再取得: ${list.status()} ${await list.text()}`).toBeTruthy()
    const media = ((await list.json()) as ApiBody<{ items: Media[] }>).data.items.find((item) => item.id === mediaId)
    expect(media, '作成済みmediaが再取得できること').toBeTruthy()
    expect(media?.caption, '403後も管理者が保存したcaptionから変化しないこと').toBe(adminCaption)
  })

  test('未認証401・不正UUID400・別予定と不存在404で既存mediaを変更しない', async () => {
    expect(scheduleId).toBeTruthy()
    expect(mediaId).toBeTruthy()
    const validScheduleId = scheduleId as number
    const validMediaId = mediaId as string
    const invalidUuid = 'not-a-uuid'
    const missingUuid = '019954cc-1a40-7000-8000-ffffffffffff'
    const wrongScheduleId = validScheduleId + 999_999

    const anonymousPatch = await anonymousCtx.patch(
      `${API_BASE}/api/v1/schedules/${validScheduleId}/media/${validMediaId}`,
      { data: { caption: 'anonymous-must-not-write' } },
    )
    const anonymousDelete = await anonymousCtx.delete(
      `${API_BASE}/api/v1/schedules/${validScheduleId}/media/${validMediaId}`,
    )
    const anonymousComplete = await anonymousCtx.post(
      `${API_BASE}/api/v1/schedules/${validScheduleId}/media/${validMediaId}/complete`,
    )
    expect(anonymousPatch.status()).toBe(401)
    expect(anonymousDelete.status()).toBe(401)
    expect(anonymousComplete.status()).toBe(401)

    const invalidPatch = await api(memberCtx, memberToken, 'PATCH',
      `/api/v1/schedules/${validScheduleId}/media/${invalidUuid}`, { caption: 'invalid-must-not-write' })
    const invalidDelete = await api(memberCtx, memberToken, 'DELETE',
      `/api/v1/schedules/${validScheduleId}/media/${invalidUuid}`)
    const invalidComplete = await api(memberCtx, memberToken, 'POST',
      `/api/v1/schedules/${validScheduleId}/media/${invalidUuid}/complete`)
    expect(invalidPatch.status()).toBe(400)
    expect(invalidDelete.status()).toBe(400)
    expect(invalidComplete.status()).toBe(400)

    const wrongSchedulePatch = await api(memberCtx, memberToken, 'PATCH',
      `/api/v1/schedules/${wrongScheduleId}/media/${validMediaId}`, { caption: 'wrong-schedule' })
    const missingDelete = await api(memberCtx, memberToken, 'DELETE',
      `/api/v1/schedules/${validScheduleId}/media/${missingUuid}`)
    const wrongScheduleComplete = await api(memberCtx, memberToken, 'POST',
      `/api/v1/schedules/${wrongScheduleId}/media/${validMediaId}/complete`)
    expect(wrongSchedulePatch.status()).toBe(404)
    expect(missingDelete.status()).toBe(404)
    expect(wrongScheduleComplete.status()).toBe(404)

    const list = await api(adminCtx, adminToken, 'GET', `/api/v1/schedules/${validScheduleId}/media`)
    expect(list.ok(), `拒否後のmedia再取得: ${list.status()} ${await list.text()}`).toBeTruthy()
    const media = ((await list.json()) as ApiBody<{ items: Media[] }>).data.items
      .find((item) => item.id === validMediaId)
    expect(media, '拒否されたcomplete・PATCH・DELETE後もmediaが残ること').toBeTruthy()
    expect(media?.caption).toBe('team-admin-caption')
  })
})
