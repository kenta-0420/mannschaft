import type {
  DigestConfigResponse,
  DigestConfigRequest,
  DigestGenerateRequest,
  DigestGenerateResponse,
  DigestDetailResponse,
  DigestSummaryResponse,
  DigestEditRequest,
  DigestPublishRequest,
  DigestPublishResponse,
  DigestRegenerateRequest,
} from '~/types/timeline-digest'
import type { CursorMeta } from '~/types/api'

/** {@link buildDigestPeriod} が必要とする useDatetime のヘルパ群。 */
export interface DigestPeriodDatetimeHelpers {
  buildOffsetDateTimeStr: (date: Date | null, time?: string) => string | null
  buildDayStartStr: (ymd: string) => string
  buildDayEndStr: (ymd: string) => string
}

/**
 * ダイジェスト生成の対象期間を BE の送信形へ組み立てる。
 *
 * BE は `@NotNull LocalDateTime periodStart / periodEnd` で、**date-only は 400 になる**
 * （かつて `toISOString().slice(0, 10)` を送っていて生成できなかった）。
 * また `toISOString()` は UTC 基準のため、JST の深夜は暦日が前日にずれる。
 *
 * 期間の比較は BE 側で**両端 inclusive**（`!isBefore(start) && !isAfter(end)`）なので、
 * 終端はその日の 23:59:59 まで含める。翌日 00:00:00 にすると翌日ちょうどの投稿を巻き込む。
 */
export function buildDigestPeriod(
  periodStart: Date,
  periodEnd: Date,
  dt: DigestPeriodDatetimeHelpers,
): { periodStart: string; periodEnd: string } {
  // ユーザーTZでの暦日を取り出す（オフセット付き ISO の先頭 10 文字）。
  const toUserYmd = (date: Date) => (dt.buildOffsetDateTimeStr(date, '00:00') ?? '').slice(0, 10)
  return {
    periodStart: dt.buildDayStartStr(toUserYmd(periodStart)),
    periodEnd: dt.buildDayEndStr(toUserYmd(periodEnd)),
  }
}

export function useTimelineDigestApi() {
  const api = useApi()
  const base = '/api/v1/timeline-digest'

  async function listDigests(cursor?: string, limit: number = 20) {
    const query = new URLSearchParams()
    if (cursor) query.set('cursor', cursor)
    query.set('limit', String(limit))
    return api<{ data: DigestSummaryResponse[]; meta: CursorMeta }>(`${base}?${query}`)
  }

  async function getDigest(digestId: number) {
    return api<{ data: DigestDetailResponse }>(`${base}/${digestId}`)
  }

  async function deleteDigest(digestId: number) {
    return api(`${base}/${digestId}`, { method: 'DELETE' })
  }

  async function editDigest(digestId: number, body: DigestEditRequest) {
    return api<{ data: DigestDetailResponse }>(`${base}/${digestId}`, { method: 'PATCH', body })
  }

  async function generateDigest(body: DigestGenerateRequest) {
    return api<{ data: DigestGenerateResponse }>(`${base}/generate`, { method: 'POST', body })
  }

  async function publishDigest(digestId: number, body: DigestPublishRequest) {
    return api<{ data: DigestPublishResponse }>(`${base}/${digestId}/publish`, {
      method: 'POST',
      body,
    })
  }

  async function regenerateDigest(digestId: number, body?: DigestRegenerateRequest) {
    return api(`${base}/${digestId}/regenerate`, { method: 'POST', body: body ?? {} })
  }

  // === Config ===
  async function getConfig() {
    return api<{ data: DigestConfigResponse }>(`${base}/config`)
  }

  async function updateConfig(body: DigestConfigRequest) {
    return api(`${base}/config`, { method: 'PUT', body })
  }

  async function deleteConfig() {
    return api(`${base}/config`, { method: 'DELETE' })
  }

  return {
    listDigests,
    getDigest,
    deleteDigest,
    editDigest,
    generateDigest,
    publishDigest,
    regenerateDigest,
    getConfig,
    updateConfig,
    deleteConfig,
  }
}
