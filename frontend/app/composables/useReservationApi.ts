export function useReservationApi() {
  const api = useApi()

  function base(teamId: number) {
    return `/api/v1/teams/${teamId}`
  }

  // === Lines ===
  async function getLines(teamId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/reservation-lines`)
  }

  async function createLine(teamId: number, body: { name: string; description?: string; capacity?: number }) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-lines`, { method: 'POST', body })
  }

  async function updateLine(teamId: number, lineId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-lines/${lineId}`, { method: 'PUT', body })
  }

  async function deleteLine(teamId: number, lineId: number) {
    return api(`${base(teamId)}/reservation-lines/${lineId}`, { method: 'DELETE' })
  }

  // === Slots ===
  async function getSlots(teamId: number, params?: { date?: string; lineId?: number }) {
    const query = new URLSearchParams()
    if (params?.date) query.set('date', params.date)
    if (params?.lineId) query.set('lineId', String(params.lineId))
    return api<{ data: unknown[] }>(`${base(teamId)}/reservation-slots?${query}`)
  }

  async function createSlot(teamId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-slots`, { method: 'POST', body })
  }

  async function updateSlot(teamId: number, slotId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-slots/${slotId}`, { method: 'PUT', body })
  }

  async function closeSlot(teamId: number, slotId: number) {
    return api(`${base(teamId)}/reservation-slots/${slotId}/close`, { method: 'PATCH' })
  }

  // === Reservations ===
  async function listReservations(teamId: number, params?: { status?: string; date?: string; page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    if (params?.date) query.set('date', params.date)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: unknown[]; meta: { page: number; size: number; totalElements: number; totalPages: number } }>(
      `${base(teamId)}/reservations?${query}`
    )
  }

  async function createReservation(teamId: number, body: { slotId: number; serviceNotes?: string }) {
    return api<{ data: unknown }>(`${base(teamId)}/reservations`, { method: 'POST', body })
  }

  async function cancelReservation(teamId: number, reservationId: number) {
    return api(`${base(teamId)}/reservations/${reservationId}/cancel`, { method: 'PATCH' })
  }

  async function approveReservation(teamId: number, reservationId: number) {
    return api(`${base(teamId)}/reservations/${reservationId}/approve`, { method: 'PATCH' })
  }

  async function rejectReservation(teamId: number, reservationId: number) {
    return api(`${base(teamId)}/reservations/${reservationId}/reject`, { method: 'PATCH' })
  }

  // === Business Hours ===
  async function getBusinessHours(teamId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/reservation-business-hours`)
  }

  async function updateBusinessHours(teamId: number, body: Array<{ dayOfWeek: number; openTime: string | null; closeTime: string | null; isClosed: boolean }>) {
    return api(`${base(teamId)}/reservation-business-hours`, { method: 'PUT', body })
  }

  return {
    getLines, createLine, updateLine, deleteLine,
    getSlots, createSlot, updateSlot, closeSlot,
    listReservations, createReservation, cancelReservation, approveReservation, rejectReservation,
    getBusinessHours, updateBusinessHours,
  }
}
