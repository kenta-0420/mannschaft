export function useSafetyCheckApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Safety Check CRUD ===
  async function triggerSafetyCheck(scopeType: 'team' | 'organization', scopeId: number, body: { title: string; description?: string; isDrill?: boolean }) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/safety-checks`, { method: 'POST', body })
  }

  async function listSafetyChecks(scopeType: 'team' | 'organization', scopeId: number, params?: { status?: string; page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: unknown[]; meta: { page: number; size: number; totalElements: number; totalPages: number } }>(
      `${buildBase(scopeType, scopeId)}/safety-checks?${query}`
    )
  }

  async function getSafetyCheck(scopeType: 'team' | 'organization', scopeId: number, checkId: number) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/safety-checks/${checkId}`)
  }

  async function getSafetyCheckResults(scopeType: 'team' | 'organization', scopeId: number, checkId: number) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/safety-checks/${checkId}/results`)
  }

  async function closeSafetyCheck(scopeType: 'team' | 'organization', scopeId: number, checkId: number) {
    return api(`${buildBase(scopeType, scopeId)}/safety-checks/${checkId}/close`, { method: 'PATCH' })
  }

  // === Response ===
  async function respondToSafetyCheck(scopeType: 'team' | 'organization', scopeId: number, checkId: number, body: { status: string; message?: string; latitude?: number; longitude?: number }) {
    return api(`${buildBase(scopeType, scopeId)}/safety-checks/${checkId}/respond`, { method: 'POST', body })
  }

  async function getMyActiveSafetyChecks() {
    return api<{ data: unknown[] }>('/api/v1/safety-checks/me/active')
  }

  // === Followup ===
  async function updateFollowup(scopeType: 'team' | 'organization', scopeId: number, checkId: number, followupId: number, body: { status: string; note?: string }) {
    return api(`${buildBase(scopeType, scopeId)}/safety-checks/${checkId}/followups/${followupId}`, { method: 'PATCH', body })
  }

  // === Templates ===
  async function getTemplates(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: unknown[] }>(`${buildBase(scopeType, scopeId)}/safety-checks/templates`)
  }

  async function createTemplate(scopeType: 'team' | 'organization', scopeId: number, body: { name: string; title: string; description?: string }) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/safety-checks/templates`, { method: 'POST', body })
  }

  // === Reminder ===
  async function sendReminder(scopeType: 'team' | 'organization', scopeId: number, checkId: number) {
    return api(`${buildBase(scopeType, scopeId)}/safety-checks/${checkId}/remind`, { method: 'POST' })
  }

  return {
    triggerSafetyCheck,
    listSafetyChecks,
    getSafetyCheck,
    getSafetyCheckResults,
    closeSafetyCheck,
    respondToSafetyCheck,
    getMyActiveSafetyChecks,
    updateFollowup,
    getTemplates,
    createTemplate,
    sendReminder,
  }
}
