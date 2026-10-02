// SET-DARK-001〜007: ダークモード背景色ピッカー＋ナビバーダーク化の受け入れテスト
// PR #1818 / #1819 / #1824 の実装を実ブラウザ（Playwright + モックAPI）で検証する
//
// 認証はlocalStorageに currentUser を書き込むことでモック（access_tokenドリフト対策）。
// appearance API は page.route でフルモック（実BE不要）。
//
// 検証対象コード:
//   frontend/app/components/BackgroundColorPicker.vue
//   frontend/app/stores/useAppearanceStore.ts
//   frontend/app/pages/settings/appearance.vue
//   frontend/app/layouts/default.vue (ナビバー dark:bg-surface-900)

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// テスト内でlocalStorageにcurrentUserを書き込み、authミドルウェアをパスする
// addInitScript を使うとgoto前に実行されるためauth redirectを防げる
async function setupAuth(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: 23,
        email: 'e2e-user@test.mannschaft.local',
        fullName: 'E2Eユーザー 一般',
        profileImageUrl: null,
        timezone: 'Asia/Tokyo',
      }),
    )
    // appearanceストアの初期値もセット（storageからの読み込み用）
    localStorage.setItem(
      'appearance',
      JSON.stringify({
        theme: 'LIGHT',
        bgColor: '#f3efe0',
        darkBgColor: '#18181b',
        seasonalThemeId: null,
        hideChatPreview: false,
      }),
    )
  })
}

// ダーク系8色プリセット（受け入れ条件2より）
const DARK_PRESET_COLORS = [
  { title: 'チャコール', value: '#18181b' },
  { title: 'ブラック', value: '#0a0a0a' },
  { title: 'グラファイト', value: '#27272a' },
  { title: 'スレート', value: '#1e293b' },
  { title: 'ネイビー', value: '#0f172a' },
  { title: 'フォレスト', value: '#14241c' },
  { title: 'コーヒー', value: '#231a14' },
  { title: 'ワイン', value: '#2a1620' },
]

// API モックセットアップ: GET で DARK+darkBgColor=デフォルト を返す
async function setupDarkAppearanceMocks(
  page: import('@playwright/test').Page,
  darkBgColor = '#18181b',
) {
  await page.route('**/api/v1/settings/appearance', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            theme: 'DARK',
            bgColor: '#f3efe0',
            darkBgColor,
            seasonalThemeId: null,
            hideChatPreview: false,
          },
        }),
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
  // /api/v1/* のその他リクエストは空応答（auth refresh等）
  await page.route('**/api/v1/auth/refresh', async (route) => {
    await route.fulfill({ status: 204, body: '' })
  })
}

// ライト初期状態のモック
async function setupLightAppearanceMocks(page: import('@playwright/test').Page) {
  await page.route('**/api/v1/settings/appearance', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            theme: 'LIGHT',
            bgColor: '#f3efe0',
            darkBgColor: '#18181b',
            seasonalThemeId: null,
            hideChatPreview: false,
          },
        }),
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
  await page.route('**/api/v1/auth/refresh', async (route) => {
    await route.fulfill({ status: 204, body: '' })
  })
}

