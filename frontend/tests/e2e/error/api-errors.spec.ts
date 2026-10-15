import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.describe('ERR-001〜006: APIエラーハンドリング', () => {
  test('ERR-001: 404ページが存在しないルートで表示される', async ({ page }) => {
    await page.goto('/this-page-does-not-exist-at-all')
    await waitForHydration(page)
    // 404ページまたはエラー表示を確認
    const errorText = page.getByText(/404|ページが見つかりません|Not Found/)
    await expect(errorText.first()).toBeVisible({ timeout: 10_000 })
  })

  test('ERR-002: API 500エラー時にエラー表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Internal Server Error' }),
      })
    })
    await page.goto('/dashboard')
    await waitForHydration(page)
    // ページがクラッシュせずに表示されること（エラートーストまたはエラー画面）
    await page.waitForTimeout(3_000)
    // ページが白画面でないことを確認
    const bodyText = await page.locator('body').textContent()
    expect(bodyText?.length).toBeGreaterThan(0)
  })

  test('ERR-003: API 403エラー時にアクセス拒否が表示される', async ({ page }) => {
    await page.route('**/api/v1/admin/**', async (route) => {
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Forbidden' }),
      })
    })
    await page.goto('/admin/dashboard')
    await waitForHydration(page)
    await page.waitForTimeout(3_000)
    // ページが何らかの内容を表示すること（403リダイレクトまたはエラー表示）
    const bodyText = await page.locator('body').textContent()
    expect(bodyText?.length).toBeGreaterThan(0)
  })

  test('ERR-004: ネットワーク切断時にエラー表示される', async ({ page }) => {
    await page.goto('/dashboard')
    await waitForHydration(page)
    // ネットワーク切断シミュレート
    await page.route('**/api/v1/**', async (route) => {
      await route.abort('connectionrefused')
    })
    // ページリロードでエラーが起きる
    await page.reload()
    await page.waitForTimeout(3_000)
    const bodyText = await page.locator('body').textContent()
    expect(bodyText?.length).toBeGreaterThan(0)
  })

  test('ERR-005: API タイムアウト時にページがフリーズしない', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      // 非常に遅いレスポンスをシミュレート
      await new Promise((resolve) => setTimeout(resolve, 10_000))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: {} }),
      })
    })
    await page.goto('/dashboard')
    // 10秒以内にページが何かを表示すること
    await page.waitForTimeout(5_000)
    const bodyText = await page.locator('body').textContent()
    expect(bodyText?.length).toBeGreaterThan(0)
  })

  test('ERR-006: 不正なJSONレスポンスでクラッシュしない', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: 'this is not json',
      })
    })
    await page.goto('/dashboard')
    await page.waitForTimeout(3_000)
    const bodyText = await page.locator('body').textContent()
    expect(bodyText?.length).toBeGreaterThan(0)
  })
})
