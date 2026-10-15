import { test, expect } from '@playwright/test'
import jaSchool from '../../../app/locales/ja/school.json' with { type: 'json' }
import {
  DEFAULT_TEAM_ID,
  STUDENT_USER_ID_1,
  loginAsTeacher,
  mockCatchAllApis,
  buildAtRiskStudentResponse,
  buildAttendanceRequirementEvaluation,
  mockGetAtRiskStudents,
  mockGetStudentEvaluations,
  mockDisclose,
  mockWithhold,
} from './_helpers'
import { waitForHydration } from '../helpers/wait'

/**
 * F03.13 Phase 16 開示判断フロー E2E テスト (SCHOOL-REQUIRE-001〜007)。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>SCHOOL-REQUIRE-001: リスク生徒一覧が正しく表示される</li>
 *   <li>SCHOOL-REQUIRE-002: 開示モーダル — WITH_NUMBERS + BOTH + メッセージで POST</li>
 *   <li>SCHOOL-REQUIRE-003: 開示モーダル — WITHOUT_NUMBERS モードで POST</li>
 *   <li>SCHOOL-REQUIRE-004: 開示モーダル — MEETING_REQUEST_ONLY モードで POST</li>
 *   <li>SCHOOL-REQUIRE-005: 非開示タブ — 理由が空のとき送信ボタンが disabled</li>
 *   <li>SCHOOL-REQUIRE-006: 非開示タブ — 理由入力後に送信 → withholdReason を検証</li>
 *   <li>SCHOOL-REQUIRE-007: キャンセルボタン → モーダルが閉じ、API コールなし</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F03.13_school_attendance_management.md §16</p>
 */

const REQUIREMENTS_PATH = `/teams/${DEFAULT_TEAM_ID}/school-attendance/requirements`
const DISCLOSURE = jaSchool.school.disclosure

/** リスク生徒一覧 + 生徒評価取得の共通モックをセットアップするヘルパ。 */
async function setupRequirementsPage(
  page: Parameters<typeof loginAsTeacher>[0],
  opts: {
    studentCount?: number
    captureDisclose?: { lastBody: unknown }
    captureWithhold?: { lastBody: unknown }
  } = {},
): Promise<void> {
  const studentCount = opts.studentCount ?? 1
  const students = Array.from({ length: studentCount }, (_, i) =>
    buildAtRiskStudentResponse({
      studentUserId: STUDENT_USER_ID_1 + i,
      status: 'RISK',
      currentAttendanceRate: 0.78,
      remainingAllowedAbsences: 3,
    }),
  )
  const evaluation = buildAttendanceRequirementEvaluation({
    id: 999,
    studentUserId: STUDENT_USER_ID_1,
    status: 'RISK',
  })

  await mockCatchAllApis(page)
  await mockGetAtRiskStudents(page, students)
  await mockGetStudentEvaluations(page, [evaluation])
  await mockDisclose(page, opts.captureDisclose)
  await mockWithhold(page, opts.captureWithhold)
}

// ---------------------------------------------------------------------------
// SCHOOL-REQUIRE-001: リスク生徒一覧が正しく表示される
// ---------------------------------------------------------------------------
test('SCHOOL-REQUIRE-001: リスク生徒一覧が正しく表示される', async ({ page }) => {
  await loginAsTeacher(page)

  const students = [
    buildAtRiskStudentResponse({ studentUserId: 101, status: 'RISK' }),
    buildAtRiskStudentResponse({ studentUserId: 102, status: 'WARNING' }),
  ]

  await mockCatchAllApis(page)
  await mockGetAtRiskStudents(page, students)
  await mockGetStudentEvaluations(page, [buildAttendanceRequirementEvaluation()])
  await mockDisclose(page)
  await mockWithhold(page)

  await page.goto(REQUIREMENTS_PATH)
  await waitForHydration(page)

  await expect(page.getByTestId('requirements-page')).toBeVisible()

  // at-risk-student-list の行が2行表示されることを確認
  const rows = page.getByTestId('at-risk-student-list').locator('tbody tr')
  await expect(rows).toHaveCount(2)
})

