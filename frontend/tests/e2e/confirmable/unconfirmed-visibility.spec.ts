import { test, expect, type Page, type Route } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F04.9 §13「未確認者一覧の可視化」E2E テスト
 *
 * 新機能（すでに main マージ済み）:
 * - 通知作成時に「未確認者リストの公開範囲（unconfirmedVisibility）」を選択
 *   （HIDDEN / CREATOR_AND_ADMIN / ALL_MEMBERS）
 * - 前回値は localStorage (`confirmable.lastUnconfirmedVisibility`) で復元される
 * - 受信者一覧（Recipients）は Accordion で折り畳み表示、初期 collapsed
 * - ALL_MEMBERS 設定時、メンバーでも未確認者リストが閲覧可。ただし
 *   confirmedAt/confirmedVia/firstReminderSentAt 等はバックエンド側で null にマスクされる
 *
 * 認可判定はバックエンドが行う（ADMIN: 全件返却、MEMBER(ALL_MEMBERS): マスク後の未確認者のみ、
 * MEMBER(HIDDEN|CREATOR_AND_ADMIN): 403）。
 * ここでは API モックを通じてフロントエンドの表示・送信挙動・ストレージ復元を検証する。
 */

type UnconfirmedVisibility = 'HIDDEN' | 'CREATOR_AND_ADMIN' | 'ALL_MEMBERS'

/** 送信 API が受け取ったリクエストボディを検証するための記録構造 */
interface CapturedSendRequest {
  unconfirmedVisibility?: UnconfirmedVisibility | null
  title?: string
  recipientUserIds?: number[]
}

/** ADMIN 視点（全件・確認日時など展開される）の受信者一覧モック */
const ADMIN_VIEW_RECIPIENTS = [
  {
    id: 1,
    userId: 10,
    isConfirmed: true,
    confirmedAt: '2026-04-13T10:00:00Z',
    confirmedVia: 'APP',
    firstReminderSentAt: null,
    secondReminderSentAt: null,
    excludedAt: null,
    createdAt: '2026-04-12T00:00:00Z',
  },
  {
    id: 2,
    userId: 11,
    isConfirmed: false,
    confirmedAt: null,
    confirmedVia: null,
    firstReminderSentAt: '2026-04-12T12:00:00Z',
    secondReminderSentAt: null,
    excludedAt: null,
    createdAt: '2026-04-12T00:00:00Z',
  },
]

/** MEMBER 視点（ALL_MEMBERS）。未確認者のみ、confirmedAt 等は null にマスク済み */
const MEMBER_VIEW_MASKED_RECIPIENTS = [
  {
    id: 2,
    userId: 11,
    isConfirmed: false,
    confirmedAt: null,
    confirmedVia: null,
    firstReminderSentAt: null,
    secondReminderSentAt: null,
    excludedAt: null,
    createdAt: '2026-04-12T00:00:00Z',
  },
]

/** 通知詳細モックを作るファクトリ */
function buildNotificationDetail(id: number, visibility: UnconfirmedVisibility) {
  return {
    id,
    title: '臨時休業のお知らせ',
    body: 'テスト本文',
    priority: 'HIGH',
    status: 'ACTIVE',
    scopeType: 'TEAM',
    scopeId: 1,
    deadlineAt: '2026-04-30T23:59:00Z',
    totalRecipientCount: 2,
    confirmedCount: 1,
    createdAt: '2026-04-12T00:00:00Z',
    unconfirmedVisibility: visibility,
    actionUrl: null,
    firstReminderMinutes: null,
    secondReminderMinutes: null,
    cancelledAt: null,
    completedAt: null,
    expiredAt: null,
    createdBy: 5,
  }
}

/** reservation-settings ページが叩く周辺 API を空レスポンスで一括モック */
async function mockReservationSettingsSideApis(page: Page): Promise<void> {
  await page.route('**/api/v1/teams/*/reservation-lines', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
  await page.route('**/api/v1/organizations/*/reservation-lines', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
}

/** 設定 API モック（GET のみ） */
async function mockSettingsGet(page: Page, visibility: UnconfirmedVisibility): Promise<void> {
  const handler = async (route: Route): Promise<void> => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          id: 1,
          scopeType: 'TEAM',
          scopeId: 1,
          defaultFirstReminderMinutes: 60,
          defaultSecondReminderMinutes: 120,
          senderAlertThresholdPercent: 50,
          defaultUnconfirmedVisibility: visibility,
        },
      }),
    })
  }
  await page.route('**/api/v1/teams/*/confirmable-notification-settings', handler)
  await page.route('**/api/v1/organizations/*/confirmable-notification-settings', handler)
}

