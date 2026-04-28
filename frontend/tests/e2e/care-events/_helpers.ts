import type { Page, Route } from '@playwright/test'
import type {
  AdvanceNoticeResponse,
  DismissalStatusResponse,
  RollCallCandidate,
  RollCallSessionResponse,
} from '../../../app/types/care'
import type { CareLinkResponse } from '../../../app/types/careLink'
import type { EventResponse } from '../../../app/types/event'

/**
 * F03.12 Phase 11 ケアイベント E2E 共通ヘルパー。
 *
 * <p>役割:</p>
 * <ul>
 *   <li>主催者・一般メンバー・見守り者の 3 ロールごとの認証注入（{@link loginAsOrganizer} / {@link loginAsMember} / {@link loginAsWatcher}）</li>
 *   <li>未モック API を 404 で握る catch-all（{@link mockCatchAllApis}）</li>
 *   <li>BE DTO 準拠の fixture builder（{@link buildRollCallCandidate} 等）</li>
 *   <li>ジョブ別のリクエストキャプチャ付き API モック（{@link mockSubmitRollCall} 等）</li>
 *   <li>Dexie に積まれた care-queue 件数を検証するヘルパ（{@link getOfflineCareQueueItems} 等）</li>
 * </ul>
 *
 * <p>方針:</p>
 * <ul>
 *   <li>care-links / surveys / shifts の {@code _helpers.ts} を踏襲（後勝ち page.route ＋ fixture 関数生成）</li>
 *   <li>後続 spec（足軽 G/H/I）は本ファイルを import して spec を実装する</li>
 *   <li>{@code captured} は {@code { lastBody: unknown }} を渡すと {@code postDataJSON()} で記録する</li>
 *   <li>未モック分は {@link mockCatchAllApis} が 404 で fail-fast する設計（既定の 200 空 data ではなく明示 404）</li>
 * </ul>
 */

// ---------------------------------------------------------------------------
// 既定 ID 定数（spec から参照されるが、上書きしたい場合は引数で渡す）
// ---------------------------------------------------------------------------

export const DEFAULT_TEAM_ID = 1
export const DEFAULT_EVENT_ID = 100
export const ORGANIZER_USER_ID = 1
export const MEMBER_USER_ID = 2
export const WATCHER_USER_ID = 3
export const RECIPIENT_USER_ID = 4

// ---------------------------------------------------------------------------
// 認証注入
// ---------------------------------------------------------------------------

/**
 * 認証ペイロードを localStorage に注入する内部実装。
 *
 * <p>{@code addInitScript} で各ページロード時に確実に流し込む。
 * shifts/surveys のヘルパと同じ流儀で {@code accessToken} / {@code refreshToken} /
 * {@code currentUser} の 3 点を入れる。</p>
 */
async function injectAuth(
  page: Page,
  payload: { id: number; email: string; displayName: string; role: string },
): Promise<void> {
  await page.addInitScript((args) => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: args.id,
        email: args.email,
        displayName: args.displayName,
        profileImageUrl: null,
        role: args.role,
      }),
    )
  }, payload)
}

/** 主催者（ADMIN ロール）としてログイン済み状態をセットアップする。 */
export async function loginAsOrganizer(
  page: Page,
  opts?: { teamId?: number; userId?: number },
): Promise<void> {
  const userId = opts?.userId ?? ORGANIZER_USER_ID
  await injectAuth(page, {
    id: userId,
    email: 'e2e-organizer@example.com',
    displayName: 'e2e_organizer',
    role: 'ADMIN',
  })
  // teamId は localStorage に流す必須項目ではないが、引数として受け取って
  // 将来 currentScope 注入が必要になった際の互換性を保つ。
  void opts?.teamId
}

/** 一般メンバー（MEMBER ロール）としてログイン済み状態をセットアップする。 */
export async function loginAsMember(
  page: Page,
  opts?: { userId?: number },
): Promise<void> {
  const userId = opts?.userId ?? MEMBER_USER_ID
  await injectAuth(page, {
    id: userId,
    email: 'e2e-member@example.com',
    displayName: 'e2e_member',
    role: 'MEMBER',
  })
}

