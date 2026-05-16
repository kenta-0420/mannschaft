import type {
  CreateShiftRequestRequest,
  ShiftRequestResponse,
  ShiftRequestSummaryResponse,
  UpdateShiftRequestRequest,
} from '~/types/shift'

export function useShiftRequestApi() {
  const api = useApi()

  async function listShiftRequests(scheduleId: number): Promise<ShiftRequestResponse[]> {
    const query = new URLSearchParams()
    query.set('scheduleId', String(scheduleId))
    const res = await api<{ data: ShiftRequestResponse[] }>(
      `/api/v1/shifts/requests?${query.toString()}`,
    )
    return res.data
  }

  async function submitShiftRequest(
    payload: CreateShiftRequestRequest,
  ): Promise<ShiftRequestResponse> {
    const res = await api<{ data: ShiftRequestResponse }>('/api/v1/shifts/requests', {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  async function updateShiftRequest(
    requestId: number,
    payload: UpdateShiftRequestRequest,
  ): Promise<ShiftRequestResponse> {
    const res = await api<{ data: ShiftRequestResponse }>(
      `/api/v1/shifts/requests/${requestId}`,
      { method: 'PATCH', body: payload },
    )
    return res.data
  }

  async function deleteShiftRequest(requestId: number): Promise<void> {
    await api(`/api/v1/shifts/requests/${requestId}`, { method: 'DELETE' })
  }

  async function getShiftRequestSummary(scheduleId: number): Promise<ShiftRequestSummaryResponse> {
    const query = new URLSearchParams()
    query.set('scheduleId', String(scheduleId))
    const res = await api<{ data: ShiftRequestSummaryResponse }>(
      `/api/v1/shifts/requests/summary?${query.toString()}`,
    )
    return res.data
  }

  async function getMyShiftRequests(): Promise<ShiftRequestResponse[]> {
    const res = await api<{ data: ShiftRequestResponse[] }>('/api/v1/shifts/my/requests')
    return res.data
  }

  return {
    listShiftRequests,
    submitShiftRequest,
    updateShiftRequest,
    deleteShiftRequest,
    getShiftRequestSummary,
    getMyShiftRequests,
  }
}
