import type {
  CreateHourlyRateRequest,
  ShiftHourlyRateResponse,
} from '~/types/shift'

/**
 * 時給として送信してよい値かを判定する（CMP-260910-1555）。
 *
 * BE の `CreateHourlyRateRequest.hourlyRate` は `@NotNull @Positive` なので、
 * 0 や負数は必ず 400 になる。画面側で 0 を有効値として通すと、
 * 利用者には正しい入力に見えるのに保存時だけ汎用エラーになるため、
 * BE の契約と同じ条件をここに置いて入口で弾く。
 *
 * @param rate 入力された時給（未入力は null）
 * @returns 送信してよければ true
 */
export function isValidHourlyRate(rate: number | null): rate is number {
  return rate !== null && Number.isFinite(rate) && rate > 0
}

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
