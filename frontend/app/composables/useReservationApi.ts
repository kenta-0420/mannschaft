export function useReservationApi() {
  const api = useApi()

  function base(teamId: number) {
    return `/api/v1/teams/${teamId}`
  }

  // === Lines ===
  async function getLines(teamId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/reservation-lines`)
  }

  async function createLine(
    teamId: number,
    body: { name: string; description?: string; capacity?: number },
  ) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-lines`, { method: 'POST', body })
  }

  async function updateLine(teamId: number, lineId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-lines/${lineId}`, {
      method: 'PATCH',
      body,
    })
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

  async function getSlot(teamId: number, slotId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-slots/${slotId}`)
  }

  async function updateSlot(teamId: number, slotId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-slots/${slotId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteSlot(teamId: number, slotId: number) {
    return api(`${base(teamId)}/reservation-slots/${slotId}`, { method: 'DELETE' })
  }

  async function closeSlot(teamId: number, slotId: number) {
    return api(`${base(teamId)}/reservation-slots/${slotId}/close`, { method: 'POST' })
  }

  async function reopenSlot(teamId: number, slotId: number) {
    return api(`${base(teamId)}/reservation-slots/${slotId}/reopen`, { method: 'POST' })
  }

  async function listAvailableSlots(teamId: number, params?: { date?: string; lineId?: number }) {
    const query = new URLSearchParams()
    if (params?.date) query.set('date', params.date)
    if (params?.lineId) query.set('lineId', String(params.lineId))
    return api<{ data: unknown[] }>(`${base(teamId)}/reservation-slots/available?${query}`)
  }

  // === Reservations ===
  async function listReservations(
    teamId: number,
    params?: { status?: string; date?: string; page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    if (params?.date) query.set('date', params.date)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{
      data: unknown[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`${base(teamId)}/reservations?${query}`)
  }

  async function createReservation(
    teamId: number,
    body: { slotId: number; serviceNotes?: string },
  ) {
    return api<{ data: unknown }>(`${base(teamId)}/reservations`, { method: 'POST', body })
  }

  async function getReservation(teamId: number, reservationId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/reservations/${reservationId}`)
  }

  async function cancelReservation(teamId: number, reservationId: number) {
    return api(`${base(teamId)}/reservations/${reservationId}/cancel`, { method: 'POST' })
  }

  async function confirmReservation(teamId: number, reservationId: number) {
    return api(`${base(teamId)}/reservations/${reservationId}/confirm`, { method: 'POST' })
  }

  async function completeReservation(teamId: number, reservationId: number) {
    return api(`${base(teamId)}/reservations/${reservationId}/complete`, { method: 'POST' })
  }

  async function rescheduleReservation(
    teamId: number,
    reservationId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${base(teamId)}/reservations/${reservationId}/reschedule`, { method: 'POST', body })
  }

  async function markNoShow(teamId: number, reservationId: number) {
    return api(`${base(teamId)}/reservations/${reservationId}/no-show`, { method: 'POST' })
  }

  async function updateAdminNote(teamId: number, reservationId: number, body: { note: string }) {
    return api(`${base(teamId)}/reservations/${reservationId}/admin-note`, {
      method: 'PATCH',
      body,
    })
  }

  async function listReminders(teamId: number, reservationId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/reservations/${reservationId}/reminders`)
  }

  async function createReminder(
    teamId: number,
    reservationId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(`${base(teamId)}/reservations/${reservationId}/reminders`, {
      method: 'POST',
      body,
    })
  }

  async function getReservationStats(teamId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/reservations/stats`)
  }

  async function approveReservation(teamId: number, reservationId: number) {
    return api(`${base(teamId)}/reservations/${reservationId}/approve`, { method: 'POST' })
  }

  async function rejectReservation(teamId: number, reservationId: number) {
    return api(`${base(teamId)}/reservations/${reservationId}/reject`, { method: 'POST' })
  }

  // === Settings ===
  async function getReservationSettings(teamId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-settings`)
  }

  async function getBusinessHours(teamId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/reservation-settings/business-hours`)
  }

  async function updateBusinessHours(
    teamId: number,
    body: Array<{
      dayOfWeek: number
      openTime: string | null
      closeTime: string | null
      isClosed: boolean
    }>,
  ) {
    return api(`${base(teamId)}/reservation-settings/business-hours`, { method: 'PUT', body })
  }

  async function listBlockedTimes(teamId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/reservation-settings/blocked-times`)
  }

  async function createBlockedTime(teamId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-settings/blocked-times`, {
      method: 'POST',
      body,
    })
  }

  async function updateBlockedTime(
    teamId: number,
    blockedId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(
      `${base(teamId)}/reservation-settings/blocked-times/${blockedId}`,
      { method: 'PATCH', body },
    )
  }

  async function deleteBlockedTime(teamId: number, blockedId: number) {
    return api(`${base(teamId)}/reservation-settings/blocked-times/${blockedId}`, {
      method: 'DELETE',
    })
  }

  // === My Reservations ===
  async function listMyReservations(params?: { status?: string; page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: unknown[]; meta: unknown }>(`/api/v1/reservations/my?${query}`)
  }

  async function listUpcomingReservations(params?: { limit?: number }) {
    const query = new URLSearchParams()
    if (params?.limit) query.set('limit', String(params.limit))
    return api<{ data: unknown[] }>(`/api/v1/reservations/upcoming?${query}`)
  }

  async function cancelMyReservation(reservationId: number) {
    return api(`/api/v1/reservations/${reservationId}/cancel`, { method: 'POST' })
  }

  return {
    getLines,
    createLine,
    updateLine,
    deleteLine,
    getSlots,
    getSlot,
    createSlot,
    updateSlot,
    deleteSlot,
    closeSlot,
    reopenSlot,
    listAvailableSlots,
    listReservations,
    getReservation,
    createReservation,
    cancelReservation,
    confirmReservation,
    completeReservation,
    rescheduleReservation,
    markNoShow,
    updateAdminNote,
    listReminders,
    createReminder,
    getReservationStats,
    approveReservation,
    rejectReservation,
    getReservationSettings,
    getBusinessHours,
    updateBusinessHours,
    listBlockedTimes,
    createBlockedTime,
    updateBlockedTime,
    deleteBlockedTime,
    listMyReservations,
    listUpcomingReservations,
    cancelMyReservation,
  }
}
