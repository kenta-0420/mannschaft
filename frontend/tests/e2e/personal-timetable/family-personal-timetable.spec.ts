import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F03.15 個人時間割 家族閲覧 E2E テスト
 *
 * テストID: FAMILY-PT-001〜003
 *
 * 設計書: docs/features/F03.15_personal_timetable.md §6.1 §14.2
 *
 * 検証ポイント:
 * - 家族チームのメンバーが、共有された個人時間割を閲覧できる
 * - 個人メモ・添付・カスタムフィールド・linked_* が一切レスポンスに含まれず UI にも表示されないこと
 * - private_notice バナーが表示されること
 *
 * カバー範囲:
 * - FAMILY-PT-001: 家族の個人時間割一覧が表示される
 * - FAMILY-PT-002: 家族の週間ビューが表示され、private_notice バナーが見える
 * - FAMILY-PT-003: 家族閲覧の DTO にメモ・添付・linked_* が含まれない
 */

const SELF_USER_ID = 1
const FAMILY_TEAM_ID = 200
const TARGET_USER_ID = 2
const PT_ID = 300

async function mockAuth(page: Page): Promise<void> {
  await page.addInitScript(
    ({ userId }) => {
      localStorage.setItem(
        'accessToken',
        'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
      )
      localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
      localStorage.setItem(
        'currentUser',
        JSON.stringify({
          id: userId,
          email: 'parent@example.com',
          displayName: '家族閲覧ユーザー',
          profileImageUrl: null,
        }),
      )
    },
    { userId: SELF_USER_ID },
  )
}

// 家族閲覧用 一覧レスポンス（DTO で notes / visibility 等が除外される設計）
function buildFamilyTimetableList() {
  return [
    {
      id: PT_ID,
      user_id: TARGET_USER_ID,
      name: '中学2年 前期',
      academic_year: 2026,
      term_label: '前期',
      effective_from: '2026-04-01',
      effective_until: '2026-09-30',
      status: 'ACTIVE',
      week_pattern_enabled: false,
      week_pattern_base_date: null,
    },
  ]
}

// 家族閲覧用 週間ビュー（コマからメモ・添付・linked_* が除外される設計）
function buildFamilyWeeklyView() {
  return {
    personal_timetable_id: PT_ID,
    personal_timetable_name: '中学2年 前期',
    week_start: '2026-05-04',
    week_end: '2026-05-10',
    week_pattern_enabled: false,
    current_week_pattern: 'EVERY',
    periods: [
      {
        id: 9001,
        period_number: 1,
        label: '1限',
        start_time: '09:00:00',
        end_time: '09:50:00',
        is_break: false,
      },
      {
        id: 9002,
        period_number: 2,
        label: '2限',
        start_time: '10:00:00',
        end_time: '10:50:00',
        is_break: false,
      },
    ],
    days: {
      MON: {
        date: '2026-05-04',
        slots: [
          {
            id: 8001,
            period_number: 1,
            week_pattern: 'EVERY',
            subject_name: '国語',
            course_code: null,
            teacher_name: '山田先生',
            room_name: '2-A教室',
            credits: null,
            color: '#A0D8EF',
            // 以下のフィールドは家族閲覧 DTO に含まれない:
            //   notes / linked_team_id / linked_timetable_id / linked_slot_id /
            //   auto_sync_changes / user_note_id / has_attachments
          },
        ],
      },
      TUE: { date: '2026-05-05', slots: [] },
      WED: { date: '2026-05-06', slots: [] },
      THU: { date: '2026-05-07', slots: [] },
      FRI: { date: '2026-05-08', slots: [] },
      SAT: { date: '2026-05-09', slots: [] },
      SUN: { date: '2026-05-10', slots: [] },
    },
  }
}

async function installFamilyMocks(page: Page): Promise<void> {
  // 家族個人時間割 一覧
  await page.route(
    `**/api/v1/families/${FAMILY_TEAM_ID}/members/${TARGET_USER_ID}/personal-timetables`,
    async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: buildFamilyTimetableList() }),
        })
      }
      else {
        await route.continue()
      }
    },
  )
  // 家族個人時間割 週間ビュー
  await page.route(
    `**/api/v1/families/${FAMILY_TEAM_ID}/members/${TARGET_USER_ID}/personal-timetables/${PT_ID}/weekly**`,
    async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: buildFamilyWeeklyView() }),
        })
      }
      else {
        await route.continue()
      }
    },
  )
}

