import type {
  ChangeRequest,
  CreateChangeRequestPayload,
  ReviewChangeRequestPayload,
} from '~/types/shift'

export function useShiftChangeRequestApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts/schedules'

  async function createChangeRequest(payload: CreateChangeRequestPayload): Promise<ChangeRequest> {
    const res = await api<{ data: ChangeRequest }>(`${BASE}/change-requests`, {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  async function listChangeRequests(scheduleId: number): Promise<ChangeRequest[]> {
    const res = await api<{ data: ChangeRequest[] }>(
      `${BASE}/change-requests?scheduleId=${scheduleId}`,
    )
    return res.data
  }

  async function getChangeRequest(id: number): Promise<ChangeRequest> {
    const res = await api<{ data: ChangeRequest }>(`${BASE}/change-requests/${id}`)
    return res.data
  }

  async function reviewChangeRequest(
    id: number,
    payload: ReviewChangeRequestPayload,
  ): Promise<ChangeRequest> {
    const res = await api<{ data: ChangeRequest }>(`${BASE}/change-requests/${id}/review`, {
      method: 'PATCH',
      body: payload,
    })
    return res.data
  }

  async function withdrawChangeRequest(id: number): Promise<void> {
    await api(`${BASE}/change-requests/${id}`, { method: 'DELETE' })
  }

  return {
    createChangeRequest,
    listChangeRequests,
    getChangeRequest,
    reviewChangeRequest,
    withdrawChangeRequest,
  }
}
