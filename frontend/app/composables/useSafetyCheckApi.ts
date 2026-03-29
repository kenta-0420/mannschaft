export function useSafetyCheckApi() {
  const api = useApi()

  const BASE = '/api/v1/safety-checks'

  // === Safety Check CRUD ===
  async function listSafetyChecks(params?: { status?: string; page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{
      data: unknown[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`${BASE}?${query}`)
  }

  async function triggerSafetyCheck(body: {
    title: string
    description?: string
    isDrill?: boolean
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
  async function getHistory(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.page !== undefined) query.set('page', String(params.page))
    if (params?.size !== undefined) query.set('size', String(params.size))
    const qs = query.toString()
    return api<{ data: unknown[] }>(`${BASE}/history${qs ? `?${qs}` : ''}`)
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
