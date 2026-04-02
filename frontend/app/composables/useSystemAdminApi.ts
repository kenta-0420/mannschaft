import type {
  AnnouncementResponse,
  CreateAnnouncementRequest,
  UpdateAnnouncementRequest,
  FeatureFlagResponse,
  MaintenanceScheduleResponse,
  CreateMaintenanceScheduleRequest,
  UpdateMaintenanceScheduleRequest,
  SystemTemplateResponse,
  CreateTemplateRequest,
  ModuleResponse,
  BatchJobLogResponse,
  NotificationStatsResponse,
  ModerationDashboardResponse,
  ModerationSettingsResponse,
  ModerationTemplateResponse,
  CreateModerationTemplateRequest,
  AffiliateConfigResponse,
  CreateAffiliateConfigRequest,
  TournamentPresetResponse,
  SafetyPresetResponse,
  SafetyTemplateResponse,
  WallpaperResponse,
  CreateWallpaperRequest,
  ActivityTemplatePresetResponse,
  WarningReReviewResponse,
  ReviewReReviewRequest,
  YabaiUnflagResponse,
  ReviewUnflagRequest,
  UserViolationHistoryResponse,
  OrganizationEntity,
  PageMeta,
  ErrorReportResponse,
  ErrorReportStatsResponse,
} from '~/types/system-admin'

const BASE = '/api/v1/system-admin'

