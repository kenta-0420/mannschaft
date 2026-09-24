import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { fillInput, fillPassword } from '../helpers/form'

test.use({ storageState: { cookies: [], origins: [] } })

test.describe('AUTH-DEEP login: ログインフォーム深掘り', () => {
  test('DEEP-LOGIN-000: 初回HTMLと戻る操作後にログインフォームを操作できる', async ({ page }) => {
    // 初回表示と戻る操作後、それぞれの hydration 完了を待つ。
    test.setTimeout(120_000)
    let documentRequests = 0
    page.on('request', (request) => {
      if (request.resourceType() === 'document' && new URL(request.url()).pathname === '/login') {
        documentRequests++
      }
    })

    const response = await page.goto('/login')
    if (!response) throw new Error('/login のHTTPレスポンスを取得できませんでした')

    const serverHtml = await response.text()
    // 属性の並び順や Vue の class 出力に依存せず、初回 HTTP HTML の form と
    // 操作可能な submit button を検証する。hydration 待ちで操作を止めない。
    expect(serverHtml).toMatch(/<form\b[^>]*>/i)
    const submitButton = serverHtml.match(
      /<button\b(?=[^>]*\btype=(?:"submit"|'submit'|submit)(?:\s|>))[^>]*>/i,
    )?.[0]
    expect(submitButton).toBeDefined()
    expect(submitButton).not.toMatch(/(?:^|\s)disabled(?:\s|=|>)/i)
    expect(serverHtml).toMatch(
      /<script\b(?=[^>]*\bdata-hid="login-pre-hydration-submit-guard")(?=[^>]*\bnonce="[^"]+")[^>]*>/i,
    )

    await page.waitForFunction(
      () => {
        const el = document.querySelector('#__nuxt')
        return el !== null && '__vue_app__' in el
      },
      undefined,
      { timeout: 60_000 },
    )

    await expect(page.getByRole('button', { name: 'ログイン', exact: true })).toBeEnabled()
    expect(documentRequests).toBe(1)

    // BFCache利用有無に依存せず、戻る操作で白画面にならない利用者結果を検証する。
    await page.goto('/register')
    await page.goBack({ waitUntil: 'domcontentloaded' })

    await expect(page).toHaveURL(/\/login/)
    await expect(page.locator('form')).toBeVisible()
    await waitForHydration(page)
    await expect(page.getByRole('button', { name: 'ログイン', exact: true })).toBeEnabled()
  })

  test('DEEP-LOGIN-000A: hydration 前の送信は即座に loading となり準備後に一度だけ再生される', async ({
    page,
  }) => {
    test.setTimeout(120_000)
    const hydrationWarnings: string[] = []
    page.on('console', (message) => {
      if (/hydration.*mismatch/i.test(message.text())) {
        hydrationWarnings.push(message.text())
      }
    })

    let releaseNuxtScripts!: () => void
    const nuxtScriptsReleased = new Promise<void>((resolve) => {
      releaseNuxtScripts = resolve
    })
    await page.route('**/_nuxt/**', async (route) => {
      if (route.request().resourceType() === 'script') {
        await nuxtScriptsReleased
      }
      await route.continue()
    })

    let loginRequests = 0
    await page.route('**/api/v1/auth/login', async (route) => {
      loginRequests++
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'AUTH_001', message: 'Unauthorized' } }),
      })
    })

    await page.goto('/login', { waitUntil: 'commit' })
    const form = page.locator('#login-form')
    const button = form.locator('[data-login-submit]')
    await expect(form).toBeVisible({ timeout: 30_000 })
    await expect(button).toBeEnabled()

    await page.locator('input#email').fill('before-hydration@example.com')
    await page.locator('input#password').fill('somepassword123')
    await button.click()

    await expect(button).toBeDisabled()
    await expect(button).toHaveAttribute('aria-busy', 'true')
    await expect(button.locator('.pi-spin, .p-button-loading-icon').first()).toBeVisible()
    // loading 中に Enter 相当の再送信が来ても、ブラウザ標準送信へ抜けない。
    await form.evaluate((element) => (element as HTMLFormElement).requestSubmit())
    await expect(page).toHaveURL(/\/login/)
    await expect(page.locator('input#email')).toHaveValue('before-hydration@example.com')
    expect(loginRequests).toBe(0)

    releaseNuxtScripts()
    // この試験は意図的に script を止めているため、reload を伴う共通 helper は使わない。
    // reload すると、送信予約と hydration 前に入力した値が失われて試験目的が変わる。
    await page.waitForFunction(
      () => {
        const el = document.querySelector('#__nuxt')
        return el !== null && '__vue_app__' in el
      },
      undefined,
      { timeout: 90_000 },
    )
    await expect.poll(() => loginRequests, { timeout: 30_000 }).toBe(1)
    await expect(page.locator('input#email')).toHaveValue('before-hydration@example.com')
    expect(hydrationWarnings).toEqual([])
  })

  test('DEEP-LOGIN-000B: hydration 待機中に入力が無効化された場合は loading を解除する', async ({
    page,
  }) => {
    test.setTimeout(120_000)

    let releaseNuxtScripts!: () => void
    const nuxtScriptsReleased = new Promise<void>((resolve) => {
      releaseNuxtScripts = resolve
    })
    await page.route('**/_nuxt/**', async (route) => {
      if (route.request().resourceType() === 'script') {
        await nuxtScriptsReleased
      }
      await route.continue()
    })

    let loginRequests = 0
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/auth/login') && request.method() === 'POST') {
        loginRequests++
      }
    })

    await page.goto('/login', { waitUntil: 'commit' })
    const button = page.locator('[data-login-submit]')
    await expect(button).toBeEnabled({ timeout: 30_000 })
    await page.locator('input#email').fill('before-hydration@example.com')
    await page.locator('input#password').fill('somepassword123')
    await button.click()
    await expect(button).toBeDisabled()

    await page.locator('input#password').fill('')
    releaseNuxtScripts()
    await page.waitForFunction(
      () => {
        const el = document.querySelector('#__nuxt')
        return el !== null && '__vue_app__' in el
      },
      undefined,
      { timeout: 90_000 },
    )

    await expect(button).toBeEnabled()
    await expect(button).not.toHaveAttribute('aria-busy', 'true')
    expect(loginRequests).toBe(0)
  })

  test('DEEP-LOGIN-000C: SPA 遷移で表示したログインフォームは一度の送信で API を呼ぶ', async ({
    page,
  }) => {
    let loginRequests = 0
    await page.route('**/api/v1/auth/login', async (route) => {
      loginRequests++
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'AUTH_001', message: 'Unauthorized' } }),
      })
    })

    await page.goto('/register')
    await waitForHydration(page)
    await page.getByRole('link', { name: 'すでにアカウントをお持ちですか？' }).click()
    await expect(page).toHaveURL(/\/login/)
    await expect(page.locator('#login-form')).toBeVisible()

    await fillInput(page.locator('input#email'), 'spa-login@example.com')
    await fillPassword(page.locator('input#password'), 'somepassword123')
    await page.locator('[data-login-submit]').click()

    await expect.poll(() => loginRequests).toBe(1)
  })

  test('DEEP-LOGIN-001: 空フォームでの送信は HTML5 バリデーションでブロックされ API は呼ばれない', async ({
    page,
  }) => {
    let apiCalled = false
    page.on('request', (req) => {
      if (req.url().includes('/api/v1/auth/login') && req.method() === 'POST') {
        apiCalled = true
      }
    })

    await page.goto('/login')
    await waitForHydration(page)
    await page.getByRole('button', { name: 'ログイン', exact: true }).click()

    // HTML5 required 属性により送信がブロックされるため、API は呼ばれず URL も変化しない
    await page.waitForTimeout(500)
    expect(apiCalled).toBe(false)
    await expect(page).toHaveURL(/\/login/)
  })

  test('DEEP-LOGIN-002: email のみ入力ではパスワード未入力でブロックされる', async ({ page }) => {
    let apiCalled = false
    page.on('request', (req) => {
      if (req.url().includes('/api/v1/auth/login') && req.method() === 'POST') {
        apiCalled = true
      }
    })

    await page.goto('/login')
    await waitForHydration(page)
    await fillInput(page.locator('input#email'), 'partial@example.com')
    await page.getByRole('button', { name: 'ログイン', exact: true }).click()

    await page.waitForTimeout(500)
    expect(apiCalled).toBe(false)
    await expect(page).toHaveURL(/\/login/)
  })

  test('DEEP-LOGIN-003: 不正な email 形式は HTML5 バリデーションでブロックされる', async ({
    page,
  }) => {
    let apiCalled = false
    page.on('request', (req) => {
      if (req.url().includes('/api/v1/auth/login') && req.method() === 'POST') {
        apiCalled = true
      }
    })

    await page.goto('/login')
    await waitForHydration(page)
    await fillInput(page.locator('input#email'), 'not-an-email')
    await fillPassword(page.locator('input[type="password"]'), 'somepassword123')
    await page.getByRole('button', { name: 'ログイン', exact: true }).click()

    await page.waitForTimeout(500)
    expect(apiCalled).toBe(false)
    await expect(page).toHaveURL(/\/login/)
  })

  test('DEEP-LOGIN-004: ログイン失敗後も入力済みの email が保持される', async ({ page }) => {
    await page.goto('/login')
    await waitForHydration(page)

    const emailInput = page.locator('input#email')
    await fillInput(emailInput, 'test-keep@example.com')
    await fillPassword(page.locator('input[type="password"]'), 'wrongpassword123')
    await page.getByRole('button', { name: 'ログイン', exact: true }).click()

    await expect(page.getByText('ログインに失敗しました')).toBeVisible({ timeout: 10_000 })
    // 失敗後も email 入力欄の値は保持される
    await expect(emailInput).toHaveValue('test-keep@example.com')
  })

  test('DEEP-LOGIN-005: 送信中はボタンが loading 状態になり再クリックされない', async ({
    page,
  }) => {
    // API に遅延を入れて loading 状態を観測可能にする
    await page.route('**/api/v1/auth/login', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 1500))
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: { message: 'Unauthorized' } }),
      })
    })

    await page.goto('/login')
    await waitForHydration(page)
    await fillInput(page.locator('input#email'), 'loading-test@example.com')
    await fillPassword(page.locator('input[type="password"]'), 'somepassword123')

    const button = page.getByRole('button', { name: 'ログイン', exact: true })
    await button.click()
    // PrimeVue Button の loading 状態を確認（spinner アイコンの表示）
    await expect(button.locator('.pi-spin, .p-button-loading-icon').first()).toBeVisible({
      timeout: 1_000,
    })
  })
})
