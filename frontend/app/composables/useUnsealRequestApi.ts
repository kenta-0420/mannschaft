import type {
  UnsealApprovalRequest,
  UnsealRequestCreateRequest,
  UnsealRequestResponse,
} from '~/types/succession'

export function useUnsealRequestApi() {
  const api = useApi()

  async function listRequests(orgId: number) {
    return api<{ data: UnsealRequestResponse[] }>(
      `/api/v1/organizations/${orgId}/succession/unseal-requests`,
    )
  }

  async function getRequest(orgId: number, id: string) {
    return api<{ data: UnsealRequestResponse }>(
      `/api/v1/organizations/${orgId}/succession/unseal-requests/${id}`,
    )
  }

  async function createRequest(orgId: number, body: UnsealRequestCreateRequest) {
    return api<{ data: UnsealRequestResponse }>(
      `/api/v1/organizations/${orgId}/succession/unseal-requests`,
      { method: 'POST', body },
    )
  }

  async function approveRequest(orgId: number, id: string, body: UnsealApprovalRequest) {
    return api<{ data: UnsealRequestResponse }>(
      `/api/v1/organizations/${orgId}/succession/unseal-requests/${id}/approve`,
      { method: 'POST', body },
    )
  }

  async function secondApproveRequest(orgId: number, id: string, body: UnsealApprovalRequest) {
    return api<{ data: UnsealRequestResponse }>(
      `/api/v1/organizations/${orgId}/succession/unseal-requests/${id}/second-approve`,
      { method: 'POST', body },
    )
  }

  async function cancelRequest(orgId: number, id: string) {
    return api<{ data: Record<string, string> }>(
      `/api/v1/organizations/${orgId}/succession/unseal-requests/${id}/cancel`,
      { method: 'POST' },
    )
  }

  return { listRequests, getRequest, createRequest, approveRequest, secondApproveRequest, cancelRequest }
}
