import { test, expect } from '@playwright/test'

test.use({ storageState: { cookies: [], origins: [] } })

test.describe('AUTH-009: 招待リンク', () => {
  test('AUTH-009a: 無効なトークンにアクセスすると招待無効エラーが表示される', async ({ page }) => {
    await page.goto('/invite/invalidtoken123')

    // ローディング後にエラー表示
    await expect(
      page.getByText('招待リンクが無効です').or(page.getByText('この招待リンクは無効です')),
    ).toBeVisible({ timeout: 10_000 })
  })

  test('AUTH-009b: 未ログイン状態で有効な招待ページを開くとログインボタンが表示される', async ({
    page,
  }) => {
    // 有効なトークンは実環境依存のためAPIをモックして検証
    await page.route('**/api/v1/invite/**', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 1,
              name: 'テストチーム',
              type: 'TEAM',
              description: 'E2Eテスト用チーム',
              iconUrl: null,
              roleName: 'MEMBER',
              expiresAt: null,
              isValid: true,
            },
          }),
        })
      }
    })

    await page.goto('/invite/validtoken123')

    await expect(page.getByRole('button', { name: 'ログインして参加' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('テストチーム')).toBeVisible()
  })
})
