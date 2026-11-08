import { expect, test, type BrowserContext, type Page, type TestInfo } from '@playwright/test'

test.use({
  storageState: { cookies: [], origins: [] },
  viewport: { width: 390, height: 844 },
  isMobile: true,
  hasTouch: true,
})

async function assertAnonymousLoginLayout(
  page: Page,
  context: BrowserContext,
  testInfo: TestInfo,
  entry: string,
  historyBase?: string,
): Promise<void> {
  const forbiddenRequests: Array<{ method: string; path: string }> = []
  const cspResponses: Array<{ method: string; status: number }> = []
  let protectedSessionGets = 0
  expect(await context.cookies()).toEqual([])
  expect((await context.storageState()).origins).toEqual([])

  context.on('request', (request) => {
    const method = request.method()
    const path = new URL(request.url()).pathname
    if (method === 'GET' && path === '/api/v1/auth/sessions') protectedSessionGets++
    if (
      method === 'DELETE'
      || (method === 'POST' && [
        '/api/v1/auth/login',
        '/api/v1/auth/logout',
        '/api/v1/account/data-export',
      ].includes(path))
    ) {
      forbiddenRequests.push({ method, path })
    }
  })
  context.on('response', (response) => {
    if (
      response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/security/csp-reports'
    ) {
      cspResponses.push({ method: 'POST', status: response.status() })
    }
  })

  const assertAuthLayout = async (): Promise<void> => {
    // フォームだけでなく、authレイアウトが通常描画されたことを守る。
    await expect(page.getByRole('heading', { level: 1, name: 'Mannschaft', exact: true }))
      .toBeVisible({ timeout: 15_000 })
    const authCard = page.locator('[data-pc-name="card"]').filter({ has: page.locator('#login-form') })
    await expect(authCard).toBeVisible({ timeout: 15_000 })
    await expect(authCard.locator('input[type="password"]')).toBeVisible({ timeout: 15_000 })
  }

  try {
    if (historyBase) {
      await page.goto(historyBase, { waitUntil: 'commit' })
      await expect(page).toHaveURL((url) => url.pathname + url.search === historyBase, { timeout: 15_000 })
      await assertAuthLayout()
    }
    await page.goto(entry, { waitUntil: 'commit' })
    await expect(page).toHaveURL((url) => url.pathname === '/login', { timeout: 15_000 })
    if (entry !== '/login') {
      expect(new URL(page.url()).searchParams.get('redirect')).toBe(entry)
    }

    await assertAuthLayout()
    if (historyBase) {
      await page.goBack()
      await expect(page).toHaveURL((url) => url.pathname + url.search === historyBase, { timeout: 15_000 })
      await assertAuthLayout()
    }
  }
  finally {
    await testInfo.attach('anonymous-network-observations', {
      body: JSON.stringify({ forbiddenRequests, protectedSessionGets, cspResponses }),
      contentType: 'application/json',
    })
    expect(forbiddenRequests).toEqual([])
    expect(protectedSessionGets).toBe(0)
    expect(cspResponses.every(response => response.status === 204)).toBe(true)
  }
}

test('ANON-LAYOUT-001 fresh匿名の直接ログインはauth見出しとCardを表示する', async ({ page, context }, testInfo) => {
  await assertAnonymousLoginLayout(page, context, testInfo, '/login')
})

test('ANON-LAYOUT-002 旧session入口の匿名redirect後もauth見出しとCardを表示する', async ({ page, context }, testInfo) => {
  await assertAnonymousLoginLayout(page, context, testInfo, '/account/sessions?synthetic=1#owned')
})

test('ANON-LAYOUT-003 匿名のセキュリティ直打ち後もauth見出しとCardを表示する', async ({ page, context }, testInfo) => {
  await assertAnonymousLoginLayout(page, context, testInfo, '/settings/security?synthetic=1#owned')
})

test('ANON-LAYOUT-004 初回匿名転送から戻ると元のログイン画面へ戻り転送を繰り返さない', async ({ page, context }, testInfo) => {
  await assertAnonymousLoginLayout(page, context, testInfo, '/account/sessions?synthetic=1#owned', '/login?historyBase=1')
})