export function useSystemAdminApi() {
  const api = useApi()

  // ===== Announcements =====
  async function getAnnouncements(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: AnnouncementResponse[]; meta: PageMeta }>(`${BASE}/announcements?${query}`)
  }

  async function createAnnouncement(body: CreateAnnouncementRequest) {
    return api<{ data: AnnouncementResponse }>(`${BASE}/announcements`, { method: 'POST', body })
  }

  async function updateAnnouncement(id: number, body: UpdateAnnouncementRequest) {
    return api<{ data: AnnouncementResponse }>(`${BASE}/announcements/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteAnnouncement(id: number) {
    return api(`${BASE}/announcements/${id}`, { method: 'DELETE' })
  }

  async function publishAnnouncement(id: number) {
    return api(`${BASE}/announcements/${id}/publish`, { method: 'PATCH' })
  }

  // ===== Feature Flags =====
  async function getFeatureFlags() {
    return api<{ data: FeatureFlagResponse[] }>(`${BASE}/feature-flags`)
  }

  async function updateFeatureFlag(
    flagKey: string,
    body: { isEnabled: boolean; description?: string },
  ) {
    return api<{ data: FeatureFlagResponse }>(`${BASE}/feature-flags/${flagKey}`, {
      method: 'PUT',
      body,
    })
  }

  // ===== Maintenance Schedules =====
  async function getMaintenanceSchedules() {
    return api<{ data: MaintenanceScheduleResponse[] }>(`${BASE}/maintenance-schedules`)
  }

  async function getMaintenanceSchedule(id: number) {
    return api<{ data: MaintenanceScheduleResponse }>(`${BASE}/maintenance-schedules/${id}`)
  }

  async function createMaintenanceSchedule(body: CreateMaintenanceScheduleRequest) {
    return api<{ data: MaintenanceScheduleResponse }>(`${BASE}/maintenance-schedules`, {
      method: 'POST',
      body,
    })
  }

  async function updateMaintenanceSchedule(id: number, body: UpdateMaintenanceScheduleRequest) {
    return api<{ data: MaintenanceScheduleResponse }>(`${BASE}/maintenance-schedules/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteMaintenanceSchedule(id: number) {
    return api(`${BASE}/maintenance-schedules/${id}`, { method: 'DELETE' })
  }

  async function activateMaintenanceSchedule(id: number) {
    return api(`${BASE}/maintenance-schedules/${id}/activate`, { method: 'POST' })
  }

  async function completeMaintenanceSchedule(id: number) {
    return api(`${BASE}/maintenance-schedules/${id}/complete`, { method: 'PATCH' })
  }

  // ===== Templates =====
  async function createTemplate(body: CreateTemplateRequest) {
    return api<{ data: SystemTemplateResponse }>(`${BASE}/templates`, { method: 'POST', body })
  }

  async function updateTemplate(id: number, body: Record<string, unknown>) {
    return api<{ data: SystemTemplateResponse }>(`${BASE}/templates/${id}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteTemplate(id: number) {
    return api(`${BASE}/templates/${id}`, { method: 'DELETE' })
  }

  // ===== Modules =====
  async function getModules() {
    return api<{ data: ModuleResponse[] }>(`${BASE}/modules`)
  }

  async function getModule(id: number) {
    return api<{ data: ModuleResponse }>(`${BASE}/modules/${id}`)
  }

  async function updateModuleLevelAvailability(id: number, body: Record<string, unknown>) {
    return api(`${BASE}/modules/${id}/level-availability`, { method: 'PATCH', body })
  }

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

  // ===== Batch Logs =====
  async function getBatchLogs(params?: { page?: number; size?: number; jobName?: string }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    if (params?.jobName) query.set('jobName', params.jobName)
    return api<{ data: BatchJobLogResponse[] }>(`${BASE}/batch-logs?${query}`)
  }

  // ===== Notification Stats =====
  async function getNotificationStats(params?: { from?: string; to?: string; channel?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    if (params?.channel) query.set('channel', params.channel)
    return api<{ data: NotificationStatsResponse[] }>(`${BASE}/notification-stats?${query}`)
  }

  // ===== Moderation =====
  async function getModerationDashboard() {
    return api<{ data: ModerationDashboardResponse }>(`${BASE}/moderation/dashboard`)
  }

  async function getModerationSettings() {
    return api<{ data: ModerationSettingsResponse[] }>(`${BASE}/moderation/settings`)
  }

  async function getModerationSettingsHistory() {
    return api<{ data: Record<string, unknown>[] }>(`${BASE}/moderation/settings/history`)
  }

  async function updateModerationSetting(key: string, body: { settingValue: string }) {
    return api(`${BASE}/moderation/settings/${key}`, { method: 'PUT', body })
  }

  async function createModerationTemplate(body: CreateModerationTemplateRequest) {
    return api<{ data: ModerationTemplateResponse }>(`${BASE}/moderation/templates`, {
      method: 'POST',
      body,
    })
  }

  async function updateModerationTemplate(
    id: number,
    body: Partial<CreateModerationTemplateRequest>,
  ) {
    return api<{ data: ModerationTemplateResponse }>(`${BASE}/moderation/templates/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteModerationTemplate(id: number) {
    return api(`${BASE}/moderation/templates/${id}`, { method: 'DELETE' })
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

  // ===== Affiliate Configs =====
  async function getAffiliateConfigs() {
    return api<{ data: AffiliateConfigResponse[] }>(`${BASE}/affiliate-configs`)
  }

  async function createAffiliateConfig(body: CreateAffiliateConfigRequest) {
    return api<{ data: AffiliateConfigResponse }>(`${BASE}/affiliate-configs`, {
      method: 'POST',
      body,
    })
  }

  async function updateAffiliateConfig(id: number, body: Partial<CreateAffiliateConfigRequest>) {
    return api<{ data: AffiliateConfigResponse }>(`${BASE}/affiliate-configs/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteAffiliateConfig(id: number) {
    return api(`${BASE}/affiliate-configs/${id}`, { method: 'DELETE' })
  }

  async function toggleAffiliateConfig(id: number) {
    return api(`${BASE}/affiliate-configs/${id}/toggle`, { method: 'PATCH' })
  }

  // ===== Tournament Presets =====
  async function getTournamentPresets() {
    return api<{ data: TournamentPresetResponse[] }>(`${BASE}/tournament-presets`)
  }

  async function createTournamentPreset(body: Record<string, unknown>) {
    return api<{ data: TournamentPresetResponse }>(`${BASE}/tournament-presets`, {
      method: 'POST',
      body,
    })
  }

  async function getTournamentPreset(id: number) {
    return api<{ data: TournamentPresetResponse }>(`${BASE}/tournament-presets/${id}`)
  }

  async function updateTournamentPreset(id: number, body: Record<string, unknown>) {
    return api<{ data: TournamentPresetResponse }>(`${BASE}/tournament-presets/${id}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteTournamentPreset(id: number) {
    return api(`${BASE}/tournament-presets/${id}`, { method: 'DELETE' })
  }

  // ===== Safety Checks =====
  async function getSafetyCheckPresets() {
    return api<{ data: SafetyPresetResponse[] }>(`${BASE}/safety-checks/presets`)
  }

  async function createSafetyCheckPreset(body: Record<string, unknown>) {
    return api<{ data: SafetyPresetResponse }>(`${BASE}/safety-checks/presets`, {
      method: 'POST',
      body,
    })
  }

  async function updateSafetyCheckPreset(id: number, body: Record<string, unknown>) {
    return api<{ data: SafetyPresetResponse }>(`${BASE}/safety-checks/presets/${id}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteSafetyCheckPreset(id: number) {
    return api(`${BASE}/safety-checks/presets/${id}`, { method: 'DELETE' })
  }

  async function getSafetyCheckTemplates() {
    return api<{ data: SafetyTemplateResponse[] }>(`${BASE}/safety-checks/templates`)
  }

  async function createSafetyCheckTemplate(body: Record<string, unknown>) {
    return api<{ data: SafetyTemplateResponse }>(`${BASE}/safety-checks/templates`, {
      method: 'POST',
      body,
    })
  }

  async function updateSafetyCheckTemplate(id: number, body: Record<string, unknown>) {
    return api<{ data: SafetyTemplateResponse }>(`${BASE}/safety-checks/templates/${id}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteSafetyCheckTemplate(id: number) {
    return api(`${BASE}/safety-checks/templates/${id}`, { method: 'DELETE' })
  }

  // ===== Template Wallpapers =====
  async function getTemplateWallpapers() {
    return api<{ data: WallpaperResponse[] }>(`${BASE}/template-wallpapers`)
  }

  async function createTemplateWallpaper(body: CreateWallpaperRequest) {
    return api<{ data: WallpaperResponse }>(`${BASE}/template-wallpapers`, { method: 'POST', body })
  }

  async function deleteTemplateWallpaper(id: number) {
    return api(`${BASE}/template-wallpapers/${id}`, { method: 'DELETE' })
  }

  // ===== Activity Template Presets =====
  async function getActivityTemplatePresets() {
    return api<{ data: ActivityTemplatePresetResponse[] }>(`${BASE}/activity-template-presets`)
  }

  async function createActivityTemplatePreset(body: Record<string, unknown>) {
    return api<{ data: ActivityTemplatePresetResponse }>(`${BASE}/activity-template-presets`, {
      method: 'POST',
      body,
    })
  }

  async function updateActivityTemplatePreset(id: number, body: Record<string, unknown>) {
    return api<{ data: ActivityTemplatePresetResponse }>(
      `${BASE}/activity-template-presets/${id}`,
      { method: 'PUT', body },
    )
  }

  async function deleteActivityTemplatePreset(id: number) {
    return api(`${BASE}/activity-template-presets/${id}`, { method: 'DELETE' })
  }

  // ===== Gallery =====
  async function regenerateThumbnails() {
    return api(`${BASE}/gallery/regenerate-thumbnails`, { method: 'POST' })
  }

  // ===== Timeline Digest =====
  async function getTimelineDigestUsage() {
    return api<{ data: Record<string, unknown> }>(`${BASE}/timeline-digest/usage`)
  }

  // ===== User Violations =====
  async function getUserViolations(userId: number) {
    return api<{ data: UserViolationHistoryResponse }>(`${BASE}/users/${userId}/violations`)
  }

  async function unflagYabaiUser(userId: number) {
    return api(`${BASE}/users/${userId}/yabai/unflag`, { method: 'PATCH' })
  }

  // ===== Warning Re-reviews =====
  async function getWarningReReviews(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: WarningReReviewResponse[] }>(`${BASE}/warning-re-reviews?${query}`)
  }

  async function reviewWarningReReview(id: number, body: ReviewReReviewRequest) {
    return api<{ data: WarningReReviewResponse }>(`${BASE}/warning-re-reviews/${id}/review`, {
      method: 'PATCH',
      body,
    })
  }

  async function escalateWarningReReview(id: number, body: { escalationReason?: string }) {
    return api(`${BASE}/warnings/re-reviews/${id}/escalate`, { method: 'PATCH', body })
  }

  // ===== Yabai Unflag Requests =====
  async function getUnflagRequests(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: YabaiUnflagResponse[]; meta: PageMeta }>(
      `${BASE}/yabai/unflag-requests?${query}`,
    )
  }

  async function reviewUnflagRequest(id: number, body: ReviewUnflagRequest) {
    return api<{ data: YabaiUnflagResponse }>(`${BASE}/yabai/unflag-requests/${id}/review`, {
      method: 'PATCH',
      body,
    })
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
    // Announcements
    getAnnouncements,
    createAnnouncement,
    updateAnnouncement,
    deleteAnnouncement,
    publishAnnouncement,
    // Feature Flags
    getFeatureFlags,
    updateFeatureFlag,
    // Maintenance Schedules
    getMaintenanceSchedules,
    getMaintenanceSchedule,
    createMaintenanceSchedule,
    updateMaintenanceSchedule,
    deleteMaintenanceSchedule,
    activateMaintenanceSchedule,
    completeMaintenanceSchedule,
    // Templates
    createTemplate,
    updateTemplate,
    deleteTemplate,
    // Modules
    getModules,
    getModule,
    updateModuleLevelAvailability,
    // Dashboard
    getDashboardOrganizations,
    freezeOrganization,
    unfreezeOrganization,
    getDashboardTeams,
    getDashboardUsers,
    // Batch Logs
    getBatchLogs,
    // Notification Stats
    getNotificationStats,
    // Moderation
    getModerationDashboard,
    getModerationSettings,
    getModerationSettingsHistory,
    updateModerationSetting,
    createModerationTemplate,
    updateModerationTemplate,
    deleteModerationTemplate,
    // Reports
    getReports,
    // Promotion Billing
    getPromotionBilling,
    // Affiliate Configs
    getAffiliateConfigs,
    createAffiliateConfig,
    updateAffiliateConfig,
    deleteAffiliateConfig,
    toggleAffiliateConfig,
    // Tournament Presets
    getTournamentPresets,
    createTournamentPreset,
    getTournamentPreset,
    updateTournamentPreset,
    deleteTournamentPreset,
    // Safety Checks
    getSafetyCheckPresets,
    createSafetyCheckPreset,
    updateSafetyCheckPreset,
    deleteSafetyCheckPreset,
    getSafetyCheckTemplates,
    createSafetyCheckTemplate,
    updateSafetyCheckTemplate,
    deleteSafetyCheckTemplate,
    // Template Wallpapers
    getTemplateWallpapers,
    createTemplateWallpaper,
    deleteTemplateWallpaper,
    // Activity Template Presets
    getActivityTemplatePresets,
    createActivityTemplatePreset,
    updateActivityTemplatePreset,
    deleteActivityTemplatePreset,
    // Gallery
    regenerateThumbnails,
    // Timeline Digest
    getTimelineDigestUsage,
    // User Violations
    getUserViolations,
    unflagYabaiUser,
    // Warning Re-reviews
    getWarningReReviews,
    reviewWarningReReview,
    escalateWarningReReview,
    // Yabai Unflag Requests
    getUnflagRequests,
    reviewUnflagRequest,
    // Stripe Admin
    reconcileStripePayment,
    // Error Reports (F12.5)
    getErrorReports,
    getErrorReport,
    updateErrorReport,
    bulkUpdateErrorReports,
    getErrorReportStats,
  }
}
