import { test, expect } from '@playwright/test'

// chromium プロジェクトの storageState（認証済みユーザー）を使用

test.describe('DASH-001〜003: ダッシュボード', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard')
    // ダッシュボードの見出しが表示されるまで待機
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible({ timeout: 10_000 })
  })

  test('DASH-001: ダッシュボードにアクセスするとユーザー名を含む挨拶が表示される', async ({
    page,
  }) => {
    // 挨拶は「おはよう、〇〇さん」等のパターン
    const heading = page.getByRole('heading', { level: 1 })
    await expect(heading).toBeVisible()
    // 「さん」で終わる挨拶テキストが存在すること
    await expect(page.getByText(/さん/)).toBeVisible()
  })

  test('DASH-002: マイチームセクションが表示される', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'マイチーム' })).toBeVisible()
    // チームがある場合はリンク、ない場合は空状態メッセージ
    const hasTeams = await page.locator('a[href^="/teams/"]').count()
    if (hasTeams === 0) {
      await expect(page.getByText('まだチームに参加していません')).toBeVisible()
    }
    // チームページへの「すべて表示」リンクが存在すること
    await expect(page.getByRole('link', { name: 'すべて表示' }).first()).toBeVisible()
  })

  test('DASH-003: マイ組織セクションが表示される', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'マイ組織' })).toBeVisible()
    // 組織がある場合はリンク、ない場合は空状態メッセージ
    const hasOrgs = await page.locator('a[href^="/organizations/"]').count()
    if (hasOrgs === 0) {
      await expect(page.getByText('まだ組織に参加していません')).toBeVisible()
    }
  })
})
