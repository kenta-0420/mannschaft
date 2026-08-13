import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const FE = process.env.BASE_URL ?? 'http://127.0.0.1:3002'
const BE = process.env.API_BASE_URL ?? 'http://127.0.0.1:8081'

async function gotoWithDevServerRetry(page: import('@playwright/test').Page, path: string) {
  let lastError: unknown
  for (let attempt = 0; attempt < 6; attempt += 1) {
    try {
      await page.goto(path, { waitUntil: 'commit', timeout: 60_000 })
      return
    } catch (error) {
      lastError = error
      await page.waitForTimeout(10_000)
    }
  }
  throw lastError
}

test.use({ baseURL: FE, storageState: { cookies: [], origins: [] } })

test('親組織なしチームで記録開始からタイムラインへ遷移する', async ({ page }) => {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const headers = { ...request.headers() }
    delete headers.host
    delete headers.origin
    delete headers['content-length']
    const response = await page.request.fetch(
      request.url().replace(/^https?:\/\/[^/]+/, BE),
      {
        method: request.method(),
        headers,
        data: request.postDataBuffer() ?? undefined,
        failOnStatusCode: false,
      },
    )
    await route.fulfill({ response })
  })
  await page.goto('/login', { waitUntil: 'commit' })
  await waitForHydration(page)
  await page.locator('input#email').fill('e2e-admin@test.mannschaft.local')
  await page.locator('input[type="password"]').fill('TestPass2026!')
  const loginResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST'
      && response.url().includes('/api/v1/auth/login'),
  )
  await page.getByRole('button', { name: 'ログイン', exact: true }).click()
  const loginResponse = await loginResponsePromise
  expect(loginResponse.ok(), await loginResponse.text()).toBeTruthy()
  await expect.poll(() => page.evaluate(() => localStorage.getItem('currentUser')), {
    timeout: 30_000,
  }).not.toBeNull()

  await expect(page).toHaveURL(/\/(?:dashboard|system-admin)$/, { timeout: 180_000 })
  await gotoWithDevServerRetry(page, '/teams/team-000092/matches/new')
  await expect(page).toHaveURL(/\/teams\/team-000092\/matches\/new/, { timeout: 180_000 })
  await waitForHydration(page)
  await page.getByRole('button', { name: '練習試合', exact: true }).waitFor({ timeout: 180_000 })

  await page.getByRole('button', { name: '練習試合', exact: true }).click()
  await page.getByPlaceholder('相手チーム名を入力').fill(`E2E対戦相手-${Date.now()}`)

  const createResponse = page.waitForResponse(
    (response) => response.request().method() === 'POST'
      && response.url().includes('/api/v1/teams/92/matches'),
    { timeout: 90_000 },
  )
  await page.getByRole('button', { name: '記録を開始', exact: true }).click()
  const response = await createResponse
  expect(response.status(), `${response.url()} ${await response.text()}`).toBe(201)
  await expect(page).toHaveURL(
    /\/teams\/team-000092\/matches\/[0-9a-f-]+\/live$/,
    { timeout: 180_000 },
  )
  await expect(page.getByText('チーム組織の情報を解決できませんでした')).toHaveCount(0)
  await page.unrouteAll({ behavior: 'ignoreErrors' })
})
