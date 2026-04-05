import type {
  OnboardingTemplate,
  OnboardingProgress,
  OnboardingPreset,
  CreateTemplateRequest,
} from '~/types/onboarding'

export function useOnboardingApi() {
  const api = useApi()

  function buildScopeBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team'
      ? `/api/v1/teams/${scopeId}`
      : `/api/v1/organizations/${scopeId}`
  }

  // --- プリセットカタログ ---

  async function listPresets() {
    const res = await api<{ data: OnboardingPreset[] }>('/api/v1/onboarding/presets')
    return res.data
  }

  // --- テンプレート管理 ---

  async function listTemplates(scopeType: 'team' | 'organization', scopeId: number) {
    const base = buildScopeBase(scopeType, scopeId)
    const res = await api<{ data: OnboardingTemplate[] }>(`${base}/onboarding/templates`)
    return res.data
  }

  async function createTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: CreateTemplateRequest,
  ) {
    const base = buildScopeBase(scopeType, scopeId)
    const res = await api<{ data: OnboardingTemplate }>(`${base}/onboarding/templates`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function getTemplate(templateId: number) {
    const res = await api<{ data: OnboardingTemplate }>(
      `/api/v1/onboarding/templates/${templateId}`,
    )
    return res.data
  }

  async function updateTemplate(templateId: number, body: Partial<CreateTemplateRequest>) {
    const res = await api<{ data: OnboardingTemplate }>(
      `/api/v1/onboarding/templates/${templateId}`,
      { method: 'PUT', body },
    )
    return res.data
  }

  async function activateTemplate(templateId: number) {
    await api(`/api/v1/onboarding/templates/${templateId}/activate`, { method: 'POST' })
  }

  async function archiveTemplate(templateId: number) {
    await api(`/api/v1/onboarding/templates/${templateId}/archive`, { method: 'POST' })
  }

  async function deleteTemplate(templateId: number) {
    await api(`/api/v1/onboarding/templates/${templateId}`, { method: 'DELETE' })
  }

  async function duplicateTemplate(templateId: number) {
    const res = await api<{ data: OnboardingTemplate }>(
      `/api/v1/onboarding/templates/${templateId}/duplicate`,
      { method: 'POST' },
    )
    return res.data
  }

  // --- 進捗管理（ADMIN） ---

  async function listProgresses(scopeType: 'team' | 'organization', scopeId: number) {
    const base = buildScopeBase(scopeType, scopeId)
    const res = await api<{ data: OnboardingProgress[] }>(`${base}/onboarding/progresses`)
    return res.data
  }

  async function getProgress(progressId: number) {
    const res = await api<{ data: OnboardingProgress }>(
      `/api/v1/onboarding/progresses/${progressId}`,
    )
    return res.data
  }

  async function skipProgress(progressId: number) {
    await api(`/api/v1/onboarding/progresses/${progressId}/skip`, { method: 'POST' })
  }

  async function resetProgress(progressId: number) {
    await api(`/api/v1/onboarding/progresses/${progressId}/reset`, { method: 'POST' })
  }

  async function adminCompleteStep(progressId: number, stepId: number) {
    await api(`/api/v1/onboarding/progresses/${progressId}/steps/${stepId}/complete`, {
      method: 'POST',
    })
  }

  async function sendReminder(scopeType: 'team' | 'organization', scopeId: number) {
    const base = buildScopeBase(scopeType, scopeId)
    await api(`${base}/onboarding/remind`, { method: 'POST' })
  }

  // --- メンバー向け ---

  async function listMyProgresses() {
    const res = await api<{ data: OnboardingProgress[] }>('/api/v1/onboarding/progresses/me')
    return res.data
  }

  async function getMyProgress(progressId: number) {
    const res = await api<{ data: OnboardingProgress }>(
      `/api/v1/onboarding/progresses/me/${progressId}`,
    )
    return res.data
  }

  async function completeStep(progressId: number, stepId: number) {
    await api(`/api/v1/onboarding/progresses/me/${progressId}/steps/${stepId}/complete`, {
      method: 'POST',
    })
  }

  return {
    listPresets,
    listTemplates,
    createTemplate,
    getTemplate,
    updateTemplate,
    activateTemplate,
    archiveTemplate,
    deleteTemplate,
    duplicateTemplate,
    listProgresses,
    getProgress,
    skipProgress,
    resetProgress,
    adminCompleteStep,
    sendReminder,
    listMyProgresses,
    getMyProgress,
    completeStep,
  }
}
