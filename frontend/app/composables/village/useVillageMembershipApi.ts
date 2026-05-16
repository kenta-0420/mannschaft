import type {
  CreationRequestListParams,
  JoinRequestCreateRequest,
  JoinRequestResponse,
  JoinRequestReviewRequest,
  MembershipBanRequest,
  MembershipJoinRequest,
  MembershipListParams,
  MembershipListResponse,
  MembershipResponse,
  RoleChangeRequest,
  VillageCreationRequestCreateRequest,
  VillageCreationRequestResponse,
  VillageCreationRequestReviewRequest,
  VillageRequestStatus,
} from '~/types/village'

/**
 * F17.1 村機能 API composable — メンバーシップ・参加申請・村作成申請
 *
 * Backend Controller: backend/src/main/java/com/mannschaft/app/village/controller/*.java
 * 設計書: docs/features/F17.1_village_community.md §4.4 / §4.5 / §4.6
 */
export function useVillageMembershipApi() {
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

  return {
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
  }
}
