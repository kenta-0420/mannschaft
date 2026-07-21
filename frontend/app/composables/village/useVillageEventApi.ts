import type {
  VillageCalendarEventCreateRequest,
  VillageCalendarEventListParams,
  VillageCalendarEventListResponse,
  VillageCalendarEventResponse,
  VillageCalendarEventUpdateRequest,
  VillageCalendarEventLogResponse,
  VillageCalendarEventLogCreateRequest,
  VillageCalendarEventLogListParams,
  VillageFestivalCreateRequest,
  VillageFestivalResponse,
  VillageFestivalStatus,
  VillageFestivalUpdateRequest,
} from '~/types/village'

/**
 * F17.1 村機能 API composable — 歳時記カレンダー・お祭り
 *
 * Backend Controller: backend/src/main/java/com/mannschaft/app/village/controller/*.java
 * 設計書: docs/features/F17.1_village_community.md §4 Phase 2
 */
export function useVillageEventApi() {
  const api = useApi()

  // クエリ文字列ヘルパー
  function qs(params?: object | null): string {
    if (!params) return ''
    const u = new URLSearchParams()
    for (const [k, v] of Object.entries(params as Record<string, unknown>)) {
      if (v !== undefined && v !== null && v !== '') u.set(k, String(v))
    }
    const s = u.toString()
    return s ? `?${s}` : ''
  }

  // =====================================================================
  // Phase 2: 歳時記カレンダー (VillageCalendarEventController)
  // /api/v1/villages/{villageId}/calendar-events
  // =====================================================================

  /** 歳時記カレンダー月別一覧（BE は `{items, year, month}` エンベロープを返す） */
  async function listCalendarEvents(
    villageId: string,
    params?: VillageCalendarEventListParams,
  ) {
    const res = await api<{ data: VillageCalendarEventListResponse }>(
      `/api/v1/villages/${villageId}/calendar-events${qs(params)}`,
    )
    return res.data
  }

  /** 歳時記カレンダー詳細 */
  async function getCalendarEvent(villageId: string, id: string) {
    const res = await api<{ data: VillageCalendarEventResponse }>(
      `/api/v1/villages/${villageId}/calendar-events/${id}`,
    )
    return res.data
  }

  /** 歳時記カレンダー作成 */
  async function createCalendarEvent(
    villageId: string,
    body: VillageCalendarEventCreateRequest,
  ) {
    const res = await api<{ data: VillageCalendarEventResponse }>(
      `/api/v1/villages/${villageId}/calendar-events`,
      { method: 'POST', body },
    )
    return res.data
  }

  /** 歳時記カレンダー更新 */
  async function updateCalendarEvent(
    villageId: string,
    id: string,
    body: VillageCalendarEventUpdateRequest,
  ) {
    const res = await api<{ data: VillageCalendarEventResponse }>(
      `/api/v1/villages/${villageId}/calendar-events/${id}`,
      { method: 'PATCH', body },
    )
    return res.data
  }

  /** 歳時記カレンダー削除 */
  async function deleteCalendarEvent(villageId: string, id: string) {
    return api(`/api/v1/villages/${villageId}/calendar-events/${id}`, {
      method: 'DELETE',
    })
  }

  // =====================================================================
  // F17.2 Wave1 ④歳時記×村史の年輪（去年の様子）
  // /api/v1/villages/{villageId}/calendar-events/{eventId}/logs
  // 設計書: docs/features/F17.2_village_events_activation.md §6
  // =====================================================================

  /** 年輪一覧を取得する（村人・year 降順→作成日降順・?year= 絞り込み可）。 */
  async function listCalendarEventLogs(
    villageId: string,
    eventId: string,
    params?: VillageCalendarEventLogListParams,
  ) {
    const res = await api<{ data: VillageCalendarEventLogResponse[] }>(
      `/api/v1/villages/${villageId}/calendar-events/${eventId}/logs${qs(params)}`,
    )
    return res.data
  }

  /** 年輪を追加する（村人・同一 year に複数件可）。 */
  async function addCalendarEventLog(
    villageId: string,
    eventId: string,
    body: VillageCalendarEventLogCreateRequest,
  ) {
    const res = await api<{ data: VillageCalendarEventLogResponse }>(
      `/api/v1/villages/${villageId}/calendar-events/${eventId}/logs`,
      { method: 'POST', body },
    )
    return res.data
  }

  /** 年輪を論理削除する（投稿者本人＋村長/長老のみ）。BE は 204 No Content。 */
  async function deleteCalendarEventLog(
    villageId: string,
    eventId: string,
    logId: string,
  ): Promise<void> {
    await api(
      `/api/v1/villages/${villageId}/calendar-events/${eventId}/logs/${logId}`,
      { method: 'DELETE' },
    )
  }

  // =====================================================================
  // Phase 2: お祭り (VillageFestivalController)
  // /api/v1/villages/{villageId}/festivals
  // =====================================================================

  /** お祭り一覧 */
  async function listFestivals(villageId: string, status?: VillageFestivalStatus) {
    const res = await api<{ data: VillageFestivalResponse[] }>(
      `/api/v1/villages/${villageId}/festivals${qs({ status })}`,
    )
    return res.data
  }

  /** お祭り詳細 */
  async function getFestival(villageId: string, id: string) {
    const res = await api<{ data: VillageFestivalResponse }>(
      `/api/v1/villages/${villageId}/festivals/${id}`,
    )
    return res.data
  }

  /** お祭り作成 */
  async function createFestival(
    villageId: string,
    body: VillageFestivalCreateRequest,
  ) {
    const res = await api<{ data: VillageFestivalResponse }>(
      `/api/v1/villages/${villageId}/festivals`,
      { method: 'POST', body },
    )
    return res.data
  }

  /** お祭り更新 */
  async function updateFestival(
    villageId: string,
    id: string,
    body: VillageFestivalUpdateRequest,
  ) {
    const res = await api<{ data: VillageFestivalResponse }>(
      `/api/v1/villages/${villageId}/festivals/${id}`,
      { method: 'PATCH', body },
    )
    return res.data
  }

  /** お祭り中止 */
  async function cancelFestival(villageId: string, id: string) {
    const res = await api<{ data: VillageFestivalResponse }>(
      `/api/v1/villages/${villageId}/festivals/${id}/cancel`,
      { method: 'POST' },
    )
    return res.data
  }

  return {
    // Phase 2: 歳時記カレンダー
    listCalendarEvents,
    getCalendarEvent,
    createCalendarEvent,
    updateCalendarEvent,
    deleteCalendarEvent,
    // F17.2 Wave1 ④歳時記×村史の年輪
    listCalendarEventLogs,
    addCalendarEventLog,
    deleteCalendarEventLog,
    // Phase 2: お祭り
    listFestivals,
    getFestival,
    createFestival,
    updateFestival,
    cancelFestival,
  }
}
