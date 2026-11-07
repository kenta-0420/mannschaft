import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

test.describe('AUTH-009: 招待リンク', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/active-incidents?**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ incidents: [] }),
      }),
    )
  })
  test('AUTH-009a: 未ログインでは招待APIを呼ばずログインと登録を案内する', async ({ page }) => {
    const requests: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/invite/')) requests.push(request.url())
    })
    await page.goto('/invite/validtoken123?folder=7', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await expect(page.getByRole('button', { name: 'ログインして参加' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'アカウントをお持ちでない方' })).toBeVisible()
    expect(requests).toEqual([])
    await page.getByRole('button', { name: 'ログインして参加' }).click()
    await expect(page).toHaveURL(/\/login\?redirect=/)
    expect(new URL(page.url()).searchParams.get('redirect')).toBe('/invite/validtoken123?folder=7')
  })
  test('AUTH-009b: 認証済みでは実API形状の招待先を表示する', async ({ page }) => {
    const hydrationMessages: string[] = []
    page.on('console', (message) => {
      if (
        ['error', 'warning'].includes(message.type()) &&
        /hydration[\s\S]*mismatch/i.test(message.text())
      ) {
        hydrationMessages.push(message.text())
      }
    })
    await page.addInitScript(() => {
      // 既存のユーザー復元契約を使い、previewの実HTTP応答形状を検証する。
      window.localStorage.setItem('tokenExpiresAt', String(Date.now() + 900_000))
      window.localStorage.setItem(
        'currentUser',
        JSON.stringify({ id: 1, email: 'invite@example.test', fullName: '招待テスト' }),
      )
    })
    const unexpectedApiRequests: string[] = []
    const mockedPaths = [
      '/api/v1/invite/validtoken123',
      '/api/v1/me/scope-folders',
      '/api/v1/feature-flags',
      '/api/v1/settings/nav',
      '/api/v1/dashboard/scope-tabs',
      '/api/v1/active-incidents',
    ]
    page.on('request', (request) => {
      const path = new URL(request.url()).pathname
      if (path.startsWith('/api/v1/') && !mockedPaths.includes(path))
        unexpectedApiRequests.push(path)
    })
    // 認証済み起動時の依存も限定モックし、実BEの401によるglobal logoutを防ぐ。
    await page.route('**/api/v1/me/scope-folders?scopeType=TEAM', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      }),
    )
    await page.route('**/api/v1/feature-flags', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      }),
    )
    await page.route('**/api/v1/settings/nav', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { features: [] } }),
      }),
    )
    await page.route('**/api/v1/dashboard/scope-tabs?**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            items: [],
            page: 0,
            page_size: 10,
            total_pages: 0,
            total_count: 0,
            has_next: false,
            has_prev: false,
          },
        }),
      }),
    )
    await page.route('**/api/v1/invite/validtoken123', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { targetName: 'テストチーム', targetType: 'TEAM', roleName: 'MEMBER', valid: true },
        }),
      }),
    )
    await page.goto('/invite/validtoken123', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await expect(page.getByText('テストチーム', { exact: true })).toBeVisible()
    await expect(page.getByTestId('invite-join-button')).toBeVisible()
    await expect(page.locator('[data-testid="invite-folder-picker"] select')).toBeEnabled()
    expect(unexpectedApiRequests).toEqual([])
    expect(hydrationMessages).toEqual([])
  })
})
