import type {
  VillageCreateRequest,
  VillageFeedResponse,
  VillageInternalSearchParams,
  VillageInternalSearchResponse,
  VillageResponse,
  VillageSearchParams,
  VillageSearchResponse,
  VillageUpdateRequest,
  LobbyChannelResponse,
  DailyThreadListResponse,
  DailyThreadResponse,
} from '~/types/village'

/**
 * F17.1 村機能 API composable — 村本体・ロビー・村内検索・横断フィード
 *
 * Backend Controller: backend/src/main/java/com/mannschaft/app/village/controller/*.java
 * 設計書: docs/features/F17.1_village_community.md §4
 */
export function useVillageApi() {
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
  // 村本体 (VillageController) — /api/v1/villages
  // =====================================================================

  /** §4.2 村検索 */
  async function searchVillages(params?: VillageSearchParams) {
    return api<VillageSearchResponse>(`/api/v1/villages/search${qs(params)}`)
  }

  /** §4.1.2 村詳細 */
  async function getVillage(villageId: string) {
    return api<VillageResponse>(`/api/v1/villages/${villageId}`)
  }

  /** §4.1.1 村作成（運営/承認自動経路） */
  async function createVillage(body: VillageCreateRequest) {
    return api<VillageResponse>('/api/v1/villages', { method: 'POST', body })
  }

  /** §4.1.3 村更新 */
  async function updateVillage(villageId: string, body: VillageUpdateRequest) {
    return api<VillageResponse>(`/api/v1/villages/${villageId}`, { method: 'PATCH', body })
  }

  /** §4.1.4 村削除（論理削除） */
  async function deleteVillage(villageId: string) {
    return api(`/api/v1/villages/${villageId}`, { method: 'DELETE' })
  }

  /** §4.1.5 村凍結 */
  async function archiveVillage(villageId: string) {
    return api<VillageResponse>(`/api/v1/villages/${villageId}/archive`, { method: 'POST' })
  }

  // =====================================================================
  // ロビー (VillageLobbyController) — /api/v1/villages/{villageId}/lobby
  // =====================================================================

  /** §4.10.1 ロビーチャネル取得 */
  async function getLobbyChannel(villageId: string) {
    return api<LobbyChannelResponse>(`/api/v1/villages/${villageId}/lobby`)
  }

  /** §4.10.2 日次スレッド一覧 */
  async function listDailyThreads(villageId: string, days?: number) {
    return api<DailyThreadListResponse>(
      `/api/v1/villages/${villageId}/lobby/daily${qs({ days })}`,
    )
  }

  /** §4.10.3 指定日の日次スレッド */
  async function getDailyThread(villageId: string, date: string) {
    return api<DailyThreadResponse>(`/api/v1/villages/${villageId}/lobby/daily/${date}`)
  }

  // =====================================================================
  // 村内検索 (VillageSearchController)
  // =====================================================================

  /** §4.13 村内検索（投稿/メッセージ/メンバー） */
  async function searchVillageInternal(villageId: string, params: VillageInternalSearchParams) {
    return api<VillageInternalSearchResponse>(
      `/api/v1/villages/${villageId}/search${qs(params)}`,
    )
  }

  // =====================================================================
  // 横断フィード (VillageFeedController) — /api/v1/me/village-feed
  // =====================================================================

  /** §4.12 自分の横断フィード（ピン村サマリ同梱） */
  async function getFeed() {
    return api<VillageFeedResponse>('/api/v1/me/village-feed')
  }

  return {
    // 村本体
    searchVillages,
    getVillage,
    createVillage,
    updateVillage,
    deleteVillage,
    archiveVillage,
    // ロビー
    getLobbyChannel,
    listDailyThreads,
    getDailyThread,
    // 村内検索
    searchVillageInternal,
    // 横断フィード
    getFeed,
  }
}