/**
 * 見守り者（MEMBER ロール、ケア対象を持つ）としてログイン済み状態をセットアップする。
 *
 * <p>{@code recipients} を渡すと {@link mockGetMyCareRecipients} 相当の前段モックも
 * 同時に登録する。spec 側で個別にケア対象一覧モックが必要な場合は省略可能。</p>
 */
export async function loginAsWatcher(
  page: Page,
  opts?: { userId?: number; recipients?: number[] },
): Promise<void> {
  const userId = opts?.userId ?? WATCHER_USER_ID
  await injectAuth(page, {
    id: userId,
    email: 'e2e-watcher@example.com',
    displayName: 'e2e_watcher',
    role: 'MEMBER',
  })
  if (opts?.recipients && opts.recipients.length > 0) {
    const recipientLinks = opts.recipients.map((rid, idx) =>
      buildCareLink({
        id: 1000 + idx,
        watcherUserId: userId,
        careRecipientUserId: rid,
        careRecipientDisplayName: `ケア対象${rid}`,
      }),
    )
    await mockGetMyCareRecipients(page, recipientLinks)
  }
}

// ---------------------------------------------------------------------------
// catch-all
// ---------------------------------------------------------------------------

/**
 * すべての `/api/v1/**` を 404 で fulfill する catch-all。
 *
 * <p>spec ごとの {@code beforeEach} でまず本関数を呼び、その後に個別 mock を
 * 上書き登録する（Playwright は後勝ち）。未モックエンドポイントを叩いた場合は
 * 404 で即時失敗するため、テストの bug を握りつぶさない。</p>
 */
export async function mockCatchAllApis(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify({
        error: { code: 'NOT_MOCKED', message: 'No mock registered for this endpoint' },
      }),
    })
  })
}

// ---------------------------------------------------------------------------
// fixture builder（BE DTO 準拠）
// ---------------------------------------------------------------------------

/** {@link buildRollCallCandidate} の overrides 引数。 */
export type RollCallCandidateOverrides = Partial<RollCallCandidate>

/** RollCallCandidate の雛形。 */
export function buildRollCallCandidate(
  overrides: RollCallCandidateOverrides = {},
): RollCallCandidate {
  return {
    userId: 101,
    displayName: '山田太郎',
    avatarUrl: null,
    rsvpStatus: 'ATTENDING',
    isAlreadyCheckedIn: false,
    isUnderCare: false,
    watcherCount: 0,
    ...overrides,
  }
}

/** RollCallSessionResponse の雛形。 */
export function buildRollCallSessionResponse(
  overrides: Partial<RollCallSessionResponse> = {},
): RollCallSessionResponse {
  return {
    rollCallSessionId: 'srv-uuid-default',
    createdCount: 0,
    updatedCount: 0,
    guardianNotificationsSent: 0,
    guardianSetupWarnings: [],
    ...overrides,
  }
}

/** AdvanceNoticeResponse の雛形（既定は LATE）。 */
export function buildAdvanceNoticeResponse(
  overrides: Partial<AdvanceNoticeResponse> = {},
): AdvanceNoticeResponse {
  return {
    userId: 101,
    displayName: '山田太郎',
    noticeType: 'LATE',
    expectedArrivalMinutesLate: 15,
    absenceReason: null,
    comment: null,
    createdAt: '2026-04-27T09:00:00.000Z',
    ...overrides,
  }
}

/** DismissalStatusResponse の雛形（既定は未送信状態）。 */
export function buildDismissalStatusResponse(
  overrides: Partial<DismissalStatusResponse> = {},
): DismissalStatusResponse {
  return {
    dismissalNotificationSentAt: null,
    dismissalNotifiedByUserId: null,
    reminderCount: 0,
    lastReminderAt: null,
    dismissed: false,
    ...overrides,
  }
}

/** EventResponse の雛形。 */
export function buildEvent(overrides: Partial<EventResponse> = {}): EventResponse {
  return {
    id: DEFAULT_EVENT_ID,
    scopeType: 'TEAM',
    scopeId: DEFAULT_TEAM_ID,
    slug: null,
    subtitle: null,
    coverImageKey: null,
    status: 'IN_PROGRESS',
    visibility: 'MEMBERS_ONLY',
    registrationStartsAt: null,
    registrationEndsAt: null,
    maxCapacity: null,
    registrationCount: 0,
    checkinCount: 0,
    createdAt: '2026-04-20T00:00:00Z',
    updatedAt: '2026-04-20T00:00:00Z',
    ...overrides,
  }
}

