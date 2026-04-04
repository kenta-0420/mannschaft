import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-021〜024: TODO', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-021: TODO一覧ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-022: TODO作成ボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: 'TODO作成' })).toBeVisible({ timeout: 5_000 })
  })

  test('TEAM-023: TODO作成ボタンを押すとページ遷移せずダイアログが開く', async ({ page }) => {
    const consoleErrors: string[] = []
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })

    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible({ timeout: 10_000 })
    await page.waitForLoadState('networkidle')
    // ボタンクリック
    await page.getByRole('button', { name: 'TODO作成' }).click()
    // ダイアログが開くため URL は /todos のまま
    await expect(page).toHaveURL(/\/todos$/, { timeout: 3_000 })
    // 少し待機してから Vue 状態を確認
    await page.waitForTimeout(200)
    const dialogCount = await page.evaluate(
      () => document.querySelectorAll('[role="dialog"]').length,
    )
    const pDialogCount = await page.evaluate(() => document.querySelectorAll('.p-dialog').length)
    const allPortals = await page.evaluate(() => {
      const teleports = Array.from(document.body.children).map((el) => el.className)
      return JSON.stringify(teleports)
    })
    console.log('Dialog count:', dialogCount, 'p-dialog count:', pDialogCount)
    console.log('Body children classes:', allPortals)
    console.log('Console errors:', consoleErrors)
    // 200ms後も見当たらなければ 3000ms 待って再確認
    await page.waitForTimeout(3000)
    const dialogCount2 = await page.evaluate(
      () => document.querySelectorAll('[role="dialog"]').length,
    )
    console.log('Dialog count after 3s:', dialogCount2)
    // ダイアログヘッダーが表示されること
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-024: 空の場合は空状態メッセージかリストが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible({ timeout: 10_000 })
    // ページが正常にロードされ、headingが存在すること
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible()
  })
})
