import type { ReservationResponse, ReservationSettingsResponse, UpdateReservationSettingRequest, CreateSlotRequest, UpdateSlotRequest } from '~/types/reservation'
import type { components } from '~/types/generated'

// === 生成型（真実のソース = openapi-typescript）===
// 機能B（予約不可枠）は BE #2109 で resourceType/resourceId/impact を追加済み。
type BlockedTimeRequest = components['schemas']['BlockedTimeRequest']
type BlockedTimeResponse = components['schemas']['BlockedTimeResponse']
type BlockedTimeImpactResponse = components['schemas']['BlockedTimeImpactResponse']
type ReservationLineResponse = components['schemas']['ReservationLineResponse']
type BusinessHourResponse = components['schemas']['BusinessHourResponse']
// 機能C（複数予約対象の空きグリッド）は BE #2112 で grid API を追加済み。
type ReservationGridResponse = components['schemas']['ReservationGridResponse']
// 機能D（予約通知メール宛先）は BE #2110 でフリーミアム件数ゲート付きの CRUD を追加済み。
type NotificationRecipientListResponse = components['schemas']['NotificationRecipientListResponse']
type NotificationRecipientResponse = components['schemas']['NotificationRecipientResponse']
type CreateNotificationRecipientRequest = components['schemas']['CreateNotificationRecipientRequest']
type UpdateNotificationRecipientRequest = components['schemas']['UpdateNotificationRecipientRequest']
// 機能E（予約メニュー）は BE #2160 で CRUD API を追加済み（F03.4.1）。
type ReservationMenuResponse = components['schemas']['ReservationMenuResponse']
type CreateReservationMenuRequest = components['schemas']['CreateReservationMenuRequest']
type UpdateReservationMenuRequest = components['schemas']['UpdateReservationMenuRequest']
type ReservationMenuDeleteResponse = components['schemas']['ReservationMenuDeleteResponse']
// 週間テンプレート・一括生成は BE #2161 で追加済み（F03.4.2）。
type SlotTemplateResponse = components['schemas']['SlotTemplateResponse']
type SlotTemplateListResponse = components['schemas']['SlotTemplateListResponse']
type CreateSlotTemplateRequest = components['schemas']['CreateSlotTemplateRequest']
type UpdateSlotTemplateRequest = components['schemas']['UpdateSlotTemplateRequest']
type DeleteSlotTemplateResponse = components['schemas']['DeleteSlotTemplateResponse']
type GenerateSlotsRequest = components['schemas']['GenerateSlotsRequest']
type GenerateSlotsResponse = components['schemas']['GenerateSlotsResponse']

/** 予約不可枠の対象軸（機能B）。MVP で enforce するのは TEAM / STAFF の2軸。 */
export type BlockedResourceType = NonNullable<BlockedTimeRequest['resourceType']>

/**
 * 週間テンプレートの曜日コード（3文字大文字 'MON'..'SUN'）。
 * BE の専用 enum `ReservationDayOfWeek` に対応する。'MONDAY' 等のフルネームは
 * デシリアライズ失敗で 400 になるため、FE から送る値は必ずこの3文字表記にすること
 * （写経元 ScheduleEventRecurrenceInput.vue の曜日トグルは 'MONDAY' フルネームを emit するため注意）。
 */
export type ReservationDayOfWeekCode = NonNullable<CreateSlotTemplateRequest['dayOfWeek']>