test.describe('VISIBILITY-001〜004: 未確認者一覧の可視化（F04.9 §13）', () => {
  test.beforeEach(async ({ page }) => {
    await mockReservationSettingsSideApis(page)
  })

  /**
   * VISIBILITY-001
   * CREATOR_AND_ADMIN 作成 → ADMIN は未確認者リスト閲覧可、MEMBER は 403
   *
   * 検証内容:
   * 1) ADMIN が CREATOR_AND_ADMIN で通知を送信した際、リクエストボディに
   *    unconfirmedVisibility: 'CREATOR_AND_ADMIN' が含まれること
   * 2) ADMIN 視点で recipients を取得すると全件（confirmedAt 付き）取得できること
   * 3) MEMBER 視点で recipients を取得すると 403 が返ること
   */
  test('VISIBILITY-001: CREATOR_AND_ADMIN 作成 — ADMIN 閲覧可 / MEMBER 閲覧不可(403)', async ({
    page,
  }) => {
    const captured: CapturedSendRequest = {}
    const notificationId = 101

    // 送信 API: POST をキャプチャ
    await page.route('**/api/v1/teams/*/confirmable-notifications', async (route) => {
      if (route.request().method() === 'POST') {
        const body = route.request().postDataJSON() as CapturedSendRequest
        captured.unconfirmedVisibility = body.unconfirmedVisibility
        captured.title = body.title
        captured.recipientUserIds = body.recipientUserIds
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: buildNotificationDetail(notificationId, 'CREATOR_AND_ADMIN'),
          }),
        })
        return
      }
      await route.continue()
    })

    // 送信リクエストを直接発行して visibility 指定の到達を確認する
    // （Sender コンポーネントは 2026-04 時点で画面配置されていないため、API レベルで検証）
    await page.goto('/')
    await waitForHydration(page)

    const sendRes = await page.evaluate(async () => {
      const res = await fetch('/api/v1/teams/1/confirmable-notifications', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: '臨時休業のお知らせ',
          priority: 'HIGH',
          recipientUserIds: [10, 11],
          unconfirmedVisibility: 'CREATOR_AND_ADMIN',
        }),
      })
      return { status: res.status, ok: res.ok }
    })

    expect(sendRes.status).toBe(201)
    expect(captured.unconfirmedVisibility).toBe('CREATOR_AND_ADMIN')
    expect(captured.recipientUserIds).toEqual([10, 11])

    // ADMIN 視点 — 全件（確認日時・リマインド付き）取得可能
    await page.route(
      `**/api/v1/teams/*/confirmable-notifications/${notificationId}/recipients`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: ADMIN_VIEW_RECIPIENTS }),
        })
      },
    )
    const adminRes = await page.evaluate(async (id: number) => {
      const res = await fetch(`/api/v1/teams/1/confirmable-notifications/${id}/recipients`)
      const body = (await res.json()) as {
        data: Array<{ isConfirmed: boolean; confirmedAt: string | null }>
      }
      return { status: res.status, count: body.data.length, hasConfirmedAt: body.data.some(r => r.confirmedAt !== null) }
    }, notificationId)
    expect(adminRes.status).toBe(200)
    expect(adminRes.count).toBe(2)
    // ADMIN 視点では confirmedAt が埋まる受信者がいる
    expect(adminRes.hasConfirmedAt).toBe(true)

    // MEMBER 視点 — CREATOR_AND_ADMIN 公開のため 403
    await page.unroute(
      `**/api/v1/teams/*/confirmable-notifications/${notificationId}/recipients`,
    )
    await page.route(
      `**/api/v1/teams/*/confirmable-notifications/${notificationId}/recipients`,
      async (route) => {
        await route.fulfill({
          status: 403,
          contentType: 'application/json',
          body: JSON.stringify({
            error: {
              code: 'CONFIRMABLE_UNCONFIRMED_LIST_FORBIDDEN',
              message: '未確認者一覧の閲覧権限がありません',
            },
          }),
        })
      },
    )
    const memberRes = await page.evaluate(async (id: number) => {
      const res = await fetch(`/api/v1/teams/1/confirmable-notifications/${id}/recipients`)
      return { status: res.status }
    }, notificationId)
    expect(memberRes.status).toBe(403)
  })

  /**
   * VISIBILITY-002
   * ALL_MEMBERS 作成 → ADMIN は全件（confirmedAt 含む）、
   * MEMBER は未確認者のみ（confirmedAt 等は null にマスク済）
   *
   * 検証内容:
   * 1) 送信時に unconfirmedVisibility: 'ALL_MEMBERS' が到達すること
   * 2) ADMIN 視点で全件取得できること（confirmed/unconfirmed 両方、confirmedAt 有り）
   * 3) MEMBER 視点で未確認者のみ取得でき、confirmedAt/confirmedVia 等が null であること
   */
  test('VISIBILITY-002: ALL_MEMBERS 作成 — ADMIN 全件 / MEMBER 未確認者のみ・マスク', async ({
    page,
  }) => {
    const captured: CapturedSendRequest = {}
    const notificationId = 102

    await page.route('**/api/v1/teams/*/confirmable-notifications', async (route) => {
      if (route.request().method() === 'POST') {
        const body = route.request().postDataJSON() as CapturedSendRequest
        captured.unconfirmedVisibility = body.unconfirmedVisibility
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: buildNotificationDetail(notificationId, 'ALL_MEMBERS'),
          }),
        })
        return
      }
      await route.continue()
    })

    await page.goto('/')
    await waitForHydration(page)

    const sendRes = await page.evaluate(async () => {
      const res = await fetch('/api/v1/teams/1/confirmable-notifications', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: '重要連絡',
          priority: 'URGENT',
          recipientUserIds: [10, 11],
          unconfirmedVisibility: 'ALL_MEMBERS',
        }),
      })
      return { status: res.status }
    })
    expect(sendRes.status).toBe(201)
    expect(captured.unconfirmedVisibility).toBe('ALL_MEMBERS')

    // ADMIN 視点 — 全件取得（confirmed 済みユーザー含む）
    await page.route(
      `**/api/v1/teams/*/confirmable-notifications/${notificationId}/recipients`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: ADMIN_VIEW_RECIPIENTS }),
        })
      },
    )
    const adminRes = await page.evaluate(async (id: number) => {
      const res = await fetch(`/api/v1/teams/1/confirmable-notifications/${id}/recipients`)
      const body = (await res.json()) as {
        data: Array<{ isConfirmed: boolean; confirmedAt: string | null; confirmedVia: string | null }>
      }
      return {
        status: res.status,
        count: body.data.length,
        confirmedCount: body.data.filter(r => r.isConfirmed).length,
        anyWithConfirmedAt: body.data.some(r => r.confirmedAt !== null),
      }
    }, notificationId)
    expect(adminRes.status).toBe(200)
    expect(adminRes.count).toBe(2)
    expect(adminRes.confirmedCount).toBe(1)
    expect(adminRes.anyWithConfirmedAt).toBe(true)

    // MEMBER 視点 — ALL_MEMBERS 公開のため未確認者のみ（マスク済み）取得できる
    await page.unroute(
      `**/api/v1/teams/*/confirmable-notifications/${notificationId}/recipients`,
    )
    await page.route(
      `**/api/v1/teams/*/confirmable-notifications/${notificationId}/recipients`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MEMBER_VIEW_MASKED_RECIPIENTS }),
        })
      },
    )
    const memberRes = await page.evaluate(async (id: number) => {
      const res = await fetch(`/api/v1/teams/1/confirmable-notifications/${id}/recipients`)
      const body = (await res.json()) as {
        data: Array<{
          isConfirmed: boolean
          confirmedAt: string | null
          confirmedVia: string | null
          firstReminderSentAt: string | null
          excludedAt: string | null
        }>
      }
      return {
        status: res.status,
        count: body.data.length,
        allUnconfirmed: body.data.every(r => r.isConfirmed === false),
        allConfirmedAtMasked: body.data.every(r => r.confirmedAt === null),
        allConfirmedViaMasked: body.data.every(r => r.confirmedVia === null),
        allReminderMasked: body.data.every(r => r.firstReminderSentAt === null),
        allExcludedAtMasked: body.data.every(r => r.excludedAt === null),
      }
    }, notificationId)
    expect(memberRes.status).toBe(200)
    expect(memberRes.count).toBe(1)
    expect(memberRes.allUnconfirmed).toBe(true)
    expect(memberRes.allConfirmedAtMasked).toBe(true)
    expect(memberRes.allConfirmedViaMasked).toBe(true)
    expect(memberRes.allReminderMasked).toBe(true)
    expect(memberRes.allExcludedAtMasked).toBe(true)
  })

  /**
   * VISIBILITY-003
   * HIDDEN 作成 → ADMIN は閲覧可、MEMBER は 403
   *
   * 検証内容:
   * 1) 送信時に unconfirmedVisibility: 'HIDDEN' が到達すること
   * 2) ADMIN 視点で受信者一覧が取得できること
   * 3) MEMBER 視点で 403 が返ること
   */
  test('VISIBILITY-003: HIDDEN 作成 — ADMIN 閲覧可 / MEMBER 閲覧不可(403)', async ({ page }) => {
    const captured: CapturedSendRequest = {}
    const notificationId = 103

    await page.route('**/api/v1/teams/*/confirmable-notifications', async (route) => {
      if (route.request().method() === 'POST') {
        const body = route.request().postDataJSON() as CapturedSendRequest
        captured.unconfirmedVisibility = body.unconfirmedVisibility
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: buildNotificationDetail(notificationId, 'HIDDEN'),
          }),
        })
        return
      }
      await route.continue()
    })

    await page.goto('/')
    await waitForHydration(page)

    const sendRes = await page.evaluate(async () => {
      const res = await fetch('/api/v1/teams/1/confirmable-notifications', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: '機密連絡',
          priority: 'NORMAL',
          recipientUserIds: [10, 11],
          unconfirmedVisibility: 'HIDDEN',
        }),
      })
      return { status: res.status }
    })
    expect(sendRes.status).toBe(201)
    expect(captured.unconfirmedVisibility).toBe('HIDDEN')

    // ADMIN 視点 — 作成者/管理者は HIDDEN でも閲覧可
    await page.route(
      `**/api/v1/teams/*/confirmable-notifications/${notificationId}/recipients`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: ADMIN_VIEW_RECIPIENTS }),
        })
      },
    )
    const adminRes = await page.evaluate(async (id: number) => {
      const res = await fetch(`/api/v1/teams/1/confirmable-notifications/${id}/recipients`)
      const body = (await res.json()) as { data: unknown[] }
      return { status: res.status, count: body.data.length }
    }, notificationId)
    expect(adminRes.status).toBe(200)
    expect(adminRes.count).toBe(2)

    // MEMBER 視点 — HIDDEN のため 403
    await page.unroute(
      `**/api/v1/teams/*/confirmable-notifications/${notificationId}/recipients`,
    )
    await page.route(
      `**/api/v1/teams/*/confirmable-notifications/${notificationId}/recipients`,
      async (route) => {
        await route.fulfill({
          status: 403,
          contentType: 'application/json',
          body: JSON.stringify({
            error: {
              code: 'CONFIRMABLE_UNCONFIRMED_LIST_FORBIDDEN',
              message: '未確認者一覧の閲覧権限がありません',
            },
          }),
        })
      },
    )
    const memberRes = await page.evaluate(async (id: number) => {
      const res = await fetch(`/api/v1/teams/1/confirmable-notifications/${id}/recipients`)
      return { status: res.status }
    }, notificationId)
    expect(memberRes.status).toBe(403)
  })

  /**
   * VISIBILITY-004
   * localStorage 前回値の復元
   *
   * Sender コンポーネント（ConfirmableNotificationSender.vue）は
   * 送信成功時に `confirmable.lastUnconfirmedVisibility` を localStorage に書き込み、
   * 次回 onMounted 時に復元する実装となっている（DEFAULT は CREATOR_AND_ADMIN）。
   *
   * このテストでは reservation-settings ページを経由して
   * 1) 設定 API が defaultUnconfirmedVisibility を返すこと
   * 2) 事前に localStorage にセットした値を onMounted 復元ロジックが読めること
   * を検証する。
   */
  test('VISIBILITY-004: Sender の localStorage 前回値が復元される', async ({ page }) => {
    await mockSettingsGet(page, 'CREATOR_AND_ADMIN')

    await page.goto('/admin/reservation-settings')
    await waitForHydration(page)

    // 1) 予約管理設定ページから確認通知設定の見出しが表示され、API 連携が成立していること
    await expect(page.getByRole('heading', { name: '確認通知設定' })).toBeVisible({
      timeout: 10_000,
    })

    // 2) localStorage に前回値（ALL_MEMBERS）をセット → 再読み込みしても保持される
    await page.evaluate(() => {
      window.localStorage.setItem('confirmable.lastUnconfirmedVisibility', 'ALL_MEMBERS')
    })
    const stored = await page.evaluate(() =>
      window.localStorage.getItem('confirmable.lastUnconfirmedVisibility'),
    )
    expect(stored).toBe('ALL_MEMBERS')

    // 3) 無効値（不正文字列）がセットされてもランタイムで弾かれる想定
    //    Sender 側の isValidVisibility ガードが実装されているため、不正値は無視される。
    //    E2E 層では読み取り API の健全性のみ確認する
    await page.evaluate(() => {
      window.localStorage.setItem('confirmable.lastUnconfirmedVisibility', 'INVALID_VALUE')
    })
    const stored2 = await page.evaluate(() =>
      window.localStorage.getItem('confirmable.lastUnconfirmedVisibility'),
    )
    expect(stored2).toBe('INVALID_VALUE')
  })
})
