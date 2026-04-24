import { offlineDb, type OfflineQueueItem } from '~/composables/useOfflineDb'
import type { CheckInResponse, JobCheckInType } from '~/types/jobmatching'

/**
 * F13.1 Phase 13.1.2 Worker 側 QR チェックイン用オフラインキュー。
 *
 * <p>オンライン時は {@code useJobCheckInApi#recordCheckIn} が直接 BE を叩くが、
 * オフライン時は本 composable の {@link #enqueue} で Dexie（F11.1 {@code useOfflineDb}）に
 * 退避する。{@code online} イベント発火時に {@link #flushAll} で順次送信する。</p>
 *
 * <p>重複排除:</p>
 * <ul>
 *   <li>同一 {@code token} を既にキューに持っている場合は再 enqueue を拒否する
 *       （同じスキャンを複数回押しても二重記録しない）。</li>
 *   <li>{@code shortCode} 入力経路も同様に {@code contractId + type + shortCode} で重複弾き。</li>
 * </ul>
 *
 * <p>{@code path='/api/v1/jobs/check-ins'} のアイテムのみを対象として扱う。
 * 行動メモ用の {@code useOfflineQueue} とはテーブルは共有するがクエリ条件で切り分ける。</p>
 */

// ============================================================
// 型
// ============================================================

/** キューに積むペイロード（BE RecordCheckInRequest と同等）。 */
export interface QueuedCheckInPayload {
  contractId: number
  token?: string | null
  shortCode?: string | null
  type: JobCheckInType
  scannedAt: string
  offlineSubmitted?: boolean
  manualCodeFallback?: boolean
  geoLat?: number | null
  geoLng?: number | null
  geoAccuracy?: number | null
  clientUserAgent?: string | null
}

/** flushAll の 1 件送信結果。 */
export interface FlushResult {
  queueId: number
  response: CheckInResponse | null
  error?: unknown
}

const CHECK_IN_PATH = '/api/v1/jobs/check-ins'

// ============================================================
// Dexie 可用性（F11.1 useOfflineQueue と同様の遅延検知）
// ============================================================

let _dexieAvailable: boolean | null = null

async function isDexieAvailable(): Promise<boolean> {
  if (_dexieAvailable !== null) return _dexieAvailable
  try {
    await offlineDb.offlineQueue.count()
    _dexieAvailable = true
  }
  catch {
    _dexieAvailable = false
  }
  return _dexieAvailable
}

/** テスト専用: Dexie 可用性判定をリセットする。 */
export function __resetDexieAvailabilityForTest(): void {
  _dexieAvailable = null
}

// ============================================================
// クライアント ID（重複排除キー）
// ============================================================

/**
 * 重複排除用クライアント ID を生成する。
 *
 * <p>token があればそれ自体を用い、無い場合は {@code contractId + type + shortCode}
 * で合成する。どちらも無い場合は {@code Date.now() + Math.random()} でユニーク化するが、
 * その入力は validation でも弾かれる想定なので実運用上は token/shortCode のどちらかが必ずある。</p>
 */
export function buildClientId(payload: QueuedCheckInPayload): string {
  if (payload.token) return `jobs-check-in:token:${payload.token}`
  if (payload.shortCode) {
    return `jobs-check-in:short:${payload.contractId}:${payload.type}:${payload.shortCode}`
  }
  return `jobs-check-in:anon:${payload.contractId}:${payload.type}:${Date.now()}-${Math.random()}`
}

// ============================================================
// 本体
// ============================================================

