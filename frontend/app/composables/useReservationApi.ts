import type { ReservationResponse, ReservationSettingsResponse, UpdateReservationSettingRequest, CreateSlotRequest, UpdateSlotRequest } from '~/types/reservation'

export function useReservationApi() {
  const api = useApi()

  function base(teamId: string) {
    return `/api/v1/teams/${teamId}`
  }

  // === Lines ===
  async function getLines(teamId: string) {
    return api<{ data: unknown[] }>(`${base(teamId)}/reservation-lines`)
  }

  async function createLine(
    teamId: string,
    body: {
      name: string
      description?: string
      displayOrder?: number
      defaultStaffUserId?: number
      isActive?: boolean
    },
  ) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-lines`, { method: 'POST', body })
  }

  async function updateLine(teamId: string, lineId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-lines/${lineId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteLine(teamId: string, lineId: number) {
    return api(`${base(teamId)}/reservation-lines/${lineId}`, { method: 'DELETE' })
  }

  // === Slots ===
  // BE: GET /reservation-slots は from/to（取得期間。ISO DATE）が必須クエリ。
  // 単日表示は from=to=対象日 を渡す。スロットはライン非依存（BEにライン紐付けは無い）。
  async function getSlots(teamId: string, params: { from: string; to: string }) {
    const query = new URLSearchParams()
    query.set('from', params.from)
    query.set('to', params.to)
    return api<{ data: unknown[] }>(`${base(teamId)}/reservation-slots?${query}`)
  }

  async function createSlot(teamId: string, body: CreateSlotRequest) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-slots`, { method: 'POST', body })
  }

  async function getSlot(teamId: string, slotId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-slots/${slotId}`)
  }

  async function updateSlot(teamId: string, slotId: number, body: UpdateSlotRequest) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-slots/${slotId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteSlot(teamId: string, slotId: number) {
    return api(`${base(teamId)}/reservation-slots/${slotId}`, { method: 'DELETE' })
  }

  async function closeSlot(teamId: string, slotId: number) {
    return api(`${base(teamId)}/reservation-slots/${slotId}/close`, { method: 'POST' })
  }

  async function reopenSlot(teamId: string, slotId: number) {
    return api(`${base(teamId)}/reservation-slots/${slotId}/reopen`, { method: 'POST' })
  }

  // BE: GET /reservation-slots/available も from/to（取得期間。ISO DATE）が必須クエリ。
  async function listAvailableSlots(teamId: string, params: { from: string; to: string }) {
    const query = new URLSearchParams()
    query.set('from', params.from)
    query.set('to', params.to)
    return api<{ data: unknown[] }>(`${base(teamId)}/reservation-slots/available?${query}`)
  }

  // === Reservations ===
  async function listReservations(
    teamId: string,
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
    teamId: string,
    // BE: CreateReservationRequest は reservationSlotId/lineId(@NotNull) + userNote(任意)
    body: { reservationSlotId: number; lineId: number; userNote?: string },
  ) {
    return api<{ data: unknown }>(`${base(teamId)}/reservations`, { method: 'POST', body })
  }

  async function getReservation(teamId: string, reservationId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/reservations/${reservationId}`)
  }

  async function cancelReservation(teamId: string, reservationId: number, reason?: string) {
    return api(`${base(teamId)}/reservations/${reservationId}/cancel`, {
      method: 'POST',
      body: { reason: reason ?? null },
    })
  }

  async function confirmReservation(teamId: string, reservationId: number) {
    return api(`${base(teamId)}/reservations/${reservationId}/confirm`, { method: 'POST' })
  }

  async function completeReservation(teamId: string, reservationId: number) {
    return api(`${base(teamId)}/reservations/${reservationId}/complete`, { method: 'POST' })
  }

  async function rescheduleReservation(
    teamId: string,
    reservationId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${base(teamId)}/reservations/${reservationId}/reschedule`, { method: 'POST', body })
  }

  async function markNoShow(teamId: string, reservationId: number) {
    return api(`${base(teamId)}/reservations/${reservationId}/no-show`, { method: 'POST' })
  }

  async function updateAdminNote(teamId: string, reservationId: number, body: { note: string }) {
    return api(`${base(teamId)}/reservations/${reservationId}/admin-note`, {
      method: 'PATCH',
      body,
    })
  }

  async function listReminders(teamId: string, reservationId: number) {
    return api<{ data: unknown[] }>(`${base(teamId)}/reservations/${reservationId}/reminders`)
  }

  async function createReminder(
    teamId: string,
    reservationId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(`${base(teamId)}/reservations/${reservationId}/reminders`, {
      method: 'POST',
      body,
    })
  }

  async function getReservationStats(teamId: string) {
    return api<{ data: unknown }>(`${base(teamId)}/reservations/stats`)
  }

  // === Settings ===
  async function getReservationSettings(teamId: string) {
    return api<{ data: ReservationSettingsResponse }>(`${base(teamId)}/reservation-settings`)
  }

  /**
   * 予約設定を更新する（ADMIN限定）。
   * BE: PATCH /api/v1/teams/{teamId}/reservation-settings
   * body: UpdateReservationSettingRequest { allowPublicReservation?: boolean }
   */
  async function updateReservationSettings(teamId: string, body: UpdateReservationSettingRequest) {
    return api<{ data: ReservationSettingsResponse }>(`${base(teamId)}/reservation-settings`, {
      method: 'PATCH',
      body,
    })
  }

  async function getBusinessHours(teamId: string) {
    return api<{ data: unknown[] }>(`${base(teamId)}/reservation-settings/business-hours`)
  }

  async function updateBusinessHours(
    teamId: string,
    body: Array<{
      dayOfWeek: number
      openTime: string | null
      closeTime: string | null
      isClosed: boolean
    }>,
  ) {
    return api(`${base(teamId)}/reservation-settings/business-hours`, { method: 'PUT', body })
  }

  async function listBlockedTimes(teamId: string) {
    return api<{ data: unknown[] }>(`${base(teamId)}/reservation-settings/blocked-times`)
  }

  async function createBlockedTime(teamId: string, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${base(teamId)}/reservation-settings/blocked-times`, {
      method: 'POST',
      body,
    })
  }

  async function updateBlockedTime(
    teamId: string,
    blockedId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(
      `${base(teamId)}/reservation-settings/blocked-times/${blockedId}`,
      { method: 'PATCH', body },
    )
  }

  async function deleteBlockedTime(teamId: string, blockedId: number) {
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
    return api<{
      data: ReservationResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/reservations/my?${query}`)
  }

  async function listUpcomingReservations(params?: { limit?: number }) {
    const query = new URLSearchParams()
    if (params?.limit) query.set('limit', String(params.limit))
    return api<{ data: unknown[] }>(`/api/v1/reservations/upcoming?${query}`)
  }

  async function cancelMyReservation(reservationId: number, reason?: string) {
    // BE: ReservationCommonController#cancelMyReservation は CancelReservationRequest を必須とするため body を渡す
    return api(`/api/v1/reservations/${reservationId}/cancel`, {
      method: 'POST',
      body: { reason: reason ?? null },
    })
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
    getReservationSettings,
    updateReservationSettings,
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
