import type {
  PinListResponse,
  PinOrderUpdateRequest,
  PinResponse,
  PostingIdentityListResponse,
  ReportCreateRequest,
  ReportListParams,
  ReportResolveRequest,
  ReportResponse,
  VillageNicknameResponse,
  VillageNicknameUpdateRequest,
  VillageRepresentativeGrantRequest,
  VillageRepresentativeResponse,
  VillageRepresentativeRevokeRequest,
  VillageResponse,
} from '~/types/village'

/**
 * F17.1 村機能 API composable — ニックネーム・投稿主体・ピン・通報・代表委任・村紋
 *
 * Backend Controller: backend/src/main/java/com/mannschaft/app/village/controller/*.java
 * 設計書: docs/features/F17.1_village_community.md §4.7 / §4.8 / §4.9 / §4.11 / §3.11 / §13.2
 */
export function useVillageFeatureApi() {
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
  // ニックネーム (VillageNicknameController) — /api/v1/me/village-nickname
  // =====================================================================

  /** §4.7.1 取得 */
  async function getMyNickname() {
    return api<VillageNicknameResponse>('/api/v1/me/village-nickname')
  }

  /** §4.7.2 更新 */
  async function updateNickname(body: VillageNicknameUpdateRequest) {
    return api<VillageNicknameResponse>('/api/v1/me/village-nickname', {
      method: 'PUT',
      body,
    })
  }

  // =====================================================================
  // 投稿主体 (PostingIdentityController)
  // /api/v1/me/villages/{villageId}/posting-identities
  // =====================================================================

  /** §4.8 投稿可能な主体一覧 */
  async function listPostingIdentities(villageId: string) {
    return api<PostingIdentityListResponse>(
      `/api/v1/me/villages/${villageId}/posting-identities`,
    )
  }

  // =====================================================================
  // ピン (VillagePinController) — /api/v1/me/village-pins
  // =====================================================================

  /** §4.9.1 一覧 */
  async function listPins() {
    return api<PinListResponse>('/api/v1/me/village-pins')
  }

  /** §4.9.2 追加 */
  async function addPin(villageId: string) {
    return api<PinResponse>(`/api/v1/me/village-pins/${villageId}`, { method: 'POST' })
  }

  /** §4.9.3 解除 */
  async function removePin(villageId: string) {
    return api(`/api/v1/me/village-pins/${villageId}`, { method: 'DELETE' })
  }

  /** §4.9.4 並び替え */
  async function updatePinOrder(body: PinOrderUpdateRequest) {
    return api<PinListResponse>('/api/v1/me/village-pins/order', { method: 'PATCH', body })
  }

  // =====================================================================
  // 通報 (VillageReportController) — /api/v1/villages/{villageId}/reports
  // =====================================================================

  /** §4.11.1 通報送信 */
  async function createReport(villageId: string, body: ReportCreateRequest) {
    return api<ReportResponse>(`/api/v1/villages/${villageId}/reports`, {
      method: 'POST',
      body,
    })
  }

  /** §4.11.2 通報一覧（村長/長老/運営向け） */
  async function listReports(villageId: string, params?: ReportListParams) {
    return api<ReportResponse[]>(
      `/api/v1/villages/${villageId}/reports${qs(params)}`,
    )
  }

  /** §4.11.3 通報解決 */
  async function resolveReport(villageId: string, reportId: string, body: ReportResolveRequest) {
    return api<ReportResponse>(
      `/api/v1/villages/${villageId}/reports/${reportId}/resolve`,
      { method: 'POST', body },
    )
  }

  // =====================================================================
  // Phase 2: 代表委任 (VillageRepresentativeController)
  // /api/v1/villages/{villageId}/representatives
  //
  // 注意: Phase 2 の Backend Controller は未実装。
  // ここでの URL は設計書 §3.11 / §13.2 に基づく推測であり、
  // Backend 完成後に微調整する可能性がある。
  // =====================================================================

  /** 代表委任一覧 */
  async function listRepresentatives(villageId: string) {
    return api<VillageRepresentativeResponse[]>(
      `/api/v1/villages/${villageId}/representatives`,
    )
  }

  /** 代表委任を発行 */
  async function grantRepresentative(
    villageId: string,
    body: VillageRepresentativeGrantRequest,
  ) {
    return api<VillageRepresentativeResponse>(
      `/api/v1/villages/${villageId}/representatives`,
      { method: 'POST', body },
    )
  }

  /** 代表委任を取消 */
  async function revokeRepresentative(
    villageId: string,
    id: string,
    body: VillageRepresentativeRevokeRequest,
  ) {
    return api<VillageRepresentativeResponse>(
      `/api/v1/villages/${villageId}/representatives/${id}/revoke`,
      { method: 'POST', body },
    )
  }

  // =====================================================================
  // Phase 2: 村紋アップロード (VillageMonshoController)
  // /api/v1/villages/{villageId}/monsho
  //
  // 設計書 §13.2: villages.monsho_r2_key 用。
  // multipart/form-data で画像をアップロードし、R2 キーを村本体に紐付ける。
  // =====================================================================

  /** 村紋画像をアップロード（multipart/form-data） */
  async function uploadMonsho(villageId: string, file: File) {
    const form = new FormData()
    form.append('file', file)
    return api<VillageResponse>(
      `/api/v1/villages/${villageId}/monsho`,
      { method: 'POST', body: form },
    )
  }

  return {
    // ニックネーム
    getMyNickname,
    updateNickname,
    // 投稿主体
    listPostingIdentities,
    // ピン
    listPins,
    addPin,
    removePin,
    updatePinOrder,
    // 通報
    createReport,
    listReports,
    resolveReport,
    // Phase 2: 代表委任
    listRepresentatives,
    grantRepresentative,
    revokeRepresentative,
    // Phase 2: 村紋
    uploadMonsho,
  }
}
