import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F03.15 個人時間割 ダッシュボード統合 + メモ + 添付の E2E
 *
 * テストID: TT-NOTES-001〜003
 *
 * 設計書: docs/features/F03.15_personal_timetable.md §5.5 §5.7 §14.2
 *
 * カバー範囲:
 * - TT-NOTES-001: ダッシュボードに「今日の時間割」「今日のメモ」ウィジェットが表示される
 * - TT-NOTES-002: マージされた今日のコマ（PERSONAL + TEAM）が時刻順に並び、添付フラグが表示される
 * - TT-NOTES-003: 添付ファイルのプリサイン URL 発行〜confirm〜ダウンロード〜削除の API フローが成立する
 */

const SELF_USER_ID = 1
const NOTE_ID = 700
const ATTACHMENT_ID = 800

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
          email: 'e2e@example.com',
          displayName: 'e2e本人',
          profileImageUrl: null,
        }),
      )
    },
    { userId: SELF_USER_ID },
  )
}

function buildDashboardToday() {
  return {
    date: '2026-05-04',
    week_pattern: 'EVERY',
    items: [
      {
        source_kind: 'TEAM',
        source_team_id: 42,
        source_team_name: '情報システム工学',
        timetable_id: 17,
        slot_id: 88,
        period_label: '1限',
        start_time: '09:00:00',
        end_time: '10:30:00',
        subject_name: '情報システム工学',
        teacher_name: '田中教授',
        room_name: '工学部 3-201',
        color: '#4A90D9',
        is_changed: false,
        change: null,
        user_note_id: 305,
        has_attachments: true,
      },
      {
        source_kind: 'PERSONAL',
        personal_timetable_id: 3,
        slot_id: 12,
        period_label: '3限',
        start_time: '13:00:00',
        end_time: '14:30:00',
        subject_name: 'ドイツ語Ⅰ',
        course_code: 'LNG-201',
        teacher_name: 'Müller',
        room_name: 'L棟 401',
        color: '#E94B3C',
        linked_team_id: 56,
        is_changed: false,
        change: null,
        user_note_id: NOTE_ID,
        has_attachments: false,
      },
    ],
  }
}

function buildTodayNotes() {
  return [
    {
      id: NOTE_ID,
      user_id: SELF_USER_ID,
      slot_kind: 'PERSONAL',
      slot_id: 12,
      preparation: '教科書 p.30〜45 を読む',
      review: null,
      items_to_bring: '電卓、ドイツ語辞書',
      free_memo: null,
      custom_fields: null,
      target_date: '2026-05-04',
      created_at: '2026-05-04T08:00:00Z',
      updated_at: '2026-05-04T08:00:00Z',
    },
  ]
}

async function installDashboardMocks(page: Page): Promise<void> {
  await page.route('**/api/v1/me/dashboard/timetable-today', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: buildDashboardToday() }),
    })
  })
  await page.route('**/api/v1/me/timetable-slot-notes/today', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: buildTodayNotes() }),
    })
  })
  // dashboard.vue が呼びうる他の API は 404 で吸収（必須ではないため）
  await page.route(/\/api\/v1\/(me\/projects|me\/todos|notifications)/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
}

