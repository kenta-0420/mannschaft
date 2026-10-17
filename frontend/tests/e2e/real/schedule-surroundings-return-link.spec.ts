/** 不在scopeの戻る導線を実DOMで測定する。APIモック・業務データ作成なし。 */
import { test, expect } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

// 同じ専用利用者のtoken rotationを競合させない。実行時もworkers=1に固定する。
test.describe.configure({ mode: 'default' })

test.use({
  storageState: { cookies: [], origins: [] },
  isMobile: true,
  hasTouch: true,
})

for (const width of [360, 390]) {
  for (const scope of ['teams', 'organizations'] as const) {
    test(`${scope}の不在案内からダッシュボードへ戻るリンクは${width}pxで44px以上`, async ({ page }) => {
      test.setTimeout(180_000)
      const email = process.env.TEST_ADMIN_EMAIL
      const password = process.env.TEST_USER_PASSWORD
      expect(email, '専用fixtureのTEST_ADMIN_EMAILが必要').toBeTruthy()
      expect(password, '専用fixtureのTEST_USER_PASSWORDが必要').toBeTruthy()
      await page.setViewportSize({ width, height: 844 })
      await loginViaApi(page, { email: email!, password: password! }, { deferNavigation: true })
      await page.goto(`/${scope}/surroundings-missing-261017/schedule`, { waitUntil: 'domcontentloaded' })
      const back = page.locator('a[href="/dashboard"]')
        .filter({ has: page.locator('.pi-arrow-left') })
      await expect(back).toBeVisible({ timeout: 120_000 })
      await page.evaluate(() => document.fonts.ready)
      const box = await back.boundingBox()
      const pageWidth = await page.evaluate(() => ({
        scroll: document.documentElement.scrollWidth,
        client: document.documentElement.clientWidth,
      }))
      await test.info().attach('戻るリンクの実寸', {
        body: JSON.stringify({ scope, viewport: width, box, pageWidth }, null, 2),
        contentType: 'application/json',
      })
      expect(box).not.toBeNull()
      expect(box!.width).toBeGreaterThanOrEqual(44)
      expect(box!.height).toBeGreaterThanOrEqual(44)
      expect(box!.x).toBeGreaterThanOrEqual(0)
      expect(box!.x + box!.width).toBeLessThanOrEqual(width)
      expect(pageWidth.scroll).toBeLessThanOrEqual(pageWidth.client)
      await back.tap()
      await expect(page).toHaveURL(/\/dashboard(?:[?#].*)?$/)
    })
  }
}
