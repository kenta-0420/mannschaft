import type {
  ForwardRequest,
  ForwardResponse,
  FriendForwardExportListResponse,
} from '~/types/friendForward'

/**
 * F01.5 フレンドコンテンツ転送 composable。
 *
 * 提供するエンドポイント（設計書 §5）:
 * - POST   /api/v1/teams/{id}/friend-feed/{postId}/forward — 転送実行
 * - DELETE /api/v1/teams/{id}/friend-feed/forwards/{forwardId} — 転送取消
 * - GET    /api/v1/teams/{id}/friend-forward-exports — 逆転送履歴（透明性確保用）
 *
 * Phase 1 では {@code target='MEMBER'} のみ受理される。
 * {@code MEMBER_AND_SUPPORTER} 指定時は 400 Bad Request。
 */
export function useFriendForwardApi() {
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
   * 管理者フィードの投稿を自チーム内タイムラインへ転送する。
   *
   * @param teamId 自チーム ID
   * @param postId 転送元投稿 ID
   * @param req    転送リクエスト
   * @returns 転送結果
   */
  async function forward(
    teamId: number,
    postId: number,
    req: ForwardRequest,
  ): Promise<ForwardResponse> {
    const result = await api<{ data: ForwardResponse }>(
      `/api/v1/teams/${teamId}/friend-feed/${postId}/forward`,
      { method: 'POST', body: req },
    )
    return result.data
  }

  /**
   * 指定の転送履歴を取消する。転送先投稿（timeline_posts）は論理削除される。
   * 取消後は同一投稿を再度転送可能。
   *
   * @param teamId    自チーム ID
   * @param forwardId 転送履歴 ID
   */
  async function revokeForward(teamId: number, forwardId: number): Promise<void> {
    await api(`/api/v1/teams/${teamId}/friend-feed/forwards/${forwardId}`, {
      method: 'DELETE',
    })
  }

  /**
   * 自チーム投稿が他フレンドチームへ転送された履歴一覧を取得する（透明性確保用）。
   * 非公開フレンドの名前は「匿名チーム」に匿名化される。
   *
   * @param teamId 自チーム ID
   * @param params ページネーションパラメータ
   * @returns 逆転送履歴レスポンス
   */
  async function listForwardExports(
    teamId: number,
    params?: { page?: number; size?: number },
  ): Promise<FriendForwardExportListResponse> {
    return api<FriendForwardExportListResponse>(
      `/api/v1/teams/${teamId}/friend-forward-exports${buildQuery(params)}`,
    )
  }

  return {
    forward,
    revokeForward,
    listForwardExports,
  }
}
