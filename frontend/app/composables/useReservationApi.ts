import type { ReservationResponse, ReservationSettingsResponse, UpdateReservationSettingRequest, CreateSlotRequest, UpdateSlotRequest } from '~/types/reservation'
import type { components, operations } from '~/types/generated'

// === 生成型（真実のソース = openapi-typescript）===
// 機能B（予約不可枠）は BE #2109 で resourceType/resourceId/impact を追加済み。
type BlockedTimeRequest = components['schemas']['BlockedTimeRequest'] & { endsNextDay?: boolean }
type BlockedTimeResponse = components['schemas']['BlockedTimeResponse'] & { endsNextDay?: boolean }
type BlockedTimeImpactResponse = components['schemas']['BlockedTimeImpactResponse']
type ReservationLineResponse = components['schemas']['ReservationLineResponse']
type BusinessHourResponse = components['schemas']['BusinessHourResponse'] & {
  businessStatus?: components['schemas']['BusinessHourResponse']['businessStatus'] & { endsNextDay?: boolean }
}
type BusinessHoursUpdateRequest = components['schemas']['BusinessHoursUpdateRequest']
// 機能C（複数予約対象の空きグリッド）は BE #2112 で grid API を追加済み。
// F03.4.4（機能H・#2189）で from/to レンジ・menuId フィルターへ拡張済み。
// #2575 でスタッフ軸（axis=STAFF）と staffUserIds を撤去し、列軸はライン固定になった。
type ReservationGridResponse = components['schemas']['ReservationGridResponse']
// F03.4.3（機能G・予約グループ・#2190）は BE で CRUD API を追加済み。
type CreateReservationGroupRequest = components['schemas']['CreateReservationGroupRequest']
type ReservationGroupResponse = components['schemas']['ReservationGroupResponse']
type CancelReservationGroupRequest = components['schemas']['CancelReservationGroupRequest']
type ReservationGroupCancelResponse = components['schemas']['ReservationGroupCancelResponse']
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
type SlotTemplateListResponse = components['schemas']['SlotTemplateListResponse']
type CreateSlotTemplateRequest = components['schemas']['CreateSlotTemplateRequest'] & { endsNextDay?: boolean }
type UpdateSlotTemplateRequest = components['schemas']['UpdateSlotTemplateRequest'] & { endsNextDay?: boolean }
type DeleteSlotTemplateResponse = components['schemas']['DeleteSlotTemplateResponse']
type GenerateSlotsRequest = components['schemas']['GenerateSlotsRequest']
type GenerateSlotsResponse = components['schemas']['GenerateSlotsResponse']
// F03.4.5 §3.1/§3.3.2（BE #2204）: テンプレ保存＝同期自動生成の統合レスポンス・単日テンプレ適用。
type SlotTemplateSaveResponse = components['schemas']['SlotTemplateSaveResponse']
type GenerateSingleDayRequest = components['schemas']['GenerateSingleDayRequest']
// F03.4.5 §3.2（BE #2204）: 営業時間 PUT の保存＋同期自動生成の統合レスポンス（GET は BusinessHourResponse[] のまま不変）。
type BusinessHoursSaveResponse = components['schemas']['BusinessHoursSaveResponse']
// F03.4.5 §4（BE PR #2232・mergeCommit 71bd24fa1）: 定期予約不可枠（週次繰り返し）CRUD5本＋impact。
type RecurringBlockedTimeResponse = components['schemas']['RecurringBlockedTimeResponse'] & { endsNextDay?: boolean }
type CreateRecurringBlockedTimeRequest = components['schemas']['CreateRecurringBlockedTimeRequest'] & { endsNextDay?: boolean }
type UpdateRecurringBlockedTimeRequest = components['schemas']['UpdateRecurringBlockedTimeRequest'] & { endsNextDay?: boolean }
type RecurringBlockedTimeImpactResponse = components['schemas']['RecurringBlockedTimeImpactResponse']
// F03.4.5 §6.1 W2-4（BE #2206 で着地済み）: キャンセル待ち（waitlist）登録・取消・件数・自分の一覧。
type WaitlistEntryResponse = components['schemas']['WaitlistEntryResponse']
type WaitlistCountResponse = components['schemas']['WaitlistCountResponse']
// F03.4.5 §6.2 W2-5（BE PR #2536・main 着地済み）: 定期予約（毎週繰り返し）・定期不可枠の強行登録。
type CancelReservationRequestBody = components['schemas']['CancelReservationRequest']
/** 会員キャンセルの範囲（THIS_ONLY=当該回のみ・既定 / THIS_AND_FOLLOWING=当該日以降すべて）。管理者キャンセル（cancelByAdmin）は参照しない。 */
export type ReservationCancelScope = NonNullable<CancelReservationRequestBody['scope']>
/**
 * 承認の範囲（THIS_ONLY=当該回のみ・既定 / SERIES=series内のPENDINGを一括承認）。
 * 生成型 `operations['confirmReservation']` のクエリパラメータ由来（専用スキーマ名を持たないインライン enum）。
 */
