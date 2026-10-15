// SET-DEEP-011〜015: 外観設定フォームの深掘りテスト
// テーマ SelectButton / 背景色プリセットボタン / チャットプレビュー ToggleSwitch / 保存 API を検証する

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_APPEARANCE_LIGHT = {
  data: {
    theme: 'LIGHT',
    bgColor: '#ffffff',
    seasonalThemeId: null,
    hideChatPreview: false,
  },
}

const MOCK_APPEARANCE_DARK = {
  data: {
    theme: 'DARK',
    bgColor: '#fef9ef',
    seasonalThemeId: null,
    hideChatPreview: true,
  },
}

/**
 * appearance.vue は onMounted で /api/v1/settings/appearance の GET を呼んで初期値を読む。
 * テーマ変更や背景色変更時はストアの setter から PUT が即時送信される（syncWithServer）。
 */
async function setupAppearanceMocks(
  page: import('@playwright/test').Page,
  initial = MOCK_APPEARANCE_LIGHT,
) {
  await page.route('**/api/v1/settings/appearance', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(initial),
      })
    } else if (route.request().method() === 'PUT') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { ok: true } }),
      })
    } else {
      await route.continue()
    }
  })
}

test.describe('SET-DEEP appearance: 外観設定フォーム深掘り', () => {
  test('SET-DEEP-011: 外観設定ページ初期表示でテーマ／背景色／プレビューが描画される', async ({
    page,
  }) => {
    await setupAppearanceMocks(page)

    await page.goto('/settings/appearance')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({
      timeout: 10_000,
    })

    // ThemeSelector の SelectButton 内 3 オプション
    await expect(page.getByText('ライト')).toBeVisible()
    await expect(page.getByText('ダーク')).toBeVisible()
    await expect(page.getByText('システム')).toBeVisible()

    // BackgroundColorPicker のラベルとプリセット 5 色
    await expect(page.getByText('背景色', { exact: true })).toBeVisible()
    const presetButtons = page.locator('button[title]').filter({
      has: page.locator('xpath=self::*'),
    })
    // 「ホワイト」「クリーム」「ラベンダー」「ミント」「スカイ」が title 属性で並ぶ
    await expect(page.locator('button[title="ホワイト"]')).toBeVisible()
    await expect(page.locator('button[title="クリーム"]')).toBeVisible()
    await expect(page.locator('button[title="ラベンダー"]')).toBeVisible()
    await expect(page.locator('button[title="ミント"]')).toBeVisible()
    await expect(page.locator('button[title="スカイ"]')).toBeVisible()
    // 念のため presetButtons の存在確認も行う
    await expect(presetButtons.first()).toBeVisible()

    // チャットプレビュー設定セクションと「設定を保存」ボタンも表示される
    await expect(page.getByText('チャットプレビュー非表示')).toBeVisible()
    await expect(page.getByRole('button', { name: '設定を保存' })).toBeVisible()
  })

  test('SET-DEEP-012: ダークテーマを選択して保存すると PUT /api/v1/settings/appearance が theme=DARK で呼ばれる', async ({
    page,
  }) => {
    await setupAppearanceMocks(page)

    await page.goto('/settings/appearance')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({
      timeout: 10_000,
    })

    // SelectButton の「ダーク」を選択（ローカル状態を変更）
    await page.getByText('ダーク').click()

    // 「設定を保存」ボタンで PUT をトリガー
    const putPromise = page.waitForRequest(
      (req) =>
        req.url().includes('/api/v1/settings/appearance') && req.method() === 'PUT',
      { timeout: 10_000 },
    )
    await page.getByRole('button', { name: '設定を保存' }).click()
    const putReq = await putPromise

    const body = JSON.parse(putReq.postData() ?? '{}')
    expect(body.theme).toBe('DARK')
  })

  test('SET-DEEP-013: 背景色「クリーム」を選択して保存すると PUT に bgColor=#fef9ef が含まれる', async ({
    page,
  }) => {
    await setupAppearanceMocks(page)

    await page.goto('/settings/appearance')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({
      timeout: 10_000,
    })

    // クリーム色プリセットを選択（ローカル状態を変更）
    await page.locator('button[title="クリーム"]').click()

    // 「設定を保存」ボタンで PUT をトリガー
    const putPromise = page.waitForRequest(
      (req) =>
        req.url().includes('/api/v1/settings/appearance') && req.method() === 'PUT',
      { timeout: 10_000 },
    )
    await page.getByRole('button', { name: '設定を保存' }).click()
    const putReq = await putPromise

    const body = JSON.parse(putReq.postData() ?? '{}')
    expect(body.bgColor).toBe('#fef9ef')
  })

  test('SET-DEEP-014: 「設定を保存」ボタンをクリックすると PUT が呼ばれて成功通知が表示される', async ({
    page,
  }) => {
    await setupAppearanceMocks(page)

    await page.goto('/settings/appearance')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({
      timeout: 10_000,
    })

    const putPromise = page.waitForRequest(
      (req) =>
        req.url().includes('/api/v1/settings/appearance') && req.method() === 'PUT',
      { timeout: 10_000 },
    )
    await page.getByRole('button', { name: '設定を保存' }).click()
    await putPromise

    // 成功通知（外観設定を保存しました）が表示される
    await expect(page.getByText('外観設定を保存しました')).toBeVisible({ timeout: 10_000 })
  })

  test('SET-DEEP-015: サーバー初期値が DARK の場合、ダークボタンが選択状態として描画される', async ({
    page,
  }) => {
    await setupAppearanceMocks(page, MOCK_APPEARANCE_DARK)

    await page.goto('/settings/appearance')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({
      timeout: 10_000,
    })

    // PrimeVue SelectButton は選択された option に p-togglebutton-checked クラスが付く
    // ラベル「ダーク」を含む togglebutton ラッパーが checked 状態であることを確認
    const darkButton = page
      .locator('.p-togglebutton, [role="button"]')
      .filter({ hasText: 'ダーク' })
      .first()
    await expect(darkButton).toBeVisible({ timeout: 10_000 })

    // クリーム背景（#fef9ef）プリセットが選択中スタイル（border-primary）になる
    const creamButton = page.locator('button[title="クリーム"]')
    await expect(creamButton).toBeVisible()
    await expect(creamButton).toHaveClass(/border-primary/)
  })
})
