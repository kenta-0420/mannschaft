import type {
  TeamPage,
  MemberProfile,
  MemberProfileField,
  CreateMemberProfileRequest,
} from '~/types/member-profile'

export function useMemberProfileApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // --- ページ管理 ---

  async function listPages(scopeType: 'team' | 'organization', scopeId: number) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: TeamPage[] }>(`${base}/pages`)
    return res.data
  }

  async function createPage(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: { title: string; pageType: string; year?: number; visibility?: string },
  ) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: TeamPage }>(`${base}/pages`, { method: 'POST', body })
    return res.data
  }

  async function getPage(scopeType: 'team' | 'organization', scopeId: number, pageId: number) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: TeamPage }>(`${base}/pages/${pageId}`)
    return res.data
  }

  async function updatePage(
    scopeType: 'team' | 'organization',
    scopeId: number,
    pageId: number,
    body: Partial<{ title: string; visibility: string }>,
  ) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: TeamPage }>(`${base}/pages/${pageId}`, { method: 'PUT', body })
    return res.data
  }

  async function publishPage(scopeType: 'team' | 'organization', scopeId: number, pageId: number) {
    const base = buildBase(scopeType, scopeId)
    await api(`${base}/pages/${pageId}/publish`, { method: 'PATCH' })
  }

  // --- メンバープロフィール ---

  async function listMembers(scopeType: 'team' | 'organization', scopeId: number) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: MemberProfile[] }>(`${base}/member-profiles`)
    return res.data
  }

  async function createMember(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: CreateMemberProfileRequest,
  ) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: MemberProfile }>(`${base}/member-profiles`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function updateMember(
    scopeType: 'team' | 'organization',
    scopeId: number,
    profileId: number,
    body: Partial<CreateMemberProfileRequest>,
  ) {
    const res = await api<{ data: MemberProfile }>(`/api/v1/member-profiles/${profileId}`, {
      method: 'PUT',
      body,
    })
    return res.data
  }

  async function deleteMember(profileId: number) {
    await api(`/api/v1/member-profiles/${profileId}`, { method: 'DELETE' })
  }

  // --- フィールド定義 ---

  async function listFields(scopeType: 'team' | 'organization', scopeId: number) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: MemberProfileField[] }>(`${base}/member-fields`)
    return res.data
  }

  async function createField(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: { fieldName: string; fieldType: string; options?: string[]; isRequired: boolean },
  ) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: MemberProfileField }>(`${base}/member-fields`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  // === /api/v1/team/members (swagger定義パス) ===
  async function listTeamMembers() {
    const res = await api<{ data: MemberProfile[] }>('/api/v1/team/members')
    return res.data
  }

  async function createTeamMember(body: Record<string, unknown>) {
    const res = await api<{ data: MemberProfile }>('/api/v1/team/members', { method: 'POST', body })
    return res.data
  }

  async function getTeamMember(id: number) {
    const res = await api<{ data: MemberProfile }>(`/api/v1/team/members/${id}`)
    return res.data
  }

  async function updateTeamMember(id: number, body: Record<string, unknown>) {
    const res = await api<{ data: MemberProfile }>(`/api/v1/team/members/${id}`, {
      method: 'PUT',
      body,
    })
    return res.data
  }

  async function deleteTeamMember(id: number) {
    await api(`/api/v1/team/members/${id}`, { method: 'DELETE' })
  }

  async function bulkCreateTeamMembers(body: Record<string, unknown>) {
    const res = await api<{ data: MemberProfile[] }>('/api/v1/team/members/bulk', {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function lookupTeamMembers(params?: Record<string, unknown>) {
    const query = new URLSearchParams()
    if (params) {
      for (const [k, v] of Object.entries(params)) {
        if (v != null) query.set(k, String(v))
      }
    }
    const qs = query.toString()
    const res = await api<{ data: MemberProfile[] }>(
      `/api/v1/team/members/lookup${qs ? `?${qs}` : ''}`,
    )
    return res.data
  }

  async function reorderTeamMembers(body: Record<string, unknown>) {
    await api('/api/v1/team/members/reorder', { method: 'PATCH', body })
  }

  return {
    listPages,
    createPage,
    getPage,
    updatePage,
    publishPage,
    listMembers,
    createMember,
    updateMember,
    deleteMember,
    listFields,
    createField,
    listTeamMembers,
    createTeamMember,
    getTeamMember,
    updateTeamMember,
    deleteTeamMember,
    bulkCreateTeamMembers,
    lookupTeamMembers,
    reorderTeamMembers,
  }
}