export type ReservationConfirmScope = NonNullable<
  NonNullable<operations['confirmReservation']['parameters']['query']>['scope']
>

/** 予約不可枠の対象軸（機能B）。MVP で enforce するのは TEAM / STAFF の2軸。 */
export type BlockedResourceType = NonNullable<BlockedTimeRequest['resourceType']>

/**
 * 週間テンプレートの曜日コード（3文字大文字 'MON'..'SUN'）。
 * BE の専用 enum `ReservationDayOfWeek` に対応する。'MONDAY' 等のフルネームは
 * デシリアライズ失敗で 400 になるため、FE から送る値は必ずこの3文字表記にすること
 * （写経元 ScheduleEventRecurrenceInput.vue の曜日トグルは 'MONDAY' フルネームを emit するため注意）。
 */
export type ReservationDayOfWeekCode = NonNullable<CreateSlotTemplateRequest['dayOfWeek']>

/**
 * 営業時間一括更新の1曜日分入力。BE `BusinessHoursUpdateRequest.hours[]`（= `{hours:[...]}` でラップ）と
 * 揃える。dayOfWeek は `ReservationDayOfWeekCode`（3文字大文字 'MON'..'SUN'）を使う。
 * isClosed ではなく isOpen（BE の実フィールド名）で表現する点に注意。
 */
