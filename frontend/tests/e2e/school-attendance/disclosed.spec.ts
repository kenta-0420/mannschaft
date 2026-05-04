import { test, expect } from '@playwright/test'
import {
  DEFAULT_TEAM_ID,
  loginAsStudent,
  mockCatchAllApis,
  buildDisclosedEvaluationResponse,
  mockGetMyDisclosedEvaluations,
} from './_helpers'
import { waitForHydration } from '../helpers/wait'

/**
 * F03.13 Phase 16 生徒・保護者インボックス E2E テスト (SCHOOL-DISCLOSE-001〜004)。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>SCHOOL-DISCLOSE-001: ページが表示され、開示済み評価カードが並ぶ</li>
 *   <li>SCHOOL-DISCLOSE-002: WITH_NUMBERS — 出席率と残余日数が表示される</li>
 *   <li>SCHOOL-DISCLOSE-003: WITHOUT_NUMBERS — 数値が非表示</li>
 *   <li>SCHOOL-DISCLOSE-004: 開示ゼロ時のエンプティステートが表示される</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F03.13_school_attendance_management.md §16</p>
 */

const DISCLOSED_PATH = `/teams/${DEFAULT_TEAM_ID}/school-attendance/disclosed`

// ---------------------------------------------------------------------------
// SCHOOL-DISCLOSE-001: ページが表示され、開示済み評価カードが並ぶ
// ---------------------------------------------------------------------------
test('SCHOOL-DISCLOSE-001: ページが表示され、開示済み評価カードが並ぶ', async ({ page }) => {
  await loginAsStudent(page)

  const evaluations = [
    buildDisclosedEvaluationResponse({
      evaluationId: 1001,
      ruleName: '進級要件（2026年度）',
      mode: 'WITH_NUMBERS',
    }),
    buildDisclosedEvaluationResponse({
      evaluationId: 1002,
      ruleName: '出席要件（前期）',
      mode: 'WITH_NUMBERS',
    }),
  ]

  await mockCatchAllApis(page)
  await mockGetMyDisclosedEvaluations(page, evaluations)

  await page.goto(DISCLOSED_PATH)
  await waitForHydration(page)

  // ページが表示されることを確認
  await expect(page.getByTestId('disclosed-inbox-page')).toBeVisible()

  // 開示カードが2枚表示されることを確認
  const items = page.getByTestId('disclosed-inbox-item')
  await expect(items).toHaveCount(2)
})

// ---------------------------------------------------------------------------
// SCHOOL-DISCLOSE-002: WITH_NUMBERS — 出席率と残余日数が表示される
// ---------------------------------------------------------------------------
test('SCHOOL-DISCLOSE-002: WITH_NUMBERS — 出席率と残余日数が表示される', async ({ page }) => {
  await loginAsStudent(page)

  const evaluations = [
    buildDisclosedEvaluationResponse({
      evaluationId: 1001,
      mode: 'WITH_NUMBERS',
      currentRate: 0.82,
      remainingAllowedDays: 5,
    }),
  ]

  await mockCatchAllApis(page)
  await mockGetMyDisclosedEvaluations(page, evaluations)

  await page.goto(DISCLOSED_PATH)
  await waitForHydration(page)

  const item = page.getByTestId('disclosed-inbox-item').first()
  await expect(item).toBeVisible()

  // 出席率「82.0%」相当のテキストが表示されることを確認
  // formatRate(0.82) => "82.0%"
  await expect(item).toContainText('82.0%')

  // 残余日数「5」が含まれていることを確認
  await expect(item).toContainText('5')
})

// ---------------------------------------------------------------------------
// SCHOOL-DISCLOSE-003: WITHOUT_NUMBERS — 数値が非表示
// ---------------------------------------------------------------------------
test('SCHOOL-DISCLOSE-003: WITHOUT_NUMBERS — 数値が非表示', async ({ page }) => {
  await loginAsStudent(page)

  const evaluations = [
    buildDisclosedEvaluationResponse({
      evaluationId: 1001,
      mode: 'WITHOUT_NUMBERS',
      currentRate: undefined,
      remainingAllowedDays: undefined,
    }),
  ]

  await mockCatchAllApis(page)
  await mockGetMyDisclosedEvaluations(page, evaluations)

  await page.goto(DISCLOSED_PATH)
  await waitForHydration(page)

  const item = page.getByTestId('disclosed-inbox-item').first()
  await expect(item).toBeVisible()

  // 出席率の数値（%表示）が表示されていないことを確認
  // WITHOUT_NUMBERS の場合 currentRate が undefined なので formatRate が呼ばれない
  await expect(item).not.toContainText('82.0%')

  // 残余日数の数値も表示されていないことを確認
  // WITHOUT_NUMBERS の場合 remainingAllowedDays が undefined なので日数ブロックが非表示
  // 代わりに「担任へご相談ください」系テキストが表示される
  await expect(item).not.toContainText('日の余裕があります')
})

// ---------------------------------------------------------------------------
// SCHOOL-DISCLOSE-004: 開示ゼロ時のエンプティステートが表示される
// ---------------------------------------------------------------------------
test('SCHOOL-DISCLOSE-004: 開示ゼロ時のエンプティステートが表示される', async ({ page }) => {
  await loginAsStudent(page)

  await mockCatchAllApis(page)
  await mockGetMyDisclosedEvaluations(page, [])

  await page.goto(DISCLOSED_PATH)
  await waitForHydration(page)

  await expect(page.getByTestId('disclosed-inbox-page')).toBeVisible()

  // エンプティステート要素が表示されることを確認
  await expect(page.getByTestId('disclosed-inbox-empty')).toBeVisible()

  // インボックスリストが存在しないことを確認
  await expect(page.getByTestId('disclosed-inbox-list')).not.toBeVisible()
})
