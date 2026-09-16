import { test, expect, type Browser, type BrowserContext, type Page } from '@playwright/test'

/** B0-J1: 実DBで管理者1＋一般2を別BrowserContextへfresh loginする専用proof。 */
test.describe('B0-J1: 個人ダッシュボードから共有スコープへ', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  const credentials = [
    { role: 'admin', email: process.env.TEST_ADMIN_EMAIL, password: process.env.TEST_ADMIN_PASSWORD },
    { role: 'user', email: process.env.TEST_USER_EMAIL, password: process.env.TEST_USER_PASSWORD },
    { role: 'user2', email: process.env.TEST_USER2_EMAIL, password: process.env.TEST_USER2_PASSWORD },
  ] as const

  function requireCredentials(): readonly [{ role: 'admin'; email: string; password: string }, { role: 'user'; email: string; password: string }, { role: 'user2'; email: string; password: string }] {
    if (credentials.some((item) => !item.email || !item.password)) throw new Error('B0-J1: 3利用者の認証情報が不足しています')
    const emails = credentials.map((item) => item.email!.toLowerCase())
    if (new Set(emails).size !== 3) throw new Error('B0-J1: 管理者・一般・一般2のemailは相互に異なる必要があります')
    return credentials as readonly [{ role: 'admin'; email: string; password: string }, { role: 'user'; email: string; password: string }, { role: 'user2'; email: string; password: string }]
  }

  async function loginFresh(page: Page, email: string, password: string): Promise<void> {
    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    await page.locator('input#email').fill(email)
    await page.locator('input#password').fill(password)
    await page.getByRole('button', { name: 'ログイン', exact: true }).click()
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 20_000 })
  }

  async function me(page: Page): Promise<string> {
    const response = await page.request.get('/api/v1/users/me')
    expect(response.status(), 'GET /api/v1/users/me').toBe(200)
    const body = await response.json() as { data?: { id?: string | number }; id?: string | number }
    const id = body.data?.id ?? body.id
    expect(id, '認証主体のid').toBeTruthy()
    return String(id)
  }

  async function scopeLinks(page: Page): Promise<void> {
    await expect(page).toHaveURL(/\/dashboard/)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    for (const [label, selector, route] of [
      ['チーム', 'a[href^="/teams/"]', /\/teams\//],
      ['組織', 'a[href^="/organizations/"]', /\/organizations\//],
      ['村', 'a[href^="/villages/"]', /\/villages\//],
    ] as const) {
      const link = page.locator(selector).first()
      await expect(link, `${label}導線`).toBeVisible()
      await link.click()
      await expect(page).toHaveURL(route)
      await expect(page.locator('body')).toBeVisible()
      await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
      await expect(page).toHaveURL(/\/dashboard/)
    }
  }

  test('B0-J1 proof: 3主体の別context・ID分離・UI/API権限境界', async ({ browser }: { browser: Browser }) => {
    const verifiedCredentials = requireCredentials()
    const contexts: BrowserContext[] = []
    try {
      const pages: Page[] = []
      for (const credential of verifiedCredentials) {
        const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
        contexts.push(context)
        const page = await context.newPage()
        pages.push(page)
        await loginFresh(page, credential.email, credential.password)
      }
      const ids = await Promise.all(pages.map((page) => me(page)))
      expect(new Set(ids).size, '3利用者のIDが相互に異なること').toBe(3)
      for (const page of pages) await scopeLinks(page)
      for (const page of pages.slice(1)) {
        await expect(page.locator('a[href*="/admin/"]')).toHaveCount(0)
        const denied = await page.request.get('/api/v1/admin/users')
        expect([401, 403], '一般利用者の管理API拒否').toContain(denied.status())
      }
    } finally {
      await Promise.all(contexts.map((context) => context.close()))
    }
  })
})
