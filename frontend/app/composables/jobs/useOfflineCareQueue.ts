import { offlineDb, type OfflineQueueItem } from '~/composables/useOfflineDb'
import type {
  AbsenceNoticeRequest,
  AdvanceNoticeResponse,
  DismissalRequest,
  DismissalStatusResponse,
  LateNoticeRequest,
  RollCallSessionRequest,
  RollCallSessionResponse,
} from '~/types/care'

/**
 * F03.12 Phase10 オフラインキュー基盤。
 *
 * <p>主催者点呼・事前遅刻/欠席連絡・解散通知の 4 種ジョブを 1 つのキューでさばく。
 * F11.1 の {@code useOfflineDb} （Dexie）の {@code offlineQueue} テーブルを共用し、
 * {@code path} プレフィックスで他キュー（{@code useOfflineCheckInQueue}・行動メモ）と切り分ける。</p>
 *
 * <p>送信中身は B/C/D が API composable 呼び出しで埋める設計だが、
 * {@link flushPendingCareJobs} だけは type ごとの API ディスパッチを A 側で完成させている。</p>
 *
 * <p>パスプレフィックス（重複排除キーにも利用）: {@link CARE_PATH_PREFIX}。</p>
 */

// ============================================================
// 型: ジョブ定義
// ============================================================

export type CareJobType = 'ROLL_CALL' | 'LATE_NOTICE' | 'ABSENCE_NOTICE' | 'DISMISSAL'

/**
 * ケア機能オフラインジョブ。
 *
 * <p>BE エンドポイント:</p>
 * <ul>
 *   <li>{@code ROLL_CALL}      → POST /api/v1/teams/{teamId}/events/{eventId}/roll-call</li>
 *   <li>{@code LATE_NOTICE}    → POST /api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/late-notice</li>
 *   <li>{@code ABSENCE_NOTICE} → POST /api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/absence-notice</li>
 *   <li>{@code DISMISSAL}      → POST /api/v1/teams/{teamId}/events/{eventId}/dismissal</li>
 * </ul>
 */
export type CareJob =
  | { type: 'ROLL_CALL'; teamId: number; eventId: number; payload: RollCallSessionRequest }
  | { type: 'LATE_NOTICE'; teamId: number; eventId: number; payload: LateNoticeRequest }
  | { type: 'ABSENCE_NOTICE'; teamId: number; eventId: number; payload: AbsenceNoticeRequest }
  | { type: 'DISMISSAL'; teamId: number; eventId: number; payload: DismissalRequest }

/** flushPendingCareJobs の集計結果。 */
export interface FlushSummary {
  /** 成功して削除した件数。 */
  success: number
  /** 失敗して残した件数（PENDING/FAILED のままキューに残る）。 */
  failed: number
}

/** Dexie 内部で {@code path} に書き込む共通プレフィックス。 */
const CARE_PATH_PREFIX = '/api/v1/care-queue'

const PATH_BY_TYPE: Record<CareJobType, string> = {
  ROLL_CALL: `${CARE_PATH_PREFIX}/roll-call`,
  LATE_NOTICE: `${CARE_PATH_PREFIX}/late-notice`,
  ABSENCE_NOTICE: `${CARE_PATH_PREFIX}/absence-notice`,
  DISMISSAL: `${CARE_PATH_PREFIX}/dismissal`,
}

// ============================================================
// Dexie 可用性
// ============================================================

let _dexieAvailable: boolean | null = null

async function isDexieAvailable(): Promise<boolean> {
  if (_dexieAvailable !== null) return _dexieAvailable
  try {
    await offlineDb.offlineQueue.count()
    _dexieAvailable = true
  } catch {
    _dexieAvailable = false
  }
  return _dexieAvailable
}

/** テスト専用: Dexie 可用性判定をリセットする。 */
export function __resetCareQueueDexieAvailabilityForTest(): void {
  _dexieAvailable = null
}

// ============================================================
// 重複排除キー
// ============================================================

/**
 * 重複排除用 clientId を生成する。
 *
 * - {@code ROLL_CALL}: payload.rollCallSessionId が冪等キー
 * - {@code LATE_NOTICE} / {@code ABSENCE_NOTICE}: userId + eventId 単位で 1 件
 * - {@code DISMISSAL}: eventId 単位で 1 件
 *
 * 同一 clientId が PENDING/FAILED で残っている場合は再 enqueue を行わない。
 */
export function buildCareJobClientId(job: CareJob): string {
  switch (job.type) {
    case 'ROLL_CALL':
      return `care:roll-call:${job.eventId}:${job.payload.rollCallSessionId}`
    case 'LATE_NOTICE':
      return `care:late-notice:${job.eventId}:${job.payload.userId}`
    case 'ABSENCE_NOTICE':
      return `care:absence-notice:${job.eventId}:${job.payload.userId}`
    case 'DISMISSAL':
      return `care:dismissal:${job.eventId}`
  }
}

// ============================================================
// 本体
// ============================================================

/**
 * キューに 1 件積む。同一 clientId が SUCCESS 以外で残っている場合は新規 enqueue を行わず既存 id を返す。
 */
export async function enqueueCareJob(job: CareJob): Promise<number> {
  if (!(await isDexieAvailable())) {
    throw new Error('IndexedDB (Dexie) が利用できません。オフラインキューを使用できません。')
  }

  const clientId = buildCareJobClientId(job)
  const path = PATH_BY_TYPE[job.type]

  const existing = await offlineDb.offlineQueue
    .where('clientId')
    .equals(clientId)
    .filter((i) => i.path === path && i.status !== 'SUCCESS')
    .first()
  if (existing?.id != null) {
    return existing.id
  }

  const id = await offlineDb.offlineQueue.add({
    clientId,
    method: 'POST',
    path,
    body: toBody(job),
    version: null,
    status: 'PENDING',
    retryCount: 0,
    errorMessage: null,
    createdAt: new Date().toISOString(),
    syncedAt: null,
  })
  return id as number
}

