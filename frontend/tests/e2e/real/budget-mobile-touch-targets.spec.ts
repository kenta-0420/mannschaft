/**
 * 会計年度フォームのモバイル操作領域を実 DOM で検証する（API モックなし）。
 * BASE_URL / API_BASE_URL と、予算を管理できる TEST_ADMIN_EMAIL / TEST_USER_PASSWORD、
 * BUDGET_TEAM_SLUG を指定して chromium-real --no-deps で実行する。
 * 年度は保存しないため業務データを変更しない。
 */
import { test, expect, type Locator, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

// 同じ専用アカウントのロケール変更とログインを並行させない。
test.describe.configure({ mode: 'serial' })

test.use({
  storageState: { cookies: [], origins: [] },
  viewport: { width: 360, height: 800 },
  isMobile: true,
  hasTouch: true,
})

async function expectTouchTargets(scope: Locator, page: Page): Promise<void> {
  // 開閉アニメーション中の縮小率を測定値へ混入させない。
  await scope.evaluate(async (element) => {
    await Promise.allSettled(
      element.getAnimations({ subtree: true }).map((animation) => animation.finished),
    )
  })
  const bounds = await scope.boundingBox()
  const viewport = page.viewportSize()!
  expect(bounds).not.toBeNull()
  expect(bounds!.x).toBeGreaterThanOrEqual(0)
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(viewport.width)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
    viewport.width,
  )
  expect(await page.evaluate(() => window.innerWidth)).toBeLessThanOrEqual(viewport.width)
  const targets = scope.locator(
    'button:visible,input:visible,[data-pc-section="day"]:visible,[data-pc-section="month"]:visible,[data-pc-section="year"]:visible',
  )
  const measurements = await targets.evaluateAll((elements) =>
    elements.map((element) => ({
      label: element.getAttribute('aria-label') || element.id || element.textContent,
      box: element.getBoundingClientRect().toJSON(),
    })),
  )
  expect(measurements.length).toBeGreaterThan(0)
  await test.info().attach('操作領域の実寸', {
    body: JSON.stringify({ bounds, measurements }, null, 2),
    contentType: 'application/json',
  })
  for (const { label, box } of measurements) {
    expect(box.width, `${label} の幅が44px以上`).toBeGreaterThanOrEqual(44)
    expect(box.height, `${label} の高さが44px以上`).toBeGreaterThanOrEqual(44)
    expect(box.x, `${label} の左端が画面内`).toBeGreaterThanOrEqual(0)
    expect(box.right, `${label} の右端が画面内`).toBeLessThanOrEqual(viewport.width)
  }
}

test('会計年度フォーム・日付選択・使い方の操作領域は360px幅でも44x44px以上', async ({ page }) => {
  test.setTimeout(180_000)
  const slug = process.env.BUDGET_TEAM_SLUG
  const email = process.env.TEST_ADMIN_EMAIL
  const password = process.env.TEST_USER_PASSWORD
  expect(slug, '対象テストチームのBUDGET_TEAM_SLUGが必要').toBeTruthy()
  expect(email, '予算管理者のTEST_ADMIN_EMAILが必要').toBeTruthy()
  expect(password, 'TEST_USER_PASSWORDが必要').toBeTruthy()
  await loginViaApi(page, { email: email!, password: password! }, { deferNavigation: true })
  await page.goto(`/teams/${slug}/budget`, { waitUntil: 'domcontentloaded' })
  await page.getByTestId('add-fiscal-year').click({ timeout: 120_000 })
  const formDialog = page.locator('.p-dialog').filter({ has: page.locator('#fiscal-year-form') })
  await expectTouchTargets(formDialog, page)

  for (const id of ['fiscal-year-start', 'fiscal-year-end']) {
    await page.locator(`#${id}`).click()
    const calendar = page.locator('.p-datepicker-panel')
    await expect(calendar).toBeVisible()
    await expectTouchTargets(calendar, page)
    await calendar.locator('[data-pc-section="selectmonth"]').click()
    await expectTouchTargets(calendar, page)
    await calendar.locator('[data-pc-section="selectyear"]').click()
    await expectTouchTargets(calendar, page)
    await page.keyboard.press('Escape')
  }
  await formDialog.getByRole('button', { name: 'キャンセル', exact: true }).click()
  await page.getByRole('button', { name: '使い方', exact: true }).click()
  await expectTouchTargets(page.getByTestId('budget-guide-modal'), page)
})

