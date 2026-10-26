import type {
  InvitationResponse,
  ParentLinkResponse,
  ChildLinkResponse,
} from '@/types/parental-consent'

export function useParentalConsentApi() {
  const api = useApi()

  async function sendInvitation(parentEmail: string): Promise<void> {
    await api('/api/v1/parental-consent/invitations', {
      method: 'POST',
      body: { parentEmail },
    })
  }

  async function getInvitations(): Promise<InvitationResponse[]> {
    const res = await api<{ data: InvitationResponse[] }>(
      '/api/v1/parental-consent/invitations',
    )
    return res.data
  }

  async function cancelInvitation(linkId: string): Promise<void> {
    await api(`/api/v1/parental-consent/invitations/${linkId}`, {
      method: 'DELETE',
    })
  }

  async function getParents(): Promise<ParentLinkResponse[]> {
    const res = await api<{ data: ParentLinkResponse[] }>(
      '/api/v1/parental-consent/parents',
    )
    return res.data
  }

  async function removeParent(linkId: string): Promise<void> {
    await api(`/api/v1/parental-consent/parents/${linkId}`, {
      method: 'DELETE',
    })
  }

  async function approve(token: string): Promise<void> {
    await api('/api/v1/parental-consent/approve', {
      method: 'POST',
      body: { token },
    })
  }

  async function reject(token: string): Promise<void> {
    await api('/api/v1/parental-consent/reject', {
      method: 'POST',
      body: { token },
    })
  }

  async function getChildren(): Promise<ChildLinkResponse[]> {
    const res = await api<{ data: ChildLinkResponse[] }>(
      '/api/v1/parental-consent/children',
    )
    return res.data
  }

  async function removeChild(linkId: string): Promise<void> {
    await api(`/api/v1/parental-consent/children/${linkId}`, {
      method: 'DELETE',
    })
  }

  return {
    sendInvitation,
    getInvitations,
    cancelInvitation,
    getParents,
    removeParent,
    approve,
    reject,
    getChildren,
    removeChild,
  }
}
