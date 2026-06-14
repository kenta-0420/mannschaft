/**
 * F22.1 横スワイプ・スコープダッシュボード — slug 判定リグレッション E2E（実機）。
 *
 * 背景:
 *   チーム/組織の識別子が slug 移行（PR #1413〜）で UUID(public_id) から人間可読 slug
 *   （fc-u-18 等）に変わった後、DashboardTeamPanel / DashboardOrgPanel のパネル表示判定
 *   だけが UUID 正規表現前提のまま取り残されていた。slug は UUID 正規表現にマッチしない
 *   ため else（永久スピナー）に落ち、ログイン後にチーム/組織パネルが何も表示されない
 *   不具合があった（このバグは F22.1 に E2E が無かったため眠っていた）。
 *
 * このテストは「修正前は赤・修正後は緑」になることを目的に、チームパネルが永久スピナー
 * ではなく実コンテンツ（ScopeTabBar / DashboardSwipeWidgetGrid）を描画することを検証する。
 *
 * 実行条件:
 *   - バックエンド http://localhost:8080 / フロントエンド起動済み
 *   - 認証: tests/e2e/.auth/real-user.json（e2e-user@test.mannschaft.local）
 *     → FC東京U-18（テスト）チームのメンバー
 */

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.describe('F221-SWIPE: スワイプダッシュボード slug パネル表示', () => {
  // /dashboard は 3 パネル同時マウント + 多数のウィジェット取得があり遅いため延長する
  test.setTimeout(120_000)

  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
  })

  test('F221-SWIPE-001: カルーセルとチームセグメントが表示される', async ({ page }) => {
    await expect(page.getByTestId('scope-carousel')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('scope-segment-TEAM')).toBeVisible({ timeout: 20_000 })
  })

  test('F221-SWIPE-002: チームパネルが永久スピナーにならず実コンテンツを描画する', async ({ page }) => {
    // チームパネルへ切り替える（クリックで activePanel=TEAM）
    await page.getByTestId('scope-segment-TEAM').click()

    // チームのタグバーが表示される（slug 移行後も即時 load されること）
    await expect(page.getByTestId('scope-tab-bar-TEAM')).toBeVisible({ timeout: 20_000 })

    // 永久スピナーのリグレッション本丸: ウィジェットグリッド（実コンテンツ）が描画される。
    // 修正前は UUID 正規表現に slug がマッチせず loading=true のまま固定され、ここで失敗する。
    await expect(page.getByTestId('swipe-widget-grid-TEAM')).toBeVisible({ timeout: 30_000 })

    // スピナー（PageLoading）が残っていないことを併せて確認する。
    const teamPanel = page.locator('#scope-panel-TEAM')
    await expect(teamPanel.locator('.pi-spin')).toHaveCount(0)
  })

  test('F221-SWIPE-003: 組織パネルも永久スピナーにならない（所属組織がある場合）', async ({ page }) => {
    await page.getByTestId('scope-segment-ORGANIZATION').click()
    await expect(page.getByTestId('scope-tab-bar-ORGANIZATION')).toBeVisible({ timeout: 20_000 })

    const orgPanel = page.locator('#scope-panel-ORGANIZATION')
    // 組織に所属していればウィジェットグリッド、未所属なら空状態が描画される。
    // どちらにせよ「永久スピナー（PageLoading）」で固まらないことを検証する。
    await expect
      .poll(
        async () => {
          const grid = await page.getByTestId('swipe-widget-grid-ORGANIZATION').count()
          const empty = await orgPanel
            .getByText('所属しているチーム/組織がありません', { exact: false })
            .count()
          const spinner = await orgPanel.locator('.pi-spin').count()
          // grid もしくは空状態が出ていて、かつスピナーが残っていなければ OK
          return (grid > 0 || empty > 0) && spinner === 0
        },
        { timeout: 30_000 },
      )
      .toBe(true)
  })

  test('F221-SWIPE-004: UUID 宛の不正なダッシュボード取得（400/404）が発生しない', async ({ page }) => {
    const badRequests: string[] = []
    page.on('response', (res) => {
      const url = res.url()
      const uuidRe =
        /\/api\/v1\/dashboard\/(team|organization)\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i
      if (uuidRe.test(url)) {
        badRequests.push(`${res.status()} ${url}`)
      }
    })

    await page.getByTestId('scope-segment-TEAM').click()
    await expect(page.getByTestId('swipe-widget-grid-TEAM')).toBeVisible({ timeout: 30_000 })
    await page.getByTestId('scope-segment-ORGANIZATION').click()
    await waitForHydration(page)
    // 取得が落ち着くのを待つ
    await page.waitForTimeout(2_000)

    expect(
      badRequests,
      `UUID 宛のダッシュボード取得が発生した（slug 移行後は slug 宛であるべき）: ${badRequests.join(', ')}`,
    ).toHaveLength(0)
  })
})
