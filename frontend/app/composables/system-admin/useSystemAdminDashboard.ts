import type {
  NotificationStatsResponse,
  OrganizationEntity,
  ErrorReportResponse,
  ErrorReportStatsResponse,
} from '~/types/system-admin'

const BASE = '/api/v1/system-admin'

/**
 * システム管理者向けダッシュボード・統計・各種レポート API。
 * 取り扱う対象: 組織/チーム/ユーザーダッシュボード / 通知統計 / 通報・販促課金 / タイムラインダイジェスト / エラーレポート(F12.5) / Stripe 管理。
 */
export function useSystemAdminDashboard() {
  const api = useApi()

  // ===== Dashboard =====
  async function getDashboardOrganizations(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{
      data: { content: OrganizationEntity[]; totalElements: number; totalPages: number }
    }>(`${BASE}/dashboard/organizations?${query}`)
  }

  async function freezeOrganization(organizationId: number) {
    return api(`${BASE}/dashboard/organizations/${organizationId}/freeze`, { method: 'PATCH' })
  }

  async function unfreezeOrganization(organizationId: number) {
    return api(`${BASE}/dashboard/organizations/${organizationId}/unfreeze`, { method: 'PATCH' })
  }

  async function getDashboardTeams(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: Record<string, unknown>[] }>(`${BASE}/dashboard/teams?${query}`)
  }

  async function getDashboardUsers(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: Record<string, unknown>[] }>(`${BASE}/dashboard/users?${query}`)
  }

  // ===== Notification Stats =====
  async function getNotificationStats(params?: { from?: string; to?: string; channel?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    if (params?.channel) query.set('channel', params.channel)
    return api<{ data: NotificationStatsResponse[] }>(`${BASE}/notification-stats?${query}`)
  }

  // ===== Reports =====
  async function getReports(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: Record<string, unknown>[] }>(`${BASE}/reports?${query}`)
  }

  // ===== Promotion Billing =====
  async function getPromotionBilling(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: Record<string, unknown>[] }>(`${BASE}/promotion-billing?${query}`)
  }

  // ===== Timeline Digest =====
  async function getTimelineDigestUsage() {
    return api<{ data: Record<string, unknown> }>(`${BASE}/timeline-digest/usage`)
  }

  // === Stripe Admin ===
  async function reconcileStripePayment(paymentId: number) {
    return api(`/api/v1/admin/stripe/reconcile/${paymentId}`, { method: 'POST' })
  }

  // === Error Reports (F12.5) ===
  async function getErrorReports(params?: {
    status?: string
    severity?: string
    from?: string
    to?: string
    page?: number
    size?: number
    sort?: string
  }) {
    const query = new URLSearchParams()
    if (params) {
      for (const [k, v] of Object.entries(params)) {
        if (v !== undefined && v !== null) query.set(k, String(v))
      }
    }
    const qs = query.toString()
    return api<{
      data: ErrorReportResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`${BASE}/error-reports${qs ? `?${qs}` : ''}`)
  }

  async function getErrorReport(id: number) {
    return api<{ data: ErrorReportResponse }>(`${BASE}/error-reports/${id}`)
  }

  async function updateErrorReport(
    id: number,
    body: { status?: string; severity?: string; adminNote?: string },
  ) {
    return api<{ data: ErrorReportResponse }>(`${BASE}/error-reports/${id}`, {
      method: 'PATCH',
      body,
    })
  }

  async function bulkUpdateErrorReports(ids: number[], status: string) {
    return api(`${BASE}/error-reports/bulk`, { method: 'PATCH', body: { ids, status } })
  }

  async function getErrorReportStats() {
    return api<{ data: ErrorReportStatsResponse }>(`${BASE}/error-reports/stats`)
  }

  return {
    getDashboardOrganizations,
    freezeOrganization,
    unfreezeOrganization,
    getDashboardTeams,
    getDashboardUsers,
    getNotificationStats,
    getReports,
    getPromotionBilling,
    getTimelineDigestUsage,
    reconcileStripePayment,
    getErrorReports,
    getErrorReport,
    updateErrorReport,
    bulkUpdateErrorReports,
    getErrorReportStats,
  }
}
