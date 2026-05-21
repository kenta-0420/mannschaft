/**
 * F09.18 Phase 18-d — システム管理者向けメール送信キュー管理 API クライアント。
 *
 * バックエンド: {@code /api/v1/system-admin/email-outbox} 配下（Phase 18-d で実装済み）。
 * SYSTEM_ADMIN ロールでのみアクセス可能。
 *
 * 提供メソッド:
 *  - {@code fetchList(params)} — 一覧取得（フィルタ・ページネーション）
 *  - {@code fetchMetrics()} — メトリクス取得
 *  - {@code fetchDetail(id)} — 詳細取得（PII 復号済み）
 *  - {@code retryDeadLetter(id)} — DEAD_LETTER → PENDING（204）
 *  - {@code cancelPending(id)} — PENDING → CANCELLED（204）
 */

const BASE = '/api/v1/system-admin/email-outbox'

export type EmailOutboxStatus =
  | 'PENDING'
  | 'SENDING'
  | 'SENT'
  | 'FAILED'
  | 'DEAD_LETTER'
  | 'CANCELLED'

export interface EmailOutboxSummary {
  id: string
  status: EmailOutboxStatus
  templateKind: string
  sourceDomain: string
  sourceEventId: string | null
  locale: string
  retryCount: number
  createdAt: string
  nextAttemptAt: string | null
  sentAt: string | null
  lastError: string | null
}

export interface EmailOutboxDetail extends EmailOutboxSummary {
  toAddress: string
  payloadVars: Record<string, string>
  sesMessageId: string | null
  bodyPurgedAt: string | null
}

export interface EmailOutboxMetrics {
  queueDepthPending: number
  queueDepthSending: number
  queueDepthDeadLetter: number
  queueDepthFailed: number
  queueDepthCancelled: number
  successRate24h: number | null
  oldestPendingAgeSeconds: number | null
}

export interface EmailOutboxListParams {
  status?: string
  sourceDomain?: string
  fromDate?: string
  toDate?: string
  page?: number
  size?: number
}

export interface PageMeta {
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface PagedResponse<T> {
  data: T[]
  meta: PageMeta
}

export function useEmailOutboxAdminApi() {
  const api = useApi()

  /**
   * メール送信キュー一覧を取得する。
   *
   * @param params - ステータス・送信元ドメイン・日付範囲・ページネーション条件
   */
  async function fetchList(
    params: EmailOutboxListParams = {},
  ): Promise<PagedResponse<EmailOutboxSummary>> {
    const query = new URLSearchParams()
    if (params.status) query.set('status', params.status)
    if (params.sourceDomain) query.set('sourceDomain', params.sourceDomain)
    if (params.fromDate) query.set('fromDate', params.fromDate)
    if (params.toDate) query.set('toDate', params.toDate)
    if (params.page !== undefined) query.set('page', String(params.page))
    if (params.size !== undefined) query.set('size', String(params.size))
    const qs = query.toString()
    const url = qs ? `${BASE}?${qs}` : BASE
    const res = await api<PagedResponse<EmailOutboxSummary>>(url)
    return res
  }

  /**
   * メール送信キューのメトリクスを取得する。
   */
  async function fetchMetrics(): Promise<{ data: EmailOutboxMetrics }> {
    return api<{ data: EmailOutboxMetrics }>(`${BASE}/metrics`)
  }

  /**
   * 指定 ID のメール送信キュー詳細を取得する（PII 復号済み）。
   *
   * @param id - 対象レコードの UUID
   */
  async function fetchDetail(id: string): Promise<{ data: EmailOutboxDetail }> {
    return api<{ data: EmailOutboxDetail }>(`${BASE}/${encodeURIComponent(id)}`)
  }

  /**
   * DEAD_LETTER 状態のレコードを再試行キューに戻す。
   *
   * @param id - 対象レコードの UUID
   * @throws 404 Not Found / 409 Conflict
   */
  async function retryDeadLetter(id: string): Promise<void> {
    await api(`${BASE}/${encodeURIComponent(id)}/retry`, { method: 'POST' })
  }

  /**
   * PENDING 状態のレコードをキャンセルする。
   *
   * @param id - 対象レコードの UUID
   * @throws 404 Not Found / 409 Conflict
   */
  async function cancelPending(id: string): Promise<void> {
    await api(`${BASE}/${encodeURIComponent(id)}/cancel`, { method: 'POST' })
  }

  return {
    fetchList,
    fetchMetrics,
    fetchDetail,
    retryDeadLetter,
    cancelPending,
  }
}