test.describe('SET-DARK: ダークモード背景色ピッカー＋ナビバー受け入れテスト', () => {
  // 受け入れ条件1: ダークテーマへ切り替えできる
  test('SET-DARK-001: 外観設定でテーマをDARKに切り替えできる', async ({ page }) => {
    await setupAuth(page)
    await setupLightAppearanceMocks(page)
    await page.goto('/settings/appearance')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({ timeout: 10_000 })

    // ダーク切替前はhtml要素にdarkクラスがない
    const htmlEl = page.locator('html')

    // 「ダーク」ボタンをクリック
    await page.getByText('ダーク').click()

    // html要素に 'dark' クラスが付与される（applyTheme() の動作）
    await expect(htmlEl).toHaveClass(/dark/, { timeout: 5_000 })
  })

  // 受け入れ条件2: DARK時にダーク系8色プリセットが表示される（ライト10色ではない）
  test('SET-DARK-002: DARK時に背景色ピッカーがダーク系8色で表示される', async ({ page }) => {
    await setupAuth(page)
    await setupDarkAppearanceMocks(page)
    await page.goto('/settings/appearance')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({ timeout: 10_000 })

    // ダーク系プリセットのtitleが表示されている
    for (const color of DARK_PRESET_COLORS) {
      await expect(page.locator(`button[title="${color.title}"]`)).toBeVisible({
        timeout: 5_000,
      })
    }

    // ライト系プリセット（クリーム/ホワイト/ラベンダー等）は表示されない
    await expect(page.locator('button[title="クリーム"]')).not.toBeVisible()
    await expect(page.locator('button[title="ホワイト"]')).not.toBeVisible()
    await expect(page.locator('button[title="ラベンダー"]')).not.toBeVisible()
  })

  // 受け入れ条件3: ダーク色を選択すると --bg-color が暗色になる
  test('SET-DARK-003: ダーク色を選択するとdocumentの--bg-colorが暗色になる', async ({ page }) => {
    await setupAuth(page)
    await setupDarkAppearanceMocks(page, '#18181b')
    await page.goto('/settings/appearance')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({ timeout: 10_000 })

    // ネイビー (#0f172a) を選択
    await page.locator('button[title="ネイビー"]').click()

    // CSS変数 --bg-color が暗色（#0f172a）に変わることを確認
    const bgColor = await page.evaluate(() => {
      return getComputedStyle(document.documentElement).getPropertyValue('--bg-color').trim()
    })
    expect(bgColor).toBe('#0f172a')

    // ボディ背景がクリーム色（#f3efe0）のままでないことを確認
    expect(bgColor).not.toBe('#f3efe0')
    expect(bgColor).not.toBe('#ffffff')
  })

  // 受け入れ条件4: ナビバー（ヘッダー）が暗色になる
  test('SET-DARK-004: DARKモード時にナビバーが暗色（dark:bg-surface-900）になる', async ({
    page,
  }) => {
    await setupAuth(page)
    await setupDarkAppearanceMocks(page)
    await page.goto('/settings/appearance')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({ timeout: 10_000 })

    // html に 'dark' クラスが付いていることを確認（loadFromServer で DARK を読み込み後）
    const htmlEl = page.locator('html')
    await expect(htmlEl).toHaveClass(/dark/, { timeout: 5_000 })

    // ヘッダー要素が存在し、 dark:bg-surface-900 クラスを持つ
    // （default.vue の header class="... dark:bg-surface-900 ..."）
    const header = page.locator('header').first()
    await expect(header).toBeVisible()
    await expect(header).toHaveClass(/dark:bg-surface-900/)

    // ダーク時のヘッダー背景色が白（#ffffff）でないことをcomputedStyleで確認
    const headerBg = await header.evaluate((el) => {
      return getComputedStyle(el).backgroundColor
    })
    // rgb(255, 255, 255) = 白 ではない
    expect(headerBg).not.toBe('rgb(255, 255, 255)')
  })

  // 受け入れ条件5: 保存PUT /api/v1/settings/appearance が darkBgColor を含む
  test('SET-DARK-005: 保存時PUTリクエストにdarkBgColorが含まれ200が返る', async ({ page }) => {
    await setupAuth(page)
    await setupDarkAppearanceMocks(page, '#27272a')
    await page.goto('/settings/appearance')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({ timeout: 10_000 })

    // ワイン色 (#2a1620) を選択
    await page.locator('button[title="ワイン"]').click()

    // 設定を保存ボタンクリック
    const putPromise = page.waitForRequest(
      (req) =>
        req.url().includes('/api/v1/settings/appearance') && req.method() === 'PUT',
      { timeout: 10_000 },
    )
    const responsePromise = page.waitForResponse(
      (res) =>
        res.url().includes('/api/v1/settings/appearance') && res.request().method() === 'PUT',
      { timeout: 10_000 },
    )
    await page.getByRole('button', { name: '設定を保存' }).click()

    const putReq = await putPromise
    const putRes = await responsePromise

    // リクエストボディに darkBgColor が含まれる（@NotNull必須フィールド）
    const body = JSON.parse(putReq.postData() ?? '{}')
    expect(body.darkBgColor).toBe('#2a1620')
    expect(body.theme).toBe('DARK')

    // レスポンスが 200
    expect(putRes.status()).toBe(200)

    // 成功通知が表示される
    await expect(page.getByText('外観設定を保存しました')).toBeVisible({ timeout: 10_000 })
  })

  // 受け入れ条件6: リロード後にダークテーマ＋選択色が復元される（localStorageベース）
  test('SET-DARK-006: ページリロード後もダークテーマと選択色が復元される', async ({ page }) => {
    await setupAuth(page)
    await setupDarkAppearanceMocks(page, '#1e293b')
    await page.goto('/settings/appearance')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({ timeout: 10_000 })

    // スレート (#1e293b) が既に選択状態（モックがdarkBgColor=#1e293bを返す）
    const htmlEl = page.locator('html')
    await expect(htmlEl).toHaveClass(/dark/, { timeout: 5_000 })

    // localStorage に appearance を書き込んで（loadFromServerが行う）リロード
    await page.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({ timeout: 10_000 })

    // リロード後も dark クラスが維持される（loadFromStorage の動作）
    await expect(htmlEl).toHaveClass(/dark/, { timeout: 5_000 })

    // --bg-color がスレート色に復元されている
    const bgColorAfterReload = await page.evaluate(() => {
      return getComputedStyle(document.documentElement).getPropertyValue('--bg-color').trim()
    })
    expect(bgColorAfterReload).toBe('#1e293b')
  })

  // 追加: ライトモードからDARKに切り替えた際、背景色がクリーム色から暗色に変わることを確認
  test('SET-DARK-007: ライト→ダーク切替時に背景色がクリーム色から暗色に変わる（ボディ白残りバグ根治確認）', async ({
    page,
  }) => {
    await setupAuth(page)
    await setupLightAppearanceMocks(page)
    await page.goto('/settings/appearance')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({ timeout: 10_000 })

    // ライトモード時の --bg-color 確認
    const bgBefore = await page.evaluate(() => {
      return getComputedStyle(document.documentElement).getPropertyValue('--bg-color').trim()
    })
    // ライトモードでクリーム色が設定されている
    expect(bgBefore).toBe('#f3efe0')

    // DARKに切替
    await page.getByText('ダーク').click()

    // 切替後は --bg-color が darkBgColor (#18181b) に変わる
    const bgAfter = await page.evaluate(() => {
      return getComputedStyle(document.documentElement).getPropertyValue('--bg-color').trim()
    })
    expect(bgAfter).toBe('#18181b')
    expect(bgAfter).not.toBe('#f3efe0')
  })
})
