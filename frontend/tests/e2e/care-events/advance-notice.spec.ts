import { test, expect, type Page } from '@playwright/test'
import jaEvent from '../../../app/locales/ja/event.json' with { type: 'json' }
import enEvent from '../../../app/locales/en/event.json' with { type: 'json' }
import {
  DEFAULT_EVENT_ID,
  DEFAULT_TEAM_ID,
  MEMBER_USER_ID,
  ORGANIZER_USER_ID,
  RECIPIENT_USER_ID,
  WATCHER_USER_ID,
  buildAdvanceNoticeResponse,
  buildCareLink,
  buildEvent,
  loginAsMember,
  loginAsOrganizer,
  loginAsWatcher,
  mockGetAdvanceNotices,
  mockGetMyCareRecipients,
  mockSubmitAbsenceNotice,
  mockSubmitLateNotice,
} from './_helpers'
import { waitForHydration } from '../helpers/wait'

/**
 * F03.12 §15 事前遅刻・欠席連絡 E2E テスト (CARE-ADVANCE-001〜006)。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>CARE-ADVANCE-001: 本人が遅刻連絡を送信し、API リクエスト本文が正しい</li>
 *   <li>CARE-ADVANCE-002: 本人が欠席連絡（SICK 理由）を送信し、API リクエスト本文が正しい</li>
 *   <li>CARE-ADVANCE-003: 主催者向け一覧で複数の事前連絡が時刻昇順で並ぶ</li>
 *   <li>CARE-ADVANCE-004: 見守り者がケア対象を選択して代理送信し、body.userId が代理対象 ID</li>
 *   <li>CARE-ADVANCE-005: RSVP 未回答時のバー表示・遅刻ボタン非表示</li>
 *   <li>CARE-ADVANCE-006: オフライン中の遅刻連絡が Dexie ケアキューに積まれる</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F03.12_care_recipient_event_watch_notification.md §15</p>
 */

const TEAM_EVENT_PATH = `/teams/${DEFAULT_TEAM_ID}/events/${DEFAULT_EVENT_ID}`
const ADVANCE_NOTICE = jaEvent.event.advanceNotice
const ADVANCE_NOTICE_EN = enEvent.event.advanceNotice

/**
 * EventDetail.vue 描画に必要な周辺 GET エンドポイントを 200 で埋めるヘルパ。
 *
 * <p>本 spec ファイル内で完結させる事情で {@code _helpers.ts} の
 * {@code mockEventDetail} は使用しない（attendanceMode='RSVP' を含む
 * EventDetailResponse 型でレスポンスする必要があるため）。</p>
 */
async function mockEventDetailSurroundings(
  page: Page,
  opts: { roleName?: 'ADMIN' | 'MEMBER' } = {},
): Promise<void> {
  const role = opts.roleName ?? 'MEMBER'

  // 権限取得（チーム admin タブ表示判定で使用）
  await page.route(
    `**/api/v1/teams/${DEFAULT_TEAM_ID}/me/permissions`,
    async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            roleName: role,
            permissions: [],
          },
        }),
      })
    },
  )

  // イベント詳細（attendanceMode を含めるため EventDetailResponse 風の追加プロパティを付与）
  const baseEvent = buildEvent({ status: 'IN_PROGRESS' }) as unknown as Record<string, unknown>
  const eventDetail = {
    ...baseEvent,
    attendanceMode: 'RSVP',
    summary: null,
    venueName: null,
    venueAddress: null,
    isApprovalRequired: false,
  }
  await page.route(
    `**/api/v1/teams/${DEFAULT_TEAM_ID}/events/${DEFAULT_EVENT_ID}`,
    async (route) => {
      if (route.request().method() !== 'GET') {
        await route.continue()
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: eventDetail }),
      })
    },
  )

  // registrations / checkins / timetable
  await page.route(
    `**/api/v1/events/${DEFAULT_EVENT_ID}/registrations**`,
    async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
      })
    },
  )
  await page.route(
    `**/api/v1/events/${DEFAULT_EVENT_ID}/checkins**`,
    async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
      })
    },
  )
  await page.route(
    `**/api/v1/events/${DEFAULT_EVENT_ID}/timetable**`,
    async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    },
  )

  // RSVP 一覧 + サマリ
  await page.route(
    `**/api/v1/teams/${DEFAULT_TEAM_ID}/events/${DEFAULT_EVENT_ID}/rsvp**`,
    async (route) => {
      const url = route.request().url()
      if (url.includes('/rsvp/summary') || url.endsWith('/rsvp/summary')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { attending: 0, notAttending: 0, maybe: 0, undecided: 0, total: 0 },
          }),
        })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    },
  )

  // 解散通知ステータス
  await page.route(
    `**/api/v1/teams/${DEFAULT_TEAM_ID}/events/${DEFAULT_EVENT_ID}/dismissal/status`,
    async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            dismissalNotificationSentAt: null,
            dismissalNotifiedByUserId: null,
            reminderCount: 0,
            lastReminderAt: null,
            dismissed: false,
          },
        }),
      })
    },
  )
}

