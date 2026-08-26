import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, MOCK_PERMISSIONS, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-027〜028: 回覧板', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-027: 回覧板ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/circulation`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '回覧板' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-028: 回覧板ページが正常にロードされる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/circulation`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '回覧板' })).toBeVisible({ timeout: 10_000 })
    // エラー表示がないこと
    await expect(page.getByText('エラー', { exact: true })).not.toBeVisible()
  })
})

/**
 * 防御的ハードニング（無限loading 恒久リグレッション / AC-10）。
 *
 * モバイル監査で「回覧ページが全画面 loading のまま」を観測。D隊＋殿の二重実測では
 * 健全なセッションでは再現しなかったが、権限/一覧取得が失敗した際に無言でスピナー/空状態へ
 * 倒れる潜在的脆弱性を根治するため、失敗をエラー面＋再試行で可視化する。
 */
test.describe('TEAM-029〜031: 回覧板 ローディング門番のエラー可視化', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-029: 権限取得失敗でエラー面＋再試行が出て、再試行で復帰する', async ({ page }) => {
    let failPermissions = true
    // beforeEach の後に登録するため、こちらが me/permissions で優先される（Playwright は LIFO）。
    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      if (failPermissions) {
        await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: true }) })
      } else {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: MOCK_PERMISSIONS }) })
      }
    })

    await page.goto(`/teams/${TEAM_ID}/circulation`)
    await waitForHydration(page)

    // 無言で権限なしに倒さず、エラー面＋再試行が可視化されること。
    await expect(page.getByText('情報を取得できませんでした')).toBeVisible({ timeout: 10_000 })
    const retryBtn = page.getByRole('button', { name: '再試行' })
    await expect(retryBtn).toBeVisible()

    // 再試行クリックで再フェッチが発火し、成功時は一覧表示へ復帰すること。
    failPermissions = false
    await retryBtn.click()
    await expect(page.getByRole('heading', { name: '回覧板' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-030: 回覧一覧の取得失敗でリストのエラー面＋再試行が出る', async ({ page }) => {
    let failList = true
    // クエリ文字列付き（?page=0&size=20）を確実に捕捉するため glob ではなく正規表現で照合する。
    await page.route(new RegExp(`/api/v1/teams/${TEAM_ID}/circulations`), async (route) => {
      if (failList) {
        await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: true }) })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
        })
      }
    })

    await page.goto(`/teams/${TEAM_ID}/circulation`)
    await waitForHydration(page)

    // ページ自体は表示され、一覧部分がエラー面になる（空状態で誤魔化さない）。
    await expect(page.getByRole('heading', { name: '回覧板' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('情報を取得できませんでした')).toBeVisible({ timeout: 10_000 })
    const retryBtn = page.getByRole('button', { name: '再試行' })
    await expect(retryBtn).toBeVisible()

    // 再試行で復帰し、空状態が表示される。
    failList = false
    await retryBtn.click()
    await expect(page.getByText('回覧がありません')).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-031: 正常時は一覧（空状態）が表示されエラー面は出ない（リグレッション）', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/circulation`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '回覧板' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('回覧がありません')).toBeVisible()
    await expect(page.getByText('情報を取得できませんでした')).not.toBeVisible()
  })
})