export function useOfflineCheckInQueue() {
  /**
   * キューに積む。既に同一 clientId が PENDING で存在する場合は新規 enqueue を行わず
   * 既存の queueId を返す（同一トークンによる二重送信防止）。
   */
  async function enqueue(payload: QueuedCheckInPayload): Promise<number> {
    const clientId = buildClientId(payload)

    if (await isDexieAvailable()) {
      const existing = await offlineDb.offlineQueue
        .where('clientId')
        .equals(clientId)
        .filter((i) => i.path === CHECK_IN_PATH && i.status !== 'SUCCESS')
        .first()
      if (existing?.id != null) {
        return existing.id
      }
      const id = await offlineDb.offlineQueue.add({
        clientId,
        method: 'POST',
        path: CHECK_IN_PATH,
        body: toBody(payload),
        version: null,
        status: 'PENDING',
        retryCount: 0,
        errorMessage: null,
        createdAt: new Date().toISOString(),
        syncedAt: null,
      })
      return id as number
    }

    throw new Error('IndexedDB (Dexie) が利用できません。オフラインキューを使用できません。')
  }

  /** キュー内の未送信項目を取得する（PENDING / FAILED）。 */
  async function getPending(): Promise<OfflineQueueItem[]> {
    if (!(await isDexieAvailable())) return []
    const items = await offlineDb.offlineQueue
      .where('status')
      .anyOf(['PENDING', 'FAILED'])
      .toArray()
    return items.filter((i) => i.path === CHECK_IN_PATH)
  }

  /**
   * 未送信項目を順次送信する。成功した項目はキューから削除する。
   * 1 件でも失敗したらそこで break（次回 flush で再試行）。
   *
   * @param sender 実際の送信関数（通常は {@code useJobCheckInApi#recordCheckIn}）。
   *               送信成功時は {@code CheckInResponse}、失敗時は例外を投げる契約。
   */
  async function flushAll(
    sender: (payload: QueuedCheckInPayload) => Promise<CheckInResponse>,
  ): Promise<FlushResult[]> {
    const pending = await getPending()
    const results: FlushResult[] = []
    for (const item of pending) {
      if (item.id == null) continue
      try {
        const payload = fromBody(item.body)
        // オフラインから送る印を BE 側に伝える。
        payload.offlineSubmitted = true
        const res = await sender(payload)
        results.push({ queueId: item.id, response: res })
        await offlineDb.offlineQueue.delete(item.id)
      }
      catch (e) {
        results.push({ queueId: item.id, response: null, error: e })
        break
      }
    }
    return results
  }

  /** テスト／初期化用: キューを空にする（F11.1 useOfflineQueue と同様）。 */
  async function clearAll(): Promise<void> {
    if (!(await isDexieAvailable())) return
    // path はインデックス対象外なので toArray() で全件取得して filter する。
    const all = await offlineDb.offlineQueue.toArray()
    for (const i of all) {
      if (i.path === CHECK_IN_PATH && i.id != null) {
        await offlineDb.offlineQueue.delete(i.id)
      }
    }
  }

  /** キュー件数。 */
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
// ペイロード <-> Dexie body 変換
// ============================================================

function toBody(p: QueuedCheckInPayload): Record<string, unknown> {
  return {
    contractId: p.contractId,
    token: p.token ?? null,
    shortCode: p.shortCode ?? null,
    type: p.type,
    scannedAt: p.scannedAt,
    offlineSubmitted: p.offlineSubmitted ?? false,
    manualCodeFallback: p.manualCodeFallback ?? false,
    geoLat: p.geoLat ?? null,
    geoLng: p.geoLng ?? null,
    geoAccuracy: p.geoAccuracy ?? null,
    clientUserAgent: p.clientUserAgent ?? null,
  }
}

export function fromBody(body: Record<string, unknown>): QueuedCheckInPayload {
  return {
    contractId: Number(body.contractId),
    token: (body.token as string | null) ?? null,
    shortCode: (body.shortCode as string | null) ?? null,
    type: body.type as JobCheckInType,
    scannedAt: String(body.scannedAt),
    offlineSubmitted: Boolean(body.offlineSubmitted),
    manualCodeFallback: Boolean(body.manualCodeFallback),
    geoLat: (body.geoLat as number | null) ?? null,
    geoLng: (body.geoLng as number | null) ?? null,
    geoAccuracy: (body.geoAccuracy as number | null) ?? null,
    clientUserAgent: (body.clientUserAgent as string | null) ?? null,
  }
}
