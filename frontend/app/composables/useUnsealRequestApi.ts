import type {
  UnsealApprovalRequest,
  UnsealRequestCreateRequest,
  UnsealRequestResponse,
} from '~/types/succession'

export function useUnsealRequestApi() {
  const api = useApi()

  async function listRequests(orgId: string) {
    return api<{ data: UnsealRequestResponse[] }>(
      `/api/v1/organizations/${orgId}/succession/unseal-requests`,
    )
  }

  async function getRequest(orgId: string, id: string) {
    return api<{ data: UnsealRequestResponse }>(
      `/api/v1/organizations/${orgId}/succession/unseal-requests/${id}`,
    )
  }

  async function createRequest(orgId: string, body: UnsealRequestCreateRequest) {
    return api<{ data: UnsealRequestResponse }>(
      `/api/v1/organizations/${orgId}/succession/unseal-requests`,
      { method: 'POST', body },
    )
  }

  async function approveRequest(orgId: string, id: string, body: UnsealApprovalRequest) {
    return api<{ data: UnsealRequestResponse }>(
      `/api/v1/organizations/${orgId}/succession/unseal-requests/${id}/approve`,
      { method: 'POST', body },
    )
  }

  async function secondApproveRequest(orgId: string, id: string, body: UnsealApprovalRequest) {
    return api<{ data: UnsealRequestResponse }>(
      `/api/v1/organizations/${orgId}/succession/unseal-requests/${id}/second-approve`,
      { method: 'POST', body },
    )
  }

  async function cancelRequest(orgId: string, id: string) {
    return api<{ data: Record<string, string> }>(
      `/api/v1/organizations/${orgId}/succession/unseal-requests/${id}/cancel`,
      { method: 'POST' },
    )
  }

  return { listRequests, getRequest, createRequest, approveRequest, secondApproveRequest, cancelRequest }
}
