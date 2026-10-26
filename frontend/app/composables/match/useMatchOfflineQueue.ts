/**
 * F08.10 ライブ記録イベントのオフラインキュー（04_frontend_and_ux.md §G.11・MVP 最低限）。
 *
 * <p>屋外会場（電波不安定）前提。イベント POST が失敗したら IndexedDB（F11.1 {@code useOfflineDb}）に
 * 退避し、通信復帰時に {@link #flushAll} で順次再送する。雛形は F13.1 {@code useOfflineCheckInQueue}。</p>
 *
 * <p>重複排除: 同一 {@code clientId}（呼び出し側がイベント単位に採番）を既に PENDING で保持する場合は
 * 再 enqueue しない（同じイベントの二重送信を防ぐ）。</p>
 *
 * <p>対象は {@code path='/api/v1/organizations/{orgId}/matches/{matchId}/events'} の POST のみ。
 * 行動メモ/チェックイン用キューとはテーブル（offlineQueue）を共有するが path 条件で切り分ける。</p>
 *
 * <p>フル同期（オフライン中の長時間記録・コンフリクト解決）は後段 Phase（06 §I.1）。本 composable は
 * 「送信失敗時の退避＋復帰時の再送」に限定する。</p>
 */
import { offlineDb, type OfflineQueueItem } from '~/composables/useOfflineDb'
import type { MatchEventRequest, MatchEventResponse } from '~/types/match'

/** キューに積むイベント送信ペイロード。 */
export interface QueuedMatchEvent {
  orgId: number
  matchId: string
  /** イベント単位の重複排除キー（呼び出し側が採番・例 `crypto.randomUUID()`）。 */
  clientId: string
  body: MatchEventRequest
}

/** flushAll の 1 件送信結果。 */
export interface MatchFlushResult {
  queueId: number
  clientId: string
  response: MatchEventResponse | null
  error?: unknown
}

function eventsPath(orgId: number, matchId: string): string {
  return `/api/v1/organizations/${orgId}/matches/${matchId}/events`
}

// ============================================================
// Dexie 可用性（F11.1 useOfflineQueue と同様の遅延検知）
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
export function __resetMatchDexieAvailabilityForTest(): void {
  _dexieAvailable = null
}

/** 重複排除用 clientId にプレフィックスを付ける（他キューと衝突しないよう名前空間化）。 */
export function buildMatchEventClientId(clientId: string): string {
  return `match-event:${clientId}`
}

export function useMatchOfflineQueue() {
  /**
   * イベントをキューに積む。既に同一 clientId が未送信で存在する場合は再 enqueue せず
   * 既存 queueId を返す（同一イベントの二重送信防止）。
   */
  async function enqueue(payload: QueuedMatchEvent): Promise<number> {
    const clientId = buildMatchEventClientId(payload.clientId)
    const path = eventsPath(payload.orgId, payload.matchId)

    if (!(await isDexieAvailable())) {
      throw new Error('IndexedDB (Dexie) が利用できません。オフラインキューを使用できません。')
    }

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
      body: payload.body as unknown as Record<string, unknown>,
      version: null,
      status: 'PENDING',
      retryCount: 0,
      errorMessage: null,
      createdAt: new Date().toISOString(),
      syncedAt: null,
    })
    return id as number
  }

  /** 未送信項目（PENDING / FAILED）を取得する。 */
  async function getPending(): Promise<OfflineQueueItem[]> {
    if (!(await isDexieAvailable())) return []
    const items = await offlineDb.offlineQueue
      .where('status')
      .anyOf(['PENDING', 'FAILED'])
      .toArray()
    return items.filter((i) => i.path.includes('/matches/') && i.path.endsWith('/events'))
  }

  /**
   * 未送信項目を順次送信する。成功した項目はキューから削除する。
   * 1 件でも失敗したらそこで break（次回 flush で再試行）。
   *
   * @param sender 実際の送信関数（通常は useMatchEventApi#addEvent をラップしたもの）。
   *               送信成功時は MatchEventResponse、失敗時は例外を投げる契約。
   */
  async function flushAll(
    sender: (orgId: number, matchId: string, body: MatchEventRequest) => Promise<MatchEventResponse>,
  ): Promise<MatchFlushResult[]> {
    const pending = await getPending()
    const results: MatchFlushResult[] = []
    for (const item of pending) {
      if (item.id == null) continue
      const { orgId, matchId } = parsePath(item.path)
      if (orgId === null || matchId === null) continue
      try {
        const res = await sender(orgId, matchId, item.body as unknown as MatchEventRequest)
        results.push({ queueId: item.id, clientId: item.clientId, response: res })
        await offlineDb.offlineQueue.delete(item.id)
      } catch (e) {
        results.push({ queueId: item.id, clientId: item.clientId, response: null, error: e })
        break
      }
    }
    return results
  }

  /** キューを空にする（テスト／初期化用・match イベント分のみ）。 */
  async function clearAll(): Promise<void> {
    if (!(await isDexieAvailable())) return
    const all = await offlineDb.offlineQueue.toArray()
    for (const i of all) {
      if (i.path.includes('/matches/') && i.path.endsWith('/events') && i.id != null) {
        await offlineDb.offlineQueue.delete(i.id)
      }
    }
  }

  /** 未送信件数。 */
  async function count(): Promise<number> {
    return (await getPending()).length
  }

  return {
    enqueue,
    getPending,
    flushAll,
    clearAll,
    count,
  }
}

// ============================================================
// path <-> {orgId, matchId} 変換（再送時にパスから復元）
// ============================================================

/** `/api/v1/organizations/{orgId}/matches/{matchId}/events` から orgId/matchId を抽出。 */
export function parsePath(path: string): { orgId: number | null; matchId: string | null } {
  const m = path.match(/\/organizations\/(\d+)\/matches\/([^/]+)\/events$/)
  if (!m) return { orgId: null, matchId: null }
  return { orgId: Number(m[1]), matchId: m[2] ?? null }
}
