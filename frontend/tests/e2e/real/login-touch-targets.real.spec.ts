import { expect, test, type Page } from '@playwright/test'
import { clearAndFillInput } from '../helpers/form'
import { waitForHydration } from '../helpers/wait'

// 専有fixtureは実機環境の正規seedで作成し、所有者が終了時にcleanupする。
// このspecはアカウント作成・メール送信・共有DBの変更を行わない。
test.use({
  storageState: { cookies: [], origins: [] },
  viewport: { width: 390, height: 844 },
  isMobile: true,
  hasTouch: true,
})
test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000)

const controls = [
  '#email',
  'input#password',
  '#login-form a[href="/"]',
  '#login-form a[href="/forgot-password"]',
  '#login-form a[href="/register"]',
]

test.beforeAll(async ({ baseURL }) => {
  expect(process.env.LOGIN_TOUCH_OWNER, '専有環境のowner').toBe('october1510-20261026')
  expect(baseURL).toBe('http://localhost:3001')
  expect(process.env.API_BASE_URL).toBe('http://localhost:8083')
})

async function openLogin(page: Page, notice = false): Promise<void> {
  await page.goto(notice ? '/login?reason=session_expired' : '/login')
  await waitForHydration(page)
  await expect(page.locator('#login-form')).toBeVisible()
  if (notice) await expect(page.locator('#login-form [role="status"]')).toBeVisible()
}

async function assertAuthenticatedScreen(page: Page): Promise<void> {
  await waitForHydration(page)
  await expect(page.locator('header').getByText('Mannschaft', { exact: true })).toBeVisible()
  await expect(page.locator('#login-form')).toHaveCount(0)
}

async function assertTargets(page: Page): Promise<void> {
  const boxes: Array<{ x: number; y: number; width: number; height: number }> = []
  for (const selector of controls) {
    const control = page.locator(selector)
    await control.scrollIntoViewIfNeeded()
    await expect(control).toBeInViewport()
    const box = await control.boundingBox()
    expect(box, selector).not.toBeNull()
    if (!box) throw new Error(`${selector} の実DOM寸法を取得できません`)
    expect(box.width, `${selector} width`).toBeGreaterThanOrEqual(44)
    expect(box.height, `${selector} height`).toBeGreaterThanOrEqual(44)

    const geometry = await control.evaluate((element) => {
      const rect = element.getBoundingClientRect()
      const range = document.createRange()
      range.selectNodeContents(element)
      const textFits = Array.from(range.getClientRects()).every(text =>
        text.left >= rect.left - 1 && text.right <= rect.right + 1
        && text.top >= rect.top - 1 && text.bottom <= rect.bottom + 1)
      const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2)
      return {
        x: rect.left + window.scrollX,
        y: rect.top + window.scrollY,
        textFits,
        reachable: hit === element || (hit !== null && element.contains(hit)),
        horizontalOverflow: document.documentElement.scrollWidth - window.innerWidth,
      }
    })
    expect(geometry.textFits, `${selector} 文字切れ`).toBe(true)
    expect(geometry.reachable, `${selector} スクロール後の操作到達`).toBe(true)
    expect(geometry.horizontalOverflow, 'ページ横パン').toBeLessThanOrEqual(1)
    boxes.push({ ...box, x: geometry.x, y: geometry.y })
  }
  for (let i = 0; i < boxes.length; i++) {
    for (let j = i + 1; j < boxes.length; j++) {
      const a = boxes[i]!
      const b = boxes[j]!
      const overlap = Math.min(a.x + a.width, b.x + b.width) > Math.max(a.x, b.x)
        && Math.min(a.y + a.height, b.y + b.height) > Math.max(a.y, b.y)
      expect(overlap, `${controls[i]} と ${controls[j]} の重なり`).toBe(false)
    }
  }
}

for (const viewport of [{ width: 360, height: 800 }, { width: 390, height: 844 }]) {
  for (const locale of ['ja', 'en', 'de']) {
    for (const notice of [false, true]) {
      test(`${viewport.width}px ${locale} 案内${notice}: 実入力と補助リンク44px・文字切れなし`, async ({ page, context }, testInfo) => {
        await page.setViewportSize(viewport)
        await context.addCookies([{ name: 'i18n_locale', value: locale, url: 'http://localhost:3001' }])
        await openLogin(page, notice)
        await expect(page.locator('html')).toHaveAttribute('lang', new RegExp(`^${locale}`))
        await assertTargets(page)
        await testInfo.attach('anonymous-login-layout', {
          body: await page.screenshot({ fullPage: true }),
          contentType: 'image/png',
        })
        await testInfo.attach('login-targets', {
          body: JSON.stringify(await page.locator(controls.join(',')).evaluateAll(elements =>
            elements.map(element => ({ id: element.id, href: element.getAttribute('href'), width: element.getBoundingClientRect().width, height: element.getBoundingClientRect().height })))),
          contentType: 'application/json',
        })
      })
    }
  }
}

