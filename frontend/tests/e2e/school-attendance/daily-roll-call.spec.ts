import { test, expect } from '@playwright/test'
import JA from '../../../app/locales/ja/school.json' with { type: 'json' }
import EN from '../../../app/locales/en/school.json' with { type: 'json' }
import { waitForHydration } from '../helpers/wait'
import {
  DEFAULT_TEAM_ID,
  DEFAULT_DATE,
  STUDENT_USER_ID_1,
  STUDENT_USER_ID_2,
  STUDENT_USER_ID_3,
  buildDailyAttendanceRecord,
  buildDailyRollCallSummary,
  loginAsTeacher,
  mockCatchAllApis,
  mockGetDailyAttendance,
  mockSubmitDailyRollCall,
} from './_helpers'

/**
 * F03.13 Phase 8 §SCHOOL-DAILY 日次点呼 E2E テスト群（SCHOOL-DAILY-001〜004）。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>SCHOOL-DAILY-001: 日次出欠一覧のロード（3名 UNDECIDED）</li>
 *   <li>SCHOOL-DAILY-002: 全員 ATTENDING で点呼確定 → summaryカウント検証</li>
 *   <li>SCHOOL-DAILY-003: 1名 ABSENT 登録 → POST ボディに absenceReason が含まれる</li>
 *   <li>SCHOOL-DAILY-004: i18n — en ロケールでタイトルが英語化される</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F03.13_school_attendance.md §Phase8-E2E</p>
 */

const DAILY_ROLL_CALL_URL = `/teams/${DEFAULT_TEAM_ID}/school-attendance/daily-roll-call`

const LABELS_JA = JA.school.attendance
const LABELS_EN = EN.school.attendance

/** 3 名構成の標準レコードセット（ID 101/102/103、全員 UNDECIDED）。 */
function buildStandardRecords() {
  return [
    buildDailyAttendanceRecord({
      id: 1,
      studentUserId: STUDENT_USER_ID_1,
      attendanceDate: DEFAULT_DATE,
      status: 'UNDECIDED',
    }),
    buildDailyAttendanceRecord({
      id: 2,
      studentUserId: STUDENT_USER_ID_2,
      attendanceDate: DEFAULT_DATE,
      status: 'UNDECIDED',
    }),
    buildDailyAttendanceRecord({
      id: 3,
      studentUserId: STUDENT_USER_ID_3,
      attendanceDate: DEFAULT_DATE,
      status: 'UNDECIDED',
    }),
  ]
}

