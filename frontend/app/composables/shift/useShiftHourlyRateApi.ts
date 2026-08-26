import type {
  CreateHourlyRateRequest,
  ShiftHourlyRateResponse,
} from '~/types/shift'

export function useShiftHourlyRateApi() {
  const api = useApi()

  async function getHourlyRate(
    teamId: string,
    userId: number,
    date?: string,
  ): Promise<ShiftHourlyRateResponse[]> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    query.set('userId', String(userId))
    if (date) query.set('date', date)
    const res = await api<{ data: ShiftHourlyRateResponse[] }>(
      `/api/v1/shifts/hourly-rate?${query.toString()}`,
    )
    return res.data
  }

  async function setHourlyRate(
    teamId: string,
    payload: CreateHourlyRateRequest,
  ): Promise<ShiftHourlyRateResponse> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: ShiftHourlyRateResponse }>(
      `/api/v1/shifts/hourly-rate?${query.toString()}`,
      { method: 'POST', body: payload },
    )
    return res.data
  }

  return {
    getHourlyRate,
    setHourlyRate,
  }
}