// ---------------------------------------------------------------------------
// SCHOOL-REQUIRE-002: 開示モーダル — WITH_NUMBERS + BOTH + メッセージで POST
// ---------------------------------------------------------------------------
test('SCHOOL-REQUIRE-002: 開示モーダル — WITH_NUMBERS + BOTH + メッセージで POST', async ({
  page,
}) => {
  const captured: { lastBody: unknown } = { lastBody: null }
  await loginAsTeacher(page)
  await setupRequirementsPage(page, { captureDisclose: captured })

  await page.goto(REQUIREMENTS_PATH)
  await waitForHydration(page)

  // 開示判断ボタンをクリック
  await page.getByTestId('disclosure-decide-btn').first().click()

  // モーダルが表示されることを確認
  await expect(page.getByTestId('disclosure-decision-modal')).toBeVisible()

  // WITH_NUMBERS はデフォルト値なので変更不要
  // BOTH はデフォルト値なので変更不要

  // メッセージを入力
  await page.getByTestId('disclosure-message-input').fill('出席状況についてご確認ください')

  // 送信
  await page.getByTestId('disclosure-submit-btn').click()

  // リクエストボディを検証
  const body = captured.lastBody as { mode: string; recipients: string; message: string }
  expect(body.mode).toBe('WITH_NUMBERS')
  expect(body.recipients).toBe('BOTH')
  expect(body.message).toBe('出席状況についてご確認ください')

  // モーダルが閉じることを確認
  await expect(page.getByTestId('disclosure-decision-modal')).not.toBeVisible()
})

// ---------------------------------------------------------------------------
// SCHOOL-REQUIRE-003: 開示モーダル — WITHOUT_NUMBERS モードで POST
// ---------------------------------------------------------------------------
test('SCHOOL-REQUIRE-003: 開示モーダル — WITHOUT_NUMBERS モードで POST', async ({ page }) => {
  const captured: { lastBody: unknown } = { lastBody: null }
  await loginAsTeacher(page)
  await setupRequirementsPage(page, { captureDisclose: captured })

  await page.goto(REQUIREMENTS_PATH)
  await waitForHydration(page)

  await page.getByTestId('disclosure-decide-btn').first().click()
  await expect(page.getByTestId('disclosure-decision-modal')).toBeVisible()

  // WITHOUT_NUMBERS を選択（SelectButton のボタンをラベルで操作）
  const modeLabel = DISCLOSURE.mode.WITHOUT_NUMBERS
  await page
    .getByTestId('disclosure-mode-selector')
    .getByRole('button', { name: modeLabel })
    .click()

  await page.getByTestId('disclosure-submit-btn').click()

  const body = captured.lastBody as { mode: string }
  expect(body.mode).toBe('WITHOUT_NUMBERS')
})

// ---------------------------------------------------------------------------
// SCHOOL-REQUIRE-004: 開示モーダル — MEETING_REQUEST_ONLY モードで POST
// ---------------------------------------------------------------------------
test('SCHOOL-REQUIRE-004: 開示モーダル — MEETING_REQUEST_ONLY モードで POST', async ({
  page,
}) => {
  const captured: { lastBody: unknown } = { lastBody: null }
  await loginAsTeacher(page)
  await setupRequirementsPage(page, { captureDisclose: captured })

  await page.goto(REQUIREMENTS_PATH)
  await waitForHydration(page)

  await page.getByTestId('disclosure-decide-btn').first().click()
  await expect(page.getByTestId('disclosure-decision-modal')).toBeVisible()

  // MEETING_REQUEST_ONLY を選択
  const modeLabel = DISCLOSURE.mode.MEETING_REQUEST_ONLY
  await page
    .getByTestId('disclosure-mode-selector')
    .getByRole('button', { name: modeLabel })
    .click()

  await page.getByTestId('disclosure-submit-btn').click()

  const body = captured.lastBody as { mode: string }
  expect(body.mode).toBe('MEETING_REQUEST_ONLY')
})

