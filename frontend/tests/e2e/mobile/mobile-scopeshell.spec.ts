import { test, expect } from '@playwright/test'
import { TEAM_ID, MOCK_TEAM, mockTeamFeatureApis } from '../teams/helpers'
import { gotoAuthed } from './helpers'

/**
 * モバイルUX根治戦役 — B隊向け受け入れ条件（AC-1/4/5/6/7）の red テスト。
 *
 * 対象: チーム永続シェル（pages/teams/[slug].vue + ScopePageShell.vue + TeamPageHeader.vue）。
 *
 * 現状（試練時点で確認済みの実装）:
 * - TeamPageHeader.vue の「チームから退出」ボタンは `v-if="!isAdmin && roleName"` のみで
 *   overflow メニュー等への格納がなく、一般メンバーには常時直接可視（実測 height=35px、
 *   overflow メニュー化されていない）。
 * - BackButton.vue は明示的な min-height を持たず、PageHeader 既定(back=true)で
 *   常時描画されるページ（/notifications 等）でタップ領域が 44px を割る。
 * - グローバルヘッダー起因で /teams/{id} も document.documentElement.scrollWidth が
 *   clientWidth(390) を超える（実測 597px）。
 *
 * 一般メンバー（非管理者）のロールで検証するため、mockTeamFeatureApis() の後に
 * /me/permissions を MEMBER ロールで上書きする（Playwright の route は後勝ちのため順序が重要）。
 */

test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 3 })

async function mockTeamAsMember(page: import('@playwright/test').Page) {
  await page.route(`**/api/v1/teams/${TEAM_ID}`, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: MOCK_TEAM }) })
  })
  await mockTeamFeatureApis(page)
  // mockTeamFeatureApis 登録後に上書き登録することで一般メンバー権限を優先させる
  await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { roleName: 'MEMBER', permissions: [] } }),
    })
  })
}

test.describe('MOBILE-SCOPESHELL: チーム永続シェル 390px受け入れ条件', () => {
  test('MSS-01: /teams/{id}/ で横パンできない', async ({ page }) => {
    await mockTeamAsMember(page)
    await gotoAuthed(page, `/teams/${TEAM_ID}`)
    await page.waitForTimeout(1200)

    const { scrollWidth, clientWidth } = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(scrollWidth, `scrollWidth(${scrollWidth}) が clientWidth(${clientWidth}) を超えてはならない`).toBeLessThanOrEqual(clientWidth)
  })

  test('MSS-02: scope-shell タブの各リンクのタップ領域が height>=44', async ({ page }) => {
    await mockTeamAsMember(page)
    await gotoAuthed(page, `/teams/${TEAM_ID}`)
    await page.waitForTimeout(1200)

    const tabLinks = page.locator('[role="tablist"] a')
    const count = await tabLinks.count()
    expect(count, 'タブリンクが1件も見つからない').toBeGreaterThan(0)

    const tooSmall: string[] = []
    for (let i = 0; i < count; i++) {
      const box = await tabLinks.nth(i).boundingBox()
      const text = (await tabLinks.nth(i).innerText().catch(() => '')).trim()
      if (box && box.height < 44) {
        tooSmall.push(`${text} height=${Math.round(box.height)}`)
      }
    }
    expect(tooSmall, `44px未満のタブリンク: ${JSON.stringify(tooSmall)}`).toEqual([])
  })

  test('MSS-03: タブバー自体がはみ出す場合はタブバー内部スクロールで吸収し、body幅は390以下', async ({ page }) => {
    await mockTeamAsMember(page)
    await gotoAuthed(page, `/teams/${TEAM_ID}`)
    await page.waitForTimeout(1200)

    const { scrollWidth, clientWidth } = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(
      scrollWidth,
      `タブバーの内容量によらず document 幅は clientWidth(${clientWidth}) 以下であるべき（実測 scrollWidth=${scrollWidth}）`,
    ).toBeLessThanOrEqual(clientWidth)
  })

  test('MSS-04: 初期ビューで「チームから退出」ボタンが不可視（overflowメニュー格納）', async ({ page }) => {
    await mockTeamAsMember(page)
    await gotoAuthed(page, `/teams/${TEAM_ID}`)
    await page.waitForTimeout(1200)

    const leaveBtn = page.getByRole('button', { name: 'チームから退出' })
    await expect(
      leaveBtn,
      '「チームから退出」ボタンが初期ビューで直接可視になっている（overflowメニューに格納されるべき）',
    ).not.toBeVisible({ timeout: 5_000 })
  })

  test('MSS-05: 初期ビュー(844px内)にタブバーが可視 かつ ヒーロー(ProfileHeader)が圧縮されている', async ({ page }) => {
    await mockTeamAsMember(page)
    await gotoAuthed(page, `/teams/${TEAM_ID}`)
    await page.waitForTimeout(1200)

    const tabList = page.locator('[role="tablist"]').first()
    await expect(tabList).toBeVisible({ timeout: 5_000 })
    const box = await tabList.boundingBox()
    expect(box, 'タブバーの座標が取得できない').not.toBeNull()

    // 単純な「844px内に収まっているか」は現状バナー+情報+アクション群を含めても
    // 収まってしまうため red にならない（試練時点で実測: tabList.y ≈ 449px）。
    // AC-5 の意図（ヒーロー圧縮）をより忠実に検証するため、ヘッダー(var(--app-header-h)
    // 既定64px)を除いたヒーロー部分の高さが 200px 以下（モバイル圧縮後の目安）であることを
    // 併せて検証する。現状は banner+ProfileHeader情報+アクションボタン群で約385pxあり red になる。
    const headerHeight = await page.evaluate(() => {
      const header = document.querySelector('header')
      return header ? header.getBoundingClientRect().height : 0
    })
    const heroHeight = box!.y - headerHeight
    expect(
      heroHeight,
      `ヒーロー(ProfileHeader)の高さ(${Math.round(heroHeight)}px)が圧縮目安の200pxを超えている（バナー+情報+アクション群がモバイルで圧縮されていない）`,
    ).toBeLessThanOrEqual(200)
  })

  test('MSS-06: BackButton（「戻る」）のヒット領域が height>=44（例: /notifications）', async ({ page }) => {
    await page.route('**/api/v1/notifications**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [], meta: { nextCursor: null, limit: 20, hasNext: false } }),
      })
    })
    await gotoAuthed(page, '/notifications')
    await page.waitForTimeout(1000)

    const back = page.getByRole('link', { name: '戻る' }).or(page.getByRole('button', { name: '戻る' }))
    await expect(back, '「戻る」ボタン/リンクが見つからない').toBeVisible({ timeout: 5_000 })
    const box = await back.boundingBox()
    expect(box, 'BackButtonの座標が取得できない').not.toBeNull()
    expect(box!.height, `BackButtonのヒット領域高さ(${box!.height})が44pxを下回っている`).toBeGreaterThanOrEqual(44)
  })
})
