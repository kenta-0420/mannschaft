import { expect, test } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

test.use({ storageState: { cookies: [], origins: [] } })
test.setTimeout(420_000)

test('一般会員がダッシュボードから所属チームのインシデント一覧を開ける', async ({ browser }) => {
  const context = await browser.newContext()
  try {
    const page = await context.newPage()
    const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080'
    await loginViaApi(page, {
      email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
      password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
    }, { apiBaseUrl, deferNavigation: true })

    const teamsResponse = await page.request.get(`${apiBaseUrl}/api/v1/me/teams`)
    expect(teamsResponse.status()).toBe(200)
    const teams = (await teamsResponse.json()).data as Array<{
      id: number
      slug: string | null
      role: string
    }>
    const memberTeam = teams.find(team => team.role !== 'SUPPORTER' && team.slug)
    if (!memberTeam?.slug) throw new Error('閲覧可能な所属チームがありません')

    await page.goto('/dashboard', { waitUntil: 'domcontentloaded', timeout: 300_000 })
    await page.getByTestId('scope-nav-dropdown-toggle-TEAM').click({ timeout: 180_000 })
    await page.getByTestId(`scope-nav-dropdown-scope-${memberTeam.id}`).click({ timeout: 120_000 })
    await expect(page).toHaveURL(new RegExp(`/teams/${memberTeam.slug}$`), { timeout: 180_000 })

    await page.getByTestId('scope-sidebar-toggle').click({ timeout: 120_000 })
    await page.getByRole('button', { name: '施設管理' }).click()
    const listResponse = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/incidents'
        && url.searchParams.get('scopeType') === 'TEAM'
        && url.searchParams.get('scopeId') === String(memberTeam.id)
        && response.request().method() === 'GET'
    }, { timeout: 120_000 })
    await page.getByRole('link', { name: 'インシデント' }).click()
    await expect(page).toHaveURL(new RegExp(`/teams/${memberTeam.slug}/incidents$`))
    expect((await listResponse).status()).toBe(200)
    await expect(page.getByText('インシデント管理', { exact: true }).first()).toBeVisible()
    await expect(page.getByText('インシデント一覧を表示できませんでした')).toBeHidden()
  } finally {
    await context.close()
  }
})
