/**
 * 市の札主別導線を、実 FE・実 BE・実 DB に対する画面操作で検証する。
 * API はログイン、所属組織の前提解決、作成データの後始末にだけ使用する。
 * 対象操作（入口クリック、フォーム入力・送信、札主フィルター選択）は API で代替しない。
 */
import { expect, test, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const ADMIN = {
  email: 'e2e-admin@test.mannschaft.local',
  password: 'TestPass2026!',
}
const USER = {
  email: 'e2e-user@test.mannschaft.local',
  password: 'TestPass2026!',
}
const TEAM_ADMIN = {
  email: 'e2e-dummy-1@test.mannschaft.local',
  password: 'TestPass2026!',
}
type Credentials = typeof ADMIN
let cleanupPersonalListingId: number | null = null
let restorePublicProfileEnabled: boolean | null = null
const cleanupOperationalListings: Array<{ id: number, credentials: Credentials }> = []

async function authenticate(page: Page, credentials = ADMIN) {
  await loginViaApi(page, credentials, { apiBaseUrl: API_BASE_URL })
  await expect.poll(async () => page.evaluate(() => localStorage.getItem('currentUser') !== null))
    .toBe(true)
  await expect.poll(async () => (await page.context().cookies())
    .some(cookie => cookie.name === 'access_token'))
    .toBe(true)
  await expect.poll(async () => page.evaluate(async apiBaseUrl =>
    (await fetch(`${apiBaseUrl}/api/v1/users/me`, { credentials: 'include' })).status,
  API_BASE_URL))
    .toBe(200)
}

async function fillDateTime(page: Page, id: string, value: string) {
  await page.locator(`input#${id}`).fill(value)
  await page.locator(`input#${id}`).dispatchEvent('change')
}

async function applyToListingFromUi(page: Page, listingId: number, credentials: Credentials) {
  await page.context().clearCookies()
  await page.evaluate(() => localStorage.clear())
  await authenticate(page, credentials)
  await page.goto(`/market/listings/${listingId}`)
  await waitForHydration(page)
  const applyButton = page.getByRole('button', { name: '札に応じる', exact: true })
  await expect(applyButton).toBeVisible()
  const [response] = await Promise.all([
    page.waitForResponse(candidate =>
      candidate.url().endsWith(`/api/v1/recruitment-listings/${listingId}/applications`)
      && candidate.request().method() === 'POST',
    ),
    applyButton.click(),
  ])
  expect(response.status(), `UIからの応募が成功すること: ${await response.text()}`).toBe(201)
  const body = (await response.json()) as { data: { status: string } }
  expect(body.data.status).toBe('CONFIRMED')
}

async function cancelOperationalListingFromUi(
  page: Page,
  listingId: number,
  marketPath: string,
) {
  await page.goto(marketPath)
  await waitForHydration(page)
  page.once('dialog', dialog => dialog.accept())
  const [response] = await Promise.all([
    page.waitForResponse(candidate =>
      candidate.url().endsWith(`/api/v1/recruitment-listings/${listingId}/cancel`)
      && candidate.request().method() === 'POST',
    ),
    page.getByTestId(`market-listing-cancel-${listingId}`).click(),
  ])
  expect(response.status(), `UIからの札取消が成功すること: ${await response.text()}`).toBe(200)
}

async function assertParticipationInactiveFromUi(
  page: Page,
  listingId: number,
  credentials: Credentials,
) {
  await page.context().clearCookies()
  await page.evaluate(() => localStorage.clear())
  await authenticate(page, credentials)
  await page.goto('/me/recruitment-listings')
  await waitForHydration(page)
  await expect(page.locator('body')).not.toContainText(`募集 #${listingId}`)
}

function localDateTime(minutesFromNow: number) {
  const value = new Date(Date.now() + minutesFromNow * 60_000)
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`
    + `T${pad(value.getHours())}:${pad(value.getMinutes())}`
}

async function createAndPublishOperationalListing(
  page: Page,
  title: string,
  credentials: Credentials,
) {
  await expect(page.getByTestId('market-form-extension')).toBeVisible()
  await page.locator('#title').fill(title)
  await page.locator('#category').click()
  const category = page.getByRole('option').filter({ hasText: '練習試合相手募集' }).first()
  await expect(category).toBeVisible()
  await category.click()
  await fillDateTime(page, 'startAt', localDateTime(24 * 60))
  await fillDateTime(page, 'endAt', localDateTime(27 * 60))
  await fillDateTime(page, 'applicationDeadline', localDateTime(60))
  await fillDateTime(page, 'autoCancelAt', localDateTime(60))
  await page.locator('#capacity input').fill('1')
  await page.locator('#minCapacity input').fill('1')

  const createResponsePromise = page.waitForResponse(response =>
    /\/api\/v1\/(?:teams|organizations)\/[^/]+\/recruitment-listings$/.test(response.url())
    && response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '作成', exact: true }).click()
  const createResponse = await createResponsePromise
  expect(
    createResponse.status(),
    `札作成APIが成功すること: ${await createResponse.text()}`,
  ).toBe(201)
  await page.waitForURL(/\/recruitment-listings\/\d+$/, { timeout: 30_000 })
  const match = page.url().match(/\/recruitment-listings\/(\d+)$/)
  expect(match).toBeTruthy()
  const id = Number(match![1])
  cleanupOperationalListings.push({ id, credentials })

  await page.goto(`/recruitment-listings/${id}`)
  await waitForHydration(page)
  const publishButton = page.locator('main').getByRole('button', { name: '公開', exact: true })
  await expect(publishButton).toBeVisible()
  const [publish] = await Promise.all([
    page.waitForResponse(response =>
      response.url().endsWith(`/api/v1/recruitment-listings/${id}/publish`)
      && response.request().method() === 'POST',
    ),
    publishButton.click(),
  ])
  expect(publish.status()).toBe(200)
  return id
}

test.afterEach(async ({ page }) => {
  if (cleanupPersonalListingId != null) {
    const cleanup = await page.request.post(
      `${API_BASE_URL}/api/v1/me/market/listings/${cleanupPersonalListingId}/cancel`,
      { data: { reason: 'e2e cleanup' } },
    )
    expect([200, 409]).toContain(cleanup.status())
    cleanupPersonalListingId = null
  }
  if (restorePublicProfileEnabled != null) {
    const restore = await page.request.patch(`${API_BASE_URL}/api/v1/users/me/public-profile`, {
      data: { publicProfileEnabled: restorePublicProfileEnabled },
    })
    expect(restore.status()).toBe(204)
    restorePublicProfileEnabled = null
  }
})

test.afterAll(async ({ browser }) => {
  for (const { id, credentials } of cleanupOperationalListings) {
    const page = await browser.newPage()
    await authenticate(page, credentials)
    const cleanup = await page.request.post(
      `${API_BASE_URL}/api/v1/recruitment-listings/${id}/cancel`,
      { data: { reason: 'e2e cleanup' } },
    )
    expect([200, 409]).toContain(cleanup.status())
    await page.close()
  }
})

test('MARKET-OWNER-UI-000: 未ログインでは個人札の作成画面へ入れない', async ({ page }) => {
  await page.goto('/market')
  await waitForHydration(page)
  await page.getByTestId('market-post-link').click()

  await expect(page).toHaveURL(/\/login(?:\?|$)/)
})

test('MARKET-OWNER-UI-001: 公開市の「札を立てる」から個人札を画面入力で作成できる', async ({ page }) => {
  await authenticate(page)
  const profile = await page.request.get(`${API_BASE_URL}/api/v1/users/me`)
  expect(profile.status()).toBe(200)
  const profileBody = (await profile.json()) as { data: { publicProfileEnabled: boolean } }
  restorePublicProfileEnabled = profileBody.data.publicProfileEnabled
  if (!restorePublicProfileEnabled) {
    const enableProfile = await page.request.patch(
      `${API_BASE_URL}/api/v1/users/me/public-profile`,
      { data: { publicProfileEnabled: true } },
    )
    expect(enableProfile.status()).toBe(204)
  }
  const authFailures: string[] = []
  page.on('response', (response) => {
    if (response.status() === 401 || response.status() === 403) {
      authFailures.push(`${response.status()} ${response.request().method()} ${response.url()}`)
    }
  })

  await page.goto('/market')
  await waitForHydration(page)
  await expect.poll(async () => (await page.context().cookies())
    .some(cookie => cookie.name === 'access_token'))
    .toBe(true)
  await expect.poll(async () => page.evaluate(async apiBaseUrl =>
    (await fetch(`${apiBaseUrl}/api/v1/users/me`, { credentials: 'include' })).status,
  API_BASE_URL))
    .toBe(200)
  const hasCurrentUser = await page.evaluate(() => localStorage.getItem('currentUser') !== null)
  expect(hasCurrentUser, `公開市の読み込みで認証情報が消失: ${authFailures.join(', ')}`).toBe(true)
  await page.getByTestId('market-post-link').click()
  await expect(page).toHaveURL(/\/me\/market\?create=true$/, { timeout: 8_000 })

  const visibilitySelect = page.locator('#personal-market-visibility')
  await expect(visibilitySelect).toBeVisible()
  const form = page.locator('form').first()
  await visibilitySelect.click()
  await page.getByRole('option', { name: '全体公開', exact: true }).click()
  const title = `E2E-個人市-UI-${Date.now()}`
  await form.locator('#title').fill(title)
  await form.locator('#category').click()
  const practiceMatchOption = page.getByRole('option')
    .filter({ hasText: '練習試合相手募集' })
  await expect(practiceMatchOption).toBeVisible()
  await practiceMatchOption.click()
  await expect(form.locator('#category')).toContainText('練習試合相手募集')
  await fillDateTime(page, 'startAt', localDateTime(24 * 60))
  await fillDateTime(page, 'endAt', localDateTime(27 * 60))
  await fillDateTime(page, 'applicationDeadline', localDateTime(5))
  await fillDateTime(page, 'autoCancelAt', localDateTime(5))
  await form.locator('#capacity input').fill('1')
  await form.locator('#minCapacity input').fill('1')

  const [createdResponse] = await Promise.all([
    page.waitForResponse(response =>
      response.url().endsWith('/api/v1/me/market/listings')
      && response.request().method() === 'POST',
    ),
    form.locator('button[type="submit"]').click(),
  ])
  expect(createdResponse.status()).toBe(201)
  const created = (await createdResponse.json()) as { data: { id: number } }
  cleanupPersonalListingId = created.data.id

  await expect(page.getByRole('heading', { name: title })).toBeVisible()

  const publishButton = page.getByRole('button', { name: '公開する', exact: true })
  const [publishResponse] = await Promise.all([
    page.waitForResponse(response =>
      response.url().endsWith(`/api/v1/me/market/listings/${created.data.id}/publish`)
      && response.request().method() === 'POST',
    ),
    publishButton.click(),
  ])
  expect(publishResponse.status()).toBe(200)

  await page.goto('/market')
  await waitForHydration(page)
  const personalResponsePromise = page.waitForResponse(response =>
    response.url().includes('/api/v1/public/market/listings?')
    && response.url().includes('owner_type=PERSONAL'),
  )
  await page.getByTestId('market-owner-type-select').click()
  await page.getByRole('option', { name: '個人', exact: true }).click()
  const personalResponse = await personalResponsePromise
  expect(personalResponse.status()).toBe(200)
  const personalBody = (await personalResponse.json()) as {
    data: Array<{ title: string, owner: { scopeType: string } }>
  }
  expect(personalBody.data.length).toBeGreaterThan(0)
  expect(personalBody.data.every(listing => listing.owner.scopeType === 'PERSONAL')).toBe(true)

  const titleResponsePromise = page.waitForResponse(response =>
    response.url().includes('/api/v1/public/market/listings?')
    && response.url().includes(`keyword=${encodeURIComponent(title)}`),
  )
  await page.getByTestId('market-keyword-input').fill(title)
  const titleResponse = await titleResponsePromise
  expect(titleResponse.status()).toBe(200)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()

  await applyToListingFromUi(page, created.data.id, USER)
  await authenticate(page, ADMIN)
  await page.goto('/me/market')
  await waitForHydration(page)
  const personalCard = page.locator('article').filter({ hasText: title })
  await expect(personalCard).toContainText('満員')
  await personalCard.getByRole('button', { name: '札を取り下げる', exact: true }).click()
  const confirmButton = page.getByRole('button', { name: '札を取り下げる', exact: true }).last()
  const [cancelResponse] = await Promise.all([
    page.waitForResponse(response =>
      response.url().endsWith(`/api/v1/me/market/listings/${created.data.id}/cancel`)
      && response.request().method() === 'POST',
    ),
    confirmButton.click(),
  ])
  expect(cancelResponse.status()).toBe(200)
  cleanupPersonalListingId = null
  await assertParticipationInactiveFromUi(page, created.data.id, USER)

})

test('MARKET-OWNER-UI-002: チーム市から主体を保って札を画面作成・公開できる', async ({ page }) => {
  await authenticate(page, TEAM_ADMIN)

  await page.goto('/teams/fc-u-18/market')
  await waitForHydration(page)
  const postLink = page.getByTestId('market-team-post-link')
  await expect(postLink).toBeEnabled()
  await postLink.click()

  await expect(page).toHaveURL(/\/teams\/fc-u-18\/recruitment-listings\/new$/)
  const title = `E2E-チーム市-UI-${Date.now()}`
  const listingId = await createAndPublishOperationalListing(page, title, TEAM_ADMIN)
  await expect(page.locator('main h1', { hasText: title })).toBeVisible()
  await applyToListingFromUi(page, listingId, USER)
  await authenticate(page, TEAM_ADMIN)
  await cancelOperationalListingFromUi(page, listingId, '/teams/fc-u-18/market')
  await assertParticipationInactiveFromUi(page, listingId, USER)
})

test('MARKET-OWNER-UI-003: 組織市から主体を保って札を画面作成・公開できる', async ({ page }) => {
  // SYSTEM_ADMIN は全組織を擬似所属表示するため、組織スコープの実権限を判定できない。
  // プラットフォームロールを持たず、seed で組織 ADMIN が保証される一般ユーザーを使う。
  await authenticate(page, USER)
  const organizationsResponse = await page.request.get(
    `${API_BASE_URL}/api/v1/me/organizations`,
  )
  expect(organizationsResponse.status()).toBe(200)
  const organizations = (await organizationsResponse.json()) as {
    data: Array<{ id: number, slug: string, role: string }>
  }
  const organization = organizations.data.find(item => item.role === 'ADMIN')
  expect(organization, '一般ユーザーが ADMIN の組織が前提データに存在する').toBeTruthy()

  await page.goto(`/organizations/${organization!.slug}/market`)
  await waitForHydration(page)
  const postLink = page.getByTestId('market-organization-post-link')
  await expect(postLink).toBeEnabled()
  await postLink.click()

  await expect(page).toHaveURL(
    new RegExp(`/organizations/${organization!.slug}/recruitment-listings/new$`),
  )
  const title = `E2E-組織市-UI-${Date.now()}`
  const listingId = await createAndPublishOperationalListing(page, title, USER)
  await expect(page.locator('main h1', { hasText: title })).toBeVisible()
  await applyToListingFromUi(page, listingId, ADMIN)
  await authenticate(page, USER)
  await cancelOperationalListingFromUi(
    page,
    listingId,
    `/organizations/${organization!.slug}/market`,
  )
  await assertParticipationInactiveFromUi(page, listingId, ADMIN)
})

test('MARKET-OWNER-UI-004: 公開市でチーム・組織を画面から絞り込める（個人はUI-001で検証）', async ({ page }) => {
  await page.goto('/market')
  await waitForHydration(page)

  for (const [label, ownerType] of [
    ['チーム', 'TEAM'],
    ['組織', 'ORGANIZATION'],
  ] as const) {
    const responsePromise = page.waitForResponse(response =>
      response.url().includes(`/api/v1/public/market/listings?`)
      && response.url().includes(`owner_type=${ownerType}`),
    )
    await page.getByTestId('market-owner-type-select').click()
    await page.getByRole('option', { name: label, exact: true }).click()
    const response = await responsePromise
    expect(response.status()).toBe(200)
    const body = (await response.json()) as {
      data: Array<{ owner: { scopeType: string } }>
    }
    expect(body.data.length).toBeGreaterThan(0)
    expect(body.data.every(listing => listing.owner.scopeType === ownerType)).toBe(true)
    await expect(page).toHaveURL(new RegExp(`owner=${ownerType}`))
  }
})