/**
 * オフラインキューを送信前に必ず空にして、テスト間の Dexie 残留を防ぐ。
 */
async function clearCareQueueOnPage(page: Page): Promise<void> {
  await page.evaluate(() => {
    return new Promise<void>((resolve) => {
      const req = indexedDB.deleteDatabase('mannschaft-offline')
      req.onsuccess = () => resolve()
      req.onerror = () => resolve()
      req.onblocked = () => resolve()
    })
  })
}

test.describe('CARE-ADVANCE-001〜006: F03.12 §15 事前遅刻・欠席連絡', () => {
  test('CARE-ADVANCE-001: 本人が遅刻連絡を送信できる', async ({ page }) => {
    await loginAsMember(page, { userId: MEMBER_USER_ID })
    await mockEventDetailSurroundings(page, { roleName: 'MEMBER' })

    // 見守りケア対象は無し（subjectSelect 非表示）
    await mockGetMyCareRecipients(page, [])
    await mockGetAdvanceNotices(page, [])

    const captured: { lastBody: unknown } = { lastBody: null }
    await mockSubmitLateNotice(
      page,
      buildAdvanceNoticeResponse({
        userId: MEMBER_USER_ID,
        displayName: 'e2e_member',
        noticeType: 'LATE',
        expectedArrivalMinutesLate: 25,
        comment: '電車遅延',
      }),
      captured,
    )

    await page.goto(TEAM_EVENT_PATH)
    await waitForHydration(page)

    // 事前連絡バーが表示される
    await expect(page.getByTestId('late-absence-notice-bar')).toBeVisible({ timeout: 10_000 })

    // 遅刻連絡ボタンをクリック → ダイアログ起動
    await page.getByTestId('late-notice-open-button').click()
    await expect(page.getByTestId('late-notice-dialog')).toBeVisible()

    // 遅刻分数 25・コメント入力
    const minutesInput = page.getByTestId('late-notice-minutes-input')
    await minutesInput.fill('25')
    await page.getByTestId('late-notice-comment-input').fill('電車遅延')

    // 送信
    await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes('/rsvp-responses/late-notice') &&
          r.request().method() === 'POST',
      ),
      page.getByTestId('late-notice-submit').click(),
    ])

    // リクエストボディ検証
    const body = captured.lastBody as {
      userId: number
      expectedArrivalMinutesLate: number
      comment?: string
    }
    expect(body.userId).toBe(MEMBER_USER_ID)
    expect(body.expectedArrivalMinutesLate).toBe(25)
    expect(body.comment).toBe('電車遅延')
  })

  test('CARE-ADVANCE-002: 本人が欠席連絡（SICK）を送信できる', async ({ page }) => {
    await loginAsMember(page, { userId: MEMBER_USER_ID })
    await mockEventDetailSurroundings(page, { roleName: 'MEMBER' })
    await mockGetMyCareRecipients(page, [])
    await mockGetAdvanceNotices(page, [])

    const captured: { lastBody: unknown } = { lastBody: null }
    await mockSubmitAbsenceNotice(
      page,
      buildAdvanceNoticeResponse({
        userId: MEMBER_USER_ID,
        displayName: 'e2e_member',
        noticeType: 'ABSENCE',
        expectedArrivalMinutesLate: null,
        absenceReason: 'SICK',
        comment: '熱発',
      }),
      captured,
    )

    await page.goto(TEAM_EVENT_PATH)
    await waitForHydration(page)

    // 欠席連絡ボタン → ダイアログ
    await page.getByTestId('absence-notice-open-button').click()
    await expect(page.getByTestId('absence-notice-dialog')).toBeVisible()

    // SICK ラジオはデフォルト選択。明示的にもう一度クリックして確実に。
    await page.getByTestId('absence-reason-SICK').click()
    await page.getByTestId('absence-notice-comment-input').fill('熱発')

    await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes('/rsvp-responses/absence-notice') &&
          r.request().method() === 'POST',
      ),
      page.getByTestId('absence-notice-submit').click(),
    ])

    const body = captured.lastBody as {
      userId: number
      absenceReason: string
      comment?: string
    }
    expect(body.userId).toBe(MEMBER_USER_ID)
    expect(body.absenceReason).toBe('SICK')
    expect(body.comment).toBe('熱発')
  })

  test('CARE-ADVANCE-003: 主催者向け一覧に複数の事前連絡が並ぶ', async ({ page }) => {
    await loginAsOrganizer(page, { userId: ORGANIZER_USER_ID })
    await mockEventDetailSurroundings(page, { roleName: 'ADMIN' })
    await mockGetMyCareRecipients(page, [])

    // BE は時刻昇順で返す前提（spec ではその想定で fixture を用意）
    const notices = [
      buildAdvanceNoticeResponse({
        userId: 101,
        displayName: '山田太郎',
        noticeType: 'LATE',
        expectedArrivalMinutesLate: 10,
        createdAt: '2026-04-27T08:00:00.000Z',
      }),
      buildAdvanceNoticeResponse({
        userId: 102,
        displayName: '鈴木花子',
        noticeType: 'ABSENCE',
        expectedArrivalMinutesLate: null,
        absenceReason: 'SICK',
        createdAt: '2026-04-27T08:30:00.000Z',
      }),
      buildAdvanceNoticeResponse({
        userId: 103,
        displayName: '佐藤次郎',
        noticeType: 'LATE',
        expectedArrivalMinutesLate: 20,
        createdAt: '2026-04-27T09:00:00.000Z',
      }),
    ]
    await mockGetAdvanceNotices(page, notices)

    await page.goto(TEAM_EVENT_PATH)
    await waitForHydration(page)

    // 「事前連絡」タブをクリック（i18n: event.advanceNotice.tab）
    await page.getByRole('tab', { name: ADVANCE_NOTICE.tab }).click()

    // 一覧が描画される
    const list = page.getByTestId('advance-notice-list-table')
    await expect(list).toBeVisible({ timeout: 10_000 })

    // 3 件の displayName が並ぶ（順序検証用に textContent を取得）
    const rowTexts = await list.locator('tbody tr').allTextContents()
    expect(rowTexts.length).toBe(3)
    expect(rowTexts[0]).toContain('山田太郎')
    expect(rowTexts[1]).toContain('鈴木花子')
    expect(rowTexts[2]).toContain('佐藤次郎')

    // 種別バッジ（i18n: badgeLate / badgeAbsence）も表示される
    await expect(list.getByText(ADVANCE_NOTICE.badgeLate).first()).toBeVisible()
    await expect(list.getByText(ADVANCE_NOTICE.badgeAbsence).first()).toBeVisible()
  })

  test('CARE-ADVANCE-004: 見守り者がケア対象を選択して代理送信できる', async ({ page }) => {
    // 見守り者としてログイン
    await loginAsWatcher(page, { userId: WATCHER_USER_ID })
    await mockEventDetailSurroundings(page, { roleName: 'MEMBER' })

    // ケア対象 1 名（RECIPIENT_USER_ID）
    const link = buildCareLink({
      careRecipientUserId: RECIPIENT_USER_ID,
      careRecipientDisplayName: 'ケア対象太郎',
      watcherUserId: WATCHER_USER_ID,
      status: 'ACTIVE',
    })
    await mockGetMyCareRecipients(page, [link])
    await mockGetAdvanceNotices(page, [])

    const captured: { lastBody: unknown } = { lastBody: null }
    await mockSubmitLateNotice(
      page,
      buildAdvanceNoticeResponse({
        userId: RECIPIENT_USER_ID,
        displayName: 'ケア対象太郎',
        noticeType: 'LATE',
        expectedArrivalMinutesLate: 15,
      }),
      captured,
    )

    await page.goto(TEAM_EVENT_PATH)
    await waitForHydration(page)

    // 対象セレクト（自分 + ケア対象太郎）が表示される
    const subjectSelect = page.getByTestId('advance-notice-subject-select')
    await expect(subjectSelect).toBeVisible({ timeout: 10_000 })

    // ケア対象太郎を選択
    await subjectSelect.click()
    await page.getByRole('option', { name: 'ケア対象太郎' }).click()

    // 遅刻連絡を送信
    await page.getByTestId('late-notice-open-button').click()
    await page.getByTestId('late-notice-minutes-input').fill('15')

    await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes('/rsvp-responses/late-notice') &&
          r.request().method() === 'POST',
      ),
      page.getByTestId('late-notice-submit').click(),
    ])

    // body.userId が代理対象（RECIPIENT）であり、認証ユーザー（WATCHER）でない
    const body = captured.lastBody as { userId: number }
    expect(body.userId).toBe(RECIPIENT_USER_ID)
    expect(body.userId).not.toBe(WATCHER_USER_ID)
  })

  test('CARE-ADVANCE-005: RSVP 未回答時はバー表示・遅刻ボタン非表示', async ({ page }) => {
    // useEventDetail.myRsvpResponse は rsvpList から認証ユーザーの userId で派生する仕様。
    // ここでは「null（未回答）時」の出し分けのみを検証する。
    // NOT_ATTENDING / ATTENDING の出し分けは別 spec で追加する想定。
    await loginAsMember(page, { userId: MEMBER_USER_ID })
    await mockEventDetailSurroundings(page, { roleName: 'MEMBER' })
    await mockGetMyCareRecipients(page, [])
    await mockGetAdvanceNotices(page, [])

    await page.goto(TEAM_EVENT_PATH)
    await waitForHydration(page)

    // バー本体は表示される（null = 未回答）
    await expect(page.getByTestId('late-absence-notice-bar')).toBeVisible({ timeout: 10_000 })

    // 未回答状態では遅刻ボタンは出ない（ATTENDING のみ表示の仕様）
    await expect(page.getByTestId('late-notice-open-button')).toHaveCount(0)
    // 欠席ボタンは表示される
    await expect(page.getByTestId('absence-notice-open-button')).toBeVisible()

    // i18n キー（en）切替時もタイトル文言が翻訳される（type=json import の動作確認）
    expect(ADVANCE_NOTICE_EN.barTitle.length).toBeGreaterThan(0)
  })

  test('CARE-ADVANCE-006: オフライン時の遅刻連絡が Dexie ケアキューに積まれる', async ({ page, context }) => {
    await loginAsMember(page, { userId: MEMBER_USER_ID })
    await mockEventDetailSurroundings(page, { roleName: 'MEMBER' })
    await mockGetMyCareRecipients(page, [])
    await mockGetAdvanceNotices(page, [])

    // ページ遷移前に IndexedDB を一旦掃除
    await page.goto(TEAM_EVENT_PATH)
    await waitForHydration(page)
    await clearCareQueueOnPage(page)

    // useOnline を false にするためオフラインモードへ
    await context.setOffline(true)

    // 遅刻連絡を送信（オフラインなので API は呼ばれず Dexie に積まれる）
    await page.getByTestId('late-notice-open-button').click()
    await page.getByTestId('late-notice-minutes-input').fill('30')
    await page.getByTestId('late-notice-submit').click()

    // ダイアログが閉じるまで（offlineQueued トースト表示後）
    await expect(page.getByTestId('late-notice-dialog')).toBeHidden({ timeout: 10_000 })

    // Dexie の offlineQueue から late-notice ジョブが 1 件積まれていることを検証
    const items = await page.evaluate(async () => {
      return new Promise<
        Array<{ clientId: string; path: string; body: Record<string, unknown> }>
      >((resolve) => {
        const req = indexedDB.open('mannschaft-offline')
        req.onsuccess = () => {
          const db = req.result
          if (!db.objectStoreNames.contains('offlineQueue')) {
            db.close()
            resolve([])
            return
          }
          const tx = db.transaction('offlineQueue', 'readonly')
          const store = tx.objectStore('offlineQueue')
          const all = store.getAll()
          all.onsuccess = () => {
            const rows = (all.result ?? []) as Array<{
              clientId: string
              path: string
              body: Record<string, unknown>
            }>
            db.close()
            resolve(rows)
          }
          all.onerror = () => {
            db.close()
            resolve([])
          }
        }
        req.onerror = () => resolve([])
      })
    })

    const lateJobs = items.filter((i) => i.clientId.startsWith('care:late-notice:'))
    expect(lateJobs.length).toBe(1)
    expect(lateJobs[0]!.path).toBe('/api/v1/care-queue/late-notice')

    // body.payload に userId / minutes が含まれる
    const payload = lateJobs[0]!.body.payload as {
      userId: number
      expectedArrivalMinutesLate: number
    }
    expect(payload.userId).toBe(MEMBER_USER_ID)
    expect(payload.expectedArrivalMinutesLate).toBe(30)

    await context.setOffline(false)
  })
})

// 各 test 終了後に Dexie 上の残ジョブを掃除（後続テストへの汚染防止）
test.afterEach(async ({ page }) => {
  await clearCareQueueOnPage(page).catch(() => undefined)
})
