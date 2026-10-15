/**
 * カレンダーUX改善・リマインダーバグ修正の実機E2Eテスト
 *
 * 検証対象 (PR #1899, #1900):
 *   CAL-UX-001: スコープフィルター（表示: ボタン群）が個人のみのカレンダーでも表示される
 *   CAL-UX-002: リマインダー種別ドロップダウンに「開始前（分/時間）」が表示される
 *   CAL-UX-003: リマインダー種別ドロップダウンに「日時で指定」が表示される
 *   CAL-UX-004: ダイアログキャンセル後に再開くとリマインダーがリセットされている (@hide修正)
 *
 * 認証戦略:
 *   storageState の古いトークンをクリアしてから loginViaApi で毎回フレッシュログイン。
 *   BE の refresh_token ローテ競合を防ぐため clearCookies() が必須。
 *   （memory: feedback_e2e_real_single_session_token_rotation）
 */

import { test, expect } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
// setup と同じ: FE proxy が未設定の環境でも確実に BE に到達できるよう直指定
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'

test.describe('CAL-UX: カレンダーUX改善・リマインダー修正 実機E2E', () => {
  // 同一ユーザーの並行ログイン起因 BE 500 を防ぐため直列実行
  test.describe.configure({ mode: 'serial' })

  test.beforeEach(async ({ page }) => {
    // storageState の古いトークンを完全クリアしてから新規ログイン。
    // クリア前に旧 Cookie が残ると旧 refresh_token と新 refresh_token が両方送られ
    // BE で競合して page.goto('/') が /login にリダイレクトされる。
    await page.context().clearCookies()
    await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE_URL })

    // カレンダーページへ遷移・ローディング待機（認証確立の確認を兼ねる）
    await page.goto('/calendar', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

    // 認証失敗 → /login リダイレクトの場合は beforeEach でフェイル（テスト本体は実行されない）
    if (page.url().includes('/login')) {
      throw new Error(`beforeEach: /calendar が /login にリダイレクト。認証に失敗しています。URL: ${page.url()}`)
    }
  })

  // ---------------------------------------------------------------------------
  // CAL-UX-001: スコープフィルターが個人のみカレンダーでも表示される (PR #1899)
  // allScopeOptions.length > 1 → > 0 に変更。PERSONAL オプション存在時は常に表示。
  // ---------------------------------------------------------------------------
  test('CAL-UX-001: 個人のみカレンダーでもスコープフィルター（個人ボタン）が表示される', async ({
    page,
  }) => {
    const personalButton = page.getByRole('button', { name: '個人' })
    await expect(personalButton).toBeVisible({ timeout: 10_000 })
  })

  // ---------------------------------------------------------------------------
  // CAL-UX-002: リマインダー種別が「開始前（分/時間）」に変わった (PR #1899)
  // ---------------------------------------------------------------------------
  test('CAL-UX-002: リマインダー種別ドロップダウンに「開始前（分/時間）」が表示される', async ({
    page,
  }) => {
    const addButton = page.getByRole('button', { name: '予定を追加' })
    await addButton.waitFor({ state: 'visible', timeout: 15_000 })
    await addButton.click()

    const dialog = page.getByRole('dialog')
    await dialog.waitFor({ state: 'visible', timeout: 10_000 })

    const addReminderButton = dialog.getByRole('button', { name: 'リマインダーを追加' })
    await addReminderButton.waitFor({ state: 'visible', timeout: 10_000 })
    await addReminderButton.click()

    const kindDropdown = dialog.getByRole('combobox', { name: '種別' })
    await kindDropdown.waitFor({ state: 'visible', timeout: 5_000 })
    await kindDropdown.click()

    // 「開始前（分/時間）」が表示され、旧ラベル「相対」は存在しないこと
    await expect(page.getByRole('option', { name: '開始前（分/時間）' })).toBeVisible({
      timeout: 5_000,
    })
    await expect(page.getByRole('option', { name: '相対' })).not.toBeVisible()
  })

  // ---------------------------------------------------------------------------
  // CAL-UX-003: リマインダー種別が「日時で指定」に変わった (PR #1899)
  // ---------------------------------------------------------------------------
  test('CAL-UX-003: リマインダー種別ドロップダウンに「日時で指定」が表示される', async ({
    page,
  }) => {
    const addButton = page.getByRole('button', { name: '予定を追加' })
    await addButton.waitFor({ state: 'visible', timeout: 15_000 })
    await addButton.click()

    const dialog = page.getByRole('dialog')
    await dialog.waitFor({ state: 'visible', timeout: 10_000 })

    const addReminderButton = dialog.getByRole('button', { name: 'リマインダーを追加' })
    await addReminderButton.waitFor({ state: 'visible', timeout: 10_000 })
    await addReminderButton.click()

    const kindDropdown = dialog.getByRole('combobox', { name: '種別' })
    await kindDropdown.waitFor({ state: 'visible', timeout: 5_000 })
    await kindDropdown.click()

    // 「日時で指定」が表示され、旧ラベル「絶対」は存在しないこと
    await expect(page.getByRole('option', { name: '日時で指定' })).toBeVisible({ timeout: 5_000 })
    await expect(page.getByRole('option', { name: '絶対' })).not.toBeVisible()
  })

  // ---------------------------------------------------------------------------
  // CAL-UX-004: @hide 後に resetForm() → 再オープン時にリマインダーが消える (PR #1900)
  // ---------------------------------------------------------------------------
  test('CAL-UX-004: ダイアログキャンセル後に再オープンするとリマインダーが消えている', async ({
    page,
  }) => {
    const addButton = page.getByRole('button', { name: '予定を追加' })
    await addButton.waitFor({ state: 'visible', timeout: 15_000 })
    await addButton.click()

    const dialog = page.getByRole('dialog')
    await dialog.waitFor({ state: 'visible', timeout: 10_000 })

    // リマインダーを1件追加して存在確認
    const addReminderButton = dialog.getByRole('button', { name: 'リマインダーを追加' })
    await addReminderButton.waitFor({ state: 'visible', timeout: 10_000 })
    await addReminderButton.click()

    await dialog.getByRole('button', { name: '削除' }).waitFor({ state: 'visible', timeout: 5_000 })

    // キャンセル → ダイアログが完全に消えるのを待つ（PrimeVue クローズアニメーション後 @hide 発火）
    await dialog.getByRole('button', { name: 'キャンセル' }).click()
    await dialog.waitFor({ state: 'hidden', timeout: 5_000 })

    // 再度フォームを開く
    await addButton.click()
    const dialog2 = page.getByRole('dialog')
    await dialog2.waitFor({ state: 'visible', timeout: 10_000 })

    // 前のリマインダーが消えている（削除ボタン不在 = フォームがリセット済み）
    await expect(dialog2.getByRole('button', { name: '削除' })).not.toBeVisible()
    await expect(dialog2.getByRole('button', { name: 'リマインダーを追加' })).toBeVisible({
      timeout: 5_000,
    })

    // 後片付け
    await dialog2.getByRole('button', { name: 'キャンセル' }).click()
    await dialog2.waitFor({ state: 'hidden', timeout: 5_000 })
  })
})
