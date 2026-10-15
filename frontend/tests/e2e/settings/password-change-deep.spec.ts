// SET-DEEP-006〜010: パスワード変更フォームの深掘りテスト
// passwordError computed のリアクティブ動作 / canSubmit 連動 / changePassword API 呼び出しを検証する

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { fillPassword, clearAndFillInput } from '../helpers/form'

const MOCK_PROFILE = {
  data: {
    id: 1,
    displayName: 'テストユーザー',
    email: 'test@example.com',
    hasPassword: true,
  },
}

/**
 * password.vue の onMounted で getProfile() が呼ばれて hasPassword が判定される。
 * モックで hasPassword: true を返し、現在パスワード欄を含む 3 入力フォームを描画させる。
 */
async function setupPasswordMocks(page: import('@playwright/test').Page) {
  await page.route('**/api/v1/users/me', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PROFILE),
      })
    } else {
      await route.continue()
    }
  })
}

/**
 * password.vue 内の Password コンポーネントは v-if 順で
 *   [0] = 現在のパスワード
 *   [1] = 新しいパスワード
 *   [2] = 新しいパスワード（確認）
 * の 3 つの input[type=password] を描画する。
 */
function passwordInputs(page: import('@playwright/test').Page) {
  return page.locator('input[type="password"]')
}

test.describe('SET-DEEP password-change: パスワード変更フォーム深掘り', () => {
  test('SET-DEEP-006: 7 文字の新パスワードで「8 文字以上」エラーが reactive に表示される', async ({
    page,
  }) => {
    await setupPasswordMocks(page)

    await page.goto('/settings/password')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'パスワード変更' })).toBeVisible({
      timeout: 10_000,
    })

    const inputs = passwordInputs(page)
    // [1] = 新しいパスワード（feedback あり）
    await fillPassword(inputs.nth(1), 'Short1!', { closeFeedback: true })

    // computed passwordError が reactive に更新される
    await expect(page.getByText('パスワードは8文字以上で入力してください')).toBeVisible({
      timeout: 5_000,
    })
    // 当然送信ボタンは disabled のまま
    await expect(page.getByRole('button', { name: 'パスワードを変更' })).toBeDisabled()
  })

  test('SET-DEEP-007: 新パスワードと確認パスワードが不一致だと「一致しません」エラーが出る', async ({
    page,
  }) => {
    await setupPasswordMocks(page)

    await page.goto('/settings/password')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'パスワード変更' })).toBeVisible({
      timeout: 10_000,
    })

    const inputs = passwordInputs(page)
    // 8 文字以上だが新と確認で異なる
    await fillPassword(inputs.nth(1), 'NewPassword2026!', { closeFeedback: true })
    await fillPassword(inputs.nth(2), 'DifferentPass2026!')

    await expect(page.getByText('パスワードが一致しません')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByRole('button', { name: 'パスワードを変更' })).toBeDisabled()
  })

  test('SET-DEEP-008: 全項目空のままでは送信ボタンが disabled になっている', async ({
    page,
  }) => {
    await setupPasswordMocks(page)

    await page.goto('/settings/password')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'パスワード変更' })).toBeVisible({
      timeout: 10_000,
    })

    // 何も入力しない初期状態で disabled
    await expect(page.getByRole('button', { name: 'パスワードを変更' })).toBeDisabled()
  })

  test('SET-DEEP-009: 現在パスワードを入れずに新パス＋確認だけ入力しても disabled', async ({
    page,
  }) => {
    await setupPasswordMocks(page)

    await page.goto('/settings/password')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'パスワード変更' })).toBeVisible({
      timeout: 10_000,
    })

    const inputs = passwordInputs(page)
    // currentPassword を空のままにし、新と確認だけ正しく入力
    await fillPassword(inputs.nth(1), 'BrandNewPass2026!', { closeFeedback: true })
    await fillPassword(inputs.nth(2), 'BrandNewPass2026!')

    // canSubmit は currentPassword を要求するため依然 disabled
    await expect(page.getByRole('button', { name: 'パスワードを変更' })).toBeDisabled()
  })

  test('SET-DEEP-010: 全項目正しく入力すると enabled になり PATCH API が呼ばれて成功通知が出る', async ({
    page,
  }) => {
    await setupPasswordMocks(page)

    // PATCH /api/v1/users/me/password を成功扱いでモック
    await page.route('**/api/v1/users/me/password', async (route) => {
      if (route.request().method() === 'PATCH') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { message: 'ok' } }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto('/settings/password')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'パスワード変更' })).toBeVisible({
      timeout: 10_000,
    })

    const inputs = passwordInputs(page)
    await fillPassword(inputs.nth(0), 'CurrentPass2026!')
    await fillPassword(inputs.nth(1), 'BrandNewPass2026!', { closeFeedback: true })
    await fillPassword(inputs.nth(2), 'BrandNewPass2026!')

    const button = page.getByRole('button', { name: 'パスワードを変更' })
    await expect(button).toBeEnabled()

    const patchPromise = page.waitForRequest(
      (req) =>
        req.url().includes('/api/v1/users/me/password') && req.method() === 'PATCH',
      { timeout: 10_000 },
    )

    await button.click()
    const patchReq = await patchPromise

    const body = JSON.parse(patchReq.postData() ?? '{}')
    expect(body.currentPassword).toBe('CurrentPass2026!')
    expect(body.newPassword).toBe('BrandNewPass2026!')

    // 成功通知が表示される
    await expect(page.getByText('パスワードを変更しました')).toBeVisible({ timeout: 10_000 })

    // 送信後フォームはクリアされる
    await expect(inputs.nth(0)).toHaveValue('')
    // クリア後に再度短いパスを入れてバリデーションが再度発火することも確認
    await clearAndFillInput(inputs.nth(1), 'short')
    await expect(page.getByText('パスワードは8文字以上で入力してください')).toBeVisible({
      timeout: 5_000,
    })
  })
})
