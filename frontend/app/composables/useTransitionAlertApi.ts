import type {
  TransitionAlertListResponse,
  TransitionAlertResponse,
} from '~/types/school'

interface ApiResponse<T> {
  data: T
}

export function useTransitionAlertApi() {
  const api = useApi()

  /**
   * 指定クラス・日付の移動検知アラート一覧を取得する。
   *
   * @param teamId         クラスチームID
   * @param date           対象日（YYYY-MM-DD 形式）
   * @param unresolvedOnly true の場合は未解決のみ取得
   * @returns アラート一覧レスポンス
   */
  async function getAlerts(
    teamId: number,
    date: string,
    unresolvedOnly: boolean,
  ): Promise<TransitionAlertListResponse> {
    const res = await api<ApiResponse<TransitionAlertListResponse>>(
      `/api/v1/teams/${teamId}/attendance/transition-alerts?date=${date}&unresolvedOnly=${unresolvedOnly}`,
    )
    return res.data
  }

  /**
   * 移動検知アラートを解決済みにする。
   *
   * @param teamId  クラスチームID
   * @param alertId アラートID
   * @param note    解決理由
   * @returns 更新後のアラートレスポンス
   */
  async function resolveAlert(
    teamId: number,
    alertId: number,
    note: string,
  ): Promise<TransitionAlertResponse> {
    const res = await api<ApiResponse<TransitionAlertResponse>>(
      `/api/v1/teams/${teamId}/attendance/transition-alerts/${alertId}/resolve`,
      { method: 'POST', body: { note } },
    )
    return res.data
  }

  return { getAlerts, resolveAlert }
}
