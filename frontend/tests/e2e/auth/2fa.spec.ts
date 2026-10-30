import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

test.describe('AUTH-011〜013: 2FA リカバリー', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/auth/2fa/recovery/request', async (route) => {
      expect(route.request().method()).toBe('POST')
      expect(route.request().postDataJSON()).toEqual({ mfaSessionToken: 'test-session-token' })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { message: 'リカバリーメールを送信しました' } }),
      })
    })
    await page.goto('/2fa-recovery?session=test-session-token')
    await waitForHydration(page)
  })

  test('AUTH-011: 2FAリカバリーページが表示される', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'アカウントリカバリー', exact: true })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: 'リカバリーメールを送信', exact: true })).toBeVisible()
  })

  test('AUTH-012: メール送信後にリカバリートークン入力欄が表示される', async ({ page }) => {
    const responsePromise = page.waitForResponse('**/api/v1/auth/2fa/recovery/request')
    await page.getByRole('button', { name: 'リカバリーメールを送信', exact: true }).click()
    expect((await responsePromise).status()).toBe(200)
    const input = page.getByPlaceholder('リカバリートークン', { exact: true })
    await expect(input).toHaveCount(1)
    await expect(input).toBeVisible({ timeout: 10_000 })
  })

  test('AUTH-013: 空のリカバリートークンで確認ボタンが無効', async ({ page }) => {
    const responsePromise = page.waitForResponse('**/api/v1/auth/2fa/recovery/request')
    await page.getByRole('button', { name: 'リカバリーメールを送信', exact: true }).click()
    expect((await responsePromise).status()).toBe(200)
    const input = page.getByPlaceholder('リカバリートークン', { exact: true })
    await expect(input).toHaveCount(1)
    await expect(input).toBeVisible({ timeout: 10_000 })
    await expect(input).toHaveValue('')
    const submitBtn = page.getByRole('button', { name: 'リカバリーを確認', exact: true })
    await expect(submitBtn).toHaveCount(1)
    await expect(submitBtn).toBeVisible()
    await expect(submitBtn).toBeDisabled()
  })
})