/** CareLinkResponse の雛形（見守り者→ケア対象方向）。 */
export function buildCareLink(overrides: Partial<CareLinkResponse> = {}): CareLinkResponse {
  return {
    id: 500,
    careRecipientUserId: RECIPIENT_USER_ID,
    careRecipientDisplayName: 'ケア対象太郎',
    watcherUserId: WATCHER_USER_ID,
    watcherDisplayName: 'e2e_watcher',
    careCategory: 'MINOR',
    relationship: 'PARENT',
    isPrimary: true,
    status: 'ACTIVE',
    invitedBy: 'WATCHER',
    confirmedAt: '2026-04-01T00:00:00Z',
    notifyOnRsvp: true,
    notifyOnCheckin: true,
    notifyOnCheckout: true,
    notifyOnAbsentAlert: true,
    notifyOnDismissal: true,
    createdAt: '2026-04-01T00:00:00Z',
    ...overrides,
  }
}

// ---------------------------------------------------------------------------
// API モック
// ---------------------------------------------------------------------------

/**
 * 共通: リクエスト本文を {@code captured.lastBody} に格納する小道具。
 *
 * <p>Playwright の {@code postDataJSON()} が型 {@code unknown} を返すので、
 * 受け取り側が {@code as} で narrowing する前提で {@code unknown} のまま保持する。</p>
 */
async function captureBody(
  route: Route,
  captured: { lastBody: unknown } | undefined,
): Promise<void> {
  if (!captured) return
  try {
    captured.lastBody = route.request().postDataJSON() as unknown
  } catch {
    captured.lastBody = null
  }
}

/** GET /api/v1/teams/{teamId}/events/{eventId}/roll-call/candidates をモック。 */
export async function mockGetCandidates(
  page: Page,
  candidates: RollCallCandidate[],
): Promise<void> {
  await page.route('**/api/v1/teams/*/events/*/roll-call/candidates', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: candidates }),
    })
  })
}

/**
 * POST /api/v1/teams/{teamId}/events/{eventId}/roll-call をモック。
 *
 * <p>{@code captured} を渡すと {@code lastBody} にリクエスト本文（rollCallSessionId 等）が記録される。</p>
 */
export async function mockSubmitRollCall(
  page: Page,
  response: RollCallSessionResponse,
  captured?: { lastBody: unknown },
): Promise<void> {
  await page.route('**/api/v1/teams/*/events/*/roll-call', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    await captureBody(route, captured)
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ data: response }),
    })
  })
}

/** GET /api/v1/teams/{teamId}/events/{eventId}/advance-notices をモック。 */
export async function mockGetAdvanceNotices(
  page: Page,
  list: AdvanceNoticeResponse[],
): Promise<void> {
  await page.route('**/api/v1/teams/*/events/*/advance-notices', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: list }),
    })
  })
}

/** POST /api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/late-notice をモック。 */
export async function mockSubmitLateNotice(
  page: Page,
  response: AdvanceNoticeResponse,
  captured?: { lastBody: unknown },
): Promise<void> {
  await page.route(
    '**/api/v1/teams/*/events/*/rsvp-responses/late-notice',
    async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      await captureBody(route, captured)
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: response }),
      })
    },
  )
}

/** POST /api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/absence-notice をモック。 */
export async function mockSubmitAbsenceNotice(
  page: Page,
  response: AdvanceNoticeResponse,
  captured?: { lastBody: unknown },
): Promise<void> {
  await page.route(
    '**/api/v1/teams/*/events/*/rsvp-responses/absence-notice',
    async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      await captureBody(route, captured)
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: response }),
      })
    },
  )
}

