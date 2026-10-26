import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// chromium プロジェクトの storageState（認証済みユーザー）を使用

test.describe('CAL-001〜002: カレンダー', () => {
  // カレンダー API をモックして安定した描画を保証
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/schedules/personal**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/schedules/calendar**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { events: [] } }),
      })
    })
  })

  test('CAL-001: カレンダーページが表示され現在の年月が表示される', async ({ page }) => {
    await page.goto('/calendar')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'マイカレンダー' })).toBeVisible({
      timeout: 10_000,
    })

    // CalendarGrid の月ラベルが表示されること（例: 2026年4月）
    const now = new Date()
    const expectedLabel = `${now.getFullYear()}年${now.getMonth() + 1}月`
    await expect(page.getByRole('heading', { name: expectedLabel })).toBeVisible({ timeout: 5_000 })

    // 「予定を追加」ボタンが存在すること
    await expect(page.getByRole('button', { name: '予定を追加' })).toBeVisible()
  })

  test('CAL-002: 次月ボタンで翌月に、前月ボタンで当月に戻れる', async ({ page }) => {
    await page.goto('/calendar')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'マイカレンダー' })).toBeVisible({
      timeout: 10_000,
    })

    const now = new Date()
    const year = now.getFullYear()
    const month = now.getMonth() + 1
    const currentLabel = `${year}年${month}月`

    // 現在月が表示されていること
    await expect(page.getByRole('heading', { name: currentLabel })).toBeVisible({ timeout: 5_000 })

    // 翌月ラベルを計算
    const nextMonth = month === 12 ? 1 : month + 1
    const nextYear = month === 12 ? year + 1 : year
    const nextLabel = `${nextYear}年${nextMonth}月`

    // 次月ボタン（pi-chevron-right を含む button）をクリック
    const nextBtn = page.getByRole('button').filter({ has: page.locator('.pi-chevron-right') })
    await nextBtn.click()
    await expect(page.getByRole('heading', { name: nextLabel })).toBeVisible({ timeout: 5_000 })

    // 前月ボタンで当月に戻る
    const prevBtn = page.getByRole('button').filter({ has: page.locator('.pi-chevron-left') })
    await prevBtn.click()
    await expect(page.getByRole('heading', { name: currentLabel })).toBeVisible({ timeout: 5_000 })
  })
})
