import type {
  AvailabilityDefaultResponse,
  BulkAvailabilityDefaultRequest,
} from '~/types/shift'

export function useShiftAvailabilityApi() {
  const api = useApi()

  async function getAvailability(teamId: string): Promise<AvailabilityDefaultResponse[]> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: AvailabilityDefaultResponse[] }>(
      `/api/v1/shifts/availability?${query.toString()}`,
    )
    return res.data
  }

  async function setAvailability(
    teamId: string,
    payload: BulkAvailabilityDefaultRequest,
  ): Promise<AvailabilityDefaultResponse[]> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: AvailabilityDefaultResponse[] }>(
      `/api/v1/shifts/availability?${query.toString()}`,
      { method: 'PUT', body: payload },
    )
    return res.data
  }

  async function deleteAvailability(teamId: string): Promise<void> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    await api(`/api/v1/shifts/availability?${query.toString()}`, { method: 'DELETE' })
  }

  return {
    getAvailability,
    setAvailability,
    deleteAvailability,
  }
}
