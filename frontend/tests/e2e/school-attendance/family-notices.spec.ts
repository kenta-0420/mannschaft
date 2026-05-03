import { test, expect } from '@playwright/test'
import JA from '../../../app/locales/ja/school.json' with { type: 'json' }
import { waitForHydration } from '../helpers/wait'
import {
  DEFAULT_TEAM_ID,
  loginAsTeacher,
  loginAsGuardian,
  mockCatchAllApis,
  buildFamilyAttendanceNoticeResponse,
  buildFamilyNoticeListResponse,
  mockGetFamilyNotices,
  mockSubmitFamilyNotice,
  mockAcknowledgeFamilyNotice,
} from './_helpers'

/**
 * F03.13 Phase 8 保護者連絡 E2E テスト群（SCHOOL-NOTICE-001〜003）。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>SCHOOL-NOTICE-001: 担任画面 — 保護者からの連絡一覧ロード（2件）</li>
 *   <li>SCHOOL-NOTICE-002: 担任が連絡を「確認済み」に → PATCH/POST ボディ検証</li>
 *   <li>SCHOOL-NOTICE-003: 保護者画面 — 欠席連絡フォーム送信 → POST ボディ検証</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F03.13_school_attendance.md §8</p>
 */

const NOTICES_URL = `/teams/${DEFAULT_TEAM_ID}/school-attendance/notices`
const FAMILY_NOTICES_URL = `/me/attendance/notices`

test.describe('SCHOOL-NOTICE-001〜003: F03.13 §8 保護者連絡', () => {
  // ---------------------------------------------------------------------------
  // SCHOOL-NOTICE-001: 担任画面 — 保護者からの連絡一覧ロード（2件）
  // ---------------------------------------------------------------------------
  test.describe('SCHOOL-NOTICE-001: 担任画面 — 連絡一覧ロード', () => {
    test.beforeEach(async ({ page }) => {
      await loginAsTeacher(page, { teamId: DEFAULT_TEAM_ID })
      await mockCatchAllApis(page)

      const records = [
        buildFamilyAttendanceNoticeResponse({ id: 1, studentUserId: 101, noticeType: 'ABSENCE', status: 'PENDING' }),
        buildFamilyAttendanceNoticeResponse({ id: 2, studentUserId: 102, noticeType: 'LATE', status: 'ACKNOWLEDGED' }),
      ]
      await mockGetFamilyNotices(page, buildFamilyNoticeListResponse({ records, totalCount: 2, unacknowledgedCount: 1 }))
    })

    test('SCHOOL-NOTICE-001: 担任画面に 2 件の保護者連絡が表示される', async ({ page }) => {
      await page.goto(NOTICES_URL)
      await waitForHydration(page)

      // ページタイトル確認
      await expect(page.getByTestId('notices-page-title')).toContainText(
        JA.school.familyNotice.title,
        { timeout: 10_000 },
      )

      // 連絡リスト表示確認
      await expect(page.getByTestId('teacher-notice-list')).toBeVisible({ timeout: 10_000 })

      // 2 件のアイテムが表示されること
      await expect(page.getByTestId('teacher-notice-item-1')).toBeVisible()
      await expect(page.getByTestId('teacher-notice-item-2')).toBeVisible()

      // ステータスバッジ確認（id=1 は PENDING、id=2 は ACKNOWLEDGED）
      await expect(page.getByTestId('teacher-notice-status-1')).toHaveAttribute('data-status', 'PENDING')
      await expect(page.getByTestId('teacher-notice-status-2')).toHaveAttribute('data-status', 'ACKNOWLEDGED')
    })
  })

  // ---------------------------------------------------------------------------
  // SCHOOL-NOTICE-002: 担任が連絡を「確認済み」に → PATCH/POST ボディ検証
  // ---------------------------------------------------------------------------
  test.describe('SCHOOL-NOTICE-002: 担任が連絡を確認済みにする', () => {
    test.beforeEach(async ({ page }) => {
      await loginAsTeacher(page, { teamId: DEFAULT_TEAM_ID })
      await mockCatchAllApis(page)

      const records = [
        buildFamilyAttendanceNoticeResponse({ id: 1, studentUserId: 101, noticeType: 'ABSENCE', status: 'PENDING' }),
      ]
      await mockGetFamilyNotices(page, buildFamilyNoticeListResponse({ records, totalCount: 1, unacknowledgedCount: 1 }))
    })

    test('SCHOOL-NOTICE-002: 確認ボタンをクリックし PATCH ボディが正しい', async ({ page }) => {
      const captured: { lastBody: unknown } = { lastBody: null }
      await mockAcknowledgeFamilyNotice(page, captured)

      await page.goto(NOTICES_URL)
      await waitForHydration(page)

      // アイテムが表示されるのを待つ
      await expect(page.getByTestId('teacher-notice-item-1')).toBeVisible({ timeout: 10_000 })

      // 確認ボタン（PENDING 状態のみ表示）をクリック
      await page.getByTestId('teacher-notice-acknowledge-1').click()

      // POST/PATCH が送られたことを verify
      await expect.poll(() => captured.lastBody, { timeout: 10_000 }).not.toBeNull()
    })
  })

  // ---------------------------------------------------------------------------
  // SCHOOL-NOTICE-003: 保護者画面 — 欠席連絡フォーム送信 → POST ボディ検証
  // ---------------------------------------------------------------------------
  test.describe('SCHOOL-NOTICE-003: 保護者が欠席連絡を送信する', () => {
    test.beforeEach(async ({ page }) => {
      await loginAsGuardian(page)
      await mockCatchAllApis(page)

      // 履歴ロード用モック（空で問題なし）
      await mockGetFamilyNotices(page, buildFamilyNoticeListResponse({ records: [], totalCount: 0, unacknowledgedCount: 0 }))
    })

    test('SCHOOL-NOTICE-003: 欠席連絡フォームを送信すると POST ボディが正しい', async ({ page }) => {
      const captured: { lastBody: unknown } = { lastBody: null }
      await mockSubmitFamilyNotice(page, buildFamilyAttendanceNoticeResponse({ id: 100, noticeType: 'ABSENCE', status: 'PENDING' }), captured)

      await page.goto(FAMILY_NOTICES_URL)
      await waitForHydration(page)

      // ページタイトル確認
      await expect(page.getByTestId('family-notices-page-title')).toContainText(
        JA.school.familyNotice.title,
        { timeout: 10_000 },
      )

      // フォームが表示されていること
      await expect(page.getByTestId('family-notice-form')).toBeVisible({ timeout: 10_000 })

      // 連絡種別ボタン群の中から「欠席」を確認（デフォルトが ABSENCE のため選択済みのはず）
      const typeContainer = page.getByTestId('family-notice-type')
      await expect(typeContainer).toBeVisible()

      // 送信ボタンをクリック
      await page.getByTestId('family-notice-submit').click()

      // POST が送られたことを verify
      await expect.poll(() => captured.lastBody, { timeout: 10_000 }).not.toBeNull()

      const body = captured.lastBody as {
        noticeType: string
        attendanceDate: string
      }
      // 欠席連絡として送信されること
      expect(body.noticeType).toBe('ABSENCE')
      // attendanceDate が本日の日付形式であること
      expect(body.attendanceDate).toMatch(/^\d{4}-\d{2}-\d{2}$/)

      // 送信成功後にフォームが非表示になり成功メッセージが表示される
      await expect(page.getByTestId('family-notice-success')).toBeVisible({ timeout: 10_000 })
    })
  })
})
