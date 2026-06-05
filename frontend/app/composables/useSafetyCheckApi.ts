export function useSafetyCheckApi() {
  const api = useApi()

  const BASE = '/api/v1/safety-checks'

  // === Safety Check CRUD ===
  // BE 契約: scopeType / scopeId は必須クエリパラメータ
  async function listSafetyChecks(params: {
    scopeType: string
    scopeId: string
    status?: string
    page?: number
    size?: number
  }) {
    const query = new URLSearchParams()
    query.set('scopeType', params.scopeType)
    query.set('scopeId', String(params.scopeId))
    if (params.status) query.set('status', params.status)
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 20))
    return api<{
      data: unknown[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`${BASE}?${query}`)
  }

  // BE 契約: CreateSafetyCheckRequest（message / scopeType / scopeId）
  async function triggerSafetyCheck(body: {
    title: string
    message?: string
    scopeType: string
    scopeId: string
    isDrill?: boolean
    reminderIntervalMinutes?: number
    templateId?: number
  }) {
    return api<{ data: unknown }>(`${BASE}`, { method: 'POST', body })
  }

  async function getSafetyCheck(safetyCheckId: number) {
    return api<{ data: unknown }>(`${BASE}/${safetyCheckId}`)
  }

  async function getSafetyCheckResults(safetyCheckId: number) {
    return api<{ data: unknown }>(`${BASE}/${safetyCheckId}/results`)
  }

  async function getUnresponded(safetyCheckId: number) {
    return api<{ data: unknown[] }>(`${BASE}/${safetyCheckId}/unresponded`)
  }

  async function closeSafetyCheck(safetyCheckId: number) {
    return api(`${BASE}/${safetyCheckId}/close`, { method: 'POST' })
  }

  async function sendReminder(safetyCheckId: number) {
    return api(`${BASE}/${safetyCheckId}/remind`, { method: 'POST' })
  }

  // === Response ===
  async function respondToSafetyCheck(
    safetyCheckId: number,
    body: { status: string; message?: string; latitude?: number; longitude?: number },
  ) {
    return api(`${BASE}/${safetyCheckId}/respond`, { method: 'POST', body })
  }

  async function bulkRespond(safetyCheckId: number, body: Record<string, unknown>) {
    return api(`${BASE}/${safetyCheckId}/respond/bulk`, { method: 'POST', body })
  }

  // === Followup ===
  async function updateFollowup(followupId: number, body: { status: string; note?: string }) {
    return api(`${BASE}/followups/${followupId}`, { method: 'PATCH', body })
  }

  // === Templates ===
  async function getTemplates() {
    return api<{ data: unknown[] }>(`${BASE}/templates`)
  }

  async function createTemplate(body: { name: string; title: string; description?: string }) {
    return api<{ data: unknown }>(`${BASE}/templates`, { method: 'POST', body })
  }

  async function getTemplate(templateId: number) {
    return api<{ data: unknown }>(`${BASE}/templates/${templateId}`)
  }

  async function updateTemplate(templateId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/templates/${templateId}`, { method: 'PATCH', body })
  }

  // === History & Presets ===
  // BE 契約: scopeType / scopeId は必須クエリパラメータ
  async function getHistory(params: {
    scopeType: string
    scopeId: string
    page?: number
    size?: number
  }) {
    const query = new URLSearchParams()
    query.set('scopeType', params.scopeType)
    query.set('scopeId', String(params.scopeId))
    if (params.page !== undefined) query.set('page', String(params.page))
    if (params.size !== undefined) query.set('size', String(params.size))
    return api<{ data: unknown[] }>(`${BASE}/history?${query}`)
  }

  async function getPresets() {
    return api<{ data: unknown[] }>(`${BASE}/presets`)
  }

  return {
    listSafetyChecks,
    triggerSafetyCheck,
    getSafetyCheck,
    getSafetyCheckResults,
    getUnresponded,
    closeSafetyCheck,
    sendReminder,
    respondToSafetyCheck,
    bulkRespond,
    updateFollowup,
    getTemplates,
    createTemplate,
    getTemplate,
    updateTemplate,
    getHistory,
    getPresets,
  }
}
