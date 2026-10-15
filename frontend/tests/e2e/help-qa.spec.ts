import { test, expect } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F12.6 Q&A・ヘルプページ E2E テスト。
 *
 * chromium プロジェクトの storageState（認証済みユーザー）を前提とする。
 * /help/qa は middleware: 'auth' が設定されているためログイン済みが必須。
 */

test.describe('Q&Aページ', () => {
  test('ナビゲーションバーから Q&A ページへ遷移できる', async ({ page }) => {
    await page.goto('/dashboard')
    await waitForHydration(page)

    // ナビゲーションバーの「Q&A」リンクをクリック
    const qaLink = page.getByRole('link', { name: 'Q&A' }).first()
    await expect(qaLink).toBeVisible({ timeout: 10_000 })
    await qaLink.click()

    // URL が /help/qa になること
    await expect(page).toHaveURL(/\/help\/qa$/, { timeout: 10_000 })
    // ページ見出しが表示されること
    await expect(
      page.getByRole('heading', { level: 1, name: /よくある質問/ }),
    ).toBeVisible({ timeout: 10_000 })
  })

  test('Q&Aページが正しく表示される（タイトル・検索フィールド・カテゴリタブ）', async ({ page }) => {
    await page.goto('/help/qa')
    await waitForHydration(page)

    // タイトル
    await expect(
      page.getByRole('heading', { level: 1, name: /よくある質問/ }),
    ).toBeVisible({ timeout: 10_000 })

    // 検索フィールド（id=qa-search）
    await expect(page.locator('#qa-search')).toBeVisible()

    // カテゴリタブ（role=tab）: all / basic / pwa / offline / troubleshooting の計5つ
    const tabs = page.getByRole('tab')
    await expect(tabs).toHaveCount(5)

    // 「すべて」タブが初期選択状態
    const allTab = page.getByRole('tab', { name: 'すべて' })
    await expect(allTab).toHaveAttribute('aria-selected', 'true')
  })

  test('カテゴリタブをクリックすると対応する質問だけが表示される', async ({ page }) => {
    await page.goto('/help/qa')
    await waitForHydration(page)

    // 「基本」タブをクリック
    const basicTab = page.getByRole('tab', { name: '基本' })
    await basicTab.click()

    // aria-selected が true に変わる
    await expect(basicTab).toHaveAttribute('aria-selected', 'true')

    // basic カテゴリの質問が少なくとも1つ表示されている（id が basic-* の要素）
    await expect(page.locator('[id^="qa-basic-"]').first()).toBeVisible({ timeout: 10_000 })

    // pwa カテゴリの質問は表示されていない
    await expect(page.locator('[id^="qa-pwa-"]')).toHaveCount(0)
  })

  test('検索フィールドに入力するとフィルタされる', async ({ page }) => {
    await page.goto('/help/qa')
    await waitForHydration(page)

    const searchInput = page.locator('#qa-search')
    await expect(searchInput).toBeVisible({ timeout: 10_000 })

    // 「PWA」で検索
    await searchInput.fill('PWA')

    // 結果件数表示（aria-live="polite"）が表示される
    const resultCount = page.getByText(/件の質問/)
    await expect(resultCount).toBeVisible({ timeout: 5_000 })

    // マッチ項目が少なくとも1件表示される
    await expect(page.locator('[id^="qa-"]').first()).toBeVisible()
  })

  test('アコーディオンをクリックすると回答が展開される', async ({ page }) => {
    await page.goto('/help/qa')
    await waitForHydration(page)

    // 最初のアコーディオンボタン（id=qa-button-*）を取得
    const firstButton = page.locator('[id^="qa-button-"]').first()
    await expect(firstButton).toBeVisible({ timeout: 10_000 })

    // デフォルトはURLハッシュがなければ閉じている（openItem は手動 toggle 前は false）
    // openDefault=true だがハッシュ指定がなければ開かない仕様
    const initialExpanded = await firstButton.getAttribute('aria-expanded')
    expect(initialExpanded).toBe('false')

    // クリックで展開
    await firstButton.click()
    await expect(firstButton).toHaveAttribute('aria-expanded', 'true')

    // パネル（role=region）が表示される
    const buttonId = await firstButton.getAttribute('id')
    const panelId = buttonId?.replace('qa-button-', 'qa-panel-')
    expect(panelId).toBeTruthy()
    await expect(page.locator(`#${panelId}`)).toBeVisible()
  })

  test('チュートリアルバナーのボタンから /my/onboarding へ遷移', async ({ page }) => {
    await page.goto('/help/qa')
    await waitForHydration(page)

    // バナー内の「チュートリアルを見る」ボタンをクリック
    const tutorialButton = page.getByRole('button', { name: 'チュートリアルを見る' })
    await expect(tutorialButton).toBeVisible({ timeout: 10_000 })
    await tutorialButton.click()

    // /my/onboarding に遷移する
    await expect(page).toHaveURL(/\/my\/onboarding$/, { timeout: 10_000 })
  })

  test('URLハッシュ (#qa-pwa-q1) で該当質問が直接展開される', async ({ page }) => {
    // ハッシュ付きでアクセス
    await page.goto('/help/qa#qa-pwa-q1')
    await waitForHydration(page)

    // pwa-q1 のボタンが aria-expanded=true になっていること
    const targetButton = page.locator('#qa-button-pwa-q1')
    await expect(targetButton).toBeVisible({ timeout: 10_000 })
    await expect(targetButton).toHaveAttribute('aria-expanded', 'true', { timeout: 5_000 })

    // 対応パネルが表示される
    await expect(page.locator('#qa-panel-pwa-q1')).toBeVisible()
  })

  test('PWAカテゴリ時にインストールCTAセクションが表示される', async ({ page }) => {
    await page.goto('/help/qa')
    await waitForHydration(page)

    // 「PWA・インストール」タブをクリック
    const pwaTab = page.getByRole('tab', { name: 'PWA・インストール' })
    await pwaTab.click()
    await expect(pwaTab).toHaveAttribute('aria-selected', 'true')

    // PWA CTA セクションの見出し（"アプリとして使うと..."）が表示される
    await expect(
      page.getByRole('heading', { name: /アプリとして使うと/ }),
    ).toBeVisible({ timeout: 10_000 })

    // 注記: PwaInstallButton 本体は beforeinstallprompt がテスト環境で発火しないため
    //       canInstall=false / isIOS=false となり、shouldRender=false で DOM に出ないことが多い。
    //       そのため、ボタン自体のクリックテストではなく CTA セクションの表示有無のみ検証する。
  })
})
