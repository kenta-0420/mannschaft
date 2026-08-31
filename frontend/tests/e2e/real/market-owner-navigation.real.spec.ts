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

async function authenticate(page: Page) {
  await loginViaApi(page, ADMIN, { apiBaseUrl: API_BASE_URL })
}

async function fillDateTime(page: Page, id: string, value: string) {
  await page.locator(`input#${id}`).fill(value)
  await page.locator(`input#${id}`).dispatchEvent('change')
}

test('MARKET-OWNER-UI-000: 未ログインでは個人札の作成画面へ入れない', async ({ page }) => {
  await page.goto('/market')
  await waitForHydration(page)
  await page.getByTestId('market-post-link').click()

  await expect(page).toHaveURL(/\/login(?:\?|$)/)
})

test('MARKET-OWNER-UI-001: 公開市の「札を立てる」から個人札を画面入力で作成できる', async ({ page }) => {
  await authenticate(page)

  await page.goto('/market')
  await waitForHydration(page)
  await page.getByTestId('market-post-link').click()
  await expect(page).toHaveURL(/\/me\/market\?create=true$/)

  const form = page.locator('form').first()
  await expect(form).toBeVisible()
  await page.locator('#personal-market-visibility').click()
  await page.getByRole('option', { name: '全体公開', exact: true }).click()
  const title = `E2E-個人市-UI-${Date.now()}`
  await form.locator('#title').fill(title)
  await form.locator('#category').click()
  const practiceMatchOption = page.getByRole('option')
    .filter({ hasText: '練習試合相手募集' })
  await expect(practiceMatchOption).toBeVisible()
  await practiceMatchOption.click()
  await expect(form.locator('#category')).toContainText('練習試合相手募集')
  await fillDateTime(page, 'startAt', '2027-02-15T09:00')
  await fillDateTime(page, 'endAt', '2027-02-15T12:00')
  await fillDateTime(page, 'applicationDeadline', '2027-02-13T23:59')
  await fillDateTime(page, 'autoCancelAt', '2027-02-13T23:59')
  await form.locator('#capacity input').fill('6')
  await form.locator('#minCapacity input').fill('2')

  const [createdResponse] = await Promise.all([
    page.waitForResponse(response =>
      response.url().endsWith('/api/v1/me/market/listings')
      && response.request().method() === 'POST',
    ),
    form.locator('button[type="submit"]').click(),
  ])
  expect(createdResponse.status()).toBe(201)
  const created = (await createdResponse.json()) as { data: { id: number } }

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
  await expect(page.getByRole('heading', { name: title })).toBeVisible()

  // 後始末だけ API を使う。作成操作と永続化確認は上の UI 経路で完了している。
  const cleanup = await page.request.post(
    `${API_BASE_URL}/api/v1/me/market/listings/${created.data.id}/cancel`,
    { data: { reason: 'e2e cleanup' } },
  )
  expect(cleanup.status()).toBe(200)
})

test('MARKET-OWNER-UI-002: チーム市から主体を保って札作成画面へ進める', async ({ page }) => {
  await authenticate(page)

  await page.goto('/teams/fc-u-18/market')
  await waitForHydration(page)
  const postLink = page.getByTestId('market-team-post-link')
  await expect(postLink).toBeEnabled()
  await postLink.click()

  await expect(page).toHaveURL(/\/teams\/fc-u-18\/recruitment-listings\/new$/)
  await expect(page.locator('form')).toBeVisible()
})

test('MARKET-OWNER-UI-003: 組織市から主体を保って札作成画面へ進める', async ({ page }) => {
  await authenticate(page)
  const organizationsResponse = await page.request.get(
    `${API_BASE_URL}/api/v1/me/organizations`,
  )
  expect(organizationsResponse.status()).toBe(200)
  const organizations = (await organizationsResponse.json()) as {
    data: Array<{ slug: string, role: string }>
  }
  const organization = organizations.data.find(item =>
    ['ADMIN', 'SYSTEM_ADMIN'].includes(item.role) && item.slug,
  )
  expect(organization, '管理権限を持つ組織が前提データに存在する').toBeTruthy()

  await page.goto(`/organizations/${organization!.slug}/market`)
  await waitForHydration(page)
  const postLink = page.getByTestId('market-organization-post-link')
  await expect(postLink).toBeEnabled()
  await postLink.click()

  await expect(page).toHaveURL(
    new RegExp(`/organizations/${organization!.slug}/recruitment-listings/new$`),
  )
  await expect(page.locator('form')).toBeVisible()
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