export function useReservationApi() {
  const api = useApi()

  function base(teamId: string) {
    return `/api/v1/teams/${teamId}`
  }

  // === Lines ===
  async function getLines(teamId: string) {
    return api<{ data: ReservationLineResponse[] }>(`${base(teamId)}/reservation-lines`)
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

  // 機能C: 複数予約対象の空きグリッド（列=予約対象／セル=時間帯 state）。
  // BE: GET /reservation-slots/grid は date（単日・必須）＋ staffUserIds（CSV・任意）。
  // 週表示は FE がこの単日 API を7日分呼び出して構成する（レスポンスDTOは2次元を保つ）。
  async function getSlotGrid(teamId: string, params: { date: string; staffUserIds?: number[] }) {
    const query = new URLSearchParams()
    query.set('date', params.date)
    if (params.staffUserIds && params.staffUserIds.length > 0) {
      query.set('staffUserIds', params.staffUserIds.join(','))
    }
    return api<{ data: ReservationGridResponse }>(
      `${base(teamId)}/reservation-slots/grid?${query}`,
    )
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
    return api<{ data: BusinessHourResponse[] }>(`${base(teamId)}/reservation-settings/business-hours`)
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

  // BE: GET /reservation-settings/blocked-times は from/to（取得期間。ISO DATE）が必須クエリ。
  async function listBlockedTimes(teamId: string, params: { from: string; to: string }) {
    const query = new URLSearchParams()
    query.set('from', params.from)
    query.set('to', params.to)
    return api<{ data: BlockedTimeResponse[] }>(
      `${base(teamId)}/reservation-settings/blocked-times?${query}`,
    )
  }

  // 機能B: resourceType/resourceId 対応（生成型 BlockedTimeRequest を使用）。
  async function createBlockedTime(teamId: string, body: BlockedTimeRequest) {
    return api<{ data: BlockedTimeResponse }>(`${base(teamId)}/reservation-settings/blocked-times`, {
      method: 'POST',
      body,
    })
  }

  async function updateBlockedTime(teamId: string, blockedId: number, body: BlockedTimeRequest) {
    return api<{ data: BlockedTimeResponse }>(
      `${base(teamId)}/reservation-settings/blocked-times/${blockedId}`,
      { method: 'PATCH', body },
    )
  }

  async function deleteBlockedTime(teamId: string, blockedId: number) {
    return api(`${base(teamId)}/reservation-settings/blocked-times/${blockedId}`, {
      method: 'DELETE',
    })
  }

  /**
   * 機能B: 予約不可枠 登録前の影響プレビュー。
   * overlap する既存 active 予約（PENDING/CONFIRMED）の件数＋一覧を返す（副作用ゼロ）。
   * BE: GET /reservation-settings/blocked-times/impact
   */
  async function getBlockedTimeImpact(
    teamId: string,
    params: {
      date: string
      resourceType?: BlockedResourceType
      resourceId?: number
      startTime?: string
      endTime?: string
    },
  ) {
    const query = new URLSearchParams()
    query.set('date', params.date)
    if (params.resourceType) query.set('resourceType', params.resourceType)
    if (params.resourceId != null) query.set('resourceId', String(params.resourceId))
    if (params.startTime) query.set('startTime', params.startTime)
    if (params.endTime) query.set('endTime', params.endTime)
    return api<{ data: BlockedTimeImpactResponse }>(
      `${base(teamId)}/reservation-settings/blocked-times/impact?${query}`,
    )
  }

  // === Notification Recipients（機能D・予約通知メール宛先）===
  // teamId 配下の宛先 CRUD。全操作 ADMIN 限定（最終ゲートは BE）。
  // recipientId は UUIDv7（文字列）。GET はフリーミアム状態（enabledCount/freeLimit/maxLimit/hasPaidPlan）を同梱する。
  async function listNotificationRecipients(teamId: string) {
    return api<{ data: NotificationRecipientListResponse }>(
      `${base(teamId)}/reservation-notification-recipients`,
    )
  }

  async function createNotificationRecipient(
    teamId: string,
    body: CreateNotificationRecipientRequest,
  ) {
    return api<{ data: NotificationRecipientResponse }>(
      `${base(teamId)}/reservation-notification-recipients`,
      { method: 'POST', body },
    )
  }

  async function updateNotificationRecipient(
    teamId: string,
    recipientId: string,
    body: UpdateNotificationRecipientRequest,
  ) {
    return api<{ data: NotificationRecipientResponse }>(
      `${base(teamId)}/reservation-notification-recipients/${recipientId}`,
      { method: 'PATCH', body },
    )
  }

  async function deleteNotificationRecipient(teamId: string, recipientId: string) {
    return api(`${base(teamId)}/reservation-notification-recipients/${recipientId}`, {
      method: 'DELETE',
    })
  }

  // === Reservation Menus（機能E・F03.4.1）===
  // ADMIN+ の CRUD 3本 + 会員/公開向け GET。lineIds は「空配列=全ライン提供可」。
  async function getMenus(teamId: string) {
    return api<{ data: ReservationMenuResponse[] }>(`${base(teamId)}/reservation-menus`)
  }

  async function createMenu(teamId: string, body: CreateReservationMenuRequest) {
    return api<{ data: ReservationMenuResponse }>(`${base(teamId)}/reservation-menus`, {
      method: 'POST',
      body,
    })
  }

  async function updateMenu(teamId: string, menuId: string, body: UpdateReservationMenuRequest) {
    return api<{ data: ReservationMenuResponse }>(`${base(teamId)}/reservation-menus/${menuId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteMenu(teamId: string, menuId: string) {
    return api<{ data: ReservationMenuDeleteResponse }>(`${base(teamId)}/reservation-menus/${menuId}`, {
      method: 'DELETE',
    })
  }

  // === 週間テンプレート・一括生成（F03.4.2）===
  // 全5本 ADMIN+ の self-gate。dayOfWeek は必ず 3文字大文字（'MON'..'SUN'）で送ること。
  async function getSlotTemplates(teamId: string) {
    return api<{ data: SlotTemplateListResponse }>(`${base(teamId)}/reservation-slot-templates`)
  }

  async function createSlotTemplate(teamId: string, body: CreateSlotTemplateRequest) {
    return api<{ data: SlotTemplateResponse }>(`${base(teamId)}/reservation-slot-templates`, {
      method: 'POST',
      body,
    })
  }

  async function updateSlotTemplate(teamId: string, templateId: string, body: UpdateSlotTemplateRequest) {
    return api<{ data: SlotTemplateResponse }>(
      `${base(teamId)}/reservation-slot-templates/${templateId}`,
      { method: 'PATCH', body },
    )
  }

  async function deleteSlotTemplate(teamId: string, templateId: string) {
    return api<{ data: DeleteSlotTemplateResponse }>(
      `${base(teamId)}/reservation-slot-templates/${templateId}`,
      { method: 'DELETE' },
    )
  }

  /**
   * チームの active テンプレ全件を対象に、明日〜horizon（既定28日先）までの枠を一括生成する（冪等）。
   * 429（RESERVATION_044）はレートリミット超過。呼び出し元でトースト表示すること。
   */
  async function generateSlotsFromTemplates(teamId: string, body: GenerateSlotsRequest) {
    return api<{ data: GenerateSlotsResponse }>(
      `${base(teamId)}/reservation-slot-templates/generate`,
      { method: 'POST', body },
    )
  }

  // === My Reservations ===
  // BE: GET /reservations/my は ApiResponse<List<ReservationResponse>> ＝ { data: [...] } を返す。
  // meta（ページング情報）は無く、status/page/size クエリも受け付けない（全件返却）。
  // 実体に合わせて戻り型は { data: ReservationResponse[] } のみとし、meta の嘘を持たせない。
  async function listMyReservations() {
    return api<{ data: ReservationResponse[] }>(`/api/v1/reservations/my`)
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
    getSlotGrid,
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
    getBlockedTimeImpact,
    listNotificationRecipients,
    createNotificationRecipient,
    updateNotificationRecipient,
    deleteNotificationRecipient,
    getMenus,
    createMenu,
    updateMenu,
    deleteMenu,
    getSlotTemplates,
    createSlotTemplate,
    updateSlotTemplate,
    deleteSlotTemplate,
    generateSlotsFromTemplates,
    listMyReservations,
    listUpcomingReservations,
    cancelMyReservation,
  }
}
