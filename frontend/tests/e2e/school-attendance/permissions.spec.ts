import { test, expect } from '@playwright/test'
import JA from '../../../app/locales/ja/school.json' with { type: 'json' }
import { waitForHydration } from '../helpers/wait'
import {
  DEFAULT_TEAM_ID,
  DEFAULT_DATE,
  STUDENT_USER_ID_1,
  buildDailyAttendanceRecord,
  loginAsTeacher,
  mockAttendancePermissions,
  mockCatchAllApis,
  mockGetDailyAttendance,
} from './_helpers'

/**
 * CMP-261001-0630 AC-18: 学校出欠の教員用画面は権限判定 API の結果で出し分ける。
 *   - 権限なし（全 false）で URL 直打ち → 「権限がありません」を明示し、一覧 API は呼ばない
 *   - 権限判定 API が 403 でも握りつぶさず「権限がありません」を明示
 *   - 閲覧のみ（canRecordDaily=false）→ 提出ボタンは非活性
 */
const BASE = `/teams/${DEFAULT_TEAM_ID}/school-attendance`
const LABELS = JA.school.attendance

test.describe('SCHOOL-AUTHZ-001〜004: 学校出欠の権限出し分け（AC-18）', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsTeacher(page, { teamId: DEFAULT_TEAM_ID })
    await mockCatchAllApis(page)
  })

  for (const path of ['daily-roll-call', 'period-attendance', 'transition-alerts', 'statistics']) {
    test(`SCHOOL-AUTHZ-001: 権限なしで ${path} を直打ち → 権限がありません`, async ({ page }) => {
      await mockAttendancePermissions(page, {
        canView: false,
        canRecordDaily: false,
        canRecordPeriod: false,
      })
      await page.goto(`${BASE}/${path}`)
      await waitForHydration(page)
      const panel = page.getByTestId('school-attendance-forbidden')
      await expect(panel).toBeVisible({ timeout: 10_000 })
      await expect(panel).toContainText(LABELS.forbidden.title)
    })
  }

  test('SCHOOL-AUTHZ-002: 一覧 API が 403 でも握りつぶさず権限がありません', async ({ page }) => {
    await page.route('**/api/v1/teams/*/attendance/daily**', async (route) => {
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'COMMON_002', message: 'forbidden' } }),
      })
    })
    await page.goto(`${BASE}/daily-roll-call`)
    await waitForHydration(page)
    await expect(page.getByTestId('school-attendance-forbidden')).toBeVisible({ timeout: 10_000 })
  })

  test('SCHOOL-AUTHZ-003: 権限判定 API が 403 でも権限がありません', async ({ page }) => {
    await mockAttendancePermissions(page, { status: 403 })
    await page.goto(`${BASE}/daily-roll-call`)
    await waitForHydration(page)
    await expect(page.getByTestId('school-attendance-forbidden')).toBeVisible({ timeout: 10_000 })
  })

  test('SCHOOL-AUTHZ-004: 閲覧のみ（登録権限なし）は提出ボタンが非活性', async ({ page }) => {
    await mockAttendancePermissions(page, { canView: true, canRecordDaily: false })
    await mockGetDailyAttendance(page, [
      buildDailyAttendanceRecord({
        id: 1,
        studentUserId: STUDENT_USER_ID_1,
        attendanceDate: DEFAULT_DATE,
        status: 'UNDECIDED',
      }),
    ])
    await page.goto(`${BASE}/daily-roll-call`)
    await waitForHydration(page)
    await expect(page.getByTestId('roll-call-row-101')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('daily-roll-call-submit')).toBeDisabled()
  })
})
