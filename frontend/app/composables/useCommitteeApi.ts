import type {
  CommitteeSummary,
  CommitteeDetail,
  CommitteeMember,
  CommitteeDistributionLog,
} from '~/types/committee'
import type { SpringPage } from '~/types/api'
import { toPagedResponse } from '~/utils/toPagedResponse'

export type CommitteeListResponse = { data: SpringPage<CommitteeSummary> }
export type CommitteeDistributionsResponse = { data: SpringPage<CommitteeDistributionLog> }

export function useCommitteeApi() {
  const api = useApi()

  async function listCommittees(orgId: string, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams({ page: String(params?.page ?? 0), size: String(params?.size ?? 20) })
    const res = await api<CommitteeListResponse>(`/api/v1/organizations/${orgId}/committees?${query}`)
    return toPagedResponse(res.data)
  }

  async function createCommittee(orgId: string, body: Record<string, unknown>) {
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

  async function listDistributions(committeeId: number, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams({ page: String(params?.page ?? 0), size: String(params?.size ?? 20) })
    const res = await api<CommitteeDistributionsResponse>(
      `/api/v1/committees/${committeeId}/distributions?${query}`,
    )
    return toPagedResponse(res.data)
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
