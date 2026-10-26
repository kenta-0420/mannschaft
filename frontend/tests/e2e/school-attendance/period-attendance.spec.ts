import { test, expect } from '@playwright/test'
import JA from '../../../app/locales/ja/school.json' with { type: 'json' }
import { waitForHydration } from '../helpers/wait'
import {
  DEFAULT_TEAM_ID,
  STUDENT_USER_ID_1,
  STUDENT_USER_ID_2,
  STUDENT_USER_ID_3,
  buildCandidateItem,
  buildPeriodAttendanceSummary,
  loginAsTeacher,
  mockCatchAllApis,
  mockGetPeriodCandidates,
  mockSubmitPeriodAttendance,
} from './_helpers'

/**
 * F03.13 Phase 8 §SCHOOL-PERIOD 時限別出欠 E2E テスト群（SCHOOL-PERIOD-001〜003）。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>SCHOOL-PERIOD-001: 候補者3名がロードされ、時限・日付が表示される</li>
 *   <li>SCHOOL-PERIOD-002: 全員 ATTENDING で時限出欠を確定 → POST ボディに entries 含む</li>
 *   <li>SCHOOL-PERIOD-003: 1名を ABSENT → summary の absent=1 を確認</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F03.13_school_attendance.md §PERIOD</p>
 */

const PERIOD_ATTENDANCE_URL = `/teams/${DEFAULT_TEAM_ID}/school-attendance/period-attendance`

/** 3名構成の標準候補者セット（ID 101/102/103）。 */
function buildStandardCandidates() {
  return [
    buildCandidateItem({ studentUserId: STUDENT_USER_ID_1, displayName: '山田太郎', dailyStatus: 'ATTENDING' }),
    buildCandidateItem({ studentUserId: STUDENT_USER_ID_2, displayName: '佐藤花子', dailyStatus: 'ATTENDING' }),
    buildCandidateItem({ studentUserId: STUDENT_USER_ID_3, displayName: '鈴木一郎', dailyStatus: 'ATTENDING' }),
  ]
}

test.describe('SCHOOL-PERIOD-001〜003: F03.13 §PERIOD 時限別出欠', () => {
  test.beforeEach(async ({ page }) => {
    // 教員として認証注入し、catch-all で未モック API を 404 化する
    await loginAsTeacher(page, { teamId: DEFAULT_TEAM_ID })
    await mockCatchAllApis(page)
  })

  test('SCHOOL-PERIOD-001: 候補者3名がロードされ、時限・日付が表示される', async ({ page }) => {
    const candidates = buildStandardCandidates()
    await mockGetPeriodCandidates(page, candidates)
    // POST もモックしておく（ページがロード時に呼ばない場合でも catch-all 除外のため）
    await mockSubmitPeriodAttendance(page, buildPeriodAttendanceSummary())

    await page.goto(PERIOD_ATTENDANCE_URL)
    await waitForHydration(page)

    // ページタイトルが表示される
    await expect(page.getByRole('heading', { name: JA.school.attendance.period.title })).toBeVisible({
      timeout: 10_000,
    })

    // 日付 InputText が表示される
    await expect(page.getByTestId('period-attendance-date')).toBeVisible({ timeout: 10_000 })

    // 時限 Select が表示される
    await expect(page.getByTestId('period-attendance-period-select')).toBeVisible()

    // 3名の候補者行が表示される
    await expect(page.getByTestId(`period-row-${STUDENT_USER_ID_1}`)).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId(`period-row-${STUDENT_USER_ID_2}`)).toBeVisible()
    await expect(page.getByTestId(`period-row-${STUDENT_USER_ID_3}`)).toBeVisible()

    // 送信ボタンが表示される
    await expect(page.getByTestId('period-attendance-submit')).toBeVisible()
  })

  test('SCHOOL-PERIOD-002: 全員 ATTENDING で時限出欠を確定 → POST ボディに entries 含む', async ({ page }) => {
    const candidates = buildStandardCandidates()
    await mockGetPeriodCandidates(page, candidates)
    const captured: { lastBody: unknown } = { lastBody: null }
    await mockSubmitPeriodAttendance(
      page,
      buildPeriodAttendanceSummary({
        total: 3,
        attending: 3,
        absent: 0,
      }),
      captured,
    )

    await page.goto(PERIOD_ATTENDANCE_URL)
    await waitForHydration(page)

    // 3名の候補者行が描画されるまで待つ
    await expect(page.getByTestId(`period-row-${STUDENT_USER_ID_1}`)).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId(`period-row-${STUDENT_USER_ID_2}`)).toBeVisible()
    await expect(page.getByTestId(`period-row-${STUDENT_USER_ID_3}`)).toBeVisible()

    // 全員 ATTENDING ボタンをクリック
    await page.getByTestId(`period-row-${STUDENT_USER_ID_1}-attending`).click()
    await page.getByTestId(`period-row-${STUDENT_USER_ID_2}-attending`).click()
    await page.getByTestId(`period-row-${STUDENT_USER_ID_3}-attending`).click()

    // data-status 属性が ATTENDING になる
    await expect(page.getByTestId(`period-row-${STUDENT_USER_ID_1}`)).toHaveAttribute('data-status', 'ATTENDING')
    await expect(page.getByTestId(`period-row-${STUDENT_USER_ID_2}`)).toHaveAttribute('data-status', 'ATTENDING')
    await expect(page.getByTestId(`period-row-${STUDENT_USER_ID_3}`)).toHaveAttribute('data-status', 'ATTENDING')

    // 送信ボタンをクリック
    await page.getByTestId('period-attendance-submit').click()

    // POST が呼ばれ、captured.lastBody に entries が含まれることを確認
    await expect.poll(() => captured.lastBody, { timeout: 10_000 }).not.toBeNull()
    const body = captured.lastBody as {
      attendanceDate: string
      entries: { studentUserId: number; status: string }[]
    }
    expect(body.entries).toHaveLength(3)
    expect(body.entries.every((e) => e.status === 'ATTENDING')).toBe(true)
    expect(body.attendanceDate).toBeTruthy()
  })

  test('SCHOOL-PERIOD-003: 1名を ABSENT → summary の absent=1 を確認', async ({ page }) => {
    const candidates = buildStandardCandidates()
    await mockGetPeriodCandidates(page, candidates)
    await mockSubmitPeriodAttendance(
      page,
      buildPeriodAttendanceSummary({
        total: 3,
        attending: 2,
        absent: 1,
      }),
    )

    await page.goto(PERIOD_ATTENDANCE_URL)
    await waitForHydration(page)

    // 候補者一覧描画待ち
    await expect(page.getByTestId(`period-row-${STUDENT_USER_ID_1}`)).toBeVisible({ timeout: 10_000 })

    // 山田太郎（101）を ABSENT に設定
    await page.getByTestId(`period-row-${STUDENT_USER_ID_1}-absent`).click()
    await expect(page.getByTestId(`period-row-${STUDENT_USER_ID_1}`)).toHaveAttribute('data-status', 'ABSENT')

    // 残り2名を ATTENDING に設定
    await page.getByTestId(`period-row-${STUDENT_USER_ID_2}-attending`).click()
    await page.getByTestId(`period-row-${STUDENT_USER_ID_3}-attending`).click()

    // 送信ボタンをクリック
    await page.getByTestId('period-attendance-submit').click()

    // サマリエリアが表示され、absent=1 が確認できる
    await expect(page.getByTestId('period-attendance-summary')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('period-attendance-summary-absent')).toContainText('1')
    await expect(page.getByTestId('period-attendance-summary-attending')).toContainText('2')
  })
})
