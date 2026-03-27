export function useQueueApi() {
  const api = useApi()

  function base(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Categories ===
  async function getCategories(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: unknown[] }>(`${base(scopeType, scopeId)}/queue-categories`)
  }

  async function createCategory(scopeType: 'team' | 'organization', scopeId: number, body: { name: string; queueMode: string; prefix: string; maxQueueSize?: number }) {
    return api<{ data: unknown }>(`${base(scopeType, scopeId)}/queue-categories`, { method: 'POST', body })
  }

  async function updateCategory(scopeType: 'team' | 'organization', scopeId: number, categoryId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(scopeType, scopeId)}/queue-categories/${categoryId}`, { method: 'PUT', body })
  }

  async function deleteCategory(scopeType: 'team' | 'organization', scopeId: number, categoryId: number) {
    return api(`${base(scopeType, scopeId)}/queue-categories/${categoryId}`, { method: 'DELETE' })
  }

  // === Counters ===
  async function getCounters(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: unknown[] }>(`${base(scopeType, scopeId)}/queue-counters`)
  }

  async function createCounter(scopeType: 'team' | 'organization', scopeId: number, body: { name: string; categoryId: number; receptionMethod: string; averageServiceMinutes?: number }) {
    return api<{ data: unknown }>(`${base(scopeType, scopeId)}/queue-counters`, { method: 'POST', body })
  }

  async function updateCounter(scopeType: 'team' | 'organization', scopeId: number, counterId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(scopeType, scopeId)}/queue-counters/${counterId}`, { method: 'PUT', body })
  }

  async function deleteCounter(scopeType: 'team' | 'organization', scopeId: number, counterId: number) {
    return api(`${base(scopeType, scopeId)}/queue-counters/${counterId}`, { method: 'DELETE' })
  }

  // === Tickets ===
  async function createTicket(scopeType: 'team' | 'organization', scopeId: number, body: { categoryId: number; counterId?: number; phoneNumber?: string }) {
    return api<{ data: unknown }>(`${base(scopeType, scopeId)}/queue-tickets`, { method: 'POST', body })
  }

  async function getTicketStatus(scopeType: 'team' | 'organization', scopeId: number, ticketId: number) {
    return api<{ data: unknown }>(`${base(scopeType, scopeId)}/queue-tickets/${ticketId}`)
  }

  async function callNextTicket(scopeType: 'team' | 'organization', scopeId: number, counterId: number) {
    return api<{ data: unknown }>(`${base(scopeType, scopeId)}/queue-counters/${counterId}/call-next`, { method: 'POST' })
  }

  async function skipTicket(scopeType: 'team' | 'organization', scopeId: number, ticketId: number) {
    return api(`${base(scopeType, scopeId)}/queue-tickets/${ticketId}/skip`, { method: 'PATCH' })
  }

  async function markNoShow(scopeType: 'team' | 'organization', scopeId: number, ticketId: number) {
    return api(`${base(scopeType, scopeId)}/queue-tickets/${ticketId}/no-show`, { method: 'PATCH' })
  }

  async function completeTicket(scopeType: 'team' | 'organization', scopeId: number, ticketId: number) {
    return api(`${base(scopeType, scopeId)}/queue-tickets/${ticketId}/complete`, { method: 'PATCH' })
  }

  async function cancelTicket(scopeType: 'team' | 'organization', scopeId: number, ticketId: number) {
    return api(`${base(scopeType, scopeId)}/queue-tickets/${ticketId}/cancel`, { method: 'PATCH' })
  }

  // === Status ===
  async function getQueueStatus(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: unknown }>(`${base(scopeType, scopeId)}/queue-status`)
  }

  // === Settings ===
  async function getSettings(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: unknown }>(`${base(scopeType, scopeId)}/queue-settings`)
  }

  async function updateSettings(scopeType: 'team' | 'organization', scopeId: number, body: Record<string, unknown>) {
    return api(`${base(scopeType, scopeId)}/queue-settings`, { method: 'PUT', body })
  }

  // === QR ===
  async function generateQr(scopeType: 'team' | 'organization', scopeId: number, counterId: number) {
    return api<{ data: { qrUrl: string } }>(`${base(scopeType, scopeId)}/queue-counters/${counterId}/qr`)
  }

  return {
    getCategories, createCategory, updateCategory, deleteCategory,
    getCounters, createCounter, updateCounter, deleteCounter,
    createTicket, getTicketStatus, callNextTicket, skipTicket, markNoShow, completeTicket, cancelTicket,
    getQueueStatus, getSettings, updateSettings, generateQr,
  }
}
