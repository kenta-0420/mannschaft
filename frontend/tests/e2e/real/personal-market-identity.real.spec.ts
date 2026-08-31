/**
 * PERSONAL 市札の公開範囲・表示名・PII 抑制を、実 BE / 実 DB / 実ブラウザで検証する。
 * page.route 等のモックは使わない。
 */
import {
  expect,
  request as pwRequest,
  test,
  type APIRequestContext,
  type Browser,
  type Page,
} from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const API = `${BE}/api/v1`
const FRONTEND = process.env.BASE_URL ?? 'http://localhost:8081'
const PASSWORD = 'TestPass2026!'
const OWNER_EMAIL = 'e2e-user@test.mannschaft.local'
const MEMBER_EMAIL = 'e2e-admin@test.mannschaft.local'
const OUTSIDER_EMAIL = 'e2e-outsider@test.mannschaft.local'
const TEAM_ID = 12
const CATEGORY_ID = 9

interface Session {
  token: string
  userId: number
}

interface MarketDetail {
  id: number
  title: string
  owner: {
    scopeType: string
    scopeId?: number
    displayName: string
  }
}

let api: APIRequestContext
let owner: Session
let member: Session
let outsider: Session
let publicListingId: number
let selectedListingId: number

function headers(session: Session): Record<string, string> {
  return { Authorization: `Bearer ${session.token}` }
}

async function loginApi(email: string): Promise<Session> {
  const response = await api.post(`${API}/auth/login`, {
    data: { email, password: PASSWORD },
  })
  expect(response.status(), `${email} の API ログイン`).toBe(200)
  const body = (await response.json()) as {
    data: { accessToken: string, userId: number }
  }
  return { token: body.data.accessToken, userId: body.data.userId }
}

async function loginUi(page: Page, email: string): Promise<void> {
  await page.goto('/login')
  await waitForHydration(page)
  await page.locator('#email').fill(email)
  await page.locator('input[type="password"]').fill(PASSWORD)
  await page.getByRole('button', { name: 'ログイン' }).click()
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 30_000 })
}

function listingBody(title: string, visibility: 'PUBLIC' | 'SELECTED_SCOPES') {
  return {
    categoryId: CATEGORY_ID,
    title,
    description: '実機検分用の個人札です',
    participationType: 'INDIVIDUAL',
    startAt: '2027-02-15T09:00:00',
    endAt: '2027-02-15T12:00:00',
    applicationDeadline: '2027-02-13T23:59:59',
    autoCancelAt: '2027-02-13T23:59:59',
    capacity: 10,
    minCapacity: 2,
    paymentEnabled: false,
    price: null,
    visibility,
    location: '東京都千代田区',
    audienceScopes: visibility === 'SELECTED_SCOPES'
      ? [{ scopeType: 'TEAM', scopeId: TEAM_ID }]
      : [],
    payeeKind: null,
    payeeUserId: null,
  }
}

async function createAndPublish(title: string, visibility: 'PUBLIC' | 'SELECTED_SCOPES') {
  const createdResponse = await api.post(`${API}/me/market/listings`, {
    headers: headers(owner),
    data: listingBody(title, visibility),
  })
  expect(createdResponse.status(), `${visibility} 個人札の作成`).toBe(201)
  const created = (await createdResponse.json()) as { data: { id: number, status: string } }
  expect(created.data.status).toBe('DRAFT')

  const publishedResponse = await api.post(
    `${API}/me/market/listings/${created.data.id}/publish`,
    { headers: headers(owner) },
  )
  expect(publishedResponse.status(), `${visibility} 個人札の公開`).toBe(200)
  return created.data.id
}

async function marketDetail(id: number, session?: Session) {
  return api.get(`${API}/public/market/listings/${id}`, {
    headers: session ? headers(session) : undefined,
  })
}

async function assertBrowserOwnerName(
  browser: Browser,
  listingId: number,
  expectedName: string,
  email?: string,
): Promise<void> {
  const context = await browser.newContext({
    baseURL: FRONTEND,
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
  })
  const page = await context.newPage()
  try {
    if (email) await loginUi(page, email)
    await page.goto(`/market/listings/${listingId}`)
    await expect(page.getByTestId('market-detail-organizer-name')).toHaveText(expectedName, {
      timeout: 20_000,
    })
  } finally {
    await context.close()
  }
}

