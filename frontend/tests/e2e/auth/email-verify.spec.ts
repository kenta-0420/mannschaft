import { test, expect } from '@playwright/test'

test.use({ storageState: { cookies: [], origins: [] } })

test.describe('AUTH-010: メール認証', () => {
  test('AUTH-010a: tokenなしでアクセスすると確認メール送信済み案内が表示される', async ({
    page,
  }) => {
    await page.goto('/verify-email?email=test@example.com')
    await expect(page.getByRole('heading', { name: '確認メールを送信しました' })).toBeVisible()
    await expect(page.getByRole('button', { name: '確認メールを再送信' })).toBeVisible()
  })

  test('AUTH-010b: 無効なtokenで認証すると失敗メッセージが表示される', async ({ page }) => {
    await page.goto('/verify-email?token=invalidtoken')
    await expect(page.getByRole('heading', { name: '認証に失敗しました' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('ログインページへ戻る')).toBeVisible()
  })

  test('AUTH-010c: 有効なtokenで認証すると完了メッセージが表示される', async ({ page }) => {
    await page.route('**/api/v1/auth/verify-email', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { verified: true } }),
      })
    })

    await page.goto('/verify-email?token=validtoken123')
    await expect(page.getByRole('heading', { name: '認証が完了しました' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: 'ログインへ' })).toBeVisible()
  })
})