/** ケアキューに残っている未送信件数を返す（PENDING/FAILED）。 */
export async function getPendingCareJobCount(): Promise<number> {
  return (await getPendingCareJobs()).length
}

/** ケアキューに残っている未送信ジョブを取得する（PENDING/FAILED）。 */
export async function getPendingCareJobs(): Promise<OfflineQueueItem[]> {
  if (!(await isDexieAvailable())) return []
  const items = await offlineDb.offlineQueue
    .where('status')
    .anyOf(['PENDING', 'FAILED'])
    .toArray()
  return items.filter((i) => i.path.startsWith(CARE_PATH_PREFIX))
}

/**
 * オンライン復帰時に未送信ジョブをまとめて送信する。
 *
 * <p>type 別に正しい API composable を呼び分け、成功した項目は Dexie から削除する。
 * 1 件失敗してもループ自体は続行（個別失敗が後続を巻き込まない設計）。</p>
 *
 * @returns 成功・失敗の件数サマリ
 */
export async function flushPendingCareJobs(): Promise<FlushSummary> {
  const summary: FlushSummary = { success: 0, failed: 0 }
  if (!(await isDexieAvailable())) return summary

  const rollCallApi = useRollCallApi()
  const advanceNoticeApi = useAdvanceNoticeApi()
  const dismissalApi = useDismissalApi()

  const pending = await getPendingCareJobs()
  for (const item of pending) {
    if (item.id == null) continue
    try {
      const job = fromBody(item.body)
      await dispatch(job, { rollCallApi, advanceNoticeApi, dismissalApi })
      await offlineDb.offlineQueue.delete(item.id)
      summary.success++
    } catch (e) {
      // 失敗は記録のみ。次回 flush で再試行する。
      summary.failed++
      await offlineDb.offlineQueue.update(item.id, {
        status: 'FAILED',
        retryCount: (item.retryCount ?? 0) + 1,
        errorMessage: e instanceof Error ? e.message : String(e),
      })
    }
  }
  return summary
}

/** テスト／初期化用: ケアキューを空にする。 */
export async function clearAllCareJobs(): Promise<void> {
  if (!(await isDexieAvailable())) return
  const all = await offlineDb.offlineQueue.toArray()
  for (const i of all) {
    if (i.path.startsWith(CARE_PATH_PREFIX) && i.id != null) {
      await offlineDb.offlineQueue.delete(i.id)
    }
  }
}

// ============================================================
// type 別ディスパッチ
// ============================================================

interface ApiHandles {
  rollCallApi: ReturnType<typeof useRollCallApi>
  advanceNoticeApi: ReturnType<typeof useAdvanceNoticeApi>
  dismissalApi: ReturnType<typeof useDismissalApi>
}

type DispatchResult = RollCallSessionResponse | AdvanceNoticeResponse | DismissalStatusResponse

async function dispatch(job: CareJob, apis: ApiHandles): Promise<DispatchResult> {
  switch (job.type) {
    case 'ROLL_CALL':
      return apis.rollCallApi.submitRollCall(job.teamId, job.eventId, job.payload)
    case 'LATE_NOTICE':
      return apis.advanceNoticeApi.submitLateNotice(job.teamId, job.eventId, job.payload)
    case 'ABSENCE_NOTICE':
      return apis.advanceNoticeApi.submitAbsenceNotice(job.teamId, job.eventId, job.payload)
    case 'DISMISSAL':
      return apis.dismissalApi.submitDismissal(job.teamId, job.eventId, job.payload)
  }
}

// ============================================================
// CareJob <-> Dexie body 変換
// ============================================================

function toBody(job: CareJob): Record<string, unknown> {
  return {
    type: job.type,
    teamId: job.teamId,
    eventId: job.eventId,
    payload: job.payload as unknown as Record<string, unknown>,
  }
}

export function fromBody(body: Record<string, unknown>): CareJob {
  const type = body.type as CareJobType
  const teamId = Number(body.teamId)
  const eventId = Number(body.eventId)
  const payload = body.payload as Record<string, unknown>

  switch (type) {
    case 'ROLL_CALL':
      return { type, teamId, eventId, payload: payload as unknown as RollCallSessionRequest }
    case 'LATE_NOTICE':
      return { type, teamId, eventId, payload: payload as unknown as LateNoticeRequest }
    case 'ABSENCE_NOTICE':
      return { type, teamId, eventId, payload: payload as unknown as AbsenceNoticeRequest }
    case 'DISMISSAL':
      return { type, teamId, eventId, payload: payload as unknown as DismissalRequest }
    default: {
      // 想定外の type は throw（FAILED として記録され retry もされない方針）
      throw new Error(`Unknown CareJob type: ${String(type)}`)
    }
  }
}

// ============================================================
// composable 形式（呼び出し側が今までと同じ書き方をできるよう便宜的に提供）
// ============================================================

/**
 * 既存 composable と揃えた呼び出し口。
 * 内部実装は上記のモジュール関数に委譲する。
 */
export function useOfflineCareQueue() {
  return {
    enqueueCareJob,
    flushPendingCareJobs,
    getPendingCareJobCount,
    getPendingCareJobs,
    clearAllCareJobs,
  }
}
