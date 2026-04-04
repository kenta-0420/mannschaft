import type {
  ContactResponse,
  ContactRequestResponse,
  ContactRequestBlockResponse,
  ContactInviteTokenResponse,
  ContactInvitePreviewResponse,
  ContactPrivacySettings,
  HandleSearchResult,
  HandleInfo,
  ContactableMember,
  SendContactRequestBody,
  CreateInviteTokenBody,
} from '~/types/contact'

export function useContactApi() {
  const api = useApi()

  // === 連絡先 ===
  async function listContacts(params?: {
    folderId?: number
    q?: string
    isPinned?: boolean
    cursor?: string
    limit?: number
  }) {
    const query = new URLSearchParams()
    if (params?.folderId) query.set('folderId', String(params.folderId))
    if (params?.q) query.set('q', params.q)
    if (params?.isPinned !== undefined) query.set('isPinned', String(params.isPinned))
    if (params?.cursor) query.set('cursor', params.cursor)
    if (params?.limit) query.set('limit', String(params.limit))
    return api<{
      data: ContactResponse[]
      meta: { nextCursor: string | null; total: number }
    }>(`/api/v1/contacts?${query}`)
  }

  async function deleteContact(userId: number) {
    return api(`/api/v1/contacts/${userId}`, { method: 'DELETE' })
  }

  // === 連絡先追加申請 ===
  async function sendRequest(body: SendContactRequestBody) {
    return api<{ data: { requestId: number; status: string } }>('/api/v1/contact-requests', {
      method: 'POST',
      body,
    })
  }

  async function listReceivedRequests() {
    return api<{ data: ContactRequestResponse[] }>('/api/v1/contact-requests/received')
  }

  async function listSentRequests() {
    return api<{ data: ContactRequestResponse[] }>('/api/v1/contact-requests/sent')
  }

  async function acceptRequest(requestId: number) {
    return api(`/api/v1/contact-requests/${requestId}/accept`, { method: 'POST' })
  }

  async function rejectRequest(requestId: number) {
    return api(`/api/v1/contact-requests/${requestId}/reject`, { method: 'POST' })
  }

  async function cancelRequest(requestId: number) {
    return api(`/api/v1/contact-requests/${requestId}`, { method: 'DELETE' })
  }

  // === 申請事前拒否 ===
  async function listRequestBlocks() {
    return api<{ data: ContactRequestBlockResponse[] }>('/api/v1/contact-request-blocks')
  }

  async function addRequestBlock(targetUserId: number) {
    return api('/api/v1/contact-request-blocks', { method: 'POST', body: { targetUserId } })
  }

  async function removeRequestBlock(blockedUserId: number) {
    return api(`/api/v1/contact-request-blocks/${blockedUserId}`, { method: 'DELETE' })
  }

  // === 招待トークン ===
  async function listInviteTokens() {
    return api<{ data: ContactInviteTokenResponse[] }>('/api/v1/contact-invite-tokens')
  }

  async function createInviteToken(body: CreateInviteTokenBody) {
    return api<{ data: ContactInviteTokenResponse }>('/api/v1/contact-invite-tokens', {
      method: 'POST',
      body,
    })
  }

  async function revokeInviteToken(id: number) {
    return api(`/api/v1/contact-invite-tokens/${id}`, { method: 'DELETE' })
  }

  async function getInvitePreview(token: string) {
    return api<{ data: ContactInvitePreviewResponse }>(`/api/v1/contact-invite/${token}`)
  }

  async function acceptInvite(token: string) {
    return api(`/api/v1/contact-invite/${token}/accept`, { method: 'POST' })
  }

  // === @ハンドル ===
  async function searchByHandle(handle: string) {
    return api<{ data: HandleSearchResult | null }>(
      `/api/v1/users/contact-handle/${encodeURIComponent(handle)}`,
    )
  }

  async function getMyHandle() {
    return api<{ data: HandleInfo }>('/api/v1/users/me/contact-handle')
  }

  async function updateMyHandle(contactHandle: string) {
    return api<{ data: HandleInfo }>('/api/v1/users/me/contact-handle', {
      method: 'PUT',
      body: { contactHandle },
    })
  }

  async function checkHandleAvailability(handle: string) {
    return api<{ available: boolean }>(
      `/api/v1/users/contact-handle-check?handle=${encodeURIComponent(handle)}`,
    )
  }

  // === チーム/組織メンバー ===
  async function getTeamContactableMembers(
    teamId: number,
    params?: { q?: string; cursor?: string; limit?: number },
  ) {
    const query = new URLSearchParams()
    if (params?.q) query.set('q', params.q)
    if (params?.cursor) query.set('cursor', params.cursor)
    if (params?.limit) query.set('limit', String(params.limit))
    return api<{ data: ContactableMember[]; meta: { nextCursor: string | null } }>(
      `/api/v1/teams/${teamId}/members/contactable?${query}`,
    )
  }

  async function getOrgContactableMembers(
    orgId: number,
    params?: { q?: string; cursor?: string; limit?: number },
  ) {
    const query = new URLSearchParams()
    if (params?.q) query.set('q', params.q)
    if (params?.cursor) query.set('cursor', params.cursor)
    if (params?.limit) query.set('limit', String(params.limit))
    return api<{ data: ContactableMember[]; meta: { nextCursor: string | null } }>(
      `/api/v1/organizations/${orgId}/members/contactable?${query}`,
    )
  }

  // === プライバシー設定 ===
  async function getPrivacySettings() {
    return api<{ data: ContactPrivacySettings }>('/api/v1/users/me/contact-privacy')
  }

  async function updatePrivacySettings(body: Partial<ContactPrivacySettings>) {
    return api<{ data: ContactPrivacySettings }>('/api/v1/users/me/contact-privacy', {
      method: 'PUT',
      body,
    })
  }

  return {
    listContacts,
    deleteContact,
    sendRequest,
    listReceivedRequests,
    listSentRequests,
    acceptRequest,
    rejectRequest,
    cancelRequest,
    listRequestBlocks,
    addRequestBlock,
    removeRequestBlock,
    listInviteTokens,
    createInviteToken,
    revokeInviteToken,
    getInvitePreview,
    acceptInvite,
    searchByHandle,
    getMyHandle,
    updateMyHandle,
    checkHandleAvailability,
    getTeamContactableMembers,
    getOrgContactableMembers,
    getPrivacySettings,
    updatePrivacySettings,
  }
}
