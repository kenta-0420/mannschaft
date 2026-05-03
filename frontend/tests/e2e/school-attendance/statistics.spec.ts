import { test, expect } from '@playwright/test'
import JA from '../../../app/locales/ja/school.json' with { type: 'json' }
import { waitForHydration } from '../helpers/wait'
import {
  DEFAULT_TEAM_ID,
  loginAsTeacher,
  mockCatchAllApis,
  buildMonthlyStatisticsResponse,
  mockGetMonthlyStatistics,
  mockGetTermStatistics,
} from './_helpers'

/**
 * F03.13 Phase 8 出欠統計 E2E テスト群（SCHOOL-STATS-001〜003）。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>SCHOOL-STATS-001: 月次統計ページロード → 出席率・カウントが表示される</li>
 *   <li>SCHOOL-STATS-002: データなし → "noData" メッセージが表示される</li>
 *   <li>SCHOOL-STATS-003: CSVエクスポートボタンが存在し期間別タブで確認できる</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F03.13_school_attendance.md §8</p>
 */

const STATISTICS_URL = `/teams/${DEFAULT_TEAM_ID}/school-attendance/statistics`

test.describe('SCHOOL-STATS-001〜003: F03.13 §8 出欠統計', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsTeacher(page, { teamId: DEFAULT_TEAM_ID })
    await mockCatchAllApis(page)
    // 期間別統計はタブ切替時にロードされるため空で抑えておく
    await mockGetTermStatistics(page, null)
  })

  // ---------------------------------------------------------------------------
  // SCHOOL-STATS-001: 月次統計ページロード → 出席率・カウントが表示される
  // ---------------------------------------------------------------------------
  test('SCHOOL-STATS-001: 月次統計ページロード → 出席率・カウントが表示される', async ({ page }) => {
    const stats = buildMonthlyStatisticsResponse({
      year: 2026,
      month: 5,
      totalStudents: 30,
      presentCount: 27,
      absentCount: 2,
      undecidedCount: 1,
      attendanceRate: 90.0,
    })
    await mockGetMonthlyStatistics(page, stats)

    await page.goto(STATISTICS_URL)
    await waitForHydration(page)

    // ページ全体コンテナ確認
    await expect(page.getByTestId('statistics-page')).toBeVisible({ timeout: 10_000 })

    // 月次タブが選択状態（デフォルト）
    await expect(page.getByTestId('statistics-tab-monthly')).toBeVisible()
    await expect(page.getByTestId('statistics-tab-term')).toBeVisible()

    // チャートコンポーネント（MonthlyAttendanceStatsChart）が表示されること
    await expect(page.getByTestId('stats-chart')).toBeVisible({ timeout: 10_000 })

    // 出席数・欠席数が正しく表示されること
    await expect(page.getByTestId('stats-present-count')).toContainText('27')
    await expect(page.getByTestId('stats-absent-count')).toContainText('2')

    // 出席率が表示されること
    await expect(page.getByTestId('statistics-attendance-rate')).toContainText('90.0%')
  })

  // ---------------------------------------------------------------------------
  // SCHOOL-STATS-002: データなし → "noData" メッセージが表示される
  // ---------------------------------------------------------------------------
  test('SCHOOL-STATS-002: 月次統計データなし → noData メッセージが表示される', async ({ page }) => {
    // null を返すことでデータなし状態をシミュレート
    await mockGetMonthlyStatistics(page, null)

    await page.goto(STATISTICS_URL)
    await waitForHydration(page)

    // ページ全体コンテナ確認
    await expect(page.getByTestId('statistics-page')).toBeVisible({ timeout: 10_000 })

    // noData メッセージが表示されること
    await expect(page.getByTestId('statistics-no-data')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('statistics-no-data')).toContainText(
      JA.school.statistics.noData,
    )

    // チャートは表示されないこと
    await expect(page.getByTestId('stats-chart')).toHaveCount(0)
  })

  // ---------------------------------------------------------------------------
  // SCHOOL-STATS-003: CSVエクスポートボタンが期間別タブに存在する
  // ---------------------------------------------------------------------------
  test('SCHOOL-STATS-003: 期間別タブに CSV エクスポートボタンが存在する', async ({ page }) => {
    // 月次統計は noData でも問題ない
    await mockGetMonthlyStatistics(page, null)

    await page.goto(STATISTICS_URL)
    await waitForHydration(page)

    // ページ全体コンテナ確認
    await expect(page.getByTestId('statistics-page')).toBeVisible({ timeout: 10_000 })

    // 期間別タブに切り替え
    await page.getByTestId('statistics-tab-term').click()

    // CSV エクスポートボタンが表示されること
    await expect(page.getByTestId('statistics-export-csv')).toBeVisible({ timeout: 10_000 })

    // ボタンのラベルが正しいこと
    await expect(page.getByTestId('statistics-export-csv')).toContainText(
      JA.school.statistics.exportCsv,
    )
  })
})
