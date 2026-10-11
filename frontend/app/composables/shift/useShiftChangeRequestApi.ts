import type {
  ChangeRequest,
  CreateChangeRequestPayload,
  ReviewChangeRequestPayload,
} from '~/types/shift'

export function useShiftChangeRequestApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts/change-requests'

  async function createChangeRequest(payload: CreateChangeRequestPayload): Promise<ChangeRequest> {
    const res = await api<{ data: ChangeRequest }>(BASE, {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  async function listChangeRequests(scheduleId: number): Promise<ChangeRequest[]> {
    const res = await api<{ data: ChangeRequest[] }>(
      `${BASE}?scheduleId=${scheduleId}`,
    )
    return res.data
  }

  async function getChangeRequest(id: number): Promise<ChangeRequest> {
    const res = await api<{ data: ChangeRequest }>(`${BASE}/${id}`)
    return res.data
  }

  async function reviewChangeRequest(
    id: number,
    payload: ReviewChangeRequestPayload,
  ): Promise<ChangeRequest> {
    const res = await api<{ data: ChangeRequest }>(`${BASE}/${id}/review`, {
      method: 'PATCH',
      body: payload,
    })
    return res.data
  }

  async function withdrawChangeRequest(id: number): Promise<void> {
    await api(`${BASE}/${id}`, { method: 'DELETE' })
  }

  return {
    createChangeRequest,
    listChangeRequests,
    getChangeRequest,
    reviewChangeRequest,
    withdrawChangeRequest,
  }
}
