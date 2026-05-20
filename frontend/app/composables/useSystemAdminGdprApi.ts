import type {
  GdprPurgeStatusQuery,
  GdprPurgeStatusPage,
  GdprPurgeSummaryData,
  GdprPurgeStatusRow,
} from '~/types/system-admin'

/**
 * Phase E — システム管理者向け GDPR パージ状況 API クライアント。
 *
 * バックエンド: {@code /api/v1/system-admin/gdpr/purge-status} 配下（Phase E で実装済み）。
 * SYSTEM_ADMIN ロールでのみアクセス可能。
 *
 * 提供メソッド:
 *  - {@code listPurgeStatus(params)} — 一覧取得（フィルタ・ページネーション）
 *  - {@code getPurgeSummary()} — サマリー取得
 *  - {@code getUserPurgeDetail(userId)} — ユーザー詳細取得（全ドメイン）
 *  - {@code getExportUrl()} — CSV エクスポート URL
 */
const BASE = '/api/v1/system-admin/gdpr/purge-status'

export function useSystemAdminGdprApi() {
  const api = useApi()

  /**
   * GDPR パージ状況一覧を取得する。
   *
   * @param params - ステータス・ドメイン・日付範囲・ページネーション条件
   */
  async function listPurgeStatus(params: GdprPurgeStatusQuery = {}) {
    const query = new URLSearchParams()
    if (params.status) query.set('status', params.status)
    if (params.domain) query.set('domain', params.domain)
    if (params.dateFrom) query.set('dateFrom', params.dateFrom)
    if (params.dateTo) query.set('dateTo', params.dateTo)
    if (params.page !== undefined) query.set('page', String(params.page))
    if (params.size !== undefined) query.set('size', String(params.size))
    const qs = query.toString()
    const url = qs ? `${BASE}?${qs}` : BASE
    return api<{ data: GdprPurgeStatusPage }>(url)
  }

  /**
   * GDPR パージ状況サマリーを取得する。
   */
  async function getPurgeSummary() {
    return api<{ data: GdprPurgeSummaryData }>(`${BASE}/summary`)
  }

  /**
   * 指定ユーザーの全ドメインパージ状況を取得する。
   *
   * @param userId - 対象ユーザーの ID
   */
  async function getUserPurgeDetail(userId: number) {
    return api<{ data: GdprPurgeStatusRow[] }>(`${BASE}/${encodeURIComponent(userId)}`)
  }

  /**
   * CSV エクスポート URL を返す。
   *
   * フロントエンドからは {@code window.location.href} または {@code <a href=...>} で
   * ダウンロードをトリガーする。
   */
  function getExportUrl(): string {
    return `${BASE}/export.csv`
  }

  return {
    listPurgeStatus,
    getPurgeSummary,
    getUserPurgeDetail,
    getExportUrl,
  }
}
