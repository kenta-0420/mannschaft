import type {
  ReportResponse,
  ResolveReportRequest,
  EscalateRequest,
  BulkResolveRequest,
  ReportStatsResponse,
  ReportActionResponse,
  InternalNoteResponse,
  CreateInternalNoteRequest,
  FeedbackResponse,
  FeedbackRespondRequest,
  FeedbackStatusRequest,
  AdminNotificationStatsResponse,
  SealResponse,
  ActionTemplateResponse,
  CreateActionTemplateRequest,
  FormPresetResponse,
  CreateFormPresetRequest,
  ReviewReReviewRequest,
  UserViolationHistoryResponse,
} from '~/types/admin-report'

const BASE = '/api/v1/admin'

export function useAdminReportApi() {
  const api = useApi()

  // ===== Reports =====
  async function getReports(params?: { page?: number; size?: number; status?: string }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    if (params?.status) query.set('status', params.status)
    return api<{
      data: ReportResponse[]
      meta: { total: number; page: number; size: number; totalPages: number }
    }>(`${BASE}/moderation/reports?${query}`)
  }

  async function getReport(id: number) {
    return api<{ data: ReportResponse }>(`${BASE}/moderation/reports/${id}`)
  }

  async function reviewReport(id: number) {
    return api(`${BASE}/moderation/reports/${id}/review`, { method: 'PATCH' })
  }

  async function resolveReport(id: number, body: ResolveReportRequest) {
    return api(`${BASE}/reports/${id}/resolve`, { method: 'PATCH', body })
  }

  async function dismissReport(id: number) {
    return api(`${BASE}/reports/${id}/dismiss`, { method: 'PATCH' })
  }

  async function escalateReport(id: number, body: EscalateRequest) {
    return api(`${BASE}/reports/${id}/escalate`, { method: 'POST', body })
  }

  async function reopenReport(id: number) {
    return api(`${BASE}/reports/${id}/reopen`, { method: 'PATCH' })
  }

  async function hideContent(id: number) {
    return api(`${BASE}/reports/${id}/hide-content`, { method: 'PATCH' })
  }

  async function unhideContent(id: number) {
    return api(`${BASE}/reports/${id}/unhide-content`, { method: 'PATCH' })
  }

  async function restoreContent(id: number) {
    return api(`${BASE}/reports/${id}/restore-content`, { method: 'PATCH' })
  }

  async function restrictReporting(userId: number) {
    return api(`${BASE}/reports/users/${userId}/restrict-reporting`, { method: 'PATCH' })
  }

  async function getViolationHistory(userId: number) {
    return api<{ data: UserViolationHistoryResponse }>(
      `${BASE}/reports/users/${userId}/violation-history`,
    )
  }

  async function getReportActions(id: number) {
    return api<{ data: ReportActionResponse[] }>(`${BASE}/reports/${id}/actions`)
  }

  // ===== Report Notes =====
  async function getReportNotes(id: number) {
    return api<{ data: InternalNoteResponse[] }>(`${BASE}/reports/${id}/notes`)
  }

  async function createReportNote(id: number, body: CreateInternalNoteRequest) {
    return api<{ data: InternalNoteResponse }>(`${BASE}/reports/${id}/notes`, {
      method: 'POST',
      body,
    })
  }

  // ===== Bulk Operations =====
  async function bulkResolveReports(body: BulkResolveRequest) {
    return api<{ data: Record<string, number> }>(`${BASE}/reports/bulk-resolve`, {
      method: 'POST',
      body,
    })
  }

  // ===== Report Stats =====
  async function getReportStats() {
    return api<{ data: ReportStatsResponse }>(`${BASE}/reports/stats`)
  }

  // ===== Feedbacks =====
  async function getFeedbacks(params?: { page?: number; size?: number; status?: string }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    if (params?.status) query.set('status', params.status)
    return api<{ data: FeedbackResponse[] }>(`${BASE}/feedbacks?${query}`)
  }

  async function respondFeedback(id: number, body: FeedbackRespondRequest) {
    return api<{ data: FeedbackResponse }>(`${BASE}/feedbacks/${id}/respond`, {
      method: 'PATCH',
      body,
    })
  }

  async function updateFeedbackStatus(id: number, body: FeedbackStatusRequest) {
    return api<{ data: FeedbackResponse }>(`${BASE}/feedbacks/${id}/status`, {
      method: 'PATCH',
      body,
    })
  }

  // ===== Notification Stats =====
  async function getNotificationStats() {
    return api<{ data: AdminNotificationStatsResponse }>(`${BASE}/notifications/stats`)
  }

  // ===== Seals =====
  async function getSeals() {
    return api<{ data: SealResponse[] }>(`${BASE}/seals`)
  }

  async function regenerateSeals() {
    return api<{ data: Record<string, unknown> }>(`${BASE}/seals/regenerate`, { method: 'POST' })
  }

  // ===== Stripe =====
  async function reconcilePayment(paymentId: string) {
    return api<{ data: Record<string, unknown> }>(`${BASE}/stripe/reconcile/${paymentId}`, {
      method: 'POST',
    })
  }

  // ===== Action Templates =====
  async function getActionTemplates() {
    return api<{ data: ActionTemplateResponse[] }>(`${BASE}/action-templates`)
  }

  async function createActionTemplate(body: CreateActionTemplateRequest) {
    return api<{ data: ActionTemplateResponse }>(`${BASE}/action-templates`, {
      method: 'POST',
      body,
    })
  }

  async function updateActionTemplate(id: number, body: Partial<CreateActionTemplateRequest>) {
    return api<{ data: ActionTemplateResponse }>(`${BASE}/action-templates/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteActionTemplate(id: number) {
    return api(`${BASE}/action-templates/${id}`, { method: 'DELETE' })
  }

  // ===== Form Presets =====
  async function getFormPresets() {
    return api<{ data: FormPresetResponse[] }>(`${BASE}/form-presets`)
  }

  async function createFormPreset(body: CreateFormPresetRequest) {
    return api<{ data: FormPresetResponse }>(`${BASE}/form-presets`, { method: 'POST', body })
  }

  async function getFormPreset(presetId: number) {
    return api<{ data: FormPresetResponse }>(`${BASE}/form-presets/${presetId}`)
  }

  async function updateFormPreset(presetId: number, body: Partial<CreateFormPresetRequest>) {
    return api<{ data: FormPresetResponse }>(`${BASE}/form-presets/${presetId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteFormPreset(presetId: number) {
    return api(`${BASE}/form-presets/${presetId}`, { method: 'DELETE' })
  }

  // ===== Warning Re-reviews =====
  async function reviewWarningReReview(id: number, body: ReviewReReviewRequest) {
    return api(`${BASE}/warning-re-reviews/${id}/review`, { method: 'PATCH', body })
  }

  // ===== User Violations =====
  async function getUserViolations(userId: number) {
    return api<{ data: UserViolationHistoryResponse }>(`${BASE}/users/${userId}/violations`)
  }

  return {
    // Reports
    getReports,
    getReport,
    reviewReport,
    resolveReport,
    dismissReport,
    escalateReport,
    reopenReport,
    hideContent,
    unhideContent,
    restoreContent,
    restrictReporting,
    getViolationHistory,
    getReportActions,
    getReportNotes,
    createReportNote,
    bulkResolveReports,
    getReportStats,
    // Feedbacks
    getFeedbacks,
    respondFeedback,
    updateFeedbackStatus,
    // Notification Stats
    getNotificationStats,
    // Seals
    getSeals,
    regenerateSeals,
    // Stripe
    reconcilePayment,
    // Action Templates
    getActionTemplates,
    createActionTemplate,
    updateActionTemplate,
    deleteActionTemplate,
    // Form Presets
    getFormPresets,
    createFormPreset,
    getFormPreset,
    updateFormPreset,
    deleteFormPreset,
    // Warning Re-reviews
    reviewWarningReReview,
    // User Violations
    getUserViolations,
  }
}
