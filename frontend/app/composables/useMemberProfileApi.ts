import type {
  TeamPage,
  MemberProfile,
  MemberProfileField,
  MemberLookupResult,
  CreateTeamPageRequest,
  UpdateTeamPageRequest,
  CreateMemberProfileRequest,
  UpdateMemberProfileRequest,
  BulkCreateMemberRequest,
  BulkCreateMemberResponse,
  CopyMembersResponse,
  ReorderRequest,
  CreateFieldRequest,
  UpdateFieldRequest,
  PreviewTokenResult,
  PageMeta,
} from '~/types/member-profile'

/**
 * F06.2 メンバー紹介 API クライアント。
 *
 * 実際にバックエンドへ実装されている住所は `/api/v1/team/pages`・`/api/v1/team/members`・
 * `/api/v1/team/member-fields` の3系統のみ（`backend/src/main/java/com/mannschaft/app/member/controller/`）。
 * `/api/v1/organizations/{slug}` や `/api/v1/teams/{slug}` のような
 * スコープ埋め込み型のパスは存在しない。ページ・メンバーはいずれも `teamId` /
 * `organizationId` をクエリ・ボディで指定する形になっている。
 */
export function useMemberProfileApi() {
  const api = useApi()

  // --- ページ管理 (/api/v1/team/pages) ---

  async function listPages(params: {
    teamId?: number
    organizationId?: number
    page?: number
    size?: number
  }) {
    const query = new URLSearchParams()
    if (params.teamId != null) query.set('teamId', String(params.teamId))
    if (params.organizationId != null) query.set('organizationId', String(params.organizationId))
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 20))
    const res = await api<{ data: TeamPage[]; meta: PageMeta }>(
      `/api/v1/team/pages?${query.toString()}`,
    )
    return res
  }

  async function createPage(body: CreateTeamPageRequest) {
    const res = await api<{ data: TeamPage }>('/api/v1/team/pages', { method: 'POST', body })
    return res.data
  }

  async function getPage(pageId: number) {
    const res = await api<{ data: TeamPage }>(`/api/v1/team/pages/${pageId}`)
    return res.data
  }

  async function updatePage(pageId: number, body: UpdateTeamPageRequest) {
    const res = await api<{ data: TeamPage }>(`/api/v1/team/pages/${pageId}`, {
      method: 'PUT',
      body,
    })
    return res.data
  }

  async function deletePage(pageId: number) {
    await api(`/api/v1/team/pages/${pageId}`, { method: 'DELETE' })
  }

  async function changePageStatus(pageId: number, status: 'DRAFT' | 'PUBLISHED') {
    const res = await api<{ data: TeamPage }>(`/api/v1/team/pages/${pageId}/publish`, {
      method: 'PATCH',
      body: { status },
    })
    return res.data
  }

  async function issuePreviewToken(pageId: number) {
    const res = await api<{ data: PreviewTokenResult }>(
      `/api/v1/team/pages/${pageId}/preview-token`,
      { method: 'POST' },
    )
    return res.data
  }

  async function revokePreviewToken(pageId: number) {
    await api(`/api/v1/team/pages/${pageId}/preview-token`, { method: 'DELETE' })
  }

  // --- メンバープロフィール (/api/v1/team/members) ---

  async function listMembers(teamPageId: number, page = 0, size = 50) {
    const query = new URLSearchParams({
      teamPageId: String(teamPageId),
      page: String(page),
      size: String(size),
    })
    const res = await api<{ data: MemberProfile[]; meta: PageMeta }>(
      `/api/v1/team/members?${query.toString()}`,
    )
    return res
  }

  async function getMember(id: number) {
    const res = await api<{ data: MemberProfile }>(`/api/v1/team/members/${id}`)
    return res.data
  }

  async function createMember(body: CreateMemberProfileRequest) {
    const res = await api<{ data: MemberProfile }>('/api/v1/team/members', {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function updateMember(id: number, body: UpdateMemberProfileRequest) {
    const res = await api<{ data: MemberProfile }>(`/api/v1/team/members/${id}`, {
      method: 'PUT',
      body,
    })
    return res.data
  }

  async function deleteMember(id: number) {
    await api(`/api/v1/team/members/${id}`, { method: 'DELETE' })
  }

  async function bulkCreateMembers(body: BulkCreateMemberRequest) {
    const res = await api<{ data: BulkCreateMemberResponse }>('/api/v1/team/members/bulk', {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function copyMembers(targetPageId: number, sourcePageId: number) {
    const res = await api<{ data: CopyMembersResponse }>(
      `/api/v1/team/pages/${targetPageId}/copy-members`,
      { method: 'POST', body: { sourcePageId } },
    )
    return res.data
  }

  async function reorderMembers(body: ReorderRequest) {
    await api('/api/v1/team/members/reorder', { method: 'PATCH', body })
  }

  async function lookupMembers(params: { q: string; teamPageId?: number; limit?: number }) {
    const query = new URLSearchParams({ q: params.q })
    if (params.teamPageId != null) query.set('teamPageId', String(params.teamPageId))
    if (params.limit != null) query.set('limit', String(params.limit))
    const res = await api<{ data: MemberLookupResult[] }>(
      `/api/v1/team/members/lookup?${query.toString()}`,
    )
    return res.data
  }

  // --- フィールド定義 (/api/v1/team/member-fields) ---

  async function listFields(teamId?: number, organizationId?: number) {
    const query = new URLSearchParams()
    if (teamId != null) query.set('teamId', String(teamId))
    if (organizationId != null) query.set('organizationId', String(organizationId))
    const qs = query.toString()
    const res = await api<{ data: MemberProfileField[] }>(
      `/api/v1/team/member-fields${qs ? `?${qs}` : ''}`,
    )
    return res.data
  }

  async function createField(body: CreateFieldRequest) {
    const res = await api<{ data: MemberProfileField }>('/api/v1/team/member-fields', {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function updateField(id: number, body: UpdateFieldRequest) {
    const res = await api<{ data: MemberProfileField }>(`/api/v1/team/member-fields/${id}`, {
      method: 'PUT',
      body,
    })
    return res.data
  }

  async function deactivateField(id: number) {
    await api(`/api/v1/team/member-fields/${id}`, { method: 'DELETE' })
  }

  return {
    listPages,
    createPage,
    getPage,
    updatePage,
    deletePage,
    changePageStatus,
    issuePreviewToken,
    revokePreviewToken,
    listMembers,
    getMember,
    createMember,
    updateMember,
    deleteMember,
    bulkCreateMembers,
    copyMembers,
    reorderMembers,
    lookupMembers,
    listFields,
    createField,
    updateField,
    deactivateField,
  }
}
