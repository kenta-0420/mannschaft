import type {
  CreationRequestListParams,
  DailyThreadListResponse,
  DailyThreadResponse,
  JoinRequestCreateRequest,
  JoinRequestResponse,
  JoinRequestReviewRequest,
  LobbyChannelResponse,
  MembershipBanRequest,
  MembershipJoinRequest,
  MembershipListParams,
  MembershipListResponse,
  MembershipResponse,
  PinListResponse,
  PinOrderUpdateRequest,
  PinResponse,
  PostingIdentityListResponse,
  ReportCreateRequest,
  ReportListParams,
  ReportResolveRequest,
  ReportResponse,
  RoleChangeRequest,
  VillageCreateRequest,
  VillageCreationRequestCreateRequest,
  VillageCreationRequestResponse,
  VillageCreationRequestReviewRequest,
  VillageFeedResponse,
  VillageInternalSearchParams,
  VillageInternalSearchResponse,
  VillageNicknameResponse,
  VillageNicknameUpdateRequest,
  VillageRequestStatus,
  VillageResponse,
  VillageSearchParams,
  VillageSearchResponse,
  VillageUpdateRequest,
} from '~/types/village'

/**
 * F17.1 村機能 API composable
 *
 * Backend Controller: backend/src/main/java/com/mannschaft/app/village/controller/*.java
 * 設計書: docs/features/F17.1_village_community.md §4
 *
 * パターン:
 *   - useApi() ベースの ofetch ラッパーを使用
 *   - レスポンスは Backend 仕様により `{ data: T }` でも素の T でもあり得るため、
 *     既存実装 (useTournamentApi) と同様に `<{ data: T }>` または `<T>` を個別指定
 *   - Backend は VillageController で純粋な DTO を返すケースが多いため、
 *     `data` ラップなしで型指定する設計
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
  // メンバーシップ (VillageMembershipController)
  // /api/v1/villages/{villageId}/memberships
  // =====================================================================

  /** §4.4.1 参加 */
  async function joinVillage(villageId: string, body: MembershipJoinRequest) {
    return api<MembershipResponse>(`/api/v1/villages/${villageId}/memberships`, {
      method: 'POST',
      body,
    })
  }

  /** §4.4.2 退村 */
  async function leaveVillage(villageId: string, membershipId: string) {
    return api(`/api/v1/villages/${villageId}/memberships/${membershipId}`, { method: 'DELETE' })
  }

  /** §4.4.3 メンバー一覧 */
  async function listMembers(villageId: string, params?: MembershipListParams) {
    return api<MembershipListResponse>(
      `/api/v1/villages/${villageId}/memberships${qs(params)}`,
    )
  }

  /** §4.4.4 ロール変更 */
  async function changeRole(villageId: string, membershipId: string, body: RoleChangeRequest) {
    return api<MembershipResponse>(
      `/api/v1/villages/${villageId}/memberships/${membershipId}/role`,
      { method: 'PATCH', body },
    )
  }

  /** §4.4.5 BAN */
  async function banMember(villageId: string, membershipId: string, body: MembershipBanRequest) {
    return api<MembershipResponse>(
      `/api/v1/villages/${villageId}/memberships/${membershipId}/ban`,
      { method: 'POST', body },
    )
  }

  // =====================================================================
  // 村作成申請 (VillageCreationRequestController)
  // =====================================================================

  /** §4.6.1 申請作成 */
  async function createCreationRequest(body: VillageCreationRequestCreateRequest) {
    return api<VillageCreationRequestResponse>('/api/v1/villages/creation-requests', {
      method: 'POST',
      body,
    })
  }

  /** §4.6.2 自分の申請一覧 */
  async function listMyCreationRequests() {
    return api<VillageCreationRequestResponse[]>('/api/v1/me/village-creation-requests')
  }

  /** §4.6.3 管理者向け一覧 */
  async function listAdminCreationRequests(params?: CreationRequestListParams) {
    return api<VillageCreationRequestResponse[]>(
      `/api/v1/admin/village-creation-requests${qs(params)}`,
    )
  }

  /** §4.6.4 承認 */
  async function approveCreationRequest(id: string, body: VillageCreationRequestReviewRequest) {
    return api<VillageCreationRequestResponse>(
      `/api/v1/admin/village-creation-requests/${id}/approve`,
      { method: 'POST', body },
    )
  }

  /** §4.6.5 却下 */
  async function rejectCreationRequest(id: string, body: VillageCreationRequestReviewRequest) {
    return api<VillageCreationRequestResponse>(
      `/api/v1/admin/village-creation-requests/${id}/reject`,
      { method: 'POST', body },
    )
  }

  /** §4.6.6 取下げ */
  async function withdrawCreationRequest(id: string) {
    return api<VillageCreationRequestResponse>(
      `/api/v1/admin/village-creation-requests/${id}/withdraw`,
      { method: 'POST' },
    )
  }

  /**
   * §4.6 統合 reviewer ヘルパ。
   * action ごとに対応エンドポイントへルーティングする。
   */
  async function reviewCreationRequest(
    id: string,
    action: 'approve' | 'reject' | 'withdraw',
    body: VillageCreationRequestReviewRequest = {},
  ) {
    switch (action) {
      case 'approve':
        return approveCreationRequest(id, body)
      case 'reject':
        return rejectCreationRequest(id, body)
      case 'withdraw':
        return withdrawCreationRequest(id)
    }
  }

  // =====================================================================
  // 参加申請 (VillageJoinRequestController)
  // /api/v1/villages/{villageId}/join-requests
  // =====================================================================

  /** §4.5.1 参加申請作成 */
  async function createJoinRequest(villageId: string, body: JoinRequestCreateRequest) {
    return api<JoinRequestResponse>(`/api/v1/villages/${villageId}/join-requests`, {
      method: 'POST',
      body,
    })
  }

  /** §4.5.2 申請一覧（村長/長老向け） */
  async function listJoinRequests(villageId: string, status?: VillageRequestStatus) {
    return api<JoinRequestResponse[]>(
      `/api/v1/villages/${villageId}/join-requests${qs({ status })}`,
    )
  }

  /** §4.5.3 承認 */
  async function approveJoinRequest(
    villageId: string,
    id: string,
    body: JoinRequestReviewRequest,
  ) {
    return api<JoinRequestResponse>(
      `/api/v1/villages/${villageId}/join-requests/${id}/approve`,
      { method: 'POST', body },
    )
  }

  /** §4.5.4 却下 */
  async function rejectJoinRequest(
    villageId: string,
    id: string,
    body: JoinRequestReviewRequest,
  ) {
    return api<JoinRequestResponse>(
      `/api/v1/villages/${villageId}/join-requests/${id}/reject`,
      { method: 'POST', body },
    )
  }

  /** §4.5.5 取下げ */
  async function withdrawJoinRequest(villageId: string, id: string) {
    return api<JoinRequestResponse>(
      `/api/v1/villages/${villageId}/join-requests/${id}/withdraw`,
      { method: 'POST' },
    )
  }

  /** §4.5 統合 reviewer ヘルパ */
  async function reviewJoinRequest(
    villageId: string,
    id: string,
    action: 'approve' | 'reject' | 'withdraw',
    body: JoinRequestReviewRequest = {},
  ) {
    switch (action) {
      case 'approve':
        return approveJoinRequest(villageId, id, body)
      case 'reject':
        return rejectJoinRequest(villageId, id, body)
      case 'withdraw':
        return withdrawJoinRequest(villageId, id)
    }
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
    // メンバーシップ
    joinVillage,
    leaveVillage,
    listMembers,
    changeRole,
    banMember,
    // 村作成申請
    createCreationRequest,
    listMyCreationRequests,
    listAdminCreationRequests,
    approveCreationRequest,
    rejectCreationRequest,
    withdrawCreationRequest,
    reviewCreationRequest,
    // 参加申請
    createJoinRequest,
    listJoinRequests,
    approveJoinRequest,
    rejectJoinRequest,
    withdrawJoinRequest,
    reviewJoinRequest,
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
    // ロビー
    getLobbyChannel,
    listDailyThreads,
    getDailyThread,
    // 通報
    createReport,
    listReports,
    resolveReport,
    // 村内検索
    searchVillageInternal,
    // 横断フィード
    getFeed,
  }
}
