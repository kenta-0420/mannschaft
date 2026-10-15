import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// ランディングページはゲスト専用のため、未認証状態で実行する
test.use({ storageState: { cookies: [], origins: [] } })

/**
 * F12.6: ランディングページ年代別テーマ E2Eテスト
 *
 * ?era= クエリパラメータでテーマを強制指定し、各テーマの
 * 表示・脱出ボタン動作・フォールバック挙動を検証する。
 */
test.describe('F12.6: ランディングページ年代別テーマ', () => {
  /**
   * テーマコンポーネントの表示を待機するヘルパー。
   * ClientOnly + onMounted でテーマが切り替わるため、
   * ハイドレーション後にルート要素が表示されるまで待機する。
   */
  async function waitForTheme(page: import('@playwright/test').Page, selector: string) {
    await waitForHydration(page)
    await expect(page.locator(selector)).toBeVisible({ timeout: 10_000 })
  }

  // ─────────────────────────────────────────
  // モダンテーマ（デフォルト）
  // ─────────────────────────────────────────

  test('LAND-ERA-001: デフォルト（クエリなし）でモダンテーマが表示される', async ({ page }) => {
    await page.goto('/')
    await waitForHydration(page)
    // LandingHero の CTA ボタン「無料で始める」が表示されることを確認
    await expect(page.getByRole('link', { name: '無料で始める' }).first()).toBeVisible({
      timeout: 10_000,
    })
    // 脱出ボタンは表示されない（モダンテーマなので）
    await expect(page.locator('.era-switcher')).not.toBeVisible()
  })

  test('LAND-ERA-002: ?era=modern でモダンテーマが表示される', async ({ page }) => {
    await page.goto('/?era=modern')
    await waitForHydration(page)
    await expect(page.getByRole('link', { name: '無料で始める' }).first()).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.locator('.era-switcher')).not.toBeVisible()
  })

  // ─────────────────────────────────────────
  // レトロテーマ — 各テーマ表示確認
  // ─────────────────────────────────────────

  test('LAND-ERA-003: ?era=y1998 で Geocities Era テーマが表示される', async ({ page }) => {
    await page.goto('/?era=y1998')
    await waitForTheme(page, '.geo-page')
    // 工事中テキストが表示されること
    await expect(page.locator('.construction-text')).toBeVisible()
    // 虹色タイトルが表示されること
    await expect(page.locator('.rainbow-title')).toBeVisible()
    // 脱出ボタンが表示されること
    await expect(page.locator('.era-switcher')).toBeVisible()
  })

  test('LAND-ERA-004: ?era=y2000 で Glass Era テーマが表示される', async ({ page }) => {
    await page.goto('/?era=y2000')
    await waitForTheme(page, '.theme-2000')
    // ブラウザクロームフレームが表示されること（2000年代の装飾）
    await expect(page.locator('.browser-chrome')).toBeVisible()
    // 脱出ボタンが表示されること
    await expect(page.locator('.era-switcher')).toBeVisible()
  })

  test('LAND-ERA-005: ?era=y2005 で Web 2.0 Era テーマが表示される', async ({ page }) => {
    await page.goto('/?era=y2005')
    await waitForTheme(page, '.theme-2005')
    // BETAバッジが表示されること（Web 2.0時代の特徴）
    await expect(page.locator('.beta-badge')).toBeVisible()
    // 脱出ボタンが表示されること
    await expect(page.locator('.era-switcher')).toBeVisible()
  })

  test('LAND-ERA-006: ?era=y2010 で Skeuomorphism Era テーマが表示される', async ({ page }) => {
    await page.goto('/?era=y2010')
    await waitForTheme(page, '.theme-2010')
    // 革製ヘッダーが表示されること
    await expect(page.locator('.leather-header')).toBeVisible()
    // 脱出ボタンが表示されること
    await expect(page.locator('.era-switcher')).toBeVisible()
  })

  test('LAND-ERA-007: ?era=y2015 で Material Design Era テーマが表示される', async ({
    page,
  }) => {
    await page.goto('/?era=y2015')
    await waitForTheme(page, '.md-page')
    // マテリアルデザインのアプリバーが表示されること
    await expect(page.locator('.md-appbar')).toBeVisible()
    // 脱出ボタンが表示されること
    await expect(page.locator('.era-switcher')).toBeVisible()
  })

  test('LAND-ERA-008: ?era=y2020 で Neumorphism Era テーマが表示される', async ({ page }) => {
    await page.goto('/?era=y2020')
    await waitForTheme(page, '.neo-page')
    // ニューモーフィズムのヒーローセクションが表示されること
    await expect(page.locator('.neo-hero')).toBeVisible()
    // 脱出ボタンが表示されること
    await expect(page.locator('.era-switcher')).toBeVisible()
  })

  test('LAND-ERA-009: ?era=fc でファミコンテーマが表示される', async ({ page }) => {
    await page.goto('/?era=fc')
    await waitForTheme(page, '.fc-root')
    // PUSH START テキストが表示されること（fcテーマのCTA）
    await expect(page.locator('.fc-push-start')).toBeVisible()
    // コナミコードヒントがページに存在すること
    await expect(page.locator('.fc-konami-hint')).toBeAttached()
    // 脱出ボタンが表示されること
    await expect(page.locator('.era-switcher')).toBeVisible()
  })

  test('LAND-ERA-010: ?era=sfc でスーパーファミコンテーマが表示される', async ({ page }) => {
    await page.goto('/?era=sfc')
    await waitForTheme(page, '.sfc-root')
    // RPGダイアログメニューが表示されること
    await expect(page.locator('.sfc-dialog').first()).toBeVisible()
    // 「はじめる」メニュー項目が表示されること
    await expect(page.locator('.sfc-menu-item').first()).toBeVisible()
    // 脱出ボタンが表示されること
    await expect(page.locator('.era-switcher')).toBeVisible()
  })

  // ─────────────────────────────────────────
  // 脱出ボタン — モダン版への復帰
  // ─────────────────────────────────────────

  test('LAND-ERA-011: 脱出ボタンをクリックするとモダン版に戻る', async ({ page }) => {
    await page.goto('/?era=y2020')
    await waitForTheme(page, '.neo-page')

    // 脱出ボタンが表示されていること
    const switcher = page.locator('.era-switcher button')
    await expect(switcher).toBeVisible()

    // 脱出ボタンをクリック
    await switcher.click()

    // モダンテーマに戻ること（LandingHeroの「無料で始める」が表示される）
    await expect(page.getByRole('link', { name: '無料で始める' }).first()).toBeVisible({
      timeout: 10_000,
    })
    // 脱出ボタンが消えること
    await expect(page.locator('.era-switcher')).not.toBeVisible()
  })

  test('LAND-ERA-012: 脱出ボタンにアクセシブルなaria-labelが設定されている', async ({
    page,
  }) => {
    await page.goto('/?era=fc')
    await waitForTheme(page, '.fc-root')

    const btn = page.locator('.era-switcher button')
    await expect(btn).toBeVisible()
    // aria-labelが設定されていること
    await expect(btn).toHaveAttribute('aria-label', /.+/)
  })

  // ─────────────────────────────────────────
  // フォールバック動作
  // ─────────────────────────────────────────

  test('LAND-ERA-013: 無効な?eraクエリはモダンテーマになる', async ({ page }) => {
    await page.goto('/?era=invalid-theme')
    await waitForHydration(page)
    // モダンテーマの要素が表示されること
    await expect(page.getByRole('link', { name: '無料で始める' }).first()).toBeVisible({
      timeout: 10_000,
    })
    // 脱出ボタンは表示されない
    await expect(page.locator('.era-switcher')).not.toBeVisible()
  })

  test('LAND-ERA-014: プロトタイプ汚染を試みるクエリはモダンテーマになる', async ({ page }) => {
    await page.goto('/?era=constructor')
    await waitForHydration(page)
    await expect(page.getByRole('link', { name: '無料で始める' }).first()).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.locator('.era-switcher')).not.toBeVisible()
  })

  test('LAND-ERA-015: 配列クエリ（?era=fc&era=sfc）は最初の値が使われる', async ({ page }) => {
    await page.goto('/?era=fc&era=sfc')
    await waitForTheme(page, '.fc-root')
    // fcテーマが表示されること（最初のクエリ値）
    await expect(page.locator('.fc-push-start')).toBeVisible()
  })

  // ─────────────────────────────────────────
  // SEO / メタデータ確認
  // ─────────────────────────────────────────

  test('LAND-ERA-016: レトロテーマ表示中もページタイトルはモダン版のままである', async ({
    page,
  }) => {
    await page.goto('/?era=y1998')
    await waitForTheme(page, '.geo-page')
    // SEOタイトルがモダン版のまま（クローラ向け）
    await expect(page).toHaveTitle(/Mannschaft/)
  })

  test('LAND-ERA-017: JSON-LDがページに存在する', async ({ page }) => {
    await page.goto('/')
    await waitForHydration(page)
    // JSON-LD スクリプトが存在すること（SEO）
    const jsonLd = page.locator('script[type="application/ld+json"]')
    await expect(jsonLd).toBeAttached()
  })
})
