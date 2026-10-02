import { test, expect } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const TEST_EMAIL = 'e2e-pwui-1782136885@test.mannschaft.local'
const TEST_PASSWORD = 'Passw0rd!2026'
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'

test.describe('OAUTH-LINK: /settings/linked-accounts OAuth連携', () => {
  test('OAUTH-LINK-001: 未認証でアクセスすると /login にリダイレクトされる', async ({ page }) => {
    await page.goto('/settings/linked-accounts')
    await page.waitForURL(/\/login/, { timeout: 10_000 })
    await expect(page).toHaveURL(/\/login/)
  })

  test('OAUTH-LINK-002: 認証済みでページが表示され「Googleと連携」ボタンが見える', async ({
    page,
  }) => {
    await loginViaApi(page, { email: TEST_EMAIL, password: TEST_PASSWORD }, { apiBaseUrl: API_BASE })
    await page.goto('/settings/linked-accounts')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: /アカウント連携/ })).toBeVisible({
      timeout: 15_000,
    })
    // OAuth連携セクションが表示される（「Googleと連携」ボタン or 「解除」ボタン）
    await expect(page.getByText('OAuth連携')).toBeVisible({ timeout: 10_000 })
    // 「Googleと連携」ボタンまたは連携済みのGoogle表示がある
    const googlePresence = page.getByText(/Google/i).first()
    await expect(googlePresence).toBeVisible({ timeout: 10_000 })
  })

  test('OAUTH-LINK-004: 未認証で auth-url API を叩くと 401', async ({ page }) => {
    const res = await page.request.get(`${API_BASE}/api/v1/users/me/oauth/link/GOOGLE/auth-url`)
    expect(res.status()).toBe(401)
  })

  test('OAUTH-LINK-005: auth-url API が正しい構造の authUrl を返す', async ({ page }) => {
    await loginViaApi(page, { email: TEST_EMAIL, password: TEST_PASSWORD }, { apiBaseUrl: API_BASE })

    const res = await page.request.get(
      `${API_BASE}/api/v1/users/me/oauth/link/GOOGLE/auth-url`,
    )

    // 連携済みの場合は 409、未連携なら 200
    if (res.status() === 409) {
      test.skip(true, 'このユーザーはすでにGoogleと連携済み(409)のためスキップ')
      return
    }

    expect(res.status()).toBe(200)
    const body = await res.json()
    expect(body.data).toHaveProperty('authUrl')

    const authUrl = body.data.authUrl as string
    expect(authUrl).toContain('accounts.google.com')
    expect(authUrl).toMatch(/client_id=[^&]+/)   // client_id が空でない
    expect(authUrl).toContain('redirect_uri=')
    expect(authUrl).toContain('scope=')
    expect(authUrl).toContain('state=')
    expect(authUrl).toContain('response_type=code')
  })
})
