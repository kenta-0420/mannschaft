import type { ApiResponse } from '~/types/api'
import type { CheckInResponse } from '~/types/jobmatching'
import {
  useOfflineCheckInQueue,
  type QueuedCheckInPayload,
} from '~/composables/jobs/useOfflineCheckInQueue'

/**
 * F13.1 Phase 13.1.2 Worker 側 QR チェックイン API クライアント。
 *
 * <p>Backend: {@code JobCheckInController}（{@code POST /api/v1/jobs/check-ins}）。
 * オンライン時はそのまま API を叩き、オフライン時は {@link useOfflineCheckInQueue}
 * にキューイングして後で送信する。</p>
 *
 * <p>オフライン判定は {@code navigator.onLine} を信頼する（F11.1 基盤と整合）。
 * 実際の fetch 失敗時（サーバー到達不能）は呼び出し側でキャッチして
 * 必要なら {@link #enqueueForRetry} を呼ぶ運用とする。</p>
 */

/** BE {@code RecordCheckInRequest} と同等。型名はフロント流儀で Request を残す。 */
export type RecordCheckInRequest = QueuedCheckInPayload

/**
 * recordCheckIn の返り値。
 *
 * <p>オンライン送信成功なら {@code status: 'SENT'}、
 * オフライン → キューイングなら {@code status: 'QUEUED'} を返す。</p>
 */
export type RecordCheckInResult =
  | { status: 'SENT'; response: CheckInResponse }
  | { status: 'QUEUED'; queueId: number }

export function useJobCheckInApi() {
  const api = useApi()
  const queue = useOfflineCheckInQueue()

  /**
   * オンライン／オフラインを判定して適切な経路で送信する。
   *
   * @param payload チェックイン／アウトペイロード
   * @param opts.forceOffline テスト用。{@code true} なら navigator.onLine に関わらずキュー行き。
   */
  async function recordCheckIn(
    payload: RecordCheckInRequest,
    opts?: { forceOffline?: boolean },
  ): Promise<RecordCheckInResult> {
    const online = !opts?.forceOffline && (typeof navigator === 'undefined' || navigator.onLine)
    if (!online) {
      const queueId = await queue.enqueue(payload)
      return { status: 'QUEUED', queueId }
    }
    const res = await api<ApiResponse<CheckInResponse>>('/api/v1/jobs/check-ins', {
      method: 'POST',
      body: payload,
    })
    return { status: 'SENT', response: res.data }
  }

  /**
   * 生の POST だけを行うユーティリティ（キュー flush 時など、判定ロジックを迂回したいケース用）。
   */
  async function postCheckInRaw(payload: RecordCheckInRequest): Promise<CheckInResponse> {
    const res = await api<ApiResponse<CheckInResponse>>('/api/v1/jobs/check-ins', {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  /** 明示的にキューへ積む（オンライン中に意図的にキューへ入れたいケース）。 */
  async function enqueueForRetry(payload: RecordCheckInRequest): Promise<number> {
    return queue.enqueue(payload)
  }

  /** キュー内の未送信チェックインを順次送信する。 */
  async function flushQueue() {
    return queue.flushAll((p) => postCheckInRaw(p))
  }

  return {
    recordCheckIn,
    postCheckInRaw,
    enqueueForRetry,
    flushQueue,
    getPending: queue.getPending,
    pendingCount: queue.count,
  }
}