// ---------------------------------------------------------------------------
// SCHOOL-REQUIRE-005: 非開示タブ — 理由が空のとき送信ボタンが disabled
// ---------------------------------------------------------------------------
test('SCHOOL-REQUIRE-005: 非開示タブ — 理由が空のとき送信ボタンが disabled', async ({
  page,
}) => {
  await loginAsTeacher(page)
  await setupRequirementsPage(page)

  await page.goto(REQUIREMENTS_PATH)
  await waitForHydration(page)

  await page.getByTestId('disclosure-decide-btn').first().click()
  await expect(page.getByTestId('disclosure-decision-modal')).toBeVisible()

  // 非開示タブに切り替え
  const withholdTabLabel = DISCLOSURE.tabWithhold
  await page
    .getByTestId('disclosure-tab-selector')
    .getByRole('button', { name: withholdTabLabel })
    .click()

  // 理由が空の状態では withhold-submit-btn が disabled であることを確認
  await expect(page.getByTestId('withhold-submit-btn')).toBeDisabled()
})

// ---------------------------------------------------------------------------
// SCHOOL-REQUIRE-006: 非開示タブ — 理由入力後に送信 → withholdReason を検証
// ---------------------------------------------------------------------------
test('SCHOOL-REQUIRE-006: 非開示タブ — 理由入力後に送信 → withholdReason を検証', async ({
  page,
}) => {
  const captured: { lastBody: unknown } = { lastBody: null }
  await loginAsTeacher(page)
  await setupRequirementsPage(page, { captureWithhold: captured })

  await page.goto(REQUIREMENTS_PATH)
  await waitForHydration(page)

  await page.getByTestId('disclosure-decide-btn').first().click()
  await expect(page.getByTestId('disclosure-decision-modal')).toBeVisible()

  // 非開示タブに切り替え
  const withholdTabLabel = DISCLOSURE.tabWithhold
  await page
    .getByTestId('disclosure-tab-selector')
    .getByRole('button', { name: withholdTabLabel })
    .click()

  // 理由を入力
  await page.getByTestId('withhold-reason-input').fill('保護者面談の予定があるため')

  // 送信ボタンが有効化されることを確認してクリック
  await expect(page.getByTestId('withhold-submit-btn')).not.toBeDisabled()
  await page.getByTestId('withhold-submit-btn').click()

  // リクエストボディを検証
  const body = captured.lastBody as { withholdReason: string }
  expect(body.withholdReason).toBe('保護者面談の予定があるため')

  // モーダルが閉じることを確認
  await expect(page.getByTestId('disclosure-decision-modal')).not.toBeVisible()
})

// ---------------------------------------------------------------------------
// SCHOOL-REQUIRE-007: キャンセルボタン → モーダルが閉じ、API コールなし
// ---------------------------------------------------------------------------
test('SCHOOL-REQUIRE-007: キャンセルボタン → モーダルが閉じ、API コールなし', async ({
  page,
}) => {
  const capturedDisclose: { lastBody: unknown } = { lastBody: null }
  const capturedWithhold: { lastBody: unknown } = { lastBody: null }
  await loginAsTeacher(page)
  await setupRequirementsPage(page, {
    captureDisclose: capturedDisclose,
    captureWithhold: capturedWithhold,
  })

  await page.goto(REQUIREMENTS_PATH)
  await waitForHydration(page)

  // モーダルを開く
  await page.getByTestId('disclosure-decide-btn').first().click()
  await expect(page.getByTestId('disclosure-decision-modal')).toBeVisible()

  // キャンセルボタンをクリック
  await page.getByTestId('disclosure-cancel-btn').click()

  // モーダルが閉じることを確認
  await expect(page.getByTestId('disclosure-decision-modal')).not.toBeVisible()

  // APIが呼ばれていないことを確認（captured が null のまま）
  expect(capturedDisclose.lastBody).toBeNull()
  expect(capturedWithhold.lastBody).toBeNull()
})
