import type {
  CreatePositionRequest,
  ShiftPositionResponse,
  UpdatePositionRequest,
} from '~/types/shift'

export function useShiftPositionApi() {
  const api = useApi()

  /**
   * チームのポジション一覧を取得する（board.vue 互換）。
   * @param teamId チーム ID（省略時は teamId なしで取得）
   */
  async function getPositions(teamId?: string): Promise<{ data: ShiftPositionResponse[] }> {
    const query = new URLSearchParams()
    if (teamId) query.set('teamId', teamId)
    const qs = teamId ? `?${query.toString()}` : ''
    return api<{ data: ShiftPositionResponse[] }>(`/api/v1/shifts/positions${qs}`)
  }

  async function createPosition(
    teamId: string,
    payload: CreatePositionRequest,
  ): Promise<ShiftPositionResponse> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: ShiftPositionResponse }>(
      `/api/v1/shifts/positions?${query.toString()}`,
      { method: 'POST', body: payload },
    )
    return res.data
  }

  async function updatePosition(
    positionId: number,
    payload: UpdatePositionRequest,
  ): Promise<ShiftPositionResponse> {
    const res = await api<{ data: ShiftPositionResponse }>(
      `/api/v1/shifts/positions/${positionId}`,
      { method: 'PATCH', body: payload },
    )
    return res.data
  }

  async function deletePosition(positionId: number): Promise<void> {
    await api(`/api/v1/shifts/positions/${positionId}`, { method: 'DELETE' })
  }

  return {
    getPositions,
    createPosition,
    updatePosition,
    deletePosition,
  }
}
