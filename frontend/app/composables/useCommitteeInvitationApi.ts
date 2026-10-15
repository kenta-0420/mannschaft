import type { CommitteeInvitation, CommitteeMember } from '~/types/committee'

export function useCommitteeInvitationApi() {
  const api = useApi()

  async function sendInvitations(committeeId: number, body: Record<string, unknown>) {
    return api<{ data: CommitteeInvitation[] }>(
      `/api/v1/committees/${committeeId}/invitations`,
      { method: 'POST', body },
    )
  }

  async function listInvitations(committeeId: number) {
    return api<{ data: CommitteeInvitation[] }>(
      `/api/v1/committees/${committeeId}/invitations`,
    )
  }

  async function cancelInvitation(invitationId: number) {
    return api(`/api/v1/committee-invitations/${invitationId}`, { method: 'DELETE' })
  }

  async function acceptByToken(token: string) {
    return api<{ data: CommitteeMember }>('/api/v1/committee-invitations/accept-by-token', {
      method: 'POST',
      body: { token },
    })
  }

  async function declineByToken(token: string) {
    return api('/api/v1/committee-invitations/decline-by-token', {
      method: 'POST',
      body: { token },
    })
  }

  return {
    sendInvitations,
    listInvitations,
    cancelInvitation,
    acceptByToken,
    declineByToken,
  }
}
