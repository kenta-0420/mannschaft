/**
 * プライバシーポリシー同意機能 E2E テスト
 * 設計書: docs/features/F_privacy_policy.md §8
 *
 * AC-5: /privacy ページが認証不要でアクセス可能
 * AC-6: 同意チェックボックス未チェックで登録ボタンを押すとバリデーションエラーが表示される
 * AC-7: チェック済みで登録すると POST body に正しいフィールドが含まれる
 * AC-8: 実際に登録すると DB に同意日時が記録される
 */
import { execSync } from 'node:child_process'
import { test, expect } from '@playwright/test'
import { waitForHydration } from './helpers/wait'
import { fillInput, fillPassword } from './helpers/form'

// ─────────────────────────────────────────────
// AC-5: /privacy ページが認証不要でアクセス可能
// ─────────────────────────────────────────────
test.describe('AC-5: /privacy ページが未ログイン状態でアクセス可能', () => {
  // 未ログイン状態（ストレージリセット）
  test.use({ storageState: { cookies: [], origins: [] } })

  test('未ログイン状態で /privacy にアクセスするとログインページにリダイレクトされない', async ({
    page,
  }) => {
    await page.goto('/privacy')
    await waitForHydration(page)

    // ログインページにリダイレクトされていないこと
    await expect(page).not.toHaveURL(/\/login/, { timeout: 8_000 })

    // "プライバシーポリシー" テキストが表示されること
    await expect(page.getByText('プライバシーポリシー').first()).toBeVisible({ timeout: 8_000 })
  })
})