for (const viewport of [{ width: 360, height: 800 }, { width: 390, height: 844 }]) {
  test(`${viewport.width}px: 補助3リンクはタッチ操作でトップ・再設定・登録へ遷移する`, async ({ page }) => {
    await page.setViewportSize(viewport)
    for (const destination of ['/', '/forgot-password', '/register']) {
      await openLogin(page)
      await page.locator(`#login-form a[href="${destination}"]`).tap()
      await expect(page).toHaveURL(url => url.pathname === destination)
      await waitForHydration(page)
    }
  })
}

test('空入力・不正emailはHTML検証でログイン送信を抑止する', async ({ page }) => {
  await openLogin(page)
  let loginRequests = 0
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/auth/login') loginRequests++
  })
  const email = page.locator('#email')
  const password = page.locator('input#password')
  for (const values of [ ['', ''], ['invalid', 'InvalidPassword1!'], ['valid@example.invalid', ''] ]) {
    await clearAndFillInput(email, values[0]!)
    await clearAndFillInput(password, values[1]!)
    await page.locator('[data-login-submit]').tap()
    expect(await page.locator('#login-form').evaluate(form => (form as HTMLFormElement).checkValidity())).toBe(false)
    await expect(page).toHaveURL(/\/login$/)
    expect(loginRequests).toBe(0)
  }
})

// 公開共通CSSには権限差がない。専有3fixtureの実ログインで既存guestガードを補完する。
for (const role of ['MEMBER', 'ADMIN', 'OTHER_TENANT']) {
  test(`${role}: 表示切替・実ログイン・認証済みlogin直打ちを維持する`, async ({ page }) => {
    const emailValue = process.env[`LOGIN_TOUCH_${role}_EMAIL`]
    const passwordValue = process.env[`LOGIN_TOUCH_${role}_PASSWORD`]
    if (!emailValue || !passwordValue) throw new Error(`${role} の専有fixture資格情報が必要です`)
    await openLogin(page)
    const email = page.locator('#email')
    const password = page.locator('input#password')
    await clearAndFillInput(email, emailValue)
    await clearAndFillInput(password, passwordValue)
    await page.locator('[data-pc-section="unmaskicon"]').tap()
    await expect(password).toHaveAttribute('type', 'text')
    expect(await password.inputValue() === passwordValue, '表示切替後もパスワード入力を保持する').toBe(true)
    await page.locator('[data-pc-section="maskicon"]').tap()
    await expect(password).toHaveAttribute('type', 'password')

    if (role === 'MEMBER') {
      const wrongPassword = `${passwordValue}-wrong`
      await clearAndFillInput(password, wrongPassword)
      const failed = page.waitForResponse(response =>
        response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/auth/login')
      await page.locator('[data-login-submit]').tap()
      expect((await failed).status()).toBe(401)
      await expect(page.getByText('ログインに失敗しました', { exact: true })).toBeVisible()
      await expect(page.locator('[data-login-submit]')).toBeEnabled()
      await expect(page).toHaveURL(/\/login$/)
      expect(await email.inputValue() === emailValue, '不正認証後もメール入力を保持する').toBe(true)
      expect(await password.inputValue() === wrongPassword, '不正認証後もパスワード入力を保持する').toBe(true)
      await clearAndFillInput(password, passwordValue)
    }

    const success = page.waitForResponse(response =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/auth/login')
    await page.locator('[data-login-submit]').tap()
    expect((await success).status()).toBe(200)
    await expect(page).toHaveURL(url => /^\/(dashboard|my|system-admin)(\/|$)/.test(url.pathname), { timeout: 90_000 })
    await assertAuthenticatedScreen(page)
    await page.goto('/login')
    await expect(page).toHaveURL(url => /^\/(dashboard|my|system-admin)(\/|$)/.test(url.pathname), { timeout: 90_000 })
    await assertAuthenticatedScreen(page)
  })
}
