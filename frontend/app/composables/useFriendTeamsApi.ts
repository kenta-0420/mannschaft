import type {
  FollowTeamRequest,
  FollowTeamResponse,
  SetVisibilityRequest,
  TeamFriendListResponse,
  UnfollowRequest,
} from '~/types/social-friend'

export function useFriendTeamsApi() {
  const api = useApi()
  const { handleApiError } = useErrorHandler()

  // ─────────────────────────────────────────
  // GET /api/v1/teams/{teamId}/friends — フレンドチーム一覧取得
  // ─────────────────────────────────────────

  async function listFriends(
    teamId: number,
    params?: { page?: number; size?: number; publicOnly?: boolean },
  ) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    if (params?.publicOnly !== undefined) {
      query.set('publicOnly', String(params.publicOnly))
    }
    return api<TeamFriendListResponse>(`/api/v1/teams/${teamId}/friends?${query}`)
  }

  // ─────────────────────────────────────────
  // POST /api/v1/teams/{teamId}/friends/follow — 他チームをフォロー
  // ─────────────────────────────────────────

  async function follow(teamId: number, body: FollowTeamRequest) {
    return api<{ data: FollowTeamResponse }>(`/api/v1/teams/${teamId}/friends/follow`, {
      method: 'POST',
      body,
    })
  }

  // ─────────────────────────────────────────
  // DELETE /api/v1/teams/{teamId}/friends/follow/{targetTeamId} — フォロー解除
  // ─────────────────────────────────────────

  async function unfollow(teamId: number, targetTeamId: number, body?: UnfollowRequest) {
    return api(`/api/v1/teams/${teamId}/friends/follow/${targetTeamId}`, {
      method: 'DELETE',
      body,
    })
  }

  // ─────────────────────────────────────────
  // PATCH /api/v1/teams/{teamId}/friends/{teamFriendId}/visibility — 公開設定変更
  // ─────────────────────────────────────────

  async function setVisibility(
    teamId: number,
    teamFriendId: number,
    body: SetVisibilityRequest,
  ) {
    return api(`/api/v1/teams/${teamId}/friends/${teamFriendId}/visibility`, {
      method: 'PATCH',
      body,
    })
  }

  return {
    listFriends,
    follow,
    unfollow,
    setVisibility,
    handleApiError,
  }
}
