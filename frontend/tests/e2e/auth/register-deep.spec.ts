import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { fillInput, fillPassword } from '../helpers/form'

test.use({ storageState: { cookies: [], origins: [] } })

/**
 * register.vue は vee-validate + Zod でクライアントサイドバリデーションを行う。
 * submitted フラグが true になって初めてエラーメッセージが表示される設計のため、
 * バリデーションエラーを観測するには必ず一度送信ボタンを押す必要がある。
 */
test.describe('AUTH-DEEP register: 新規登録フォーム深掘り', () => {
  test('DEEP-REG-001: 不正な email 形式で送信するとフォーマットエラーが表示される', async ({
    page,
  }) => {
    await page.goto('/register')
    await waitForHydration(page)

    await fillInput(page.locator('input#email'), 'invalid-email-format')
    await fillPassword(page.locator('input[type="password"]'), 'ValidPass2026!', { closeFeedback: true })
    await fillInput(page.locator('input#postalCode'), '123-4567')
    await fillInput(page.locator('input#lastName'), '山田')
    await fillInput(page.locator('input#firstName'), '太郎')
    await fillInput(page.locator('input#displayName'), 'yamada_taro')

    await page.getByRole('button', { name: 'アカウント作成' }).click()

    await expect(page.getByText('有効なメールアドレスを入力してください')).toBeVisible({
      timeout: 5_000,
    })
    await expect(page).toHaveURL(/\/register/)
  })

  test('DEEP-REG-002: 7 文字以下のパスワードは「8 文字以上」エラーが表示される', async ({
    page,
  }) => {
    await page.goto('/register')
    await waitForHydration(page)

    await fillInput(page.locator('input#email'), 'short-pw@example.com')
    await fillPassword(page.locator('input[type="password"]'), 'Short1!', { closeFeedback: true }) // 7 文字
    await fillInput(page.locator('input#postalCode'), '123-4567')
    await fillInput(page.locator('input#lastName'), '山田')
    await fillInput(page.locator('input#firstName'), '太郎')
    await fillInput(page.locator('input#displayName'), 'yamada_taro')

    await page.getByRole('button', { name: 'アカウント作成' }).click()

    await expect(page.getByText('パスワードは8文字以上で入力してください')).toBeVisible({
      timeout: 5_000,
    })
  })

  test('DEEP-REG-003: 不正な郵便番号形式で送信するとフォーマットエラーが表示される', async ({
    page,
  }) => {
    await page.goto('/register')
    await waitForHydration(page)

    await fillInput(page.locator('input#email'), 'bad-postal@example.com')
    await fillPassword(page.locator('input[type="password"]'), 'ValidPass2026!', { closeFeedback: true })
    await fillInput(page.locator('input#postalCode'), 'ABCDEFG') // 数字 7 桁ではない
    await fillInput(page.locator('input#lastName'), '山田')
    await fillInput(page.locator('input#firstName'), '太郎')
    await fillInput(page.locator('input#displayName'), 'yamada_taro')

    await page.getByRole('button', { name: 'アカウント作成' }).click()

    await expect(
      page.getByText('郵便番号の形式が正しくありません（例: 123-4567）'),
    ).toBeVisible({ timeout: 5_000 })
  })

  test('DEEP-REG-004: 51 文字以上の姓は「50 文字以内」エラーが表示される', async ({ page }) => {
    await page.goto('/register')
    await waitForHydration(page)

    const tooLongLastName = 'あ'.repeat(51)

    await fillInput(page.locator('input#email'), 'long-name@example.com')
    await fillPassword(page.locator('input[type="password"]'), 'ValidPass2026!', { closeFeedback: true })
    await fillInput(page.locator('input#postalCode'), '123-4567')
    await fillInput(page.locator('input#lastName'), tooLongLastName)
    await fillInput(page.locator('input#firstName'), '太郎')
    await fillInput(page.locator('input#displayName'), 'yamada_taro')

    await page.getByRole('button', { name: 'アカウント作成' }).click()

    await expect(page.getByText('姓は50文字以内で入力してください')).toBeVisible({
      timeout: 5_000,
    })
  })

  test('DEEP-REG-005: 全項目空送信で全フィールドのエラーメッセージが同時表示される', async ({
    page,
  }) => {
    await page.goto('/register')
    await waitForHydration(page)
    await page.getByRole('button', { name: 'アカウント作成' }).click()

    // 6 つの必須項目すべてのエラーメッセージが同時に表示されることを確認
    await expect(page.getByText('メールアドレスは必須です')).toBeVisible({ timeout: 5_000 })
    // password は zod の min(8) に引っかかる（必須メッセージは別になる）
    await expect(page.getByText('パスワードは8文字以上で入力してください')).toBeVisible()
    await expect(page.getByText('郵便番号は必須です')).toBeVisible()
    await expect(page.getByText('姓は必須です')).toBeVisible()
    await expect(page.getByText('名は必須です')).toBeVisible()
    await expect(page.getByText('表示名は必須です')).toBeVisible()
    await expect(page).toHaveURL(/\/register/)
  })
})
