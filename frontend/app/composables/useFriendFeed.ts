import type {
  FriendFeedMeta,
  FriendFeedParams,
  FriendFeedPost,
  FriendFeedResponse,
} from '~/types/friendFeed'

/**
 * F01.5 管理者フィード composable。
 *
 * GET /api/v1/teams/{id}/friend-feed を呼び出し、
 * フレンドチームからの投稿一覧と転送状態を管理する。
 *
 * @param teamId 自チーム ID
 */
export function useFriendFeed(teamId: number) {
  const api = useApi()

  const posts = ref<FriendFeedPost[]>([])
  const meta = ref<FriendFeedMeta | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  /**
   * フレンドフィードを取得する。
   *
   * @param params フィルタ・ページネーションパラメータ（省略可）
   */
  async function fetchFeed(params?: FriendFeedParams) {
    loading.value = true
    error.value = null
    try {
      const query = new URLSearchParams()
      if (params?.folderId !== undefined) query.set('folder_id', String(params.folderId))
      if (params?.sourceTeamId !== undefined) query.set('source_team_id', String(params.sourceTeamId))
      if (params?.forwardedOnly !== undefined) query.set('forwarded_only', String(params.forwardedOnly))
      if (params?.cursor !== undefined) query.set('cursor', String(params.cursor))
      if (params?.limit !== undefined) query.set('limit', String(params.limit))

      const qs = query.toString()
      const url = `/api/v1/teams/${teamId}/friend-feed${qs ? '?' + qs : ''}`
      const res = await api<FriendFeedResponse>(url)
      posts.value = res.data
      meta.value = res.meta
    }
    catch {
      error.value = '投稿の取得に失敗しました'
    }
    finally {
      loading.value = false
    }
  }

  /**
   * 転送成功後、該当投稿の isForwarded を true に更新する。
   *
   * @param postId    転送した投稿 ID
   * @param forwardId 発行された転送 ID
   */
  function markAsForwarded(postId: number, forwardId: number) {
    const idx = posts.value.findIndex(p => p.postId === postId)
    if (idx !== -1) {
      posts.value[idx] = {
        ...posts.value[idx]!,
        forwardStatus: {
          isForwarded: true,
          forwardId,
          forwardedAt: new Date().toISOString(),
        },
      }
    }
  }

  return { posts, meta, loading, error, fetchFeed, markAsForwarded }
}
