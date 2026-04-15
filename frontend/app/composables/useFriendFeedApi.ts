import type {
  ForwardRequest,
  ForwardResponse,
  FriendForwardExportListResponse,
} from '~/types/social-friend'

export function useFriendFeedApi() {
  const api = useApi()
  const { handleApiError } = useErrorHandler()

  // ─────────────────────────────────────────
  // POST /api/v1/teams/{teamId}/friend-feed/{postId}/forward — 投稿転送
  // ─────────────────────────────────────────

  async function forward(teamId: number, postId: number, body: ForwardRequest) {
    return api<{ data: ForwardResponse }>(
      `/api/v1/teams/${teamId}/friend-feed/${postId}/forward`,
      { method: 'POST', body },
    )
  }

  // ─────────────────────────────────────────
  // DELETE /api/v1/teams/{teamId}/friend-feed/forwards/{forwardId} — 転送取消
  // ─────────────────────────────────────────

  async function revokeForward(teamId: number, forwardId: number) {
    return api(`/api/v1/teams/${teamId}/friend-feed/forwards/${forwardId}`, {
      method: 'DELETE',
    })
  }

  // ─────────────────────────────────────────
  // GET /api/v1/teams/{teamId}/friend-forward-exports — 逆転送履歴取得
  // ─────────────────────────────────────────

  async function listExportedPosts(
    teamId: number,
    params?: { page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<FriendForwardExportListResponse>(
      `/api/v1/teams/${teamId}/friend-forward-exports?${query}`,
    )
  }

  return {
    forward,
    revokeForward,
    listExportedPosts,
    handleApiError,
  }
}