export interface BusinessHoursUpdateHourInput {
  dayOfWeek: ReservationDayOfWeekCode
  isOpen: boolean
  openTime?: string
  closeTime?: string
  endsNextDay?: boolean
}

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

  // 機能C: 空きグリッド（列=予約対象ライン＋共通列／セル=時間帯 state）。
  // BE: GET /reservation-slots/grid は date（単日）または from/to（レンジ・最大7日・排他）＋
  // menuId（提供可能ラインで列を絞る）。列軸はライン固定（#2575 でスタッフ軸を撤去）。
  async function getSlotGrid(
    teamId: string,
    params: { date?: string; from?: string; to?: string; menuId?: string },
  ) {
    const query = new URLSearchParams()
    if (params.date) query.set('date', params.date)
    if (params.from) query.set('from', params.from)
    if (params.to) query.set('to', params.to)
    if (params.menuId) query.set('menuId', params.menuId)
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
    // BE: CreateReservationRequest は reservationSlotId/lineId(@NotNull) + userNote(任意) +
    // repeatWeeks(任意・@Min(1)・省略/1=従来同一・2〜12で定期予約・F03.4.5 §6.2 W2-5)。
    // 上限12超のフロント側ガードは呼び出し元（ReservationForm 等）のUIで行う（12を超える値を作れないUIにする）。
    body: { reservationSlotId: number; lineId: number; userNote?: string; repeatWeeks?: number },
  ) {
    return api<{ data: ReservationResponse }>(`${base(teamId)}/reservations`, { method: 'POST', body })
  }

  async function getReservation(teamId: string, reservationId: number) {
    return api<{ data: unknown }>(`${base(teamId)}/reservations/${reservationId}`)
  }

  /** 管理者キャンセル（`cancelByAdmin`）。scope は参照されない（会員キャンセル専用・§6.2 API契約表）。 */
  async function cancelReservation(teamId: string, reservationId: number, reason?: string) {
    return api<{ data: ReservationResponse }>(`${base(teamId)}/reservations/${reservationId}/cancel`, {
      method: 'POST',
      body: { reason: reason ?? null },
    })
  }

  /**
   * 承認（PENDING→CONFIRMED）。scope=SERIES で series 内の PENDING を一括承認する（既定 THIS_ONLY・
   * F03.4.5 §6.2 W2-5・管理者専用画面から呼ぶ）。scope はクエリパラメータ（生成型 operations['confirmReservation']）。
   */
  async function confirmReservation(teamId: string, reservationId: number, scope?: ReservationConfirmScope) {
    const query = scope ? `?scope=${scope}` : ''
    return api<{ data: ReservationResponse }>(
      `${base(teamId)}/reservations/${reservationId}/confirm${query}`,
      { method: 'POST' },
    )
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

  // === 予約グループ（F03.4.3・機能G）===
  // マトリックスUI（F03.4.4）のメニュー選択→連続枠確保からのみ呼ばれる。単枠（N=1・メニューなし）は
  // 従来どおり createReservation を使う（グループAPIは使わない。§5.3 の確定フロー）。
  async function createGroup(teamId: string, body: CreateReservationGroupRequest) {
    return api<{ data: ReservationGroupResponse }>(`${base(teamId)}/reservation-groups`, {
      method: 'POST',
      body,
    })
  }

  async function getGroup(teamId: string, groupId: string) {
    return api<{ data: ReservationGroupResponse }>(`${base(teamId)}/reservation-groups/${groupId}`)
  }

  /**
   * グループ全枠の一括キャンセル。グループ所属行（group_id 非 null）への単票キャンセルは
   * 400=RESERVATION_042 で拒否されるため、一覧（ReservationList）はグループ行の操作を必ずこちらへ回す。
   */
  async function cancelGroup(teamId: string, groupId: string, cancelReason?: string) {
    const body: CancelReservationGroupRequest = { cancelReason: cancelReason ?? undefined }
    return api<{ data: ReservationGroupCancelResponse }>(
      `${base(teamId)}/reservation-groups/${groupId}/cancel`,
      { method: 'POST', body },
    )
  }

  /** グループ全枠の手動承認（PENDING→CONFIRMED）。理由は cancelGroup と同じく単票 400=042 回避。 */
  async function confirmGroup(teamId: string, groupId: string) {
    return api<{ data: ReservationGroupResponse }>(
      `${base(teamId)}/reservation-groups/${groupId}/confirm`,
      { method: 'POST' },
    )
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
    // GET は F03.4.5 §3.2 で不変（BusinessHourResponse[] のまま）。PUT のみ応答型が変わる。
    return api<{ data: BusinessHourResponse[] }>(`${base(teamId)}/reservation-settings/business-hours`)
  }

  /**
   * 営業時間を一括更新する（ADMIN限定）。F03.4.5 §3.2: 保存＋「変更のあった曜日」の同期自動生成結果を
   * `BusinessHoursSaveResponse{ hours, generation }` で受け取る（旧 `BusinessHourResponse[]` 直返しではない）。
   */
  async function updateBusinessHours(teamId: string, hours: BusinessHoursUpdateHourInput[]) {
    // BE DTO は配列直渡しではなく { hours: [...] } でラップし、dayOfWeek は3文字大文字（'MON'..'SUN'）、
    // 開閉フラグは isOpen（isClosed ではない）。生成型 BusinessHoursUpdateRequest と構造一致させる。
    const body: BusinessHoursUpdateRequest = { hours }
    return api<{ data: BusinessHoursSaveResponse }>(`${base(teamId)}/reservation-settings/business-hours`, {
      method: 'PUT',
      body,
    })
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

  // === 定期予約不可枠（F03.4.5 §4・週次繰り返し）===
  // 全5本 ADMIN+ の self-gate。dayOfWeek は必ず3文字大文字（'MON'..'SUN'）で送ること
  // （フルネーム 'MONDAY' 等は Jackson デシリアライズ失敗で 400・§4.6/§10）。
  async function listRecurringBlockedTimes(teamId: string) {
    return api<{ data: RecurringBlockedTimeResponse[] }>(
      `${base(teamId)}/reservation-recurring-blocked-times`,
    )
  }

  async function createRecurringBlockedTime(teamId: string, body: CreateRecurringBlockedTimeRequest) {
    return api<{ data: RecurringBlockedTimeResponse }>(
      `${base(teamId)}/reservation-recurring-blocked-times`,
      { method: 'POST', body },
    )
  }

  async function updateRecurringBlockedTime(
    teamId: string,
    ruleId: string,
    body: UpdateRecurringBlockedTimeRequest,
  ) {
    return api<{ data: RecurringBlockedTimeResponse }>(
      `${base(teamId)}/reservation-recurring-blocked-times/${ruleId}`,
      { method: 'PATCH', body },
    )
  }

  async function deleteRecurringBlockedTime(teamId: string, ruleId: string) {
    return api(`${base(teamId)}/reservation-recurring-blocked-times/${ruleId}`, {
      method: 'DELETE',
    })
  }

  /**
   * 定期予約不可枠 作成/更新前の影響プレビュー（副作用ゼロ）。
   * 今後90日以内に overlap する active 予約（PENDING/CONFIRMED）の件数＋一覧を返す（§4.3）。
   */
  async function getRecurringBlockedTimeImpact(
    teamId: string,
    params: { dayOfWeek: ReservationDayOfWeekCode; startTime: string; endTime: string; endsNextDay?: boolean; lineId?: number },
  ) {
    const query = new URLSearchParams()
    query.set('dayOfWeek', params.dayOfWeek)
    query.set('startTime', params.startTime)
    query.set('endTime', params.endTime)
    if (params.endsNextDay) query.set('endsNextDay', 'true')
    if (params.lineId != null) query.set('lineId', String(params.lineId))
    return api<{ data: RecurringBlockedTimeImpactResponse }>(
      `${base(teamId)}/reservation-recurring-blocked-times/impact?${query}`,
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

  /**
   * テンプレを作成する（ADMIN限定）。F03.4.5 §3.1: 保存＋当該テンプレの同期自動生成（horizon 28日）の
   * 結果を `SlotTemplateSaveResponse{ template, generation }` で受け取る（旧 `SlotTemplateResponse`
   * 単体返しではない。「今すぐ枠を作成」を押さずに枠が生成される契約）。
   */
  async function createSlotTemplate(teamId: string, body: CreateSlotTemplateRequest) {
    return api<{ data: SlotTemplateSaveResponse }>(`${base(teamId)}/reservation-slot-templates`, {
      method: 'POST',
      body,
    })
  }

  /** テンプレを部分更新する（ADMIN限定）。応答は createSlotTemplate と同型の SlotTemplateSaveResponse。 */
  async function updateSlotTemplate(teamId: string, templateId: string, body: UpdateSlotTemplateRequest) {
    return api<{ data: SlotTemplateSaveResponse }>(
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

  /**
   * 臨時営業（単日テンプレ適用・F03.4.5 §3.3.2）: 指定日に、指定曜日ダイヤ（省略時=実曜日）の
   * active テンプレ構成で枠を一括生成する（営業時間突合をスキップ・冪等）。
   * 例外日カレンダー（⑤タブ・第二隊 W2-1 後段）から呼ばれる想定のため、ここでは API 結線のみ用意する。
   */
  async function generateSingleDaySlots(teamId: string, body: GenerateSingleDayRequest) {
    return api<{ data: GenerateSlotsResponse }>(
      `${base(teamId)}/reservation-slot-templates/generate-single-day`,
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

  /**
   * 自分の予約をキャンセルする（`cancelByUser`）。
   * `scope`（既定 THIS_ONLY・THIS_AND_FOLLOWING で series の当該日以降をまとめてキャンセル）を
   * 参照するのはこの経路のみ（会員キャンセル専用・F03.4.5 §6.2 W2-5）。
   */
  async function cancelMyReservation(
    reservationId: number,
    opts?: { reason?: string; scope?: ReservationCancelScope },
  ) {
    // BE: ReservationCommonController#cancelMyReservation は CancelReservationRequest を必須とするため body を渡す
    return api<{ data: ReservationResponse }>(`/api/v1/reservations/${reservationId}/cancel`, {
      method: 'POST',
      body: { reason: opts?.reason ?? null, scope: opts?.scope },
    })
  }

  // === キャンセル待ち（waitlist・F03.4.5 §6.1 W2-4）===
  // 登録・取消は会員/公開の view ゲート（BE Service 層）で認可。件数のみ ADMIN 専用（403 になるため
  // isAdmin=false のときは呼ばないこと）。

  /**
   * 満席枠へキャンセル待ちを登録する。
   * エラー: 409=RESERVATION_047（二重登録）/ 400=RESERVATION_048（空きあり）/
   * 400=RESERVATION_049（上限超過）/ 429=RESERVATION_050（レートリミット）。
   */
  async function joinWaitlist(teamId: string, slotId: number) {
    return api<{ data: WaitlistEntryResponse }>(
      `${base(teamId)}/reservation-slots/${slotId}/waitlist`,
      { method: 'POST' },
    )
  }

  /**
   * 自分の WAITING を取消する（本人。(slot, 本人) で解決するため他人のエントリは掴めない）。
   * エラー: 404=RESERVATION_046（対象なし・IDOR秘匿）。
   */
  async function leaveWaitlist(teamId: string, slotId: number) {
    return api(`${base(teamId)}/reservation-slots/${slotId}/waitlist`, { method: 'DELETE' })
  }

  /** 枠別のキャンセル待ち件数（ADMIN 専用）。非 ADMIN が呼ぶと 403。 */
  async function getWaitlistCount(teamId: string, slotId: number) {
    return api<{ data: WaitlistCountResponse }>(
      `${base(teamId)}/reservation-slots/${slotId}/waitlist/count`,
    )
  }

  /** 自分のキャンセル待ち一覧（全チーム横断・WAITING のみ・新しい順）。 */
  async function listMyWaitlist() {
    return api<{ data: WaitlistEntryResponse[] }>(`/api/v1/users/me/reservation-waitlist`)
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
    createGroup,
    getGroup,
    cancelGroup,
    confirmGroup,
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
    listRecurringBlockedTimes,
    createRecurringBlockedTime,
    updateRecurringBlockedTime,
    deleteRecurringBlockedTime,
    getRecurringBlockedTimeImpact,
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
    generateSingleDaySlots,
    listMyReservations,
    listUpcomingReservations,
    cancelMyReservation,
    joinWaitlist,
    leaveWaitlist,
    getWaitlistCount,
    listMyWaitlist,
  }
}
