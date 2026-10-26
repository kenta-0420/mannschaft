import { expect, test, type Browser, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

/**
 * CMP-260820-1013 実機E2E。
 *
 * 対象操作（退出）は必ず実画面から行う。API はログインと本人確認にだけ使用する。
 * 専用DBには、e2e-user を TEAM 1 / ORGANIZATION 9 の memberships のみの MEMBER
 * （user_roles 行なし）として投入してから実行する。
 */
const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3001'
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PASSWORD = 'TestPass2026!'
const TEAM_SLUG = 'fc-u-18'
const ORG_SLUG = 's-98024ad7'

const MEMBER = { email: 'e2e-user@test.mannschaft.local', password: PASSWORD }
const SUPPORTER = { email: 'e2e-supporter@test.mannschaft.local', password: PASSWORD }
const OUTSIDER = { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD }

test.describe.configure({ mode: 'serial' })

async function openAs(
  browser: Browser,
  credentials: { email: string, password: string },
  viewport = { width: 1440, height: 900 },
): Promise<Page> {
  const context = await browser.newContext({
    storageState: { cookies: [], origins: [] },
    viewport,
  })
  const page = await context.newPage()
  await loginViaApi(page, credentials, { apiBaseUrl: API_BASE_URL, deferNavigation: true })

  const me = await page.request.get(`${API_BASE_URL}/api/v1/users/me`)
  expect(me.status()).toBe(200)
  const body = (await me.json()).data as { email: string }
  expect(body.email, '別アカウントの認証状態が混入している').toBe(credentials.email)
  return page
}

async function openScope(page: Page, path: string): Promise<void> {
  const response = await page.goto(`${BASE_URL}${path}`, { waitUntil: 'domcontentloaded' })
  expect(response?.status() ?? 200).toBeLessThan(400)
  await waitForHydration(page)
  await waitForSpinnerGone(page)
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const width = await page.evaluate(() => ({
    client: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }))
  expect(width.scroll, `横はみ出し: scrollWidth=${width.scroll}, clientWidth=${width.client}`).toBeLessThanOrEqual(width.client)
}

test('ALICE-OUTSIDER: 部外者は直接URLでもチーム退出導線を持たない', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, OUTSIDER)
  await openScope(page, `/teams/${TEAM_SLUG}`)

  await expect(page.getByRole('button', { name: 'チームから退出', exact: true })).toHaveCount(0)
  await expect(page.getByText('退出する', { exact: true })).toHaveCount(0)
  await page.context().close()
})

test('ALICE-SUPPORTER: SUPPORTERには会員退出導線が表示されず在籍画面に留まる', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, SUPPORTER)
  await openScope(page, `/teams/${TEAM_SLUG}`)

  const leaveButton = page.getByRole('button', { name: 'チームから退出', exact: true })
  await expect(leaveButton).toHaveCount(0)
  await expect(page.getByRole('dialog', { name: 'チームから退出' })).toHaveCount(0)
  await expect(page).toHaveURL(new RegExp(`/teams/${TEAM_SLUG}`))
  await page.context().close()
})

test('E2E-POSITIVE: membershipsのみのMEMBERがPCのチームとモバイルの組織から自主退出できる', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, MEMBER)
  await openScope(page, `/teams/${TEAM_SLUG}`)
  await assertNoHorizontalOverflow(page)

  const leaveButton = page.getByRole('button', { name: 'チームから退出', exact: true })
  await expect(leaveButton).toBeVisible({ timeout: 60_000 })
  await leaveButton.click()

  const teamDialog = page.getByRole('dialog', { name: 'チームから退出' })
  await expect(teamDialog.getByText('本当にこのチームから退出しますか？この操作は取り消せません。')).toBeVisible()
  const teamCompleted = page.waitForResponse(
    response => response.request().method() === 'DELETE'
      && new URL(response.url()).pathname === `/api/v1/teams/${TEAM_SLUG}/me`,
  )
  await teamDialog.getByRole('button', { name: '退出する', exact: true }).click()
  expect((await teamCompleted).status()).toBe(204)
  await expect(page).toHaveURL(/\/dashboard(?:\?|$)/, { timeout: 60_000 })

  // 同じ実ログインセッションのまま実機幅をモバイルへ切り替え、組織退出も画面操作する。
  await page.setViewportSize({ width: 390, height: 844 })
  await openScope(page, `/organizations/${ORG_SLUG}`)
  await assertNoHorizontalOverflow(page)

  await page.locator('button:has(.pi-ellipsis-v)').click()
  const leaveItem = page.getByText('組織から退出', { exact: true }).last()
  await expect(leaveItem).toBeVisible()
  await leaveItem.click()

  const orgDialog = page.getByRole('dialog', { name: '組織から退出' })
  await expect(orgDialog.getByText('本当にこの組織から退出しますか？この操作は取り消せません。')).toBeVisible()
  const orgCompleted = page.waitForResponse(
    response => response.request().method() === 'DELETE'
      && new URL(response.url()).pathname === `/api/v1/organizations/${ORG_SLUG}/me`,
  )
  await orgDialog.getByRole('button', { name: '退出する', exact: true }).click()
  expect((await orgCompleted).status()).toBe(204)
  await expect(page).toHaveURL(/\/dashboard(?:\?|$)/, { timeout: 60_000 })
  await assertNoHorizontalOverflow(page)
  await page.context().close()
})
