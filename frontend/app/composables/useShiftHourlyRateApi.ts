import type {
  CreateHourlyRateRequest,
  ShiftHourlyRateResponse,
} from '~/types/shift'

/**
 * F03.5 時給設定 API クライアント。
 *
 * エンドポイント:
 * - `POST /api/v1/shifts/hourly-rate`   時給設定
 * - `GET  /api/v1/shifts/hourly-rate`   時給履歴取得
 */
export function useShiftHourlyRateApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts/hourly-rate'

  /**
   * 時給を設定する。
   * @param teamId  チーム ID
   * @param payload 時給作成リクエスト
   */
  async function createHourlyRate(
    teamId: number,
    payload: CreateHourlyRateRequest,
  ): Promise<ShiftHourlyRateResponse> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: ShiftHourlyRateResponse }>(`${BASE}?${query.toString()}`, {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  /**
   * 時給履歴を取得する。
   * @param teamId チーム ID
   * @param userId 対象ユーザー ID
   * @param date   有効日（YYYY-MM-DD）— 指定時はその日付の適用時給のみ返す
   */
  async function listHourlyRates(
    teamId: number,
    userId: number,
    date?: string,
  ): Promise<ShiftHourlyRateResponse[]> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    query.set('userId', String(userId))
    if (date) query.set('date', date)
    const res = await api<{ data: ShiftHourlyRateResponse[] }>(`${BASE}?${query.toString()}`)
    return res.data
  }

  return {
    createHourlyRate,
    listHourlyRates,
  }
}
