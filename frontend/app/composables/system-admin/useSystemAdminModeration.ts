import type { PageMeta } from '~/types/api'
import type {
  ModerationDashboardResponse,
  ModerationSettingsResponse,
  ModerationTemplateResponse,
  CreateModerationTemplateRequest,
  AffiliateConfigResponse,
  CreateAffiliateConfigRequest,
  TournamentPresetResponse,
  SafetyPresetResponse,
  SystemAdminSafetyTemplateResponse,
  WarningReReviewResponse,
  ReviewReReviewRequest,
  YabaiUnflagResponse,
  ReviewUnflagRequest,
  UserViolationHistoryResponse,
} from '~/types/system-admin'

const BASE = '/api/v1/system-admin'

/**
 * システム管理者向けモデレーション・運営設定 API。
 * 取り扱う対象: モデレーション / ユーザー違反 / 警告再審査 / yabai 解除申請 / アフィリエイト設定 / 大会プリセット / 安否確認プリセット&テンプレート。
 */
export function useSystemAdminModeration() {
  const api = useApi()

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
    return api<{ data: SystemAdminSafetyTemplateResponse[] }>(`${BASE}/safety-checks/templates`)
  }

  async function createSafetyCheckTemplate(body: Record<string, unknown>) {
    return api<{ data: SystemAdminSafetyTemplateResponse }>(`${BASE}/safety-checks/templates`, {
      method: 'POST',
      body,
    })
  }

  async function updateSafetyCheckTemplate(id: number, body: Record<string, unknown>) {
    return api<{ data: SystemAdminSafetyTemplateResponse }>(`${BASE}/safety-checks/templates/${id}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteSafetyCheckTemplate(id: number) {
    return api(`${BASE}/safety-checks/templates/${id}`, { method: 'DELETE' })
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

  return {
    getModerationDashboard,
    getModerationSettings,
    getModerationSettingsHistory,
    updateModerationSetting,
    createModerationTemplate,
    updateModerationTemplate,
    deleteModerationTemplate,
    getAffiliateConfigs,
    createAffiliateConfig,
    updateAffiliateConfig,
    deleteAffiliateConfig,
    toggleAffiliateConfig,
    getTournamentPresets,
    createTournamentPreset,
    getTournamentPreset,
    updateTournamentPreset,
    deleteTournamentPreset,
    getSafetyCheckPresets,
    createSafetyCheckPreset,
    updateSafetyCheckPreset,
    deleteSafetyCheckPreset,
    getSafetyCheckTemplates,
    createSafetyCheckTemplate,
    updateSafetyCheckTemplate,
    deleteSafetyCheckTemplate,
    getUserViolations,
    unflagYabaiUser,
    getWarningReReviews,
    reviewWarningReReview,
    escalateWarningReReview,
    getUnflagRequests,
    reviewUnflagRequest,
  }
}