/**
 * POST /api/v1/teams/{teamId}/events/{eventId}/dismissal をモック。
 *
 * <p>BE は本文 null で 201 を返すのみだが、上位 composable {@code useDismissalApi} は
 * 送信後すぐ {@code getDismissalStatus} を呼ぶ。本ヘルパでは状況シミュレーションのため
 * {@code response} は {@code DismissalStatusResponse} を受け取り、続く GET /status へも
 * 同じ値を返す形にする（{@link mockGetDismissalStatus} を内部で同時に登録）。</p>
 */
export async function mockSubmitDismissal(
  page: Page,
  response: DismissalStatusResponse,
  captured?: { lastBody: unknown },
): Promise<void> {
  await page.route('**/api/v1/teams/*/events/*/dismissal', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    await captureBody(route, captured)
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ data: null }),
    })
  })
  await mockGetDismissalStatus(page, response)
}

/** GET /api/v1/teams/{teamId}/events/{eventId}/dismissal/status をモック。 */
export async function mockGetDismissalStatus(
  page: Page,
  status: DismissalStatusResponse,
): Promise<void> {
  await page.route('**/api/v1/teams/*/events/*/dismissal/status', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: status }),
    })
  })
}

/** GET /api/v1/me/care-links/recipients をモック（見守り者視点のケア対象一覧）。 */
export async function mockGetMyCareRecipients(
  page: Page,
  recipients: CareLinkResponse[],
): Promise<void> {
  await page.route('**/api/v1/me/care-links/recipients**', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: recipients }),
    })
  })
}

/** GET /api/v1/teams/{teamId}/events/{eventId} をモック（イベント詳細）。 */
export async function mockEventDetail(page: Page, event: EventResponse): Promise<void> {
  await page.route('**/api/v1/teams/*/events/*', async (route) => {
    // /events/{id}/... のサブパスは別ハンドラに委譲する
    const url = new URL(route.request().url())
    if (!/\/events\/\d+$/.test(url.pathname)) {
      await route.continue()
      return
    }
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: event }),
    })
  })
}

// ---------------------------------------------------------------------------
// Dexie 検証ヘルパー
// ---------------------------------------------------------------------------

/** {@link getOfflineCareQueueItems} の戻り値要素。 */
export interface OfflineCareQueueItemSummary {
  /** {@code buildCareJobClientId} で生成された clientId（例: care:roll-call:100:abc）。 */
  clientId: string
  /** Dexie に格納された path（{@code /api/v1/care-queue/...}）。 */
  path: string
  /** Dexie に格納された body。{@code { type, teamId, eventId, payload }} 構造。 */
  payload: Record<string, unknown>
}

/**
 * useOfflineCareQueue が Dexie に積んだジョブの一覧を取得する。
 *
 * <p>ブラウザ側で IndexedDB を直接開き、{@code mannschaft-offline.offlineQueue} から
 * {@code path} が {@code /api/v1/care-queue} で始まるものを抽出する。
 * {@code page.evaluate} 内では Dexie への参照が無いため、IDB 直叩きで実装する。</p>
 */
export async function getOfflineCareQueueItems(
  page: Page,
): Promise<OfflineCareQueueItemSummary[]> {
  return page.evaluate<OfflineCareQueueItemSummary[]>(() => {
    return new Promise<OfflineCareQueueItemSummary[]>((resolve, reject) => {
      const req = indexedDB.open('mannschaft-offline')
      req.onerror = () => reject(req.error ?? new Error('IndexedDB open failed'))
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
          const filtered = rows
            .filter((r) => typeof r.path === 'string' && r.path.startsWith('/api/v1/care-queue'))
            .map((r) => ({ clientId: r.clientId, path: r.path, payload: r.body }))
          db.close()
          resolve(filtered)
        }
        all.onerror = () => {
          db.close()
          reject(all.error ?? new Error('offlineQueue.getAll failed'))
        }
      }
    })
  })
}

/**
 * 指定 clientId プレフィックスにマッチするケアキュー件数を返す。
 *
 * <p>例: {@code 'care:roll-call:'} を渡すと点呼ジョブのみ件数を取得できる。</p>
 */
export async function getOfflineCareQueueCount(
  page: Page,
  clientIdPrefix: string,
): Promise<number> {
  const items = await getOfflineCareQueueItems(page)
  return items.filter((i) => i.clientId.startsWith(clientIdPrefix)).length
}
