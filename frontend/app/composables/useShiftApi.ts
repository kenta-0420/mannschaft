export function useShiftApi() {
  const api = useApi()

  const BASE = '/api/v1/shifts'

  // === Schedule ===
  async function listShiftSchedules(params?: { status?: string; page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{
      data: unknown[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`${BASE}/schedules?${query}`)
  }

  async function createShiftSchedule(body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/schedules`, { method: 'POST', body })
  }

  async function getShiftSchedule(scheduleId: number) {
    return api<{ data: unknown }>(`${BASE}/schedules/${scheduleId}`)
  }

  async function updateShiftSchedule(scheduleId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/schedules/${scheduleId}`, { method: 'PATCH', body })
  }

  async function deleteShiftSchedule(scheduleId: number) {
    return api(`${BASE}/schedules/${scheduleId}`, { method: 'DELETE' })
  }

  async function duplicateShiftSchedule(scheduleId: number) {
    return api<{ data: unknown }>(`${BASE}/schedules/${scheduleId}/duplicate`, { method: 'POST' })
  }

  async function transitionShiftSchedule(scheduleId: number, body?: Record<string, unknown>) {
    return api(`${BASE}/schedules/${scheduleId}/transition`, { method: 'POST', body })
  }

  // === Positions ===
  async function getPositions() {
    return api<{ data: unknown[] }>(`${BASE}/positions`)
  }

  async function createPosition(body: {
    name: string
    description?: string
    requiredCount: number
    color?: string
  }) {
    return api<{ data: unknown }>(`${BASE}/positions`, { method: 'POST', body })
  }

  async function updatePosition(positionId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/positions/${positionId}`, { method: 'PATCH', body })
  }

  async function deletePosition(positionId: number) {
    return api(`${BASE}/positions/${positionId}`, { method: 'DELETE' })
  }

  // === Slots ===
  async function getShiftSlots(
    scheduleId: number,
    params?: { date?: string; positionId?: number },
  ) {
    const query = new URLSearchParams()
    if (params?.date) query.set('date', params.date)
    if (params?.positionId) query.set('positionId', String(params.positionId))
    return api<{ data: unknown[] }>(`${BASE}/schedules/${scheduleId}/slots?${query}`)
  }

  async function createShiftSlot(scheduleId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/schedules/${scheduleId}/slots`, { method: 'POST', body })
  }

  async function bulkCreateSlots(scheduleId: number, body: Array<Record<string, unknown>>) {
    return api(`${BASE}/schedules/${scheduleId}/slots/bulk`, { method: 'POST', body })
  }

  async function updateSlot(slotId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/slots/${slotId}`, { method: 'PATCH', body })
  }

  async function deleteSlot(slotId: number) {
    return api(`${BASE}/slots/${slotId}`, { method: 'DELETE' })
  }

  // === Requests ===
  async function listShiftRequests(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.page !== undefined) query.set('page', String(params.page))
    if (params?.size !== undefined) query.set('size', String(params.size))
    const qs = query.toString()
    return api<{ data: unknown[] }>(`${BASE}/requests${qs ? `?${qs}` : ''}`)
  }

  async function submitShiftRequest(body: Record<string, unknown>) {
    return api(`${BASE}/requests`, { method: 'POST', body })
  }

  async function updateShiftRequest(requestId: number, body: Record<string, unknown>) {
    return api(`${BASE}/requests/${requestId}`, { method: 'PATCH', body })
  }

  async function deleteShiftRequest(requestId: number) {
    return api(`${BASE}/requests/${requestId}`, { method: 'DELETE' })
  }

  async function getShiftRequestSummary() {
    return api<{ data: unknown }>(`${BASE}/requests/summary`)
  }

  async function getMyShiftRequests() {
    return api<{ data: unknown[] }>(`${BASE}/my/requests`)
  }

  // === Swap Requests ===
  async function listSwapRequests(params?: { status?: string }) {
    const query = params?.status ? `?status=${params.status}` : ''
    return api<{ data: unknown[] }>(`${BASE}/swap-requests${query}`)
  }

  async function createSwapRequest(body: {
    slotId: number
    targetUserId: number
    reason?: string
  }) {
    return api<{ data: unknown }>(`${BASE}/swap-requests`, { method: 'POST', body })
  }

  async function deleteSwapRequest(swapId: number) {
    return api(`${BASE}/swap-requests/${swapId}`, { method: 'DELETE' })
  }

  async function acceptSwap(swapId: number) {
    return api(`${BASE}/swap-requests/${swapId}/accept`, { method: 'POST' })
  }

  async function resolveSwap(swapId: number, body?: Record<string, unknown>) {
    return api(`${BASE}/swap-requests/${swapId}/resolve`, { method: 'POST', body })
  }

  // === Availability ===
  async function getAvailability(params?: Record<string, string>) {
    const query = new URLSearchParams(params)
    const qs = query.toString()
    return api<{ data: unknown[] }>(`${BASE}/availability${qs ? `?${qs}` : ''}`)
  }

  async function setAvailability(
    body: Array<{
      dayOfWeek: number
      startTime: string | null
      endTime: string | null
      isAvailable: boolean
    }>,
  ) {
    return api(`${BASE}/availability`, { method: 'PUT', body })
  }

  async function deleteAvailability() {
    return api(`${BASE}/availability`, { method: 'DELETE' })
  }

  // === Hourly Rate ===
  async function getHourlyRate() {
    return api<{ data: unknown }>(`${BASE}/hourly-rate`)
  }

  async function setHourlyRate(body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/hourly-rate`, { method: 'POST', body })
  }

  return {
    listShiftSchedules,
    createShiftSchedule,
    getShiftSchedule,
    updateShiftSchedule,
    deleteShiftSchedule,
    duplicateShiftSchedule,
    transitionShiftSchedule,
    getPositions,
    createPosition,
    updatePosition,
    deletePosition,
    getShiftSlots,
    createShiftSlot,
    bulkCreateSlots,
    updateSlot,
    deleteSlot,
    listShiftRequests,
    submitShiftRequest,
    updateShiftRequest,
    deleteShiftRequest,
    getShiftRequestSummary,
    getMyShiftRequests,
    listSwapRequests,
    createSwapRequest,
    deleteSwapRequest,
    acceptSwap,
    resolveSwap,
    getAvailability,
    setAvailability,
    deleteAvailability,
    getHourlyRate,
    setHourlyRate,
  }
}
