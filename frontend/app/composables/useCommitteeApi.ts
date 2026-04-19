import type {
  CommitteeSummary,
  CommitteeDetail,
  CommitteeMember,
  CommitteeDistributionLog,
} from '~/types/committee'

export function useCommitteeApi() {
  const api = useApi()

  async function listCommittees(orgId: number) {
    return api<{ data: CommitteeSummary[] }>(`/api/v1/organizations/${orgId}/committees`)
  }

  async function createCommittee(orgId: number, body: Record<string, unknown>) {
    return api<{ data: CommitteeSummary }>(`/api/v1/organizations/${orgId}/committees`, {
      method: 'POST',
      body,
    })
  }

  async function getCommittee(committeeId: number) {
    return api<{ data: CommitteeDetail }>(`/api/v1/committees/${committeeId}`)
  }

  async function updateCommittee(committeeId: number, body: Record<string, unknown>) {
    return api<{ data: CommitteeDetail }>(`/api/v1/committees/${committeeId}`, {
      method: 'PUT',
      body,
    })
  }

  async function transitionStatus(committeeId: number, to: string) {
    return api<{ data: CommitteeDetail }>(`/api/v1/committees/${committeeId}/status`, {
      method: 'PUT',
      body: { to },
    })
  }

  async function listMembers(committeeId: number) {
    return api<{ data: CommitteeMember[] }>(`/api/v1/committees/${committeeId}/members`)
  }

  async function removeMember(committeeId: number, userId: number) {
    return api(`/api/v1/committees/${committeeId}/members/${userId}`, { method: 'DELETE' })
  }

  async function leaveCommittee(committeeId: number) {
    return api(`/api/v1/committees/${committeeId}/leave`, { method: 'POST' })
  }

  async function listDistributions(committeeId: number) {
    return api<{ data: CommitteeDistributionLog[] }>(
      `/api/v1/committees/${committeeId}/distributions`,
    )
  }

  return {
    listCommittees,
    createCommittee,
    getCommittee,
    updateCommittee,
    transitionStatus,
    listMembers,
    removeMember,
    leaveCommittee,
    listDistributions,
  }
}
