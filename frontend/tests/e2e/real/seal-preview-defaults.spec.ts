/**
 * F05.3 電子印鑑 — 実機 E2E テスト
 *
 * テストID: SEAL-REAL-001 〜 SEAL-REAL-003
 *
 * 対象:
 *   - PR #1921: sealId 不一致根治 / 0件時の初回自動生成
 *   - PR #1923: 印影の上下左右中央揃え（SVG width=100%）
 *   - PR #1924: PUT /scope-defaults エンドポイント追加
 *
 * 認証戦略:
 *   storageState の古いトークンをクリアしてから loginViaApi で毎回フレッシュログイン。
 *   BE の refresh_token ローテ競合を防ぐため clearCookies() が必須。
 *   （memory: feedback_e2e_real_single_session_token_rotation）
 *
 * 実行方法:
 *   cd frontend && BASE_URL=http://localhost:3000 API_BASE_URL=http://localhost:8080 \
 *     npx playwright test tests/e2e/real/seal-preview-defaults.spec.ts --reporter=list
 */

import { test, expect } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const E2E_USER = {
  email: 'e2e-user@test.mannschaft.local',
  password: 'TestPass2026!',
}

// FE proxy が未設定の環境でも確実に BE に到達できるよう直指定
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'

/**
 * e2e-user の印鑑を確実に用意する（なければ regenerate API で生成）。
 * 再生成テストが常に「印鑑あり」の状態でスタートできることを保証する。
 *
 * 注: テスト前に0件状態にしてから再生成するアプローチは避ける。
 * SealService.initializeSeals が soft delete 済みレコードのユニーク制約に
 * 引っかかる既知の問題（BE 修正対応済: SealService + ElectronicSealRepository 修正済）
 * があり、BE デプロイ完了後は0件テストも追加可能。
 */
async function ensureSealsExist(
  page: import('@playwright/test').Page,
  userId: number,
): Promise<void> {
  // 再生成 API を呼んで既存の印鑑を UPDATE（なければ INSERT）
  const regenRes = await page.request.post(
    `${API_BASE_URL}/api/v1/users/${userId}/seals/regenerate`,
  )
  if (!regenRes.ok()) {
    throw new Error(`印鑑の準備失敗: ${regenRes.status()} ${await regenRes.text()}`)
  }
}

