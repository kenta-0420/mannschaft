import type {
  VillageCalendarEventCreateRequest,
  VillageCalendarEventListParams,
  VillageCalendarEventResponse,
  VillageCalendarEventUpdateRequest,
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

  /** 歳時記カレンダー一覧 */
  async function listCalendarEvents(
    villageId: string,
    params?: VillageCalendarEventListParams,
  ) {
    return api<VillageCalendarEventResponse[]>(
      `/api/v1/villages/${villageId}/calendar-events${qs(params)}`,
    )
  }

  /** 歳時記カレンダー詳細 */
  async function getCalendarEvent(villageId: string, id: string) {
    return api<VillageCalendarEventResponse>(
      `/api/v1/villages/${villageId}/calendar-events/${id}`,
    )
  }

  /** 歳時記カレンダー作成 */
  async function createCalendarEvent(
    villageId: string,
    body: VillageCalendarEventCreateRequest,
  ) {
    return api<VillageCalendarEventResponse>(
      `/api/v1/villages/${villageId}/calendar-events`,
      { method: 'POST', body },
    )
  }

  /** 歳時記カレンダー更新 */
  async function updateCalendarEvent(
    villageId: string,
    id: string,
    body: VillageCalendarEventUpdateRequest,
  ) {
    return api<VillageCalendarEventResponse>(
      `/api/v1/villages/${villageId}/calendar-events/${id}`,
      { method: 'PATCH', body },
    )
  }

  /** 歳時記カレンダー削除 */
  async function deleteCalendarEvent(villageId: string, id: string) {
    return api(`/api/v1/villages/${villageId}/calendar-events/${id}`, {
      method: 'DELETE',
    })
  }

  // =====================================================================
  // Phase 2: お祭り (VillageFestivalController)
  // /api/v1/villages/{villageId}/festivals
  // =====================================================================

  /** お祭り一覧 */
  async function listFestivals(villageId: string, status?: VillageFestivalStatus) {
    return api<VillageFestivalResponse[]>(
      `/api/v1/villages/${villageId}/festivals${qs({ status })}`,
    )
  }

  /** お祭り詳細 */
  async function getFestival(villageId: string, id: string) {
    return api<VillageFestivalResponse>(
      `/api/v1/villages/${villageId}/festivals/${id}`,
    )
  }

  /** お祭り作成 */
  async function createFestival(
    villageId: string,
    body: VillageFestivalCreateRequest,
  ) {
    return api<VillageFestivalResponse>(
      `/api/v1/villages/${villageId}/festivals`,
      { method: 'POST', body },
    )
  }

  /** お祭り更新 */
  async function updateFestival(
    villageId: string,
    id: string,
    body: VillageFestivalUpdateRequest,
  ) {
    return api<VillageFestivalResponse>(
      `/api/v1/villages/${villageId}/festivals/${id}`,
      { method: 'PATCH', body },
    )
  }

  /** お祭り中止 */
  async function cancelFestival(villageId: string, id: string) {
    return api<VillageFestivalResponse>(
      `/api/v1/villages/${villageId}/festivals/${id}/cancel`,
      { method: 'POST' },
    )
  }

  return {
    // Phase 2: 歳時記カレンダー
    listCalendarEvents,
    getCalendarEvent,
    createCalendarEvent,
    updateCalendarEvent,
    deleteCalendarEvent,
    // Phase 2: お祭り
    listFestivals,
    getFestival,
    createFestival,
    updateFestival,
    cancelFestival,
  }
}
