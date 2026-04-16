import type {
  FollowTeamRequest,
  FollowTeamResponse,
  SetVisibilityRequest,
  TeamFriendListResponse,
  UnfollowRequest,
} from '~/types/friends'

/**
 * F01.5 チーム間フォロー・フレンド関係管理 composable。
 *
 * 提供するエンドポイント（設計書 §5）:
 * - POST   /api/v1/teams/{id}/friends/follow — フォロー
 * - DELETE /api/v1/teams/{id}/friends/follow/{targetTeamId} — フォロー解除
 * - GET    /api/v1/teams/{id}/friends — フレンドチーム一覧
 * - PATCH  /api/v1/teams/{id}/friends/{teamFriendId}/visibility — 公開設定変更
 */
export function useFriendTeamsApi() {
  const api = useApi()

  /** クエリパラメータを URLSearchParams 文字列に変換する。 */
  function buildQuery(params: Record<string, unknown> | undefined): string {
    if (!params) return ''
    const qs = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) qs.set(key, String(value))
    }
    const s = qs.toString()
    return s ? `?${s}` : ''
  }

  /**
   * 指定した他チームをフォローする。
   * 相互フォロー成立時は {@link FollowTeamResponse#mutual} が true となり
   * フレンド関係が同時に生成される。
   *
   * @param teamId 自チーム ID
   * @param req    フォローリクエスト
   * @returns フォロー結果
   */
  async function follow(teamId: number, req: FollowTeamRequest): Promise<FollowTeamResponse> {
    const result = await api<{ data: FollowTeamResponse }>(
      `/api/v1/teams/${teamId}/friends/follow`,
      { method: 'POST', body: req },
    )
    return result.data
  }

  /**
   * 指定した他チームへのフォローを解除する。
   * 相互フォロー状態の場合はフレンド関係も連動解除される。
   *
   * @param teamId       自チーム ID
   * @param targetTeamId フォロー解除先チーム ID
   * @param req          省略時は KEEP が適用される
   */
  async function unfollow(
    teamId: number,
    targetTeamId: number,
    req?: UnfollowRequest,
  ): Promise<void> {
    await api(`/api/v1/teams/${teamId}/friends/follow/${targetTeamId}`, {
      method: 'DELETE',
      body: req,
    })
  }

  /**
   * 自チームのフレンドチーム一覧を取得する。
   *
   * @param teamId 自チーム ID
   * @param params ページネーションパラメータ
   * @returns フレンドチーム一覧レスポンス
   */
  async function listFriends(
    teamId: number,
    params?: { page?: number; size?: number },
  ): Promise<TeamFriendListResponse> {
    return api<TeamFriendListResponse>(
      `/api/v1/teams/${teamId}/friends${buildQuery(params)}`,
    )
  }

  /**
   * フレンド関係の公開設定を変更する（ADMIN のみ）。
   *
   * @param teamId       自チーム ID
   * @param teamFriendId フレンド関係 ID
   * @param req          公開設定リクエスト
   */
  async function setVisibility(
    teamId: number,
    teamFriendId: number,
    req: SetVisibilityRequest,
  ): Promise<void> {
    await api(`/api/v1/teams/${teamId}/friends/${teamFriendId}/visibility`, {
      method: 'PATCH',
      body: req,
    })
  }

  return {
    follow,
    unfollow,
    listFriends,
    setVisibility,
  }
}