test.beforeAll(async () => {
  api = await pwRequest.newContext()
  owner = await loginApi(OWNER_EMAIL)
  member = await loginApi(MEMBER_EMAIL)
  outsider = await loginApi(OUTSIDER_EMAIL)

  const profileResponse = await api.patch(`${API}/users/me/public-profile`, {
    headers: headers(owner),
    data: { publicProfileEnabled: true },
  })
  expect(profileResponse.status(), '札主の公開プロフィールを有効化').toBe(200)

  const suffix = Date.now()
  publicListingId = await createAndPublish(`E2E-PERSONAL-PUBLIC-${suffix}`, 'PUBLIC')
  selectedListingId = await createAndPublish(
    `E2E-PERSONAL-SELECTED-${suffix}`,
    'SELECTED_SCOPES',
  )
})

test.afterAll(async () => {
  if (api) await api.dispose()
})

test('PMI-001: PUBLIC は同所属に実名、匿名・所属外にニックネームで表示する', async ({ browser }) => {
  const anonymousResponse = await marketDetail(publicListingId)
  const memberResponse = await marketDetail(publicListingId, member)
  const outsiderResponse = await marketDetail(publicListingId, outsider)
  expect(anonymousResponse.status()).toBe(200)
  expect(memberResponse.status()).toBe(200)
  expect(outsiderResponse.status()).toBe(200)

  const anonymous = (await anonymousResponse.json()) as { data: MarketDetail }
  const sameTeam = (await memberResponse.json()) as { data: MarketDetail }
  const outside = (await outsiderResponse.json()) as { data: MarketDetail }
  expect(sameTeam.data.owner.displayName).not.toBe(anonymous.data.owner.displayName)
  expect(outside.data.owner.displayName).toBe(anonymous.data.owner.displayName)

  await assertBrowserOwnerName(browser, publicListingId, anonymous.data.owner.displayName)
  await assertBrowserOwnerName(browser, publicListingId, sameTeam.data.owner.displayName, MEMBER_EMAIL)
  await assertBrowserOwnerName(browser, publicListingId, outside.data.owner.displayName, OUTSIDER_EMAIL)
})

test('PMI-002: SELECTED_SCOPES は選択先の現所属者だけが閲覧できる', async () => {
  const sameTeam = await marketDetail(selectedListingId, member)
  const outside = await marketDetail(selectedListingId, outsider)
  const anonymous = await marketDetail(selectedListingId)
  expect(sameTeam.status()).toBe(200)
  expect(outside.status()).toBe(404)
  expect(anonymous.status()).toBe(404)
})

test('PMI-003: 公開 DTO と旧汎用詳細の双方から PERSONAL 内部 ID を取得できない', async () => {
  const response = await marketDetail(publicListingId, outsider)
  expect(response.status()).toBe(200)
  expect(response.headers()['cache-control']).toContain('private')
  expect(response.headers()['cache-control']).toContain('no-store')
  const body = (await response.json()) as { data: MarketDetail }
  expect(body.data.owner.scopeType).toBe('PERSONAL')
  expect(body.data.owner).not.toHaveProperty('scopeId')
  expect(JSON.stringify(body)).not.toContain(`"createdBy":${owner.userId}`)

  const legacyResponse = await api.get(`${API}/recruitment-listings/${publicListingId}`, {
    headers: headers(outsider),
  })
  expect(legacyResponse.status(), '内部 ID を含む旧汎用詳細は PERSONAL 公開札を返さない').toBe(404)
})

test('PMI-004: 本人一覧へ永続化され、第三者は更新・公開・取消できない', async () => {
  const mine = await api.get(`${API}/me/market/listings?size=100`, { headers: headers(owner) })
  expect(mine.status()).toBe(200)
  const mineBody = (await mine.json()) as { data: Array<{ id: number }> }
  expect(mineBody.data.map((listing) => listing.id)).toEqual(
    expect.arrayContaining([publicListingId, selectedListingId]),
  )

  const patch = await api.patch(`${API}/me/market/listings/${publicListingId}`, {
    headers: headers(outsider),
    data: { title: 'IDOR' },
  })
  const publish = await api.post(`${API}/me/market/listings/${publicListingId}/publish`, {
    headers: headers(outsider),
  })
  const cancel = await api.post(`${API}/me/market/listings/${publicListingId}/cancel`, {
    headers: headers(outsider),
    data: { reason: 'IDOR' },
  })
  expect(patch.status()).toBe(404)
  expect(publish.status()).toBe(404)
  expect(cancel.status()).toBe(404)
})
