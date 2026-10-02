import { expect, test } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3010'
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'

test.describe('CMP-260928-1214: クイック確認の多言語モバイル表示', () => {
  test.setTimeout(300_000)

  test('ドイツ語・390px幅でもページ全体が横にはみ出さない', async ({ browser }) => {
    const context = await browser.newContext({
      locale: 'de-DE',
      viewport: { width: 390, height: 844 },
    })
    const page = await context.newPage()

    try {
      await loginViaApi(
        page,
        {
          email: 'e2e-dummy-6@test.mannschaft.local',
          password: 'TestPass2026!',
        },
        { apiBaseUrl: API_BASE_URL, deferNavigation: true },
      )

      await page.goto(`${BASE_URL}/teams/fc-u-15/settings/confirmable-notifications`, {
        waitUntil: 'domcontentloaded',
        timeout: 180_000,
      })
      await expect(page.getByRole('heading', { name: 'Schnellbestätigung', exact: true })).toBeVisible({
        timeout: 180_000,
      })

      const onboardingMask = page.locator('[data-pc-section="mask"]').last()
      if (await onboardingMask.isVisible().catch(() => false)) {
        await onboardingMask.locator('button').first().click()
        await onboardingMask.waitFor({ state: 'hidden', timeout: 20_000 })
      }

      await expect(page.getByText('Kurze Nachricht senden und Bestätigungen sowie offene Empfänger schnell überblicken')).toBeVisible()
      await expect.poll(async () => page.locator('html').evaluate(element => ({
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth,
      })), { timeout: 20_000 }).toEqual({ clientWidth: 390, scrollWidth: 390 })
    } finally {
      await context.close()
    }
  })
})
