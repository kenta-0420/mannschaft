import type { TeamPage, MemberProfile, MemberProfileField, CreateMemberProfileRequest } from '~/types/member-profile'

export function useMemberProfileApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team'
      ? `/api/v1/teams/${scopeId}`
      : `/api/v1/organizations/${scopeId}`
  }

  // --- ページ管理 ---

  async function listPages(scopeType: 'team' | 'organization', scopeId: number) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: TeamPage[] }>(`${base}/pages`)
    return res.data
  }

  async function createPage(scopeType: 'team' | 'organization', scopeId: number, body: { title: string; pageType: string; year?: number; visibility?: string }) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: TeamPage }>(`${base}/pages`, { method: 'POST', body })
    return res.data
  }

  async function getPage(scopeType: 'team' | 'organization', scopeId: number, pageId: number) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: TeamPage }>(`${base}/pages/${pageId}`)
    return res.data
  }

  async function updatePage(scopeType: 'team' | 'organization', scopeId: number, pageId: number, body: Partial<{ title: string; visibility: string }>) {
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

  async function createMember(scopeType: 'team' | 'organization', scopeId: number, body: CreateMemberProfileRequest) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: MemberProfile }>(`${base}/member-profiles`, { method: 'POST', body })
    return res.data
  }

  async function updateMember(scopeType: 'team' | 'organization', scopeId: number, profileId: number, body: Partial<CreateMemberProfileRequest>) {
    const res = await api<{ data: MemberProfile }>(`/api/v1/member-profiles/${profileId}`, { method: 'PUT', body })
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

  async function createField(scopeType: 'team' | 'organization', scopeId: number, body: { fieldName: string; fieldType: string; options?: string[]; isRequired: boolean }) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: MemberProfileField }>(`${base}/member-fields`, { method: 'POST', body })
    return res.data
  }

  return { listPages, createPage, getPage, updatePage, publishPage, listMembers, createMember, updateMember, deleteMember, listFields, createField }
}
