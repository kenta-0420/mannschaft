import type {
  CreatePositionRequest,
  ShiftPositionResponse,
  UpdatePositionRequest,
} from '~/types/shift'

/**
 * F03.5 シフトポジション API クライアント。
 *
 * エンドポイントベース: `/api/v1/shifts/positions`
 */
export function useShiftPositionApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts/positions'

  /**
   * チームのポジション一覧を取得する。
   * @param teamId チーム ID
   */
  async function listPositions(teamId: number): Promise<ShiftPositionResponse[]> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: ShiftPositionResponse[] }>(`${BASE}?${query.toString()}`)
    return res.data
  }

  /**
   * ポジションを作成する。
   * @param teamId  チーム ID
   * @param payload 作成リクエスト
   */
  async function createPosition(
    teamId: number,
    payload: CreatePositionRequest,
  ): Promise<ShiftPositionResponse> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: ShiftPositionResponse }>(`${BASE}?${query.toString()}`, {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  /**
   * ポジションを更新する。
   * @param positionId ポジション ID
   * @param payload    更新リクエスト
   */
  async function updatePosition(
    positionId: number,
    payload: UpdatePositionRequest,
  ): Promise<ShiftPositionResponse> {
    const res = await api<{ data: ShiftPositionResponse }>(`${BASE}/${positionId}`, {
      method: 'PATCH',
      body: payload,
    })
    return res.data
  }

  /**
   * ポジションを削除する（is_active = FALSE）。
   * @param positionId ポジション ID
   */
  async function deletePosition(positionId: number): Promise<void> {
    await api(`${BASE}/${positionId}`, { method: 'DELETE' })
  }

  return {
    listPositions,
    createPosition,
    updatePosition,
    deletePosition,
  }
}
