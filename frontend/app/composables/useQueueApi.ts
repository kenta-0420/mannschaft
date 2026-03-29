export function useQueueApi() {
  const api = useApi()

  function base(teamId: number) {
    return `/api/v1/teams/${teamId}/queue`
  }

  // === Categories ===
  async function getCategories(teamId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/categories`)
  }

  async function createCategory(
    teamId: number,
    body: { name: string; queueMode: string; prefix: string; maxQueueSize?: number },
  ) {
    return api<{ data: unknown }>(`${base(teamId)}/categories`, { method: 'POST', body })
  }

  async function getCategory(teamId: number, categoryId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/categories/${categoryId}`)
  }

  async function updateCategory(teamId: number, categoryId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/categories/${categoryId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function getCategoryTickets(teamId: number, categoryId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/categories/${categoryId}/tickets`)
  }

  // === Counters ===
  async function getCounters(teamId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/counters`)
  }

  async function createCounter(
    teamId: number,
    body: {
      name: string
      categoryId: number
      receptionMethod: string
      averageServiceMinutes?: number
    },
  ) {
    return api<{ data: unknown }>(`${base(teamId)}/counters`, { method: 'POST', body })
  }

  async function getCounter(teamId: number, counterId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/counters/${counterId}`)
  }

  async function updateCounter(teamId: number, counterId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/counters/${counterId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function getCounterTickets(teamId: number, counterId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/counters/${counterId}/tickets`)
  }

  async function createCounterTicket(
    teamId: number,
    counterId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(`${base(teamId)}/counters/${counterId}/tickets`, {
      method: 'POST',
      body,
    })
  }

  async function getAllCounterTickets(teamId: number, counterId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/counters/${counterId}/tickets/all`)
  }

  async function callNextTicket(teamId: number, counterId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/counters/${counterId}/tickets/call-next`, {
      method: 'POST',
    })
  }

  async function createGuestTicket(
    teamId: number,
    counterId: number,
    body?: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(`${base(teamId)}/counters/${counterId}/tickets/guest`, {
      method: 'POST',
      body,
    })
  }

  async function createQrTicket(teamId: number, counterId: number, body?: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/counters/${counterId}/tickets/qr`, {
      method: 'POST',
      body,
    })
  }

  // === QR Codes ===
  async function getQrCodes(teamId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/qr-codes`)
  }

  async function createQrCode(teamId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/qr-codes`, { method: 'POST', body })
  }

  async function getQrCodeByToken(teamId: number, qrToken: string) {
    return api<{ data: unknown }>(`${base(teamId)}/qr-codes/token/${qrToken}`)
  }

  async function deleteQrCode(teamId: number, qrCodeId: number) {
    return api(`${base(teamId)}/qr-codes/${qrCodeId}`, { method: 'DELETE' })
  }

  // === Settings ===
  async function getSettings(teamId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/settings`)
  }

  async function updateSettings(teamId: number, body: Record<string, unknown>) {
    return api(`${base(teamId)}/settings`, { method: 'PATCH', body })
  }

  // === Status ===
  async function getQueueStatus(teamId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/status`)
  }

  // === Tickets ===
  async function getMyTickets(teamId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/tickets/me`)
  }

  async function getTicket(teamId: number, ticketId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/tickets/${ticketId}`)
  }

  async function deleteTicket(teamId: number, ticketId: number) {
    return api(`${base(teamId)}/tickets/${ticketId}`, { method: 'DELETE' })
  }

  async function ticketAction(teamId: number, ticketId: number, body: Record<string, unknown>) {
    return api(`${base(teamId)}/tickets/${ticketId}/action`, { method: 'PATCH', body })
  }

  return {
    getCategories,
    createCategory,
    getCategory,
    updateCategory,
    getCategoryTickets,
    getCounters,
    createCounter,
    getCounter,
    updateCounter,
    getCounterTickets,
    createCounterTicket,
    getAllCounterTickets,
    callNextTicket,
    createGuestTicket,
    createQrTicket,
    getQrCodes,
    createQrCode,
    getQrCodeByToken,
    deleteQrCode,
    getSettings,
    updateSettings,
    getQueueStatus,
    getMyTickets,
    getTicket,
    deleteTicket,
    ticketAction,
  }
}
