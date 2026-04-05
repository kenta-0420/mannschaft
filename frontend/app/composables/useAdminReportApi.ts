export function useAdminReportApi() {
  const api = useApi()

  // === 権限グループ管理 ===
  async function getPermissionGroups() {
    return api<{
      data: Array<{
        id: number
        name: string
        description: string | null
        permissions: string[]
        createdAt: string
      }>
    }>('/api/v1/admin/permission-groups')
  }

  async function createPermissionGroup(body: {
    name: string
    description?: string
    permissions: string[]
  }) {
    return api<{
      data: {
        id: number
        name: string
        description: string | null
        permissions: string[]
        createdAt: string
      }
    }>('/api/v1/admin/permission-groups', { method: 'POST', body })
  }

  async function updatePermissionGroup(
    id: number,
    body: { name?: string; description?: string; permissions?: string[] },
  ) {
    return api<{
      data: {
        id: number
        name: string
        description: string | null
        permissions: string[]
        createdAt: string
      }
    }>(`/api/v1/admin/permission-groups/${id}`, { method: 'PUT', body })
  }

  async function deletePermissionGroup(id: number) {
    return api(`/api/v1/admin/permission-groups/${id}`, { method: 'DELETE' })
  }

  async function assignPermissionGroup(groupId: number, userId: number) {
    return api(`/api/v1/admin/permission-groups/${groupId}/assign/${userId}`, { method: 'PATCH' })
  }

  async function unassignPermissionGroup(groupId: number, userId: number) {
    return api(`/api/v1/admin/permission-groups/${groupId}/unassign/${userId}`, { method: 'PATCH' })
  }

  async function duplicatePermissionGroup(groupId: number) {
    return api<{ data: string }>(`/api/v1/admin/permission-groups/${groupId}/duplicate`, {
      method: 'POST',
    })
  }

  // === Admin Dashboard (F10.1) ===
  async function getAdminDashboard() {
    return api<{ data: unknown }>('/api/v1/admin/dashboard')
  }

  async function getAdminDashboardUsers(params?: Record<string, unknown>) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    return api<{ data: unknown[] }>(`/api/v1/admin/dashboard/users?${q}`)
  }

  async function updateUserRole(userId: number, body: { role: string }) {
    return api(`/api/v1/admin/dashboard/users/${userId}/role`, { method: 'PATCH', body })
  }

  // === Admin Feedback (F10.1) ===
  async function getFeedbacks(params?: Record<string, unknown>) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    return api<{ data: unknown[] }>(`/api/v1/admin/feedbacks?${q}`)
  }

  async function respondToFeedback(id: number, body: Record<string, unknown>) {
    return api(`/api/v1/admin/feedbacks/${id}/respond`, { method: 'PATCH', body })
  }

  async function updateFeedbackStatus(id: number, body: { status: string }) {
    return api(`/api/v1/admin/feedbacks/${id}/status`, { method: 'PATCH', body })
  }

  // === Admin Action Templates (F10.1) ===
  async function getActionTemplates() {
    return api<{ data: unknown[] }>('/api/v1/admin/action-templates')
  }

  async function createActionTemplate(body: Record<string, unknown>) {
    return api<{ data: unknown }>('/api/v1/admin/action-templates', { method: 'POST', body })
  }

  async function updateActionTemplate(id: number, body: Record<string, unknown>) {
    return api('/api/v1/admin/action-templates/' + id, { method: 'PUT', body })
  }

  async function deleteActionTemplate(id: number) {
    return api(`/api/v1/admin/action-templates/${id}`, { method: 'DELETE' })
  }

  // === Report Management (F10.1) ===
  async function bulkResolveReports(body: Record<string, unknown>) {
    return api('/api/v1/admin/reports/bulk-resolve', { method: 'POST', body })
  }

  async function getReportStats() {
    return api<{ data: unknown }>('/api/v1/admin/reports/stats')
  }

  async function restrictReporting(userId: number, body: Record<string, unknown>) {
    return api(`/api/v1/admin/reports/users/${userId}/restrict-reporting`, {
      method: 'PATCH',
      body,
    })
  }

  async function getViolationHistory(userId: number) {
    return api<{ data: unknown[] }>(`/api/v1/admin/reports/users/${userId}/violation-history`)
  }

  async function getReportActions(reportId: number) {
    return api<{ data: unknown[] }>(`/api/v1/admin/reports/${reportId}/actions`)
  }

  async function dismissReport(reportId: number, body?: Record<string, unknown>) {
    return api(`/api/v1/admin/reports/${reportId}/dismiss`, { method: 'PATCH', body })
  }

  async function escalateReport(reportId: number, body?: Record<string, unknown>) {
    return api(`/api/v1/admin/reports/${reportId}/escalate`, { method: 'POST', body })
  }

  async function reopenReport(reportId: number) {
    return api(`/api/v1/admin/reports/${reportId}/reopen`, { method: 'PATCH' })
  }

  async function resolveReport(reportId: number, body: Record<string, unknown>) {
    return api(`/api/v1/admin/reports/${reportId}/resolve`, { method: 'PATCH', body })
  }

  async function restoreContent(reportId: number) {
    return api(`/api/v1/admin/reports/${reportId}/restore-content`, { method: 'PATCH' })
  }

  async function startReview(reportId: number) {
    return api(`/api/v1/admin/reports/${reportId}/review`, { method: 'PATCH' })
  }

  return {
    getPermissionGroups,
    createPermissionGroup,
    updatePermissionGroup,
    deletePermissionGroup,
    assignPermissionGroup,
    unassignPermissionGroup,
    duplicatePermissionGroup,
    getAdminDashboard,
    getAdminDashboardUsers,
    updateUserRole,
    getFeedbacks,
    respondToFeedback,
    updateFeedbackStatus,
    getActionTemplates,
    createActionTemplate,
    updateActionTemplate,
    deleteActionTemplate,
    bulkResolveReports,
    getReportStats,
    restrictReporting,
    getViolationHistory,
    getReportActions,
    dismissReport,
    escalateReport,
    reopenReport,
    resolveReport,
    restoreContent,
    startReview,
  }
}
