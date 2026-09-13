/**
 * #3253 Schedule media の更新認可を実HTTPで検証するローカル専用spec。
 * AWS/R2 本番環境には接続しない。API_BASE_URL はローカルBE、upload-url はローカルMinIO前提。
 */
import { test, expect, type APIRequestContext } from '@playwright/test'

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const TEAM_SLUG = 'fc-u-18'
const PASSWORD = 'TestPass2026!'
const ADMIN = 'e2e-admin@test.mannschaft.local'
const MEMBER = 'e2e-user@test.mannschaft.local'
const OUTSIDER = 'e2e-dummy-6@test.mannschaft.local'

type ApiBody<T> = { data: T }
type Schedule = { id: number }
type Media = { id: number; caption: string | null }

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
  let adminToken = ''
  let memberToken = ''
  let outsiderToken = ''
  let scheduleId: number | undefined
  let mediaId: number | undefined

  test.beforeAll(async ({ playwright }) => {
    // single-session 制約を回避するため、操作者ごとに独立した APIRequestContext を使う。
    adminCtx = await playwright.request.newContext()
    memberCtx = await playwright.request.newContext()
    outsiderCtx = await playwright.request.newContext()
    adminToken = await login(adminCtx, ADMIN)
    memberToken = await login(memberCtx, MEMBER)
    outsiderToken = await login(outsiderCtx, OUTSIDER)

    const stamp = Date.now()
    const create = await api(adminCtx, adminToken, 'POST', `/api/v1/teams/${TEAM_SLUG}/schedules`, {
      title: `CMP019-media-authz-${stamp}`,
      startAt: '2028-12-15T10:00:00',
      endAt: '2028-12-15T11:00:00',
      allDay: false,
    })
    expect(create.ok(), `使い捨てTEAM予定作成: ${create.status()} ${await create.text()}`).toBeTruthy()
    scheduleId = ((await create.json()) as ApiBody<Schedule>).data.id

    // upload-url の発行時に media row が作られる。実バイトのPUTは行わず、外部ストレージに触れない。
    const upload = await api(memberCtx, memberToken, 'POST', `/api/v1/schedules/${scheduleId}/media/upload-url`, {
      mediaType: 'IMAGE',
      contentType: 'image/jpeg',
      fileSize: 1,
      fileName: `cmp019-${stamp}.jpg`,
    })
    expect(upload.ok(), `MEMBERのmedia fixture作成: ${upload.status()} ${await upload.text()}`).toBeTruthy()
    mediaId = ((await upload.json()) as ApiBody<{ mediaId: number }>).data.mediaId
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
    await Promise.all([adminCtx?.dispose(), memberCtx?.dispose(), outsiderCtx?.dispose()])
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
})
