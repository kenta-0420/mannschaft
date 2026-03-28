import type { ContentReportResponse, UserViolation, ModerationAppeal, AuditLogResponse } from '~/types/moderation'

export function useModerationApi() {
  const api = useApi()

  // === Reports ===
  async function submitReport(body: Record<string, unknown>) { return api('/api/v1/reports', { method: 'POST', body }) }
  async function getReports(params?: Record<string, unknown>) {
    const q = new URLSearchParams(); if (params) for (const [k, v] of Object.entries(params)) { if (v != null) q.set(k, String(v)) }
    return api<{ data: ContentReportResponse[] }>(`/api/v1/admin/reports?${q}`)
  }
  async function reviewReport(reportId: number, body: Record<string, unknown>) { return api(`/api/v1/admin/reports/${reportId}`, { method: 'PATCH', body }) }

  // === Violations ===
  async function getUserViolations(userId: number) { return api<{ data: UserViolation[] }>(`/api/v1/admin/users/${userId}/violations`) }
  async function issueViolation(userId: number, body: Record<string, unknown>) { return api(`/api/v1/admin/users/${userId}/violations`, { method: 'POST', body }) }

  // === Appeals ===
  async function getAppeals(params?: Record<string, unknown>) {
    const q = new URLSearchParams(); if (params) for (const [k, v] of Object.entries(params)) { if (v != null) q.set(k, String(v)) }
    return api<{ data: ModerationAppeal[] }>(`/api/v1/system-admin/appeals?${q}`)
  }
  async function reviewAppeal(appealId: number, body: Record<string, unknown>) { return api(`/api/v1/system-admin/appeals/${appealId}`, { method: 'PATCH', body }) }

  // === Audit Logs ===
  async function getAuditLogs(scope: string, scopeId?: number, params?: Record<string, unknown>) {
    const q = new URLSearchParams(); if (params) for (const [k, v] of Object.entries(params)) { if (v != null) q.set(k, String(v)) }
    const path = scope === 'admin' ? '/api/v1/admin/audit-logs' : scope === 'me' ? '/api/v1/users/me/audit-logs' : scope === 'team' ? `/api/v1/teams/${scopeId}/audit-logs` : `/api/v1/organizations/${scopeId}/audit-logs`
    return api<{ data: AuditLogResponse[] }>(`${path}?${q}`)
  }

  return { submitReport, getReports, reviewReport, getUserViolations, issueViolation, getAppeals, reviewAppeal, getAuditLogs }
}