for (const locale of ['ja', 'de']) {
  for (const width of [360, 390]) {
    test(`予算ヘッダーと使い方は${locale}/${width}pxで横溢れせず通常操作できる`, async ({
      page,
      baseURL,
    }) => {
      test.setTimeout(180_000)
      const slug = process.env.BUDGET_TEAM_SLUG
      const email = process.env.TEST_ADMIN_EMAIL
      const password = process.env.TEST_USER_PASSWORD
      expect(slug, '年度登録済みテストチームのBUDGET_TEAM_SLUGが必要').toBeTruthy()
      expect(email, '予算管理者のTEST_ADMIN_EMAILが必要').toBeTruthy()
      expect(password, 'TEST_USER_PASSWORDが必要').toBeTruthy()
      await page.setViewportSize({ width, height: 800 })
      await loginViaApi(page, { email: email!, password: password! }, { deferNavigation: true })
      const profileUrl = `${process.env.API_BASE_URL ?? ''}/api/v1/users/me`
      const profile = await page.request.get(profileUrl)
      expect(profile.ok()).toBeTruthy()
      const originalLocale = (await profile.json()).data.locale as string

      try {
        // APIは認証と専用アカウントの言語前提だけに使い、対象操作は実UIで行う。
        const localized = await page.request.put(profileUrl, { data: { locale } })
        expect(localized.ok()).toBeTruthy()
        await page.context().addCookies([{ name: 'i18n_locale', value: locale, url: baseURL! }])
        await page.goto(`/teams/${slug}/budget`, { waitUntil: 'domcontentloaded' })
        const addYear = page.getByTestId('add-fiscal-year')
        await expect(addYear).toBeVisible({ timeout: 120_000 })
        await expect(page.locator('html')).toHaveAttribute('lang', new RegExp(`^${locale}`))
        const header = page.locator('h1').locator('..')
        // ヘルプと年度追加の実寸に加え、見出し行によるページ拡幅を検出する。
        await expectTouchTargets(header, page)
        const back = page
          .locator(`a[href="/teams/${slug}"]`)
          .filter({ has: page.locator('.pi-arrow-left') })
        const backBounds = await back.boundingBox()
        expect(backBounds).not.toBeNull()
        expect(backBounds!.width).toBeGreaterThanOrEqual(44)
        expect(backBounds!.height).toBeGreaterThanOrEqual(44)
        const period = page.getByTestId('fiscal-year-period')
        await expect(period).toBeVisible()
        await expect(period).toContainText(/\d{4}/)
        expect(
          await period.evaluate((element) => element.scrollWidth <= element.clientWidth),
        ).toBeTruthy()

        await page.getByTestId('page-header-help').click()
        const guide = page.getByTestId('budget-guide-modal')
        await expect(guide).toBeVisible()
        await expectTouchTargets(guide, page)
        await guide.locator('[data-pc-section="footer"] button').click()
        await expect(guide).toBeHidden()
        await page.getByTestId('page-header-help').click()
        await guide.locator('button[data-pc-name="pcclosebutton"]').click()
        await expect(guide).toBeHidden()

        await addYear.click()
        const form = page.locator('.p-dialog').filter({ has: page.locator('#fiscal-year-form') })
        await expect(form).toBeVisible()
        await expectTouchTargets(form, page)
        await form.locator('button[data-pc-name="pcclosebutton"]').click()
        await expect(form).toBeHidden()
        const navigationStarted = Date.now()
        await back.click()
        // 遷移先の初回コンパイルを含め、実URLへ到達するまで待つ。
        try {
          await page.waitForURL(new RegExp(`/teams/${slug}$`), {
            timeout: 120_000,
            waitUntil: 'domcontentloaded',
          })
        } finally {
          await test.info().attach('戻るの遷移結果', {
            body: JSON.stringify({
              url: page.url(),
              expectedUrl: `/teams/${slug}`,
              elapsedMs: Date.now() - navigationStarted,
            }),
            contentType: 'application/json',
          })
        }
      } finally {
        const restored = await page.request.put(profileUrl, { data: { locale: originalLocale } })
        expect(restored.ok(), '専用アカウントの言語を復元する').toBeTruthy()
      }
    })
  }
}
