import { test, expect, type Route } from '@playwright/test'
import jaEvent from '../../../app/locales/ja/event.json' with { type: 'json' }
import enEvent from '../../../app/locales/en/event.json' with { type: 'json' }
import { waitForHydration } from '../helpers/wait'
import {
  DEFAULT_EVENT_ID,
  DEFAULT_TEAM_ID,
  buildDismissalStatusResponse,
  buildEvent,
  loginAsOrganizer,
  mockCatchAllApis,
  mockEventDetail,
  mockGetDismissalStatus,
  mockSubmitDismissal,
  getOfflineCareQueueItems,
} from './_helpers'

/**
 * F03.12 Phase 11 §16 解散通知 E2E。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>CARE-DISMISSAL-001: 主催者送信 — POST /dismissal が呼ばれ、送信後に解散済みバッジが表示される</li>
 *   <li>CARE-DISMISSAL-002: 解散済みバッジ — dismissalStatus.dismissed===true のとき解散ボタンが DOM から消える</li>
 *   <li>CARE-DISMISSAL-003: 二重送信防止 — 初期 status 未送信で BE が 409 を返したときエラー処理が走り Dialog が閉じない</li>
 *   <li>CARE-DISMISSAL-004: notifyGuardians OFF — フロント送信ペイロードの notifyGuardians=false を確認</li>
 *   <li>CARE-DISMISSAL-005: ダッシュボード Widget — 主催イベント解散リマインダー一覧 API のモックで Widget が描画される</li>
 *   <li>CARE-DISMISSAL-006: オフライン保存 — navigator.onLine=false のときに Dexie のケアキューに care:dismissal:{eventId} が積まれる</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F03.12_care_recipient_event_watch_notification.md §16</p>
 */

const TEAM_ID = DEFAULT_TEAM_ID
const EVENT_ID = DEFAULT_EVENT_ID
const EVENT_DETAIL_URL = `/teams/${TEAM_ID}/events/${EVENT_ID}`
const DASHBOARD_URL = '/dashboard'

const NOTIFY_GUARDIANS_LABEL_JA = jaEvent.event.dismissal.notify_guardians_toggle
// en 取り込みは将来 locale 切替で利用予定（未使用警告回避のため参照だけ保持）
void enEvent.event.dismissal.send_button

interface DismissalRequestBody {
  message?: string
  actualEndAt?: string
  notifyGuardians?: boolean
}