test.describe('SCHOOL-DAILY-001〜004: F03.13 Phase 8 日次点呼', () => {
  test.beforeEach(async ({ page }) => {
    // 教師として認証注入し、catch-all で未モック API を 404 化する
    await loginAsTeacher(page, { teamId: DEFAULT_TEAM_ID })
    await mockCatchAllApis(page)
  })

  test('SCHOOL-DAILY-001: 日次出欠一覧のロード（3名 UNDECIDED）', async ({ page }) => {
    const records = buildStandardRecords()
    await mockGetDailyAttendance(page, records)
    // POST は呼ばれないが、念のために空サマリを登録しておく
    await mockSubmitDailyRollCall(page, buildDailyRollCallSummary())

    await page.goto(DAILY_ROLL_CALL_URL)
    await waitForHydration(page)

    // ページタイトルが日本語で表示される
    await expect(
      page.getByRole('heading', { name: LABELS_JA.dailyRollCall.title }),
    ).toBeVisible({ timeout: 10_000 })

    // 3 名の生徒行がレンダリングされる
    await expect(page.getByTestId('roll-call-row-101')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('roll-call-row-102')).toBeVisible()
    await expect(page.getByTestId('roll-call-row-103')).toBeVisible()

    // 全員 UNDECIDED ステータスで表示される
    await expect(page.getByTestId('roll-call-row-101')).toHaveAttribute(
      'data-status',
      'UNDECIDED',
    )
    await expect(page.getByTestId('roll-call-row-102')).toHaveAttribute(
      'data-status',
      'UNDECIDED',
    )
    await expect(page.getByTestId('roll-call-row-103')).toHaveAttribute(
      'data-status',
      'UNDECIDED',
    )
  })

  test('SCHOOL-DAILY-002: 全員 ATTENDING で点呼確定 → summaryカウント検証', async ({
    page,
  }) => {
    const records = buildStandardRecords()
    await mockGetDailyAttendance(page, records)
    const captured: { lastBody: unknown } = { lastBody: null }
    await mockSubmitDailyRollCall(
      page,
      buildDailyRollCallSummary({
        total: 3,
        attending: 3,
        partial: 0,
        absent: 0,
        undecided: 0,
      }),
      captured,
    )

    await page.goto(DAILY_ROLL_CALL_URL)
    await waitForHydration(page)

    // 生徒行の表示を待つ
    await expect(page.getByTestId('roll-call-row-101')).toBeVisible({ timeout: 10_000 })

    // 全員 ATTENDING ボタンをクリック
    await page.getByTestId('roll-call-row-101-attending').click()
    await page.getByTestId('roll-call-row-102-attending').click()
    await page.getByTestId('roll-call-row-103-attending').click()

    // ステータスが ATTENDING に変わる
    await expect(page.getByTestId('roll-call-row-101')).toHaveAttribute(
      'data-status',
      'ATTENDING',
    )
    await expect(page.getByTestId('roll-call-row-102')).toHaveAttribute(
      'data-status',
      'ATTENDING',
    )
    await expect(page.getByTestId('roll-call-row-103')).toHaveAttribute(
      'data-status',
      'ATTENDING',
    )

    // 送信ボタンをクリック
    await page.getByTestId('daily-roll-call-submit').click()

    // サマリエリアが表示される
    await expect(page.getByTestId('daily-roll-call-summary')).toBeVisible({ timeout: 10_000 })

    // サマリの各カウントが正しい
    await expect(page.getByTestId('daily-roll-call-summary-total')).toContainText('3')
    await expect(page.getByTestId('daily-roll-call-summary-attending')).toContainText('3')
    await expect(page.getByTestId('daily-roll-call-summary-absent')).toContainText('0')

    // POST 本体が正しく送られたことを検証
    await expect.poll(() => captured.lastBody, { timeout: 10_000 }).not.toBeNull()
    const body = captured.lastBody as {
      attendanceDate: string
      entries: { studentUserId: number; status: string }[]
    }
    expect(body.attendanceDate).toBe(DEFAULT_DATE)
    expect(body.entries).toHaveLength(3)
    expect(body.entries.every((e) => e.status === 'ATTENDING')).toBe(true)
  })

  test('SCHOOL-DAILY-003: 1名 ABSENT 登録 → POST ボディに absenceReason が含まれる', async ({
    page,
  }) => {
    const records = buildStandardRecords()
    await mockGetDailyAttendance(page, records)
    const captured: { lastBody: unknown } = { lastBody: null }
    await mockSubmitDailyRollCall(
      page,
      buildDailyRollCallSummary({
        total: 3,
        attending: 2,
        partial: 0,
        absent: 1,
        undecided: 0,
      }),
      captured,
    )

    await page.goto(DAILY_ROLL_CALL_URL)
    await waitForHydration(page)

    // 生徒行の表示を待つ
    await expect(page.getByTestId('roll-call-row-101')).toBeVisible({ timeout: 10_000 })

    // 1 名 ABSENT に設定（101番の生徒）
    await page.getByTestId('roll-call-row-101-absent').click()
    await expect(page.getByTestId('roll-call-row-101')).toHaveAttribute('data-status', 'ABSENT')

    // 他 2 名は ATTENDING に設定
    await page.getByTestId('roll-call-row-102-attending').click()
    await page.getByTestId('roll-call-row-103-attending').click()

    // ABSENT 行に欠席理由セレクトが表示されることを確認
    const reasonSelect = page
      .getByTestId('roll-call-row-101')
      .locator('select, [data-pc-name="select"], .p-select')
      .first()
    await expect(reasonSelect).toBeVisible({ timeout: 5_000 })

    // 送信ボタンをクリック
    await page.getByTestId('daily-roll-call-submit').click()

    // サマリに absent=1 が反映される
    await expect(page.getByTestId('daily-roll-call-summary')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('daily-roll-call-summary-absent')).toContainText('1')
    await expect(page.getByTestId('daily-roll-call-summary-attending')).toContainText('2')

    // POST ボディを確認
    await expect.poll(() => captured.lastBody, { timeout: 10_000 }).not.toBeNull()
    const body = captured.lastBody as {
      attendanceDate: string
      entries: { studentUserId: number; status: string; absenceReason?: string }[]
    }
    const absentEntry = body.entries.find((e) => e.studentUserId === STUDENT_USER_ID_1)
    expect(absentEntry).toBeDefined()
    expect(absentEntry?.status).toBe('ABSENT')
    // absenceReason フィールドが含まれる（undefined または文字列）
    expect('absenceReason' in (absentEntry ?? {})).toBe(true)
  })

  test('SCHOOL-DAILY-004: i18n — en ロケールでタイトルが英語化される', async ({ page }) => {
    const records = buildStandardRecords()
    await mockGetDailyAttendance(page, records)
    await mockSubmitDailyRollCall(page, buildDailyRollCallSummary())

    await page.goto(DAILY_ROLL_CALL_URL)
    await waitForHydration(page)

    // 生徒行の表示を待つ
    await expect(page.getByTestId('roll-call-row-101')).toBeVisible({ timeout: 10_000 })

    // Vue App の $i18n.setLocale を呼んで en ロケールへ切替
    await page.waitForFunction(() => {
      const el = document.querySelector('#__nuxt') as
        | (HTMLElement & { __vue_app__?: unknown })
        | null
      if (!el || !('__vue_app__' in el)) return false
      const app = el.__vue_app__ as
        | { config?: { globalProperties?: { $i18n?: unknown } } }
        | undefined
      return Boolean(app?.config?.globalProperties?.$i18n)
    })
    await page.evaluate(async (locale) => {
      const el = document.querySelector('#__nuxt') as
        | (HTMLElement & { __vue_app__?: unknown })
        | null
      if (!el) throw new Error('Nuxt root element not found')
      const app = el.__vue_app__ as
        | {
            config: {
              globalProperties: {
                $i18n: { setLocale?: (l: string) => Promise<void>; locale: { value: string } }
              }
            }
          }
        | undefined
      if (!app) throw new Error('Vue app instance not found on #__nuxt')
      const i18n = app.config.globalProperties.$i18n
      if (typeof i18n.setLocale === 'function') {
        await i18n.setLocale(locale)
      } else {
        i18n.locale.value = locale
      }
    }, 'en')

    // 英語ロケールでタイトルが英語になる
    await expect(
      page.getByRole('heading', { name: LABELS_EN.dailyRollCall.title }),
    ).toBeVisible({ timeout: 10_000 })

    // 送信ボタンのラベルも英語になる
    await expect(page.getByTestId('daily-roll-call-submit')).toContainText(
      LABELS_EN.dailyRollCall.submit,
    )

    // ATTENDING ボタンのラベルも英語になる（"Present"）
    await expect(page.getByTestId('roll-call-row-101-attending')).toContainText(
      LABELS_EN.status.ATTENDING,
    )

    // ABSENT ボタンのラベルも英語になる（"Absent"）
    await expect(page.getByTestId('roll-call-row-101-absent')).toContainText(
      LABELS_EN.status.ABSENT,
    )
  })
})
