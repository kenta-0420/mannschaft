import type {
  MemberInfoFieldResponse,
  CreateMemberInfoFieldRequest,
  UpdateMemberInfoFieldRequest,
  ReorderMemberInfoFieldsRequest,
  UpsertMemberInfoResponseRequest,
  MemberInfoResponseMeItem,
  MemberInfoStatusResponse,
} from '~/types/memberInfo'

interface ApiResponse<T> {
  data: T
}

/**
 * F14.2 メンバー情報管理 API クライアント。
 * /api/v1/teams/{teamId}/member-info を扱う。
 */
export function useMemberInfoApi() {
  const api = useApi()

  // ===========================================
  // フィールド定義管理
  // ===========================================

  async function getFields(teamId: number) {
    return api<ApiResponse<MemberInfoFieldResponse[]>>(
      `/api/v1/teams/${teamId}/member-info/fields`,
    )
  }

  async function createField(teamId: number, request: CreateMemberInfoFieldRequest) {
    return api<ApiResponse<MemberInfoFieldResponse>>(
      `/api/v1/teams/${teamId}/member-info/fields`,
      { method: 'POST', body: request },
    )
  }

  async function updateField(
    teamId: number,
    fieldId: number,
    request: UpdateMemberInfoFieldRequest,
  ) {
    return api<ApiResponse<MemberInfoFieldResponse>>(
      `/api/v1/teams/${teamId}/member-info/fields/${fieldId}`,
      { method: 'PUT', body: request },
    )
  }

  async function deleteField(teamId: number, fieldId: number) {
    return api(`/api/v1/teams/${teamId}/member-info/fields/${fieldId}`, { method: 'DELETE' })
  }

  async function reorderFields(teamId: number, request: ReorderMemberInfoFieldsRequest) {
    return api(`/api/v1/teams/${teamId}/member-info/fields/reorder`, {
      method: 'PUT',
      body: request,
    })
  }

  // ===========================================
  // ステータス確認・リマインド（ADMIN）
  // ===========================================

  async function getResponseStatus(teamId: number) {
    return api<ApiResponse<MemberInfoStatusResponse>>(
      `/api/v1/teams/${teamId}/member-info/responses/status`,
    )
  }

  async function sendRemind(teamId: number, targetUserId: number) {
    return api(
      `/api/v1/teams/${teamId}/member-info/responses/${targetUserId}/remind`,
      { method: 'POST' },
    )
  }

  // ===========================================
  // 自分の回答管理（MEMBER）
  // ===========================================

  async function getMyResponses(teamId: number) {
    return api<ApiResponse<MemberInfoResponseMeItem[]>>(
      `/api/v1/teams/${teamId}/member-info/responses/me`,
    )
  }

  async function upsertMyResponses(teamId: number, request: UpsertMemberInfoResponseRequest) {
    return api(`/api/v1/teams/${teamId}/member-info/responses/me`, {
      method: 'PUT',
      body: request,
    })
  }

  return {
    getFields,
    createField,
    updateField,
    deleteField,
    reorderFields,
    getResponseStatus,
    sendRemind,
    getMyResponses,
    upsertMyResponses,
  }
}