test.describe('CARE-DISMISSAL §16 解散通知', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsOrganizer(page, { teamId: TEAM_ID })
    await mockCatchAllApis(page)
  })

  test('CARE-DISMISSAL-001: 主催者がダイアログから解散通知を送信できる', async ({ page }) => {
    await mockEventDetail(page, buildEvent({ status: 'IN_PROGRESS' }))
    // 初期は未送信、送信後の status は dismissed=true で返す
    await mockGetDismissalStatus(page, buildDismissalStatusResponse({ dismissed: false }))

    const captured: { lastBody: unknown } = { lastBody: null }
    let postCalls = 0
    // ヘルパは内部で再度 mockGetDismissalStatus を上書きするので、最初に POST を仕込んでから
    // mockSubmitDismissal を呼ぶことで送信後の GET レスポンスを送信済み状態に切り替える
    await page.route('**/api/v1/teams/*/events/*/dismissal', async (route: Route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      postCalls += 1
      try {
        captured.lastBody = route.request().postDataJSON() as unknown
      } catch {
        captured.lastBody = null
      }
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: null }),
      })
    })
    // POST 後の status 再取得は dismissed=true
    await mockGetDismissalStatus(
      page,
      buildDismissalStatusResponse({
        dismissed: true,
        dismissalNotificationSentAt: '2026-04-28T09:00:00.000Z',
        dismissalNotifiedByUserId: 1,
      }),
    )

    await page.goto(EVENT_DETAIL_URL)
    await waitForHydration(page)

    // 解散ボタンが表示される（status 取得完了後）
    const dismissBtn = page.getByTestId('event-dismissal-button')
    await expect(dismissBtn).toBeVisible({ timeout: 10_000 })

    await dismissBtn.click()

    // ダイアログ送信
    const submitBtn = page.getByTestId('dismissal-submit')
    await expect(submitBtn).toBeVisible({ timeout: 5_000 })
    await submitBtn.click()

    // POST が 1 回呼ばれる
    await expect.poll(() => postCalls, { timeout: 10_000 }).toBe(1)
    const body = captured.lastBody as DismissalRequestBody
    expect(body.notifyGuardians).toBe(true)
    expect(typeof body.actualEndAt).toBe('string')

    // 送信後は解散済みバッジが表示される（status 再取得完了後）
    await expect(page.getByTestId('dismissal-status-badge')).toBeVisible({ timeout: 10_000 })
  })

  test('CARE-DISMISSAL-002: 既に解散済みなら解散ボタンが表示されない', async ({ page }) => {
    await mockEventDetail(page, buildEvent({ status: 'IN_PROGRESS' }))
    await mockGetDismissalStatus(
      page,
      buildDismissalStatusResponse({
        dismissed: true,
        dismissalNotificationSentAt: '2026-04-27T15:00:00.000Z',
        dismissalNotifiedByUserId: 1,
      }),
    )

    await page.goto(EVENT_DETAIL_URL)
    await waitForHydration(page)

    // 解散済みバッジは見えるが、解散ボタンは v-if で DOM ごと消える
    await expect(page.getByTestId('dismissal-status-badge')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('event-dismissal-button')).toHaveCount(0)
  })

  test('CARE-DISMISSAL-003: BE が 409 を返したらダイアログが閉じずエラーが残る', async ({ page }) => {
    await mockEventDetail(page, buildEvent({ status: 'IN_PROGRESS' }))
    // 初期 status を未送信にして、useDismissal.send のクライアント側ショートカット（dismissed===true）を回避し、
    // BE が 409 を返すケースを直接検証する
    await mockGetDismissalStatus(page, buildDismissalStatusResponse({ dismissed: false }))

    let postCalls = 0
    await page.route('**/api/v1/teams/*/events/*/dismissal', async (route: Route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      postCalls += 1
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          error: { code: 'DISMISSAL_ALREADY_SENT', message: 'already sent' },
        }),
      })
    })

    await page.goto(EVENT_DETAIL_URL)
    await waitForHydration(page)

    await page.getByTestId('event-dismissal-button').click()
    const submitBtn = page.getByTestId('dismissal-submit')
    await expect(submitBtn).toBeVisible({ timeout: 5_000 })
    await submitBtn.click()

    await expect.poll(() => postCalls, { timeout: 10_000 }).toBe(1)

    // 送信失敗時はダイアログを閉じない（DismissalDialog.onSubmit の error.value 分岐）
    await expect(submitBtn).toBeVisible({ timeout: 5_000 })
  })

  test('CARE-DISMISSAL-004: notifyGuardians OFF で送信したらペイロードが false になる', async ({ page }) => {
    await mockEventDetail(page, buildEvent({ status: 'IN_PROGRESS' }))
    const captured: { lastBody: unknown } = { lastBody: null }
    await mockSubmitDismissal(
      page,
      buildDismissalStatusResponse({
        dismissed: true,
        dismissalNotificationSentAt: '2026-04-28T09:00:00.000Z',
        dismissalNotifiedByUserId: 1,
      }),
      captured,
    )
    // mockSubmitDismissal は内部で mockGetDismissalStatus も登録するため、
    // 初期取得（未送信）を後勝ちで上書きしておく
    await page.route('**/api/v1/teams/*/events/*/dismissal/status', async (route: Route) => {
      if (route.request().method() !== 'GET') {
        await route.continue()
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: buildDismissalStatusResponse({ dismissed: false }),
        }),
      })
    })

    await page.goto(EVENT_DETAIL_URL)
    await waitForHydration(page)

    await page.getByTestId('event-dismissal-button').click()

    // notify_guardians トグルをオフにする（PrimeVue ToggleSwitch の input 経由）
    const toggleInput = page.locator('#dismissal-notify-guardians')
    await expect(toggleInput).toBeAttached({ timeout: 5_000 })
    // ラベルクリックで切り替える（PrimeVue ToggleSwitch のラッパは inputId を label に紐付ける）
    await page
      .getByLabel(NOTIFY_GUARDIANS_LABEL_JA)
      .click({ force: true })

    await page.getByTestId('dismissal-submit').click()

    await expect
      .poll(() => (captured.lastBody as DismissalRequestBody | null)?.notifyGuardians, {
        timeout: 10_000,
      })
      .toBe(false)
  })

  test('CARE-DISMISSAL-005: ダッシュボード Widget に主催者向けリマインダーが表示される', async ({ page }) => {
    // Widget は GET /api/v1/events/my-organizing/dismissal-reminders を叩いて targets を埋める想定
    // （足軽 K の BE 追加 + フロント追加が同梱された統合ブランチで描画される）
    await page.route(
      '**/api/v1/events/my-organizing/dismissal-reminders',
      async (route: Route) => {
        if (route.request().method() !== 'GET') {
          await route.continue()
          return
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              {
                teamId: TEAM_ID,
                eventId: EVENT_ID,
                eventName: '練習会 #100',
              },
            ],
          }),
        })
      },
    )

    await page.goto(DASHBOARD_URL)
    await waitForHydration(page)

    // Widget ルート（targets が描画されたときのみ存在）
    await expect(
      page.getByTestId('widget-event-dismissal-reminder'),
    ).toBeVisible({ timeout: 10_000 })
    // EventDismissalCard が 1 件描画される
    await expect(page.getByTestId('event-dismissal-card')).toHaveCount(1)
  })

  test('CARE-DISMISSAL-006: オフライン時は Dexie のケアキューに積まれる', async ({ page }) => {
    // 起動前に navigator.onLine を false に固定（useDismissal.isOnline が false 経路を通る）
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'onLine', {
        configurable: true,
        get: () => false,
      })
    })

    await mockEventDetail(page, buildEvent({ status: 'IN_PROGRESS' }))
    await mockGetDismissalStatus(page, buildDismissalStatusResponse({ dismissed: false }))

    // POST が呼ばれてしまったら検出するためカウンタを置く
    let postCalls = 0
    await page.route('**/api/v1/teams/*/events/*/dismissal', async (route: Route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      postCalls += 1
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'UNEXPECTED', message: 'should not be called' } }),
      })
    })

    await page.goto(EVENT_DETAIL_URL)
    await waitForHydration(page)

    await page.getByTestId('event-dismissal-button').click()
    await page.getByTestId('dismissal-submit').click()

    // Dexie に care:dismissal:{eventId} が積まれる
    await expect
      .poll(
        async () => {
          const items = await getOfflineCareQueueItems(page)
          return items.filter((i) => i.clientId === `care:dismissal:${EVENT_ID}`).length
        },
        { timeout: 10_000 },
      )
      .toBe(1)

    // オフラインなので POST は走らない
    expect(postCalls).toBe(0)
  })
})
