import { expect, request as pwRequest, test, type APIRequestContext, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

const TEAM = { id: 1, slug: 'fc-u-18' }
const ORGANIZATION = { id: 9, slug: 'org-000009' }

let api: APIRequestContext
let accessToken: string

function authorization(): Record<string, string> {
  return { Authorization: `Bearer ${accessToken}` }
}

async function loginBrowser(page: Page): Promise<void> {
  await page.goto('/login')
  await waitForHydration(page)
  await page.locator('input#email').fill(ADMIN_EMAIL)
  await page.locator('input[type="password"]').fill(ADMIN_PASSWORD)
  await page.getByRole('button', { name: 'ログイン', exact: true }).click()
  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 60_000,
    waitUntil: 'domcontentloaded',
  })
}

async function openAndExpectApi(page: Page, path: string, apiPath: string): Promise<void> {
  const responsePromise = page.waitForResponse(
    (response) => response.url().includes(apiPath)
      && response.request().method() === 'GET'
      && response.status() === 200,
    { timeout: 60_000 },
  )
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  const response = await responsePromise
  expect(response.status(), `${apiPath} should return 200`).toBe(200)
  await expect(page).toHaveURL(new RegExp(path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
}

test.beforeAll(async () => {
  api = await pwRequest.newContext({ baseURL: API_BASE_URL })
  const login = await api.post('/api/v1/auth/login', {
    data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
  })
  expect(login.status()).toBe(200)
  accessToken = (await login.json() as { data: { accessToken: string } }).data.accessToken
})

test.afterAll(async () => {
  await api.dispose()
})

test('チームslug画面導線の実APIと表示結果を確認する', async ({ page }) => {
  test.setTimeout(180_000)
  await loginBrowser(page)

  await openAndExpectApi(
    page,
    `/teams/${TEAM.slug}/analytics`,
    `/api/v1/teams/${TEAM.slug}/analytics`,
  )
  await expect(page.locator('p.text-3xl')).toHaveCount(4)
  await expect(page.locator('canvas').first()).toBeVisible()

  await openAndExpectApi(
    page,
    `/teams/${TEAM.slug}/settings/shift`,
    `/api/v1/teams/${TEAM.slug}/shift-settings`,
  )
  await expect(page.getByTestId('reminder-settings-form')).toBeVisible()
  await expect(page.getByTestId('reminder-settings-save-btn')).toBeVisible()

  await openAndExpectApi(
    page,
    `/teams/${TEAM.slug}/modules`,
    `/api/v1/teams/${TEAM.slug}/modules/catalog`,
  )
  await expect(page.locator('div.rounded-xl:has(.pi-puzzle)').first()).toBeVisible()
})

test('組織slug画面導線の実APIと表示結果を確認する', async ({ page }) => {
  test.setTimeout(180_000)
  await loginBrowser(page)

  await openAndExpectApi(
    page,
    `/organizations/${ORGANIZATION.slug}/analytics`,
    `/api/v1/organizations/${ORGANIZATION.slug}/analytics`,
  )
  await expect(page.locator('p.text-3xl')).toHaveCount(4)
  await expect(page.locator('canvas').first()).toBeVisible()

  await openAndExpectApi(
    page,
    `/organizations/${ORGANIZATION.slug}/modules`,
    `/api/v1/organizations/${ORGANIZATION.slug}/modules/catalog`,
  )
  await expect(page.locator('div.rounded-xl:has(.pi-puzzle)').first()).toBeVisible()

  await openAndExpectApi(
    page,
    `/organizations/${ORGANIZATION.slug}/projects`,
    `/api/v1/organizations/${ORGANIZATION.slug}/projects`,
  )
  await expect(page.locator('h1').first()).toBeVisible()
})

test('匿名の組織内チーム検索で地域絞り込みを利用できる', async ({ page }) => {
  test.setTimeout(180_000)
  const prefecturesResponsePromise = page.waitForResponse(
      (response) => response.url().endsWith('/api/v1/master/prefectures')
        && response.request().method() === 'GET',
      { timeout: 60_000 },
  )
  await openAndExpectApi(
    page,
    `/organizations/${ORGANIZATION.slug}/teams/search`,
    `/api/v1/organizations/${ORGANIZATION.slug}/teams/search`,
  )
  expect((await prefecturesResponsePromise).status()).toBe(200)
  await expect(page.getByText('FC Tokyo U-18 Test', { exact: true })).toBeVisible()
  await expect(page.locator(`a[href="/public/teams/${TEAM.slug}"]`).first()).toBeVisible()
  await expect(page.locator('body')).not.toContainText('Organization not found')

  const prefectureSelect = page.locator('#team-search-prefecture')
  await expect(prefectureSelect).toBeEnabled()
  await prefectureSelect.click()
  const tokyoOption = page.getByRole('option', { name: '東京都', exact: true })
  await expect(tokyoOption).toBeVisible()
  const citiesResponsePromise = page.waitForResponse(
    (response) => response.url().endsWith('/api/v1/master/prefectures/13/cities')
      && response.request().method() === 'GET',
    { timeout: 60_000 },
  )
  await tokyoOption.click()
  expect((await citiesResponsePromise).status()).toBe(200)

  const citySelect = page.locator('#team-search-city')
  await expect(citySelect).toBeEnabled()
  await citySelect.click()
  await expect.poll(() => page.getByRole('option').count()).toBeGreaterThan(1)
})

test('数値ID互換導線はACTIVEスコープだけを受理する', async () => {
  const cases = [
    `/api/v1/teams/${TEAM.id}/analytics`,
    `/api/v1/organizations/${ORGANIZATION.id}/analytics`,
    `/api/v1/organizations/${ORGANIZATION.id}/teams/search`,
    `/api/v1/teams/${TEAM.id}/shift-settings`,
    `/api/v1/teams/${TEAM.id}/modules/catalog`,
    `/api/v1/organizations/${ORGANIZATION.id}/modules/catalog`,
    `/api/v1/organizations/${ORGANIZATION.id}/projects`,
  ]

  for (const path of cases) {
    const response = await api.get(path, { headers: authorization() })
    expect(response.status(), `${path} should accept an active numeric scope ID`).toBe(200)
  }

  const missing = 999_999_999
  const missingCases = [
    `/api/v1/teams/${missing}/analytics`,
    `/api/v1/organizations/${missing}/analytics`,
    `/api/v1/organizations/${missing}/teams/search`,
    `/api/v1/teams/${missing}/shift-settings`,
    `/api/v1/teams/${missing}/modules/catalog`,
    `/api/v1/organizations/${missing}/modules/catalog`,
    `/api/v1/organizations/${missing}/projects`,
  ]

  for (const path of missingCases) {
    const response = await api.get(path, { headers: authorization() })
    expect(response.status(), `${path} should reject a missing numeric scope ID`).toBe(404)
  }
})
