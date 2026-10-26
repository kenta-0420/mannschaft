import type {
  ContentReportResponse,
  UserViolation,
  ModerationAppeal,
  AuditLogResponse,
} from '~/types/moderation'

export function useModerationApi() {
  const api = useApi()

  // === Reports ===
  async function submitReport(body: Record<string, unknown>) {
    return api('/api/v1/reports', { method: 'POST', body })
  }
  async function getReports(params?: Record<string, unknown>) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    return api<{ data: ContentReportResponse[] }>(`/api/v1/admin/moderation/reports?${q}`)
  }
  async function getReport(reportId: number) {
    return api<{ data: ContentReportResponse }>(`/api/v1/admin/moderation/reports/${reportId}`)
  }
  async function reviewReport(reportId: number, body: Record<string, unknown>) {
    return api(`/api/v1/admin/moderation/reports/${reportId}/review`, { method: 'PATCH', body })
  }

  // === Violations ===
  async function getUserViolations(userId: number) {
    return api<{ data: UserViolation[] }>(`/api/v1/admin/users/${userId}/violations`)
  }
  async function issueViolation(userId: number, body: Record<string, unknown>) {
    return api(`/api/v1/admin/users/${userId}/violations`, { method: 'POST', body })
  }

  // === Appeals ===
  async function getAppeals(params?: Record<string, unknown>) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    return api<{ data: ModerationAppeal[] }>(`/api/v1/system-admin/appeals?${q}`)
  }
  async function reviewAppeal(appealId: number, body: Record<string, unknown>) {
    return api(`/api/v1/system-admin/appeals/${appealId}/review`, { method: 'PATCH', body })
  }

  // === Audit Logs ===
  async function getAuditLogs(scope: string, scopeId?: number, params?: Record<string, unknown>) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    const path =
      scope === 'admin'
        ? '/api/v1/admin/audit-logs'
        : scope === 'me'
          ? '/api/v1/users/me/audit-logs'
          : scope === 'team'
            ? `/api/v1/teams/${scopeId}/audit-logs`
            : `/api/v1/organizations/${scopeId}/audit-logs`
    return api<{ data: AuditLogResponse[] }>(`${path}?${q}`)
  }

  // === ADMIN Moderation Extensions (F10.2) ===
  async function getModerationTemplates() {
    return api<{ data: unknown[] }>('/api/v1/admin/moderation/templates')
  }

  async function hideContent(reportId: number) {
    return api(`/api/v1/admin/reports/${reportId}/hide-content`, { method: 'PATCH' })
  }

  async function unhideContent(reportId: number) {
    return api(`/api/v1/admin/reports/${reportId}/unhide-content`, { method: 'PATCH' })
  }

  async function getReportNotes(reportId: number) {
    return api<{ data: unknown[] }>(`/api/v1/admin/reports/${reportId}/notes`)
  }

  async function addReportNote(reportId: number, body: Record<string, unknown>) {
    return api(`/api/v1/admin/reports/${reportId}/notes`, { method: 'POST', body })
  }

  async function reviewWarningReReview(reReviewId: number, body: Record<string, unknown>) {
    return api(`/api/v1/admin/warning-re-reviews/${reReviewId}/review`, { method: 'PATCH', body })
  }

  return {
    submitReport,
    getReports,
    getReport,
    reviewReport,
    getUserViolations,
    issueViolation,
    getAppeals,
    reviewAppeal,
    getAuditLogs,
    getModerationTemplates,
    hideContent,
    unhideContent,
    getReportNotes,
    addReportNote,
    reviewWarningReReview,
  }
}
