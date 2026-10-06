import { test, expect } from '@playwright/test'
import { gotoAuthed } from './helpers'

/**
 * モバイルUX根治戦役 — A隊向け受け入れ条件（AC-1/2/16）の red テスト。
 *
 * 対象: グローバルヘッダー（AppHeader.vue）+ モバイルドロワー（AppShell.vue / GlobalSidebar.vue）。
 *
 * 現状（試練時点で確認済みの実装）:
 * - AppHeader.vue はビューポート幅に関わらず全アクション（チーム/組織セレクタ、目安箱、
 *   受信箱、通知ベル、PWAインストール、ログアウト）を常時描画しており、モバイル専用の
 *   非表示/退避ロジックが一切ない。
 * - AppShell.vue のモバイルドロワーには GlobalSidebar（ナビグループ）のみが force-wide で
 *   描画され、受信箱・ログアウト等のヘッダー専用アクションは含まれない。
 * - 実測（390x844, /my/）: document.documentElement.scrollWidth - clientWidth = 207px の
 *   横オーバーフローを確認済み（ヘッダー内インタラクティブ要素の右端が最大 596.9px に達する）。
 */

test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 3 })

test.describe('MOBILE-SHELL: グローバルヘッダー/ドロワー 390px受け入れ条件', () => {
  test('MSH-01: /my/ で横パンできない（document.documentElement.scrollWidth <= clientWidth）', async ({ page }) => {
    await gotoAuthed(page, '/my/')
    await page.waitForTimeout(1000)

    const { scrollWidth, clientWidth } = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))

    expect(scrollWidth, `scrollWidth(${scrollWidth}) が clientWidth(${clientWidth}) を超えてはならない`).toBeLessThanOrEqual(clientWidth)
  })

  test('MSH-02: グローバルヘッダーの可視インタラクティブ要素が全て画面幅(390px)内に収まる', async ({ page }) => {
    await gotoAuthed(page, '/my/')
    await page.waitForTimeout(1000)

    const offenders = await page.evaluate(() => {
      const header = document.querySelector('header')
      if (!header) return ['header要素が見つからない']
      const interactive = Array.from(header.querySelectorAll('button, a, [role="button"]'))
        .filter((el) => (el as HTMLElement).offsetParent !== null)
      const bad: string[] = []
      for (const el of interactive) {
        const r = el.getBoundingClientRect()
        if (r.width > 0 && r.height > 0 && r.right > 390) {
          const label = (el.getAttribute('aria-label') || el.textContent || el.tagName).trim().slice(0, 30)
          bad.push(`${label} right=${Math.round(r.right)}`)
        }
      }
      return bad
    })

    expect(offenders, `画面幅(390px)をはみ出すヘッダー要素: ${JSON.stringify(offenders)}`).toEqual([])
  })

  test('MSH-03: 390pxでヘッダーから退避された機能（受信箱・ログアウト）がモバイルドロワー経由で到達可能', async ({ page }) => {
    await gotoAuthed(page, '/my/')
    await page.waitForTimeout(1000)

    // モバイル用ドロワートグル（AppHeader.vue の md:hidden ボタン）を開く
    const drawerToggle = page.locator('button.md\\:hidden').first()
    await expect(drawerToggle, 'モバイルドロワートグルボタンが見つからない').toBeVisible({ timeout: 5_000 })
    await drawerToggle.click()
    await page.waitForTimeout(500)

    // ドロワー内に受信箱への導線があること
    const inboxLink = page.locator('[data-testid="global-sidebar"]').getByRole('link', { name: /受信箱|inbox/i })
    await expect(inboxLink, 'モバイルドロワー内に受信箱への導線が見つからない（現状 GlobalSidebar にはナビグループのみが描画され、ヘッダー専用アクションは含まれない）').toBeVisible({ timeout: 5_000 })
  })

  test('MSH-04: 768px幅ではデスクトップヘッダー要素(チーム/組織セレクタ)が直接可視、390pxでは直接可視であってはならない(AC-16境界)', async ({ page }) => {
    // 768px: チーム/組織セレクタが直接可視であること（デスクトップ相当・現行仕様どおり）
    await page.setViewportSize({ width: 768, height: 1024 })
    await gotoAuthed(page, '/my/')
    await page.waitForTimeout(1000)
    const teamDropdownAt768 = page.locator('[data-testid="scope-nav-dropdown-toggle-TEAM"]')
    await expect(teamDropdownAt768, '768px でチームセレクタが直接可視であるべき').toBeVisible({ timeout: 5_000 })

    // 390px: AC-16 の境界どおりであれば、チーム/組織セレクタはヘッダーから退避され
    // 直接は可視でないはず（ドロワー等の間接導線に回る）。
    // 現状は常時ヘッダーに残置されるため、ここで red になる。
    await page.setViewportSize({ width: 390, height: 844 })
    await gotoAuthed(page, '/my/')
    await page.waitForTimeout(1000)
    const teamDropdownAt390 = page.locator('[data-testid="scope-nav-dropdown-toggle-TEAM"]')
    await expect(
      teamDropdownAt390,
      '390px でチームセレクタがヘッダーに直接可視のまま残っている（AC-16: モバイルでは退避されるべき）',
    ).not.toBeVisible({ timeout: 5_000 })
  })

  test('MSH-05: 共通ヘッダー/ドロワー/スコープメニューの独立操作が44px以上で画面内に収まる', async ({ page }, testInfo) => {
    await gotoAuthed(page, '/my/')
    await expect(page.locator('header')).toBeVisible()
    for (const width of [360, 390, 568, 767, 768]) {
      await page.setViewportSize({ width, height: 844 })
      await expect(page.locator('header')).toBeVisible()
      const headerTargets = await page.evaluate(() => {
        const header = document.querySelector('header')
        if (!header) return ['header要素が見つからない']
        return Array.from(header.querySelectorAll('button, a, [role="button"], [role="link"]'))
          .filter((el) => getComputedStyle(el).display !== 'none' && el.getClientRects().length > 0)
          .flatMap((el) => {
            const rect = el.getBoundingClientRect()
            const label = (el.getAttribute('aria-label') || el.textContent || el.tagName).trim().slice(0, 32)
            const errors: string[] = []
            if (rect.width < 44 || rect.height < 44) errors.push(`${label} ${Math.round(rect.width)}x${Math.round(rect.height)}`)
            if (rect.left < 0 || rect.right > window.innerWidth) errors.push(`${label} left=${Math.round(rect.left)} right=${Math.round(rect.right)}`)
            return errors
          })
      })
      expect(headerTargets, `${width}px header target geometry: ${JSON.stringify(headerTargets)}`).toEqual([])
      if (width === 768) {
        await page.screenshot({ path: testInfo.outputPath('header-768px.png') })
      }
    }

    await page.setViewportSize({ width: 390, height: 844 })
    const drawerToggle = page.locator('button.md\\:hidden').first()
    await expect(drawerToggle).toBeVisible()
    await drawerToggle.click()
    await expect(page.locator('[data-testid="global-sidebar"]')).toBeVisible()

    const drawerTargets = await page.evaluate(() => {
      const sidebar = document.querySelector('[data-testid="global-sidebar"]')
      const drawer = document.querySelector('.p-drawer')
      if (!sidebar || !drawer) return ['drawer/sidebar not found']
      return Array.from(drawer.querySelectorAll('button, a, [role="button"], [role="link"]'))
        .filter((el) => getComputedStyle(el).display !== 'none' && el.getClientRects().length > 0)
        .flatMap((el) => {
          const rect = el.getBoundingClientRect()
          const label = (el.getAttribute('aria-label') || el.textContent || el.tagName).trim().slice(0, 32)
          return rect.width < 44 || rect.height < 44
            ? [`${label} ${Math.round(rect.width)}x${Math.round(rect.height)}`]
            : []
        })
    })
    expect(drawerTargets, `drawer target geometry: ${JSON.stringify(drawerTargets)}`).toEqual([])
    const lastDrawerAction = page.locator('[data-testid="mobile-drawer-actions"] button').last()
    await lastDrawerAction.scrollIntoViewIfNeeded()
    await expect(lastDrawerAction).toBeInViewport()

    const teamToggle = page.getByTestId('mobile-drawer-actions').getByTestId('scope-nav-dropdown-toggle-TEAM')
    await teamToggle.focus()
    await teamToggle.press('Enter')
    await expect(teamToggle).toHaveAttribute('aria-expanded', 'true')
    const popover = page.locator('.p-popover:visible').last()
    await expect(popover).toBeVisible()
    const popoverGeometry = await popover.evaluate((el) => {
      const rect = el.getBoundingClientRect()
      const main = el.querySelector('.flex.gap-0.divide-x')
      const columns = main?.children
      const leftColumn = columns?.item(0)?.getBoundingClientRect()
      const rightColumn = columns?.item(1)?.getBoundingClientRect()
      const undersized = Array.from(el.querySelectorAll('button, a, [role="button"], [role="menuitem"]'))
        .filter((target) => getComputedStyle(target).display !== 'none' && target.getClientRects().length > 0)
        .flatMap((target) => {
          const targetRect = target.getBoundingClientRect()
          return targetRect.width < 44 || targetRect.height < 44
            ? [`${target.textContent?.trim().slice(0, 32)} ${Math.round(targetRect.width)}x${Math.round(targetRect.height)}`]
            : []
        })
      return {
        left: rect.left,
        right: rect.right,
        top: rect.top,
        bottom: rect.bottom,
        width: rect.width,
        columnGap: leftColumn && rightColumn ? rightColumn.left - leftColumn.right : null,
        undersized,
      }
    })
    const popoverLastAction = popover.locator('.border-t button').last()
    await popoverLastAction.scrollIntoViewIfNeeded()
    await expect(popoverLastAction).toBeInViewport()
    expect(popoverGeometry.left, `popover left=${popoverGeometry.left}`).toBeGreaterThanOrEqual(0)
    expect(popoverGeometry.right, `popover right=${popoverGeometry.right}`).toBeLessThanOrEqual(390)
    expect(popoverGeometry.top, `popover top=${popoverGeometry.top}`).toBeGreaterThanOrEqual(0)
    expect(popoverGeometry.bottom, `popover bottom=${popoverGeometry.bottom}`).toBeLessThanOrEqual(844)
    if (popoverGeometry.columnGap !== null) expect(popoverGeometry.columnGap).toBeGreaterThanOrEqual(-1)
    expect(popoverGeometry.undersized).toEqual([])
    await expect(page.getByTestId('scope-nav-dropdown-all-hub-TEAM')).toBeVisible()
    expect(await page.getByTestId('scope-nav-dropdown-all-hub-TEAM').evaluate((el) => el.closest('[role="menuitem"]'))).toBeNull()
    expect(await popover.locator('[role="menuitem"] button').count()).toBe(0)
    await page.screenshot({ path: testInfo.outputPath('drawer-scope-popover-390px.png') })
    await page.getByTestId('scope-nav-dropdown-all-hub-TEAM').press('Enter')
    await expect(page).toHaveURL(/\/teams\/?$/)
    await expect(page.locator('[data-testid="global-sidebar"]')).toBeHidden()

    await page.setViewportSize({ width: 360, height: 844 })
    const narrowDrawerToggle = page.locator('button.md\\:hidden').first()
    await expect(narrowDrawerToggle).toBeVisible()
    await narrowDrawerToggle.click()
    const narrowTeamToggle = page.getByTestId('mobile-drawer-actions').getByTestId('scope-nav-dropdown-toggle-TEAM')
    await narrowTeamToggle.focus()
    await narrowTeamToggle.press('Enter')
    const narrowPopover = page.locator('.p-popover:visible').last()
    await expect(narrowPopover).toBeVisible()
    const narrowLastAction = narrowPopover.locator('.border-t button').last()
    await narrowLastAction.scrollIntoViewIfNeeded()
    await expect(narrowLastAction).toBeInViewport()
    const narrowPopoverGeometry = await narrowPopover.evaluate((el) => {
      const rect = el.getBoundingClientRect()
      const main = el.querySelector('.flex.gap-0.divide-x')
      const columns = main?.children
      const leftColumn = columns?.item(0)?.getBoundingClientRect()
      const rightColumn = columns?.item(1)?.getBoundingClientRect()
      const actionFooter = el.querySelector('.border-t')?.getBoundingClientRect()
      return {
        left: rect.left,
        right: rect.right,
        top: rect.top,
        bottom: rect.bottom,
        columnGap: leftColumn && rightColumn ? rightColumn.left - leftColumn.right : null,
        footerBottom: actionFooter?.bottom ?? null,
      }
    })
    expect(narrowPopoverGeometry.left, `360px popover left=${narrowPopoverGeometry.left}`).toBeGreaterThanOrEqual(0)
    expect(narrowPopoverGeometry.right, `360px popover right=${narrowPopoverGeometry.right}`).toBeLessThanOrEqual(360)
    expect(narrowPopoverGeometry.top, `360px popover top=${narrowPopoverGeometry.top}`).toBeGreaterThanOrEqual(0)
    expect(narrowPopoverGeometry.bottom, `360px popover bottom=${narrowPopoverGeometry.bottom}`).toBeLessThanOrEqual(844)
    if (narrowPopoverGeometry.columnGap !== null) expect(narrowPopoverGeometry.columnGap).toBeGreaterThanOrEqual(-1)
    if (narrowPopoverGeometry.footerBottom !== null) expect(narrowPopoverGeometry.footerBottom).toBeLessThanOrEqual(844)
    await page.screenshot({ path: testInfo.outputPath('drawer-scope-popover-360px.png') })
  })
})