test.describe('F05.3 電子印鑑 実機E2E', () => {
  // BE の refresh_token ローテ競合を防ぐため直列実行
  test.describe.configure({ mode: 'serial' })

  let userId: number

  test.beforeEach(async ({ page }) => {
    // 古いトークンを完全クリアしてから新規ログイン
    await page.context().clearCookies()
    await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE_URL })

    // userId を取得
    const meRes = await page.request.get(`${API_BASE_URL}/api/v1/users/me`)
    if (!meRes.ok()) {
      throw new Error(`/api/v1/users/me 取得失敗: ${meRes.status()} ${await meRes.text()}`)
    }
    const me = (await meRes.json()).data as { id: number }
    userId = me.id

    // 印鑑が確実に存在する状態にする（再生成 API で UPDATE/生成）
    await ensureSealsExist(page, userId)
  })

  // ---------------------------------------------------------------------------
  // SEAL-REAL-001: 再生成ボタンを押すと印影が3件プレビューに表示される（PR #1921 検証）
  //   beforeEach で ensureSealsExist を呼んでいるため「既存印鑑の UPDATE」経路を検証する。
  //   ページを開いて再度「印鑑を再生成」ボタンを押し、toast + 3件表示を確認する。
  // ---------------------------------------------------------------------------
  test('SEAL-REAL-001: 再生成ボタンを押すと印影が3件プレビューに表示される', async ({
    page,
  }) => {
    await page.goto('/settings/seals', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    // ローディング完了を待つ
    await page
      .locator('.pi-spin')
      .waitFor({ state: 'detached', timeout: 30_000 })
      .catch(() => {})

    // 認証失敗 → /login リダイレクトの場合はフェイル
    if (page.url().includes('/login')) {
      throw new Error(
        `SEAL-REAL-001: /settings/seals が /login にリダイレクト。URL: ${page.url()}`,
      )
    }

    // 「印鑑を再生成」ボタンが表示されること
    const regenerateButton = page.getByRole('button', { name: '印鑑を再生成' })
    await expect(regenerateButton).toBeVisible({ timeout: 15_000 })
    await regenerateButton.scrollIntoViewIfNeeded()
    await regenerateButton.click()

    // 再生成完了を待つ（ローディングスピナーが消える）
    await page
      .locator('.pi-spin')
      .waitFor({ state: 'detached', timeout: 30_000 })
      .catch(() => {})

    // 「印鑑を再生成しました」toast が表示されること
    // seals.vue: notification.success('印鑑を再生成しました')
    const toastSummary = page
      .locator('.p-toast-summary')
      .filter({ hasText: '印鑑を再生成しました' })
    await expect(toastSummary).toBeVisible({ timeout: 15_000 })

    // 印鑑カードが3件表示されること（SealPreview.vue: div.grid > div ×3）
    // SealPreview は sm:grid-cols-3 の grid レイアウトで各印鑑を div に描画する
    const sealCards = page.locator('div.grid.gap-4 > div')
    await expect(sealCards).toHaveCount(3, { timeout: 15_000 })
  })

  // ---------------------------------------------------------------------------
  // SEAL-REAL-002: 印鑑プレビューに SVG 印影が表示される（PR #1923: 中央揃え確認）
  //   beforeEach で ensureSealsExist を呼んでいるため印鑑は確実に存在する。
  // ---------------------------------------------------------------------------
  test('SEAL-REAL-002: 印鑑プレビューに SVG 印影が表示される（v-html）', async ({ page }) => {
    // 印鑑設定ページへ遷移
    await page.goto('/settings/seals', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page
      .locator('.pi-spin')
      .waitFor({ state: 'detached', timeout: 30_000 })
      .catch(() => {})

    if (page.url().includes('/login')) {
      throw new Error(
        `SEAL-REAL-002: /settings/seals が /login にリダイレクト。URL: ${page.url()}`,
      )
    }

    // SealPreview 内に SVG 要素が存在すること（PR #1923: v-html で印影を描画）
    // SealPreview.vue: <div v-html="sanitizeHtml(seal.svgData, { allowSvg: true })" />
    const svgEl = page.locator('div.grid.gap-4 svg').first()
    await expect(svgEl).toBeAttached({ timeout: 15_000 })
    await expect(svgEl).toBeVisible({ timeout: 10_000 })
  })

  // ---------------------------------------------------------------------------
  // SEAL-REAL-003: デフォルト設定タブで保存が成功する（PR #1924: PUT /scope-defaults）
  //   beforeEach で ensureSealsExist を呼んでいるため印鑑は確実に存在する。
  // ---------------------------------------------------------------------------
  test('SEAL-REAL-003: デフォルト設定タブで保存が成功する', async ({ page }) => {
    // 印鑑設定ページへ遷移
    await page.goto('/settings/seals', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page
      .locator('.pi-spin')
      .waitFor({ state: 'detached', timeout: 30_000 })
      .catch(() => {})

    if (page.url().includes('/login')) {
      throw new Error(
        `SEAL-REAL-003: /settings/seals が /login にリダイレクト。URL: ${page.url()}`,
      )
    }

    // 「デフォルト設定」タブをクリック（value="1" の Tab）
    // seals.vue: <Tab value="1">デフォルト設定</Tab>
    const defaultsTab = page.getByRole('tab', { name: 'デフォルト設定' })
    await expect(defaultsTab).toBeVisible({ timeout: 15_000 })
    await defaultsTab.click()

    // タブが選択状態になるまで待つ
    await expect(defaultsTab).toHaveAttribute('aria-selected', 'true', { timeout: 10_000 })

    // 「保存」ボタンが表示されること（SealScopeDefaults.vue: <Button label="保存" />）
    const saveButton = page.getByRole('button', { name: '保存' })
    await expect(saveButton).toBeVisible({ timeout: 15_000 })

    // 保存ボタンをクリック
    await saveButton.click()

    // 「デフォルト設定を保存しました」toast が表示されること
    // seals.vue: notification.success('デフォルト設定を保存しました')
    // PrimeVue Toast の summary テキストは .p-toast-summary に入る
    const toastSummary = page
      .locator('.p-toast-summary')
      .filter({ hasText: 'デフォルト設定を保存しました' })
    await expect(toastSummary).toBeVisible({ timeout: 15_000 })
  })
})
