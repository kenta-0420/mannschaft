import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  TEAM_ID,
  SCHEDULE_ID,
  SLOT_ID_1,
  SLOT_ID_2,
  setupAdminAuth,
  mockCatchAllApis,
  mockTeamMembersApi,
  buildSchedule,
  buildSlot,
  mockSchedule,
  mockSlots,
  mockAssignmentRuns,
  mockShiftFeatureFlags,
} from './_helpers'

/**
 * AC-11-04: 自動割当の停止（フィーチャーフラグ OFF）時の UI 表示。
 *
 * <p>設計: docs/features/F03.5_shift/06_manual_authoring.md §11.1.2 項目4・5</p>
 *
 * <ul>
 *   <li>自動割当ボタン・実行履歴ボタンは <b>DOM から消さない</b>（v-if で隠すのは禁止）</li>
 *   <li>disabled ＋ 理由のツールチップで「停止中」と分かる形にする</li>
 *   <li>フラグ ON に戻せばそのまま押せる状態へ復帰する</li>
 * </ul>
 */

const BOARD_URL = `/teams/${TEAM_ID}/shifts/${SCHEDULE_ID}/board`
const DISABLED_REASON = '自動割当は現在停止しています。手動で組んでください。'

/** チーム権限 API を ADMIN として応答させる（自動割当導線は ADMIN/DEPUTY_ADMIN 限定のため）。 */
async function mockAdminPermissions(page: Page): Promise<void> {
  await page.route('**/api/v1/teams/*/me/permissions', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { roleName: 'ADMIN', permissions: [] } }),
    })
  })
}

async function setupBoard(page: Page): Promise<void> {
  await mockSchedule(page, buildSchedule({ status: 'ADJUSTING' }))
  await mockSlots(page, [buildSlot(SLOT_ID_1), buildSlot(SLOT_ID_2)])
  await mockAssignmentRuns(page, [])
}

test.describe('AC-11-04: 自動割当の停止中表示', () => {
  test.beforeEach(async ({ page }) => {
    await setupAdminAuth(page)
    await mockCatchAllApis(page)
    await mockTeamMembersApi(page)
    await mockAdminPermissions(page)
  })

  test('フラグ OFF: 自動割当ボタンは DOM に残り、disabled で停止中と分かる', async ({ page }) => {
    await mockShiftFeatureFlags(page, false)
    await setupBoard(page)

    await page.goto(BOARD_URL)
    await waitForHydration(page)

    const autoAssignBtn = page.getByTestId('auto-assign-button')

    // ① DOM から消えていないこと（非表示にするのは禁止）
    await expect(autoAssignBtn).toBeAttached({ timeout: 10_000 })
    await expect(autoAssignBtn).toBeVisible()
    await expect(autoAssignBtn).toContainText('自動割当')

    // ② 押せない（停止中）こと
    await expect(autoAssignBtn).toBeDisabled()

    // ③ 実行履歴ボタンも同様に残り、かつ disabled
    const historyBtn = page.getByTestId('auto-assign-history-button')
    await expect(historyBtn).toBeAttached()
    await expect(historyBtn).toBeVisible()
    await expect(historyBtn).toBeDisabled()

    // ④ 停止中である理由が提示されていること
    const reason = page.getByTestId('auto-assign-disabled-reason')
    await expect(reason).toBeAttached()
    await expect(reason).toHaveAttribute('title', DISABLED_REASON)

    // ⑤ 「停止中」バッジが見えること
    await expect(page.getByTestId('auto-assign-disabled-badge')).toBeVisible()
  })

  test('フラグ ON: 自動割当ボタンは有効で停止中バッジも出ない', async ({ page }) => {
    await mockShiftFeatureFlags(page, true)
    await setupBoard(page)

    await page.goto(BOARD_URL)
    await waitForHydration(page)

    const autoAssignBtn = page.getByTestId('auto-assign-button')
    await expect(autoAssignBtn).toBeVisible({ timeout: 10_000 })
    await expect(autoAssignBtn).toBeEnabled()

    await expect(page.getByTestId('auto-assign-disabled-badge')).toHaveCount(0)
    await expect(page.getByTestId('auto-assign-disabled-reason')).toHaveCount(0)
  })
})
