/**
 * 目安箱フィードバック投稿UI 実機E2Eテスト
 *
 * 前提条件:
 *   - バックエンド http://localhost:8080 が起動済み
 *   - フロントエンド http://localhost:3001（または BASE_URL）が起動済み
 *   - e2e-user@test.mannschaft.local / TestPass2026! が DB に存在する
 *
 * テスト対象:
 *   - PR #1875: ナビバーへの目安箱ボタン追加 + FeedbackSubmitModal
 */

import { test, expect } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

// 各テストはフレッシュな認証状態から開始する
test.use({ storageState: { cookies: [], origins: [] } })

test.describe('目安箱フィードバック投稿', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaApi(page, { email: USER_EMAIL, password: USER_PASSWORD })
  })

  // FB-001: ナビバーに目安箱ボタンが表示される
  test('FB-001: ナビバーに pi-comment アイコンのボタンが表示される', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    // pi-comment アイコンを持つ Button が存在する（ナビバー内のデスクトップ用ボタン）
    const feedbackBtn = page.locator('button').filter({ has: page.locator('.pi-comment') }).first()
    await expect(feedbackBtn).toBeVisible({ timeout: 15_000 })
  })

  // FB-002: 目安箱ボタンクリックでモーダルが開く
  test('FB-002: 目安箱ボタンをクリックするとモーダルが開き「ご意見」が表示される', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    const feedbackBtn = page.locator('button').filter({ has: page.locator('.pi-comment') }).first()
    await feedbackBtn.click()

    // PrimeVue Dialog が表示される
    const dialog = page.locator('[role="dialog"]')
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // ヘッダーにタイトルが含まれる（i18n: feedback.submit.title = "ご意見・ご要望（目安箱）"）
    await expect(dialog).toContainText('ご意見')
  })

  // FB-003: フォームが空の状態では送信ボタンが無効
  test('FB-003: カテゴリ・タイトル・本文が空のとき送信ボタンが無効', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    const feedbackBtn = page.locator('button').filter({ has: page.locator('.pi-comment') }).first()
    await feedbackBtn.click()

    const dialog = page.locator('[role="dialog"]')
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // 送信ボタンは disabled（isValid = false のため）
    // PrimeVue Button は disabled 属性を持つ
    const submitBtn = dialog.locator('button').filter({ hasText: '送信' }).last()
    await expect(submitBtn).toBeDisabled()
  })

  // FB-004: フォームを入力して送信 → 成功通知でモーダルが閉じる（CRUD 一気通貫）
  test('FB-004: フォーム入力して送信→成功トースト表示→モーダルが閉じる', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    const feedbackBtn = page.locator('button').filter({ has: page.locator('.pi-comment') }).first()
    await feedbackBtn.click()

    const dialog = page.locator('[role="dialog"]')
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // カテゴリ選択（PrimeVue Select コンポーネント）
    const selectTrigger = dialog.locator('[data-pc-name="select"], .p-select, [role="combobox"]').first()
    await selectTrigger.click()

    // ドロップダウンオプションが表示されるまで待機
    const bugOption = page.locator('[role="option"]').filter({ hasText: 'バグ報告' })
    await expect(bugOption).toBeVisible({ timeout: 5_000 })
    await bugOption.click()

    // タイトル入力
    const titleInput = dialog.locator('input[type="text"], input.p-inputtext').first()
    await titleInput.fill('E2Eテスト用バグ報告')

    // 本文入力
    const bodyTextarea = dialog.locator('textarea').first()
    await bodyTextarea.fill('これはE2Eテストによる自動投稿です（PR #1875 検証）。')

    // 送信ボタンが有効になっていることを確認
    const submitBtn = dialog.locator('button').filter({ hasText: '送信' }).last()
    await expect(submitBtn).toBeEnabled({ timeout: 3_000 })

    // 送信
    await submitBtn.click()

    // 成功トースト表示確認（i18n: feedback.submit.success = "送信しました。ありがとうございます！"）
    const successToast = page.locator('.p-toast-message, [role="alert"]').filter({ hasText: '送信しました' })
    await expect(successToast).toBeVisible({ timeout: 15_000 })

    // モーダルが閉じる
    await expect(dialog).not.toBeVisible({ timeout: 10_000 })
  })

  // FB-005: キャンセルボタンでモーダルが閉じる
  test('FB-005: キャンセルボタンをクリックするとモーダルが閉じる', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    const feedbackBtn = page.locator('button').filter({ has: page.locator('.pi-comment') }).first()
    await feedbackBtn.click()

    const dialog = page.locator('[role="dialog"]')
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // キャンセルボタンをクリック
    const cancelBtn = dialog.locator('button').filter({ hasText: 'キャンセル' })
    await expect(cancelBtn).toBeVisible()
    await cancelBtn.click()

    // モーダルが閉じる
    await expect(dialog).not.toBeVisible({ timeout: 5_000 })
  })
})
