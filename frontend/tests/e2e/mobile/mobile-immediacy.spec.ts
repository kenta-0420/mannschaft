import { test, expect } from '@playwright/test'
import { TEAM_ID, MOCK_TEAM, mockTeamFeatureApis } from '../teams/helpers'
import { gotoAuthed } from './helpers'

/**
 * モバイルUX根治戦役 — E隊向け受け入れ条件（AC-11/12）の red テスト。
 *
 * 対象:
 * - /my/shift（一般メンバーのマイシフト確認）: GET /api/v1/shifts/my/confirmed-slots は
 *   既に実装済みで一覧・空状態表示も動作するが、月次カレンダービュー（既定表示）の
 *   時刻スニペットは `hidden sm:block`（frontend/app/pages/my/shift.vue 付近）で
 *   390px幅では非表示になり、件数バッジのみしか一見できない（タップしないと時刻が
 *   見えない = 即時性が無い）。
 * - チームスケジュール（pages/teams/[slug]/schedule.vue）: カレンダーグリッド表示のみで
 *   リストビューが存在しない。出欠必須イベントの行内RSVPは AttendancePanel が
 *   カレンダーセル選択後の詳細サイドパネルにのみ存在し、一覧行には無い。
 *
 * MIM-03〜05 は「将来実装されるリストビュー」を前提にした data-testid 想定セレクタで
 * 検証する（現状は未実装のため要素不在で red になる）。
 */

test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 3 })

function todayStr(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

test.describe('MOBILE-IMMEDIACY: シフト・スケジュール 390px受け入れ条件', () => {
  test('MIM-01: 一般メンバーで /my/shift を開くと confirmed-slots が呼ばれ、確定シフトの時刻が初期ビューで直接可視', async ({ page }) => {
    let called = false
    await page.route('**/api/v1/shifts/my/confirmed-slots**', async (route) => {
      called = true
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [
            {
              slotId: 1,
              slotDate: todayStr(),
              startTime: '09:00:00',
              endTime: '12:00:00',
              teamId: TEAM_ID,
              teamName: 'テストチーム',
              scheduleId: 1,
              scheduleName: 'テスト大会',
              positionName: null,
            },
          ],
        }),
      })
    })

    await gotoAuthed(page, '/my/shift')
    await page.waitForTimeout(1500)

    expect(called, 'GET /api/v1/shifts/my/confirmed-slots が呼ばれていない').toBe(true)

    const timeText = page.getByText('09:00', { exact: false }).first()
    await expect(
      timeText,
      '確定シフトの時刻(09:00)が初期ビューで直接可視でない（月次カレンダーの時刻スニペットは hidden sm:block でモバイル非表示のため、タップしないと確認できない）',
    ).toBeVisible({ timeout: 5_000 })
  })

  test('MIM-02: confirmed-slots が空配列のとき空状態文言が表示され、無限スピナー・エラーにしない', async ({ page }) => {
    await page.route('**/api/v1/shifts/my/confirmed-slots**', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }) })
    })

    await gotoAuthed(page, '/my/shift')
    await page.waitForTimeout(1500)

    await expect(page.getByText('シフトがありません')).toBeVisible({ timeout: 5_000 })
    await expect(page.locator('.pi-spin')).toHaveCount(0)

    // 同一ページの PageHeader(back-to="/my") が描画する BackButton のヒット領域も
    // 44px を満たすべき（BackButton.vue に min-height が無い共通欠陥）。
    const back = page.getByRole('link', { name: '戻る' }).or(page.getByRole('button', { name: '戻る' }))
    await expect(back).toBeVisible({ timeout: 5_000 })
    const box = await back.boundingBox()
    expect(box, 'BackButtonの座標が取得できない').not.toBeNull()
    expect(box!.height, `/my/shift の BackButton ヒット領域高さ(${box!.height})が44pxを下回っている`).toBeGreaterThanOrEqual(44)
  })

  test('MIM-03: 390pxで /teams/{id}/schedule にリストビューが表示され、モックイベントが日付順で並ぶ', async ({ page }) => {
    await page.route(`**/api/v1/teams/${TEAM_ID}`, async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: MOCK_TEAM }) })
    })
    await mockTeamFeatureApis(page)
    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { roleName: 'MEMBER', permissions: [] } }) })
    })

    await gotoAuthed(page, `/teams/${TEAM_ID}/schedule`)
    await page.waitForTimeout(1200)

    // 将来実装のリストビュー想定セレクタ（現状は不在のため red）
    const listView = page.locator('[data-testid="schedule-list-view"]')
    await expect(
      listView,
      '390px向けのスケジュールリストビューが見つからない（現状はカレンダーグリッド表示のみ）',
    ).toBeVisible({ timeout: 5_000 })
  })

  test('MIM-04: 出欠必須イベントの行に出席/欠席/未定ボタンがあり、タップでrespondAttendanceが呼ばれる', async ({ page }) => {
    await page.route(`**/api/v1/teams/${TEAM_ID}`, async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: MOCK_TEAM }) })
    })
    await mockTeamFeatureApis(page)
    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { roleName: 'MEMBER', permissions: [] } }) })
    })

    let attendanceCalled: { body: unknown } | null = null
    await page.route(`**/api/v1/teams/${TEAM_ID}/schedules/*/attendances/me`, async (route) => {
      attendanceCalled = { body: route.request().postDataJSON() }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: {} }) })
    })

    await gotoAuthed(page, `/teams/${TEAM_ID}/schedule`)
    await page.waitForTimeout(1200)

    const listRow = page.locator('[data-testid="schedule-list-row"]').first()
    await expect(
      listRow,
      'スケジュール一覧行が見つからない（現状はリストビュー自体が未実装）',
    ).toBeVisible({ timeout: 5_000 })

    await listRow.getByRole('button', { name: '出席' }).click()
    await page.waitForTimeout(500)
    expect(attendanceCalled, 'respondAttendance API が呼ばれていない').not.toBeNull()
  })

  test('MIM-05: 出欠APIを500で返すと、トースト表示され選択状態が変わらない', async ({ page }) => {
    await page.route(`**/api/v1/teams/${TEAM_ID}`, async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: MOCK_TEAM }) })
    })
    await mockTeamFeatureApis(page)
    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { roleName: 'MEMBER', permissions: [] } }) })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/schedules/*/attendances/me`, async (route) => {
      await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: true, message: 'internal' }) })
    })

    await gotoAuthed(page, `/teams/${TEAM_ID}/schedule`)
    await page.waitForTimeout(1200)

    const listRow = page.locator('[data-testid="schedule-list-row"]').first()
    await expect(
      listRow,
      'スケジュール一覧行が見つからない（現状はリストビュー自体が未実装）',
    ).toBeVisible({ timeout: 5_000 })

    const yesBtn = listRow.getByRole('button', { name: '出席' })
    await yesBtn.click()
    await page.waitForTimeout(800)

    await expect(page.locator('.p-toast-message, [role="alert"]')).toBeVisible({ timeout: 5_000 })
    await expect(yesBtn, '500エラー後も出席ボタンが選択状態のままになってはいけない').not.toHaveClass(/selected|active|p-button-success/)
  })
})
