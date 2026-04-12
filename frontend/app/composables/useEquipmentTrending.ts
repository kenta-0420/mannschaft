import type {
  EquipmentTrendingResponse,
  EquipmentTrendingParams,
} from '~/types/equipment-ranking'

export function useEquipmentTrending(teamId: number) {
  const api = useApi()

  async function getTrending(params?: EquipmentTrendingParams): Promise<{ data: EquipmentTrendingResponse }> {
    const query = new URLSearchParams()
    if (params?.category) query.set('category', params.category)
    if (params?.limit !== undefined) query.set('limit', String(params.limit))
    if (params?.linkedOnly !== undefined) query.set('linked_only', String(params.linkedOnly))
    const qs = query.toString()
    return api<{ data: EquipmentTrendingResponse }>(
      `/api/v1/teams/${teamId}/equipment/trending${qs ? `?${qs}` : ''}`,
    )
  }

  async function optOut(): Promise<void> {
    await api(`/api/v1/teams/${teamId}/equipment/trending/opt-out`, { method: 'POST' })
  }

  async function optIn(): Promise<void> {
    await api(`/api/v1/teams/${teamId}/equipment/trending/opt-out`, { method: 'DELETE' })
  }

  return { getTrending, optOut, optIn }
}
