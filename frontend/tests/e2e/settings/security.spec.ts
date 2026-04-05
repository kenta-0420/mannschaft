import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_SESSIONS = {
  data: [
    {
      id: 1,
      ipAddress: '192.168.1.1',
      userAgent: 'Chrome on Windows',
      isCurrent: true,
      createdAt: '2026-03-01T10:00:00Z',
      lastActiveAt: '2026-04-04T10:00:00Z',
    },
  ],
}

const MOCK_WEBAUTHN = { data: [] }

test.describe('SET-006〜008: セキュリティ設定', () => {
  test('SET-006: セキュリティページが表示される', async ({ page }) => {
    await page.route('**/api/v1/auth/sessions**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_SESSIONS),
      })
    })
    await page.route('**/api/v1/auth/webauthn/credentials**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_WEBAUTHN),
      })
    })

    await page.goto('/settings/security')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'セキュリティ' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SET-007: セッション一覧が表示される', async ({ page }) => {
    await page.route('**/api/v1/auth/sessions**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_SESSIONS),
      })
    })
    await page.route('**/api/v1/auth/webauthn/credentials**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_WEBAUTHN),
      })
    })

    await page.goto('/settings/security')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'セキュリティ' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('Chrome on Windows')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('現在')).toBeVisible()
  })

  test('SET-008: 2FAセットアップボタンが表示される', async ({ page }) => {
    await page.route('**/api/v1/auth/sessions**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_SESSIONS),
      })
    })
    await page.route('**/api/v1/auth/webauthn/credentials**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_WEBAUTHN),
      })
    })

    await page.goto('/settings/security')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'セキュリティ' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: '2FAをセットアップ' })).toBeVisible({
      timeout: 5_000,
    })
  })
})