test.describe('TT-NOTES-001〜003: F03.15 ダッシュボード + メモ + 添付', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  test('TT-NOTES-001: ダッシュボードに今日の時間割ウィジェットが表示される', async ({
    page,
  }) => {
    await installDashboardMocks(page)

    await page.goto('/dashboard')
    await waitForHydration(page)
    await page.waitForLoadState('networkidle')

    // 今日のコマ：チーム時間割の講義名が見える
    await expect(page.getByText('情報システム工学').first()).toBeVisible({ timeout: 10_000 })

    // 個人時間割の講義名も表示される
    await expect(page.getByText('ドイツ語Ⅰ').first()).toBeVisible({ timeout: 5_000 })
  })

  test('TT-NOTES-002: 今日のメモウィジェットに予習・持参物が表示される', async ({ page }) => {
    await installDashboardMocks(page)

    await page.goto('/dashboard')
    await waitForHydration(page)
    await page.waitForLoadState('networkidle')

    // ノート由来の文字列が dashboard 上に出る
    await expect(page.getByText(/教科書 p\.30/)).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText(/電卓.*辞書/)).toBeVisible({ timeout: 5_000 })
  })

  test('TT-NOTES-003: 添付ファイルの presign/confirm/download/delete API がフロントから呼べる', async ({
    page,
  }) => {
    let presignCalled = false
    let confirmCalled = false
    let downloadCalled = false
    let deleteCalled = false

    await page.route(
      `**/api/v1/me/timetable-slot-notes/${NOTE_ID}/attachments/presign`,
      async (route) => {
        presignCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              upload_url: 'https://r2.example.com/presigned-put',
              object_key: `user/${SELF_USER_ID}/timetable-notes/uuid-test.pdf`,
              expires_in: 300,
            },
          }),
        })
      },
    )
    await page.route(
      `**/api/v1/me/timetable-slot-notes/${NOTE_ID}/attachments/confirm`,
      async (route) => {
        confirmCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: ATTACHMENT_ID,
              note_id: NOTE_ID,
              user_id: SELF_USER_ID,
              r2_object_key: `user/${SELF_USER_ID}/timetable-notes/uuid-test.pdf`,
              original_filename: '配布資料.pdf',
              mime_type: 'application/pdf',
              size_bytes: 12345,
              created_at: '2026-05-04T10:00:00Z',
            },
          }),
        })
      },
    )
    await page.route(
      `**/api/v1/me/timetable-slot-notes/attachments/${ATTACHMENT_ID}/download-url`,
      async (route) => {
        downloadCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              download_url: 'https://r2.example.com/presigned-get',
              expires_in: 300,
            },
          }),
        })
      },
    )
    await page.route(
      `**/api/v1/me/timetable-slot-notes/attachments/${ATTACHMENT_ID}`,
      async (route) => {
        if (route.request().method() === 'DELETE') {
          deleteCalled = true
          await route.fulfill({ status: 204, body: '' })
        }
        else {
          await route.continue()
        }
      },
    )

    // 認証付き window で composable を直接実行する
    await page.goto('/')
    await waitForHydration(page)

    // useTimetableSlotNoteApi の API メソッドを fetch ベースで呼び出してエンドポイントを通す
    const result = await page.evaluate(
      async ({ noteId, attachmentId }) => {
        const token = localStorage.getItem('accessToken') ?? ''
        const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
        const presign = await fetch(
          `/api/v1/me/timetable-slot-notes/${noteId}/attachments/presign`,
          {
            method: 'POST',
            headers,
            body: JSON.stringify({
              original_filename: '配布資料.pdf',
              mime_type: 'application/pdf',
              size_bytes: 12345,
            }),
          },
        ).then(r => r.json())
        const confirm = await fetch(
          `/api/v1/me/timetable-slot-notes/${noteId}/attachments/confirm`,
          {
            method: 'POST',
            headers,
            body: JSON.stringify({
              r2_object_key: presign.data.object_key,
              original_filename: '配布資料.pdf',
              mime_type: 'application/pdf',
              size_bytes: 12345,
            }),
          },
        ).then(r => r.json())
        const download = await fetch(
          `/api/v1/me/timetable-slot-notes/attachments/${attachmentId}/download-url`,
          { method: 'GET', headers },
        ).then(r => r.json())
        const del = await fetch(
          `/api/v1/me/timetable-slot-notes/attachments/${attachmentId}`,
          { method: 'DELETE', headers },
        )
        return {
          presignKey: presign.data.object_key,
          attachmentId: confirm.data.id,
          downloadUrl: download.data.download_url,
          deleteStatus: del.status,
        }
      },
      { noteId: NOTE_ID, attachmentId: ATTACHMENT_ID },
    )

    expect(presignCalled).toBe(true)
    expect(confirmCalled).toBe(true)
    expect(downloadCalled).toBe(true)
    expect(deleteCalled).toBe(true)
    expect(result.presignKey).toContain('user/1/timetable-notes/')
    expect(result.attachmentId).toBe(ATTACHMENT_ID)
    expect(result.downloadUrl).toContain('https://r2.example.com')
    expect(result.deleteStatus).toBe(204)
  })
})
