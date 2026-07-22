import type {
  VillageCalendarEventCreateRequest,
  VillageCalendarEventListParams,
  VillageCalendarEventListResponse,
  VillageCalendarEventResponse,
  VillageCalendarEventUpdateRequest,
  VillageCalendarEventLogResponse,
  VillageCalendarEventLogCreateRequest,
  VillageCalendarEventLogListParams,
  VillageEventArchiveListParams,
  VillageEventArchiveResponse,
  VillageFestivalCreateRequest,
  VillageFestivalLivePostResponse,
  VillageFestivalLivePostTagRequest,
  VillageFestivalResponse,
  VillageFestivalRsvpListParams,
  VillageFestivalRsvpResponse,
  VillageFestivalRsvpUpsertRequest,
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

  // =====================================================================
  // F17.2 Wave2 ③お祭りの参加レイヤー（RSVP・実況）
  // /api/v1/villages/{villageId}/festivals/{festivalId}/...
  // 設計書: docs/features/F17.2_village_events_activation.md §5
  // =====================================================================

  /** 自分の参加表明を登録/更新する（村人・SCHEDULED/ACTIVE のみ・冪等 upsert・§5.6/§4.4.1）。 */
  async function upsertRsvp(
    villageId: string,
    festivalId: string,
    body: VillageFestivalRsvpUpsertRequest,
  ) {
    const res = await api<{ data: VillageFestivalRsvpResponse }>(
      `/api/v1/villages/${villageId}/festivals/${festivalId}/rsvp`,
      { method: 'PUT', body },
    )
    return res.data
  }

  /** 自分の参加表明を取り消す（村人本人・SCHEDULED/ACTIVE のみ・ENDED後は不可・§5.6）。BE は 204。 */
  async function deleteRsvp(villageId: string, festivalId: string): Promise<void> {
    await api(`/api/v1/villages/${villageId}/festivals/${festivalId}/rsvp`, {
      method: 'DELETE',
    })
  }

  /**
   * 参加者一覧（村ニックネーム・GOING/MAYBE別・役割ラベル）。
   *
   * 数百人規模になりうるため必ず size 上限付きページングで呼ぶこと（AC-14b・G3）。
   * 応答は素の配列（BE はページ総数・hasNext を返さない）。
   */
  async function listRsvps(
    villageId: string,
    festivalId: string,
    params?: VillageFestivalRsvpListParams,
  ) {
    const res = await api<{ data: VillageFestivalRsvpResponse[] }>(
      `/api/v1/villages/${villageId}/festivals/${festivalId}/rsvps${qs(params)}`,
    )
    return res.data
  }

  /** 実況タグを付ける（村人・ACTIVE中のみ・二重タグは409 VILLAGE_102・§5.6）。 */
  async function tagLivePost(
    villageId: string,
    festivalId: string,
    body: VillageFestivalLivePostTagRequest,
  ) {
    const res = await api<{ data: VillageFestivalLivePostResponse }>(
      `/api/v1/villages/${villageId}/festivals/${festivalId}/live-posts`,
      { method: 'POST', body },
    )
    return res.data
  }

  /** 実況投稿一覧（村人・timeline側delete済みは除外・§5.6/AC-17c）。 */
  async function listLivePosts(villageId: string, festivalId: string) {
    const res = await api<{ data: VillageFestivalLivePostResponse[] }>(
      `/api/v1/villages/${villageId}/festivals/${festivalId}/live-posts`,
    )
    return res.data
  }

  // =====================================================================
  // F17.2 Wave2 ⑦ 村史（行事アーカイブ）
  // /api/v1/villages/{villageId}/event-archives
  // 設計書: docs/features/F17.2_village_events_activation.md §7
  //
  // ⚠️ BE Controller 未実装（village.ts の該当セクション先頭コメント参照）。
  // Controller が main 済みになるまで、以下の呼び出しは 404 になる。
  // =====================================================================

  /** 村史（行事アーカイブ）一覧（archived_at 降順・§7.4）。 */
  async function listEventArchives(
    villageId: string,
    params?: VillageEventArchiveListParams,
  ) {
    const res = await api<{ data: VillageEventArchiveResponse[] }>(
      `/api/v1/villages/${villageId}/event-archives${qs(params)}`,
    )
    return res.data
  }

  /** 村史（行事アーカイブ）詳細（§7.4）。 */
  async function getEventArchive(villageId: string, archiveId: string) {
    const res = await api<{ data: VillageEventArchiveResponse }>(
      `/api/v1/villages/${villageId}/event-archives/${archiveId}`,
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
    // F17.2 Wave2 ③お祭りの参加レイヤー（RSVP・実況）
    upsertRsvp,
    deleteRsvp,
    listRsvps,
    tagLivePost,
    listLivePosts,
    // F17.2 Wave2 ⑦ 村史（行事アーカイブ）※ BE Controller 未実装（先行実装）
    listEventArchives,
    getEventArchive,
  }
}
