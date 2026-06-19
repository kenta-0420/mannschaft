/**
 * F10.1.1 管理者レンズ L1 トグル — emulated touch（タッチ）ジェスチャ補完 E2E（P4 要素3 補完）
 *
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が
 * 起動済みの状態で実行してください（playwright-real.config.ts は webServer 無効＝既存サーバー前提）。
 *
 * 実行プロジェクト: chromium-real（baseURL=http://localhost:3000）+ test.use({ hasTouch: true }) でタッチ端末を擬似。
 *
 * 目的（DashboardScopeLensToggle.vue §1.3 のジェスチャ排他ロジックを実ブラウザで検証）:
 *   - **タップ（移動なし）** → トグルが 1 回だけ切り替わる（ghost click による二重発火がない）。
 *   - **横スワイプ（閾値超）** → トグルは切り替わらない（カルーセルへジェスチャ委譲）。
 *
 * これは実機 QA（iOS Safari / Android Chrome での実タップ・実スワイプ）の代替ではなく、
 * Chromium の emulated touch でロジックの正しさを補完検証するもの（コンポーネント側コメント §1.3 参照）。
 *
 * テストユーザー: e2e-admin@test.mannschaft.local（FC東京U-18 ADMIN）— トグルは ADMIN/DEPUTY のみ描画されるため。
 */

import { test as base, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

const test = base.extend({})
// storageState 非依存 + タッチ端末擬似（hasTouch）。
test.use({ storageState: { cookies: [], origins: [] }, hasTouch: true })
test.setTimeout(120_000)

async function loginUI(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login')
  await waitForHydration(page)
  const emailInput = page.locator('input#email')
  await emailInput.click()
  await emailInput.pressSequentially(email, { delay: 10 })
  const passwordInput = page.locator('input[type="password"]')
  await passwordInput.click()
  await passwordInput.pressSequentially(password, { delay: 10 })
  await page.getByRole('button', { name: 'ログイン' }).click()
  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 30_000,
    waitUntil: 'commit',
  })
}

/** TEAM スコープの管理者レンズトグルが出るまでダッシュボードを開く。 */
async function openTeamLens(page: Page): Promise<void> {
  await page.goto('/dashboard')
  await waitForHydration(page)
  const segment = page.getByTestId('scope-segment-TEAM')
  await expect(segment).toBeVisible({ timeout: 20_000 })
  await segment.click()
  await expect(page.getByTestId('admin-lens-toggle-TEAM')).toBeVisible({ timeout: 20_000 })
}

/**
 * 指定要素の中心に対して touchstart→touchmove→touchend の Touch イベント列を dispatch する。
 * dx が大きいと「横スワイプ」、ほぼ 0 なら「タップ」を擬似する。
 * DashboardScopeLensToggle は @touchstart/@touchmove/@touchend ハンドラでこれを判定する（§1.3）。
 */
async function dispatchTouchGesture(
  page: Page,
  testId: string,
  dx: number,
): Promise<void> {
  await page.evaluate(
    ({ tid, deltaX }) => {
      const el = document.querySelector(`[data-testid="${tid}"]`) as HTMLElement | null
      if (!el) throw new Error(`toggle element not found: ${tid}`)
      const rect = el.getBoundingClientRect()
      const startX = rect.left + rect.width / 2
      const startY = rect.top + rect.height / 2

      const makeTouch = (x: number, y: number): Touch =>
        new Touch({ identifier: 1, target: el, clientX: x, clientY: y })

      const fire = (type: string, x: number, y: number) => {
        const touch = makeTouch(x, y)
        const ev = new TouchEvent(type, {
          bubbles: true,
          cancelable: true,
          touches: type === 'touchend' ? [] : [touch],
          targetTouches: type === 'touchend' ? [] : [touch],
          changedTouches: [touch],
        })
        el.dispatchEvent(ev)
      }

      fire('touchstart', startX, startY)
      if (deltaX !== 0) {
        // 横移動を段階的に発火（閾値 8px 超を確実に跨ぐ）
        fire('touchmove', startX + deltaX / 2, startY)
        fire('touchmove', startX + deltaX, startY)
      }
      fire('touchend', startX + deltaX, startY)
    },
    { tid: testId, deltaX: dx },
  )
}

test.describe('F10.1.1 管理者レンズ L1 トグル — emulated touch ジェスチャ', () => {
  test('TOUCH-001: タップ（移動なし）でトグルが 1 回だけ切り替わる（ghost click 二重発火なし）', async ({
    page,
  }) => {
    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await openTeamLens(page)

    const toggle = page.getByTestId('admin-lens-toggle-TEAM')
    const before = await toggle.getAttribute('aria-checked')

    // タップ（dx=0）: タップ判定 → toggle() 1 回 + ghost click は onClick が無視するはず。
    await dispatchTouchGesture(page, 'admin-lens-toggle-TEAM', 0)

    // 状態が「1 回だけ」反転すること（二重発火なら元に戻ってしまう）。
    const expectedAfter = before === 'true' ? 'false' : 'true'
    await expect(
      toggle,
      'タップで aria-checked が 1 回だけ反転する（二重発火していない）',
    ).toHaveAttribute('aria-checked', expectedAfter, { timeout: 8_000 })

    // 反転後、ON ならグリッドが、OFF ならグリッドが消えていること（実挙動の裏取り）。
    if (expectedAfter === 'true') {
      await expect(page.getByTestId('admin-widget-grid-TEAM')).toBeVisible({ timeout: 10_000 })
    } else {
      await expect(page.getByTestId('admin-widget-grid-TEAM')).toBeHidden({ timeout: 10_000 })
    }
  })

  test('TOUCH-002: 横スワイプ（閾値超）ではトグルが切り替わらない（カルーセルへ委譲）', async ({ page }) => {
    await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await openTeamLens(page)

    const toggle = page.getByTestId('admin-lens-toggle-TEAM')
    const before = await toggle.getAttribute('aria-checked')

    // 横スワイプ（dx=80px、|Δx| > |Δy|*1.5 を満たす）: touchMoved=true → toggle() を呼ばない。
    await dispatchTouchGesture(page, 'admin-lens-toggle-TEAM', 80)

    // しばらく待っても aria-checked が変化しないこと（スワイプではトグルしない §1.3）。
    await page.waitForTimeout(800)
    await expect(
      toggle,
      'スワイプでは aria-checked が変化しない（トグル発火しない）',
    ).toHaveAttribute('aria-checked', before ?? 'false')
  })
})
