export function useShiftApi() {
  const api = useApi()

  function base(teamId: number) {
    return `/api/v1/teams/${teamId}`
  }

  // === Schedule ===
  async function listShiftSchedules(teamId: number, params?: { status?: string; page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: unknown[]; meta: { page: number; size: number; totalElements: number; totalPages: number } }>(
      `${base(teamId)}/shift-schedules?${query}`
    )
  }

  async function createShiftSchedule(teamId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/shift-schedules`, { method: 'POST', body })
  }

  async function getShiftSchedule(teamId: number, scheduleId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/shift-schedules/${scheduleId}`)
  }

  async function updateShiftSchedule(teamId: number, scheduleId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/shift-schedules/${scheduleId}`, { method: 'PUT', body })
  }

  async function publishShift(teamId: number, scheduleId: number) {
    return api(`${base(teamId)}/shift-schedules/${scheduleId}/publish`, { method: 'PATCH' })
  }

  async function archiveShift(teamId: number, scheduleId: number) {
    return api(`${base(teamId)}/shift-schedules/${scheduleId}/archive`, { method: 'PATCH' })
  }

  // === Positions ===
  async function getPositions(teamId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/shift-positions`)
  }

  async function createPosition(teamId: number, body: { name: string; description?: string; requiredCount: number; color?: string }) {
    return api<{ data: unknown }>(`${base(teamId)}/shift-positions`, { method: 'POST', body })
  }

  async function updatePosition(teamId: number, positionId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/shift-positions/${positionId}`, { method: 'PUT', body })
  }

  async function deletePosition(teamId: number, positionId: number) {
    return api(`${base(teamId)}/shift-positions/${positionId}`, { method: 'DELETE' })
  }

  // === Slots ===
  async function getShiftSlots(teamId: number, scheduleId: number, params?: { date?: string; positionId?: number }) {
    const query = new URLSearchParams()
    if (params?.date) query.set('date', params.date)
    if (params?.positionId) query.set('positionId', String(params.positionId))
    return api<{ data: unknown[] }>(`${base(teamId)}/shift-schedules/${scheduleId}/slots?${query}`)
  }

  async function batchCreateSlots(teamId: number, scheduleId: number, body: Array<Record<string, unknown>>) {
    return api(`${base(teamId)}/shift-schedules/${scheduleId}/slots/batch`, { method: 'POST', body })
  }

  // === Requests ===
  async function submitShiftRequest(teamId: number, scheduleId: number, body: Record<string, unknown>) {
    return api(`${base(teamId)}/shift-schedules/${scheduleId}/requests`, { method: 'POST', body })
  }

  async function getMyShiftRequests(teamId: number, scheduleId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/shift-schedules/${scheduleId}/requests/me`)
  }

  async function getShiftRequestSummary(teamId: number, scheduleId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/shift-schedules/${scheduleId}/requests/summary`)
  }

  // === Swap ===
  async function createSwapRequest(teamId: number, body: { slotId: number; targetUserId: number; reason?: string }) {
    return api<{ data: unknown }>(`${base(teamId)}/shift-swaps`, { method: 'POST', body })
  }

  async function listSwapRequests(teamId: number, params?: { status?: string }) {
    const query = params?.status ? `?status=${params.status}` : ''
    return api<{ data: unknown[] }>(`${base(teamId)}/shift-swaps${query}`)
  }

  async function acceptSwap(teamId: number, swapId: number) {
    return api(`${base(teamId)}/shift-swaps/${swapId}/accept`, { method: 'PATCH' })
  }

  async function rejectSwap(teamId: number, swapId: number) {
    return api(`${base(teamId)}/shift-swaps/${swapId}/reject`, { method: 'PATCH' })
  }

  // === Availability ===
  async function getDefaultAvailability(teamId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/shift-availability/defaults`)
  }

  async function setDefaultAvailability(teamId: number, body: Array<{ dayOfWeek: number; startTime: string | null; endTime: string | null; isAvailable: boolean }>) {
    return api(`${base(teamId)}/shift-availability/defaults`, { method: 'PUT', body })
  }

  return {
    listShiftSchedules, createShiftSchedule, getShiftSchedule, updateShiftSchedule, publishShift, archiveShift,
    getPositions, createPosition, updatePosition, deletePosition,
    getShiftSlots, batchCreateSlots,
    submitShiftRequest, getMyShiftRequests, getShiftRequestSummary,
    createSwapRequest, listSwapRequests, acceptSwap, rejectSwap,
    getDefaultAvailability, setDefaultAvailability,
  }
}
