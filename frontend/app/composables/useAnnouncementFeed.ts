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
 * 「そのお知らせはもう開けない」ことを表す BE エラーコード。
 *
 * BE（{@code AnnouncementReadService#assertReadable}）は存在秘匿のため
 * 「当該スコープに属さない」「そもそも存在しない」「自分には可視でない（内輪限定・削除済み・
 * 期限切れ）」の 3 つをすべて {@code ANNOUNCE_001} に畳み込んで返す。
 *
 * 注意: このコードは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に未登録のため、
 * {@code Severity.WARN} 既定の **HTTP 400** で返る（enum の Javadoc にある「404」は宣言と
 * 実挙動の乖離）。したがって HTTP ステータスでは判別できず、**エラーコードで判別する**。
 */
const ANNOUNCEMENT_GONE_ERROR_CODE = 'ANNOUNCE_001'

/**
 * 「もう見えない（不可視・不在）」エラーかどうかを判定する。
 *
 * ネットワーク断・5xx などの「通信に失敗した」エラーと区別するために使う。
 * 後者で一覧から項目を消すと、利用者にはデータが消えたように見えてしまう。
 */
export function isAnnouncementGoneError(error: unknown): boolean {
  const apiError = error as { data?: { error?: { code?: string } } }
  return apiError?.data?.error?.code === ANNOUNCEMENT_GONE_ERROR_CODE
}

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
export function useAnnouncementFeed(scopeType: AnnouncementScopeType, scopeId: string) {
  const api = useApi()
  const { t } = useI18n()
  const notification = useNotification()
  const { handleApiError } = useErrorHandler()

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
   * 取得済みフィードから 1 件を「ローカルだけ」取り除く（API は呼ばない）。
   *
   * <p>{@link deleteAnnouncement} と違い「お知らせ解除」の DELETE は投げない。
   * BE 側では既に見えなくなっている（期限切れ・削除済み）項目を、手元の一覧表示から
   * 落とすためだけに使う。</p>
   */
  function removeFromFeedLocally(id: number): void {
    const target = feed.value.find(item => item.id === id)
    if (!target) return
    feed.value = feed.value.filter(item => item.id !== id)
    // 消した項目が未読だったぶんだけ未読カウントを戻す（表示と実体を揃える）
    if (!target.isRead && meta.value && meta.value.unreadCount > 0) {
      meta.value = { ...meta.value, unreadCount: meta.value.unreadCount - 1 }
    }
  }

  /**
   * お知らせを開く前の既読マーク（#2495 の根治）。
   *
   * <p><b>背景</b>: 一覧を開いたまま放置している間に元コンテンツが期限切れ・削除になると、
   * 描画時点では可視だった項目がクリック時点で既読 API の可視判定に落ち、
   * {@code ANNOUNCE_001} が飛ぶ。従来はこの例外が呼び出し元の {@code navigateTo} の手前で
   * 抜けてしまい、<b>クリックしても何も起きない</b>状態になっていた。</p>
   *
   * <p><b>「もう見えない」と「通信に失敗した」を区別する</b>:</p>
   * <ul>
   *   <li>{@code ANNOUNCE_001}（不可視・不在）: もう開けない。一覧から取り除いてトーストで
   *       知らせ、{@code false} を返す（利用者に再読み込み等の再操作を求めない）。</li>
   *   <li>それ以外（ネットワーク断・5xx・認証失敗など）: 一覧からは<b>取り除かない</b>
   *       （消すと利用者のデータが消えたように見える）。既存の流儀どおり
   *       {@link useErrorHandler} でエラーを表示したうえで {@code true} を返し、遷移は続行する。
   *       既読マークはあくまで副作用であり、その失敗で「開く」という意図まで
   *       巻き添えにしないため。</li>
   * </ul>
   *
   * <p>エラーを握りつぶさない（#2460）。いずれの経路でも利用者に必ず何かを提示する。</p>
   *
   * @param item クリックされたお知らせ
   * @return 元コンテンツへ遷移してよければ {@code true}
   */
  async function markAsReadBeforeOpen(item: AnnouncementFeedItem): Promise<boolean> {
    if (item.isRead) return true
    try {
      await markAsRead(item.id)
      return true
    }
    catch (e) {
      if (isAnnouncementGoneError(e)) {
        removeFromFeedLocally(item.id)
        notification.warn(t('announcement.no_longer_available'))
        return false
      }
      handleApiError(e, 'announcementMarkAsRead')
      return true
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
    markAsReadBeforeOpen,
    removeFromFeedLocally,
    markAllAsRead,
  }
}
