import { expect, test } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

test.use({ storageState: { cookies: [], origins: [] } })
test.setTimeout(180_000)

test('チームのサイドバーからインシデント一覧を開ける', async ({ browser }) => {
  const context = await browser.newContext()
  try {
    const page = await context.newPage()
    await loginViaApi(
      page,
      {
        email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
        password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!',
      },
      { apiBaseUrl: process.env.API_BASE_URL ?? 'http://127.0.0.1:8090', deferNavigation: true },
    )
    await page.goto('/teams/fc-u-18', { waitUntil: 'domcontentloaded', timeout: 120_000 })
    await page.getByTestId('scope-sidebar-toggle').click({ timeout: 60_000 })
    await page.getByRole('button', { name: '施設管理' }).click({ timeout: 60_000 })
    const link = page.getByRole('link', { name: 'インシデント' })
    await expect(link).toHaveAttribute('href', '/teams/fc-u-18/incidents')
    await link.click()
    await expect(page).toHaveURL(/\/teams\/fc-u-18\/incidents$/)
    console.log(`NAV_SUCCESS=${page.url()}`)
  } finally {
    await context.close()
  }
})
