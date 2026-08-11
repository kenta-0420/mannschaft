import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * 実機E2E: チーム回覧ページの表示（無限loading 事象の恒久リグレッション / AC-10）
 *
 * モックなし・実BE(:8080)・実FE。認証済みメンバー（e2e-user, team-000092 の MEMBER）で
 * /teams/team-000092/circulation を開き、ページ門番（loadPermissions）が settle して
 * 一覧 or 空状態（回覧がありません）が描画されることを踏む。
 *
 * 背景: モバイル監査で「全画面 loading のまま」を観測 → D隊＋殿の二重実測では健全な
 * セッションで再現せず（全API 200）。本スペックは「回覧ページが無限スピナーで固着しない」
 * ことを実機で恒久的に守る番人。real系は CI 対象外・殿が手動実走する。
 *
 * 対象: team-000092（数値ID=92）。e2e-user は MEMBER。
 */
test.describe('CIRC-REAL: 回覧ページ実機（無限loading リグレッション）', () => {
  // 権限EP待ち(20s) + 見出し可視化待ち(20s) + 一覧EP待ち(20s) + 状態poll(15s) の合算が
  // 既定60秒を超えうるため、算術的な理由でタイムアウトを延長する（時間で殴る対処療法ではない）。
  test.setTimeout(120_000)

  test('CIRC-001: 認証済みメンバーで回覧ページが一覧or空状態を表示する', async ({ page }) => {
    // --- 権限 EP が settle すること（門番解除の核心） ---
    const [permResp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/api/v1/teams/team-000092/me/permissions')
          && r.request().method() === 'GET',
        { timeout: 20_000 },
      ),
      page.goto('/teams/team-000092/circulation'),
    ])
    expect(permResp.status(), `権限取得失敗: ${await permResp.text()}`).toBe(200)
    await waitForHydration(page)

    // --- ページ門番が解除され「回覧板」見出しが出ること（無限 loading でないこと） ---
    await expect(page.getByRole('heading', { name: '回覧板' })).toBeVisible({ timeout: 20_000 })

    // --- 一覧 EP も settle すること ---
    const listResp = await page.waitForResponse(
      (r) => r.url().includes('/api/v1/teams/team-000092/circulations')
        && r.request().method() === 'GET',
      { timeout: 20_000 },
    )
    expect(listResp.status(), `一覧取得失敗: ${await listResp.text()}`).toBe(200)

    // --- 成功パスのUI: 一覧項目 or 空状態のいずれかが見えること（エラー面/スピナー固着でない） ---
    const emptyState = page.getByText('回覧がありません')
    const listItems = page.locator('button:has(h3)')
    await expect
      .poll(async () => (await emptyState.isVisible()) || (await listItems.count()) > 0, {
        timeout: 15_000,
      })
      .toBe(true)

    // --- エラー面が出ていないこと（健全なセッションでは可視化トリガに触れない） ---
    await expect(page.getByText('情報を取得できませんでした')).not.toBeVisible()
  })
})
