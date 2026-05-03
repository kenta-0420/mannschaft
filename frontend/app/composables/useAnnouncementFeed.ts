import type {
  AnnouncementFeedItem,
  AnnouncementFeedMeta,
  AnnouncementFeedParams,
  AnnouncementFeedResponse,
  AnnouncementScopeType,
  CreateAnnouncementRequest,
  MarkAllReadResponse,
  MarkReadResponse,
  TogglePinRequest,
  TogglePinResponse,
} from '~/types/announcement'
import type { ApiResponse } from '~/types/api'

/**
 * F02.6 お知らせウィジェット composable。
 *
 * GET /api/v1/teams/{id}/announcements または
 * GET /api/v1/organizations/{id}/announcements を呼び出し、
 * お知らせ一覧と関連操作を管理する。
 *
 * @param scopeType スコープ種別（TEAM / ORGANIZATION）
 * @param scopeId   スコープ ID（チームまたは組織の ID）
 */
export function useAnnouncementFeed(scopeType: AnnouncementScopeType, scopeId: number) {
  const api = useApi()

  const feed = ref<AnnouncementFeedItem[]>([])
  const meta = ref<AnnouncementFeedMeta | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** スコープに応じた API ベースパスを返す */
  function basePath() {
    if (scopeType === 'TEAM') return `/api/v1/teams/${scopeId}/announcements`
    return `/api/v1/organizations/${scopeId}/announcements`
  }

  /**
   * お知らせ一覧を取得する。
   * cursor が指定された場合は既存リストに追記（「もっと見る」用）。
   * @param params フィルタ・ページネーションパラメータ（省略可）
   */
  async function fetchFeed(params?: AnnouncementFeedParams) {
    loading.value = true
    error.value = null
    try {
      const query = new URLSearchParams()
      if (params?.cursor !== undefined) query.set('cursor', String(params.cursor))
      if (params?.limit !== undefined) query.set('limit', String(params.limit))
      if (params?.includeRead !== undefined) query.set('include_read', String(params.includeRead))
      if (params?.sourceType !== undefined) query.set('source_type', params.sourceType)

      const qs = query.toString()
      const url = `${basePath()}${qs ? '?' + qs : ''}`
      const res = await api<AnnouncementFeedResponse>(url)
      // cursor がある場合は追記（ページング）、なければ置き換え（初回ロード）
      if (params?.cursor !== undefined) {
        feed.value = [...feed.value, ...res.data]
      } else {
        feed.value = res.data
      }
      meta.value = res.meta
    }
    catch {
      error.value = 'お知らせの取得に失敗しました'
    }
    finally {
      loading.value = false
    }
  }

  /**
   * コンテンツをお知らせ化する。
   * @param params ソース種別・ソース ID 等
   */
  async function createAnnouncement(params: CreateAnnouncementRequest): Promise<void> {
    await api<ApiResponse<{ id: number }>>(basePath(), {
      method: 'POST',
      body: params,
    })
  }

  /**
   * お知らせを解除する（元コンテンツは残す）。
   * @param id announcement_feed ID
   */
  async function deleteAnnouncement(id: number): Promise<void> {
    await api(`${basePath()}/${id}`, { method: 'DELETE' })
    feed.value = feed.value.filter(item => item.id !== id)
  }

  /**
   * ピン留めの ON/OFF を切り替える。
   * @param id      announcement_feed ID
   */
  async function togglePin(id: number): Promise<void> {
    const item = feed.value.find(f => f.id === id)
    if (!item) return
    const req: TogglePinRequest = { pinned: !item.isPinned }
    const res = await api<ApiResponse<TogglePinResponse>>(`${basePath()}/${id}/pin`, {
      method: 'PATCH',
      body: req,
    })
    const idx = feed.value.findIndex(f => f.id === id)
    if (idx !== -1) {
      feed.value[idx] = {
        ...feed.value[idx]!,
        isPinned: res.data.isPinned,
        pinnedAt: res.data.pinnedAt,
      }
    }
  }

  /**
   * 1件を既読にする（冪等）。
   * @param id announcement_feed ID
   */
  async function markAsRead(id: number): Promise<void> {
    await api<ApiResponse<MarkReadResponse>>(`${basePath()}/${id}/read`, { method: 'POST' })
    const idx = feed.value.findIndex(f => f.id === id)
    if (idx !== -1) {
      feed.value[idx] = { ...feed.value[idx]!, isRead: true }
    }
    // 未読カウント減算
    if (meta.value && meta.value.unreadCount > 0) {
      meta.value = { ...meta.value, unreadCount: meta.value.unreadCount - 1 }
    }
  }

  /**
   * スコープ内の未読お知らせを全件既読にする。
   */
  async function markAllAsRead(): Promise<void> {
    await api<ApiResponse<MarkAllReadResponse>>(`${basePath()}/read-all`, { method: 'POST' })
    feed.value = feed.value.map(item => ({ ...item, isRead: true }))
    if (meta.value) {
      meta.value = { ...meta.value, unreadCount: 0 }
    }
  }

  return {
    feed,
    meta,
    loading,
    error,
    fetchFeed,
    createAnnouncement,
    deleteAnnouncement,
    togglePin,
    markAsRead,
    markAllAsRead,
  }
}