// ─────────────────────────────────────────────────────────────────────
// AC-6: 同意チェックボックス未チェックで登録ボタンを押すとバリデーションエラー
// ─────────────────────────────────────────────────────────────────────
test.describe('AC-6: 同意チェックボックス未チェックでバリデーションエラーが表示される', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('同意チェックなしで送信するとプライバシーポリシー同意必須エラーが表示される', async ({
    page,
  }) => {
    await page.goto('/register')
    await waitForHydration(page)

    // 必須フィールドを入力（privacyPolicyAccepted はチェックしない）
    await fillInput(page.locator('input#email'), `e2e-ac6-${Date.now()}@example.com`)
    await fillPassword(page.locator('input#password'), 'Passw0rd!2026', { closeFeedback: true })
    await fillInput(page.locator('input#postalCode'), '123-4567')
    await fillInput(page.locator('input#lastName'), 'テスト')
    await fillInput(page.locator('input#firstName'), '太郎')
    await fillInput(page.locator('input#displayName'), 'test_taro')

    // 生年月日を入力（dateピッカー）
    const birthDateInput = page.locator('input#birthDate')
    await birthDateInput.fill('1990-01-01')

    // privacyPolicyAccepted チェックボックスはクリックしない
    // 送信ボタンをクリック
    await page.getByRole('button', { name: 'アカウント作成' }).click()

    // プライバシーポリシー関連エラーメッセージが表示されること
    // バリデーションエラー: "プライバシーポリシーへの同意が必要です"
    await expect(
      page.getByText('プライバシーポリシーへの同意が必要です'),
    ).toBeVisible({ timeout: 8_000 })

    // ページ遷移していないこと（登録画面に留まる）
    await expect(page).toHaveURL(/\/register/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// AC-7: チェック済みで登録すると POST body に正しいフィールドが含まれる
// ─────────────────────────────────────────────────────────────────────────────
test.describe('AC-7: 同意チェック済みの場合 POST body に privacyPolicyAccepted と version が含まれる', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('登録フォーム送信時のリクエストに privacyPolicyAccepted:true と privacyPolicyVersion:1.1.0 が含まれる', async ({
    page,
  }) => {
    await page.goto('/register')
    await waitForHydration(page)

    // リクエストを横取りするための Promise を先に設定
    let capturedBody: Record<string, unknown> | null = null
    await page.route('**/api/v1/auth/register', async (route) => {
      const request = route.request()
      try {
        capturedBody = request.postDataJSON() as Record<string, unknown>
      } catch {
        capturedBody = null
      }
      // モックレスポンスを返す（実際に登録しない）
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: { message: 'Registration successful' } }),
      })
    })

    // フォームを埋める
    await fillInput(page.locator('input#email'), `e2e-ac7-${Date.now()}@example.com`)
    await fillPassword(page.locator('input#password'), 'Passw0rd!2026', { closeFeedback: true })
    await fillInput(page.locator('input#postalCode'), '123-4567')
    await fillInput(page.locator('input#lastName'), 'テスト')
    await fillInput(page.locator('input#firstName'), '七郎')
    await fillInput(page.locator('input#displayName'), 'test_shichiro')

    const birthDateInput = page.locator('input#birthDate')
    await birthDateInput.fill('1990-01-01')

    // privacyPolicyAccepted チェックボックスをチェックする
    // PrimeVue v4 の Checkbox: data-p-checked 属性付きの div ルートを直接クリック
    // label の中にある NuxtLink をクリックするとページ遷移してしまうため div を使う
    const checkboxRoot = page.locator('[data-p-checked]').last()
    await expect(checkboxRoot).toBeVisible({ timeout: 8_000 })
    await checkboxRoot.click()

    // チェックが入ったことを確認（data-p-checked="true" になっている）
    await expect(checkboxRoot).toHaveAttribute('data-p-checked', 'true', { timeout: 5_000 })

    // 送信ボタンをクリック
    await page.getByRole('button', { name: 'アカウント作成' }).click()

    // API が呼ばれるまで待機
    await page.waitForURL(/\/verify-email/, { timeout: 15_000 })

    // キャプチャされたリクエストボディを検証
    expect(capturedBody).not.toBeNull()
    expect(capturedBody!['privacyPolicyAccepted']).toBe(true)
    expect(capturedBody!['privacyPolicyVersion']).toBe('1.1.0')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// AC-8: 実際に登録すると DB に同意日時が記録される
// ─────────────────────────────────────────────────────────────────────────────
test.describe('AC-8: 実際に登録すると DB に privacy_policy_accepted_at が記録される', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('登録成功後 MySQL の privacy_policy_accepted_at が NOT NULL かつ version が 1.1.0 になる', async ({
    page,
  }) => {
    const uniqueEmail = `e2e-ac8-${Date.now()}@example.com`

    await page.goto('/register')
    await waitForHydration(page)

    // フォームを埋める（モックなし・実際に登録）
    await fillInput(page.locator('input#email'), uniqueEmail)
    await fillPassword(page.locator('input#password'), 'Passw0rd!2026', { closeFeedback: true })
    await fillInput(page.locator('input#postalCode'), '123-4567')
    await fillInput(page.locator('input#lastName'), '実機')
    await fillInput(page.locator('input#firstName'), '登録')
    await fillInput(page.locator('input#displayName'), 'e2e_real_reg')

    const birthDateInput = page.locator('input#birthDate')
    await birthDateInput.fill('1990-01-01')

    // privacyPolicyAccepted チェックボックスをチェック
    // PrimeVue v4: data-p-checked 属性を持つ div ルートを直接クリック
    const checkboxRootAc8 = page.locator('[data-p-checked]').last()
    await expect(checkboxRootAc8).toBeVisible({ timeout: 8_000 })
    await checkboxRootAc8.click()

    // チェック済み確認
    await expect(checkboxRootAc8).toHaveAttribute('data-p-checked', 'true', { timeout: 5_000 })

    // 送信
    await page.getByRole('button', { name: 'アカウント作成' }).click()

    // 確認メール送信ページへの遷移を待つ
    await expect(page).toHaveURL(/\/verify-email/, { timeout: 20_000 })
    await expect(page.getByRole('heading', { name: '確認メールを送信しました' })).toBeVisible({
      timeout: 10_000,
    })

    // MySQL で当該 email の同意記録を確認
    // WSL2 経由で docker exec
    const escapedEmail = uniqueEmail.replace(/'/g, "\\'")
    let dbResult: string
    try {
      dbResult = execSync(
        `wsl.exe -e docker exec mannschaft-mysql mysql -u root -proot mannschaft -sN -e "SELECT CONCAT(IFNULL(privacy_policy_accepted_at,'NULL'), '|', IFNULL(privacy_policy_version,'NULL')) FROM users WHERE email='${escapedEmail}' LIMIT 1;"`,
        { encoding: 'utf-8', timeout: 15_000 },
      ).trim()
    } catch (err) {
      throw new Error(`MySQL クエリ失敗: ${String(err)}`)
    }

    // 結果が取得できること（行が存在すること）
    expect(dbResult).not.toBe('')
    expect(dbResult).not.toBe('NULL|NULL')

    const [acceptedAt, version] = dbResult.split('|')

    // privacy_policy_accepted_at が NULL でないこと
    expect(acceptedAt).not.toBe('NULL')
    expect(acceptedAt).not.toBe('')

    // privacy_policy_version が '1.1.0' であること
    expect(version).toBe('1.1.0')
  })
})
