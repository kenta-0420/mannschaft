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
 * 流量制限（429 Too Many Requests）のエラーかどうかを判定する。
 *
 * BE の `AbstractRateLimitFilter` は 429 + `Retry-After` + `X-RateLimit-*` を返し、
 * それを `useApi.ts` が横断ハンドリングして「あと N 秒待ってください」のトーストを出す。
 * 呼び出し元は**提示を重ねない**ために本判定を使う（#2530 ⑥）。
 *
 * ofetch の `FetchError` は `statusCode` を持ち、`response` も参照できる。
 * 前段の CDN / WAF が返した 429 でボディ形状が違っても拾えるよう両方を見る。
 */
export function isRateLimitedError(error: unknown): boolean {
  const fetchError = error as { statusCode?: number; response?: { status?: number } }
  return fetchError?.statusCode === 429 || fetchError?.response?.status === 429
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
  const errorReport = useErrorReport()

  // ⚠️ この composable は setup 直下だけでなく、**async イベントハンドラの await の後**からも
  // 構築される（`createAnnouncement` 経路: `components/timeline/TimelinePostForm.vue` の
  // `await createPost()` 後、`pages/blog/posts/[id]/edit.vue` の `await publishMyPost()` 後）。
  // そのため構築時に `useI18n()` / `useToast()` を呼んではならない。
  // （`useNotification()` は `useToast()` を、`useErrorHandler()` は両方を内部で呼ぶため同罪。
  //   呼ぶと「投稿は成功しているのに『投稿に失敗しました』」という silent な事故になる。）
  //
  // 既存の正解パターン（`useApi.ts:254-256` / `useDashboardWidgets.ts:729-733` /
  // `plugins/toast-provider.client.ts`）どおり、構築時に掴んだ nuxtApp 経由で参照する。
  const nuxtApp = useNuxtApp()
  // 戻り値の string 明示は `middleware/admin-console.ts:32` に揃えている（showToast が string を要求するため）。
  const t = (key: string): string => nuxtApp.$i18n.t(key)
  // 名前付き補間つきの翻訳。$i18n は useI18n() と同じ Composer なので t(key, named) 形が使える
  // （`useApi.ts` の `tn` と同じ流儀）。
  const tn = (key: string, named: Record<string, unknown>): string => nuxtApp.$i18n.t(key, named)

  /**
   * setup コンテキスト外でも安全に呼べるトースト表示（`$toast` 経由）。
   *
   * <p>`useNotification()` は使わない（内部の `useToast()` が setup を要求するため）。
   * `life` は `useNotification` の warn / error と同じ 5000ms に揃えている。</p>
   */
  function showToast(severity: 'warn' | 'error', summary: string, detail?: string): void {
    const toast = nuxtApp.$toast as { add: (opts: Record<string, unknown>) => void } | undefined
    toast?.add({ severity, summary, detail, life: 5000 })
  }

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
   *       （消すと利用者のデータが消えたように見える）。エラー報告を送ったうえで
   *       {@link showToast} で提示し、{@code true} を返して遷移は続行する。
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
        showToast('warn', t('announcement.no_longer_available'))
        return false
      }
      // 流量制限（429）: `useApi.ts` の横断ハンドリングが既に「あと N 秒待ってください」を
      // 提示済みなので、ここで汎用エラートーストを重ねると同じ事象で 2 枚並ぶ（#2530 ⑥）。
      // 提示だけを上流に譲る形であり、握りつぶしではない — エラー報告は下と同じく送り、
      // 遷移も従来どおり続行する（既読マークは副作用にすぎない）。
      if (isRateLimitedError(e)) {
        errorReport.captureQuiet(e, { context: 'announcementMarkAsRead' })
        return true
      }
      // 通信に失敗した: 項目は消さず、必ず利用者に提示する（握りつぶさない・#2460）。
      // 提示内容は useErrorHandler#handleApiError と同じ方針（BE の理由を優先し、
      // 無ければ汎用文言に落とす）。handleApiError 自体は内部で useI18n / useToast を
      // 呼ぶため、setup 外から構築され得る本 composable では使えない。
      errorReport.captureQuiet(e, { context: 'announcementMarkAsRead' })
      const apiError = e as { data?: { error?: { message?: string } } }
      showToast('error', t('dialog.error'), apiError?.data?.error?.message ?? t('error.unknown'))
      return true
    }
  }

  /**
   * スコープ内の未読お知らせを全件既読にする。
   *
   * <p><b>打ち切りを隠さない（#2530 ①）</b>: BE は 1 リクエストあたり最大 10,000 件
   * （500 件 × 20 チャンク）で打ち切り、残りは次回に回す。従来はここで `unreadCount` を
   * **無条件に 0 へ上書き**していたため、実際には未読が残っているのに画面上は「未読 0」に
   * なっていた（再取得すると復活する不気味な挙動）。現在は BE が
   * `markedCount`（実際に既読化した件数）と `hasMoreUnread`（残っているか）を返すので、
   * それに従って表示を実体へ合わせる。</p>
   *
   * <p>打ち切られた場合、**どのお知らせが既読化されたかは応答から分からない**。
   * 手元のリストを推測で塗るのではなく BE の真値を取り直す（正直さを優先する）。
   * そのうえで「N 件処理した・まだ残りがある・もう一度押せば続きを処理する」ことを
   * トーストで伝える。</p>
   */
  async function markAllAsRead(): Promise<void> {
    const res = await api<ApiResponse<MarkAllReadResponse>>(
      `${basePath()}/read-all`, { method: 'POST' })
    const markedCount = res.data?.markedCount ?? 0
    const hasMoreUnread = res.data?.hasMoreUnread === true

    if (!hasMoreUnread) {
      // 未読を最後まで処理しきった。手元の一覧と未読カウントを 0 に揃えて良い。
      feed.value = feed.value.map(item => ({ ...item, isRead: true }))
      if (meta.value) {
        meta.value = { ...meta.value, unreadCount: 0 }
      }
      return
    }

    // 打ち切られた: BE の真値へ揃え直してから、残りがあることを件数付きで伝える。
    await fetchFeed()
    showToast('warn', tn('announcement.mark_all_read_partial', { count: markedCount }))
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
