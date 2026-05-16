import type {
  CreateSwapRequestRequest,
  ResolveSwapRequestRequest,
  SwapRequestResponse,
} from '~/types/shift'

export function useShiftSwapApi() {
  const api = useApi()

  async function listSwapRequests(status?: string): Promise<SwapRequestResponse[]> {
    const query = status ? `?status=${encodeURIComponent(status)}` : ''
    const res = await api<{ data: SwapRequestResponse[] }>(
      `/api/v1/shifts/swap-requests${query}`,
    )
    return res.data
  }

  async function createSwapRequest(
    payload: CreateSwapRequestRequest,
  ): Promise<SwapRequestResponse> {
    const res = await api<{ data: SwapRequestResponse }>('/api/v1/shifts/swap-requests', {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  async function deleteSwapRequest(swapId: number): Promise<void> {
    await api(`/api/v1/shifts/swap-requests/${swapId}`, { method: 'DELETE' })
  }

  async function acceptSwap(swapId: number): Promise<SwapRequestResponse> {
    const res = await api<{ data: SwapRequestResponse }>(
      `/api/v1/shifts/swap-requests/${swapId}/accept`,
      { method: 'POST' },
    )
    return res.data
  }

  async function resolveSwap(
    swapId: number,
    payload: ResolveSwapRequestRequest,
  ): Promise<SwapRequestResponse> {
    const res = await api<{ data: SwapRequestResponse }>(
      `/api/v1/shifts/swap-requests/${swapId}/resolve`,
      { method: 'POST', body: payload },
    )
    return res.data
  }

  return {
    listSwapRequests,
    createSwapRequest,
    deleteSwapRequest,
    acceptSwap,
    resolveSwap,
  }
}
