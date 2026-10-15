import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

test.describe('AUTH-009: 招待リンク', () => {
  test('AUTH-009a: 未ログインでは招待APIを呼ばずログインと登録を案内する', async ({ page }) => {
    const requests: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/invite/')) requests.push(request.url())
    })
    await page.goto('/invite/validtoken123?folder=7')
    await waitForHydration(page)
    await expect(page.getByRole('button', { name: 'ログインして参加' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'アカウントをお持ちでない方' })).toBeVisible()
    expect(requests).toEqual([])
    await page.getByRole('button', { name: 'ログインして参加' }).click()
    await expect(page).toHaveURL(/\/login\?redirect=/)
    expect(new URL(page.url()).searchParams.get('redirect')).toBe('/invite/validtoken123?folder=7')
  })
  test('AUTH-009b: 認証済みでは実API形状の招待先を表示する', async ({ page }) => {
    await page.addInitScript(() => {
      // 既存のユーザー復元契約を使い、previewの実HTTP応答形状を検証する。
      window.localStorage.setItem(
        'currentUser',
        JSON.stringify({ id: 1, email: 'invite@example.test', fullName: '招待テスト' }),
      )
    })
    await page.route('**/api/v1/auth/refresh', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { accessToken: 'mock-access-token', expiresIn: 900 } }),
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
    await page.goto('/invite/validtoken123')
    await waitForHydration(page)
    await expect(page.getByText('テストチーム', { exact: true })).toBeVisible()
    await expect(page.getByTestId('invite-join-button')).toBeVisible()
  })
})