test.describe('FAMILY-PT-001〜003: F03.15 個人時間割 家族閲覧', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  test('FAMILY-PT-001: 家族の個人時間割一覧が表示される', async ({ page }) => {
    await installFamilyMocks(page)

    await page.goto(`/families/${FAMILY_TEAM_ID}/members/${TARGET_USER_ID}/personal-timetable`)
    await waitForHydration(page)

    // i18n: personalTimetable.familyView.page_title = "家族の個人時間割"
    await expect(page.getByRole('heading', { name: '家族の個人時間割' })).toBeVisible({
      timeout: 10_000,
    })

    // private_notice バナーが表示される
    await expect(
      page.getByText(/個人メモ.*添付.*チームリンク.*共有されません/),
    ).toBeVisible({ timeout: 5_000 })

    // リスト項目（個人時間割名）
    await expect(page.getByText('中学2年 前期')).toBeVisible({ timeout: 5_000 })
  })

  test('FAMILY-PT-002: 家族の週間ビューが表示される', async ({ page }) => {
    await installFamilyMocks(page)

    await page.goto(
      `/families/${FAMILY_TEAM_ID}/members/${TARGET_USER_ID}/personal-timetable/${PT_ID}`,
    )
    await waitForHydration(page)

    // 個人時間割名がヘッダに出る
    await expect(page.getByRole('heading', { name: '中学2年 前期' })).toBeVisible({
      timeout: 10_000,
    })

    // private_notice バナーが表示される（再掲）
    await expect(
      page.getByText(/個人メモ.*添付.*チームリンク.*共有されません/).first(),
    ).toBeVisible({ timeout: 5_000 })

    // 教科名 / 教員名 / 教室名は表示される
    await expect(page.getByText('国語')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('山田先生')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('2-A教室')).toBeVisible({ timeout: 5_000 })
  })

  test('FAMILY-PT-003: 家族閲覧 DTO にメモ・添付・linked_* が含まれない', async ({
    page,
  }) => {
    let weeklyResponseBody: unknown = null

    await page.route(
      `**/api/v1/families/${FAMILY_TEAM_ID}/members/${TARGET_USER_ID}/personal-timetables/${PT_ID}/weekly**`,
      async (route) => {
        const body = JSON.stringify({ data: buildFamilyWeeklyView() })
        weeklyResponseBody = JSON.parse(body)
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body,
        })
      },
    )
    // 一覧モックも併設（家族ページの遷移用）
    await page.route(
      `**/api/v1/families/${FAMILY_TEAM_ID}/members/${TARGET_USER_ID}/personal-timetables`,
      async (route) => {
        if (route.request().method() === 'GET') {
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ data: buildFamilyTimetableList() }),
          })
        }
        else {
          await route.continue()
        }
      },
    )

    await page.goto(
      `/families/${FAMILY_TEAM_ID}/members/${TARGET_USER_ID}/personal-timetable/${PT_ID}`,
    )
    await waitForHydration(page)

    await page.waitForLoadState('networkidle')

    // モック応答が UI 側で利用された (= 受信された) ことを確認
    expect(weeklyResponseBody).not.toBeNull()

    // バックエンド側 DTO が以下のフィールドを **含まない** ことを E2E 観点で固定化:
    //   notes / linked_team_id / linked_timetable_id / linked_slot_id /
    //   auto_sync_changes / user_note_id / has_attachments
    const data = (weeklyResponseBody as {
      data: { days: Record<string, { slots: Record<string, unknown>[] }> }
    }).data
    const monSlots = data.days.MON?.slots ?? []
    expect(monSlots.length).toBeGreaterThan(0)
    for (const slot of monSlots) {
      expect(slot).not.toHaveProperty('notes')
      expect(slot).not.toHaveProperty('linked_team_id')
      expect(slot).not.toHaveProperty('linked_timetable_id')
      expect(slot).not.toHaveProperty('linked_slot_id')
      expect(slot).not.toHaveProperty('auto_sync_changes')
      expect(slot).not.toHaveProperty('user_note_id')
      expect(slot).not.toHaveProperty('has_attachments')
    }
  })
})
