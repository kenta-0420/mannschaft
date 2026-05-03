import type {
  ActionMemo,
  ActionMemoAuditLog,
  ActionMemoCategory,
  ActionMemoListResponse,
  ActionMemoRateLimitError,
  ActionMemoSettings,
  ActionMemoTag,
  AvailableOrg,
  AvailableTeam,
  CreateActionMemoPayload,
  CreateTagPayload,
  ListActionMemoParams,
  Mood,
  MoodStatsResponse,
  OrgVisibility,
  PublishDailyPayload,
  PublishDailyResponse,
  PublishDailyToTeamPayload,
  PublishToTeamPayload,
  UpdateActionMemoPayload,
  UpdateTagPayload,
  WeeklySummary,
  WeeklySummaryListResponse,
} from '~/types/actionMemo'

/**
 * F02.5 行動メモ API クライアント。
 *
 * <p>Backend は一部フィールドを {@code @JsonProperty} でスネークケース表現にしているため
 * （{@code memo_date}, {@code tag_ids}, {@code related_todo_id}, {@code timeline_post_id},
 * {@code created_at}, {@code updated_at}, {@code next_cursor}, {@code mood_enabled}）、
 * このクライアント内でキャメルケース ⇔ スネークケースを明示的に変換する。</p>
 *
 * <p>レートリミット 429 応答は {@link ActionMemoRateLimitError} に再ラップして
 * {@code Retry-After} ヘッダーの秒数を呼び出し側へ伝える。</p>
 *
 * <p><b>Phase 2 スコープ</b>: CRUD + link-todo + 設定 + publish-daily（終業まとめ投稿）。
 * Tag CRUD は Phase 4 で別途追加する。</p>
 */
export function useActionMemoApi() {
  const api = useApi()
  const BASE = '/api/v1/action-memos'
  const TAGS_BASE = '/api/v1/action-memo-tags'
  const SETTINGS_BASE = '/api/v1/action-memo-settings'

  // === 内部ヘルパー: スネーク → キャメル変換 ===

  type RawTag = {
    id: number
    name: string
    color: string | null
    deleted?: boolean
  }

  type RawMemo = {
    id: number
    content: string
    mood: Mood | null
    memo_date: string
    related_todo_id: number | null
    timeline_post_id: number | null
    tags: RawTag[] | null
    created_at: string
    updated_at?: string
    // Phase 3
    category?: string | null
    duration_minutes?: number | null
    progress_rate?: number | null
    completes_todo?: boolean | null
    posted_team_id?: number | null
    // Phase 4-α
    organization_id?: number | null
    org_visibility?: string | null
  }

  type RawSettings = {
    mood_enabled: boolean
    // Phase 3
    default_post_team_id?: number | null
    default_category?: string | null
    // Phase 4-β
    reminder_enabled?: boolean
    reminder_time?: string | null
  }

  type RawAvailableTeam = {
    id: number
    name: string
    is_default: boolean
  }

  type RawPublishDailyResponse = {
    timeline_post_id: number
    memo_count: number
    memo_date: string
  }

  type RawListResponse = {
    data: RawMemo[]
    next_cursor: string | null
  }

  function normalizeTag(raw: RawTag): ActionMemoTag {
    return {
      id: raw.id,
      name: raw.name,
      color: raw.color ?? null,
      deleted: raw.deleted === true,
    }
  }

  function normalizeMemo(raw: RawMemo): ActionMemo {
    return {
      id: raw.id,
      memoDate: raw.memo_date,
      content: raw.content,
      mood: raw.mood ?? null,
      relatedTodoId: raw.related_todo_id ?? null,
      timelinePostId: raw.timeline_post_id ?? null,
      tags: (raw.tags ?? []).map(normalizeTag),
      createdAt: raw.created_at,
      updatedAt: raw.updated_at,
      // Phase 3
      category: (raw.category as ActionMemoCategory) ?? 'OTHER',
      durationMinutes: raw.duration_minutes ?? null,
      progressRate: raw.progress_rate ?? null,
      completesTodo: raw.completes_todo ?? false,
      postedTeamId: raw.posted_team_id ?? null,
      // Phase 4-α
      organizationId: raw.organization_id ?? null,
      orgVisibility: (raw.org_visibility as OrgVisibility) ?? null,
    }
  }

  function normalizeSettings(raw: RawSettings): ActionMemoSettings {
    return {
      moodEnabled: raw.mood_enabled,
      // Phase 3
      defaultPostTeamId: raw.default_post_team_id ?? null,
      defaultCategory: (raw.default_category as ActionMemoCategory) ?? 'OTHER',
      // Phase 4-β
      reminderEnabled: raw.reminder_enabled ?? false,
      reminderTime: raw.reminder_time ?? null,
    }
  }

  function normalizeAvailableTeam(raw: RawAvailableTeam): AvailableTeam {
    return {
      id: raw.id,
      name: raw.name,
      isDefault: raw.is_default === true,
    }
  }

  function buildCreateBody(payload: CreateActionMemoPayload) {
    const body: Record<string, unknown> = {
      content: payload.content,
    }
    if (payload.memoDate !== undefined) body.memo_date = payload.memoDate
    if (payload.mood !== undefined) body.mood = payload.mood
    if (payload.relatedTodoId !== undefined) body.related_todo_id = payload.relatedTodoId
    if (payload.tagIds !== undefined) body.tag_ids = payload.tagIds
    // Phase 3
    if (payload.category !== undefined) body.category = payload.category
    if (payload.durationMinutes !== undefined) body.duration_minutes = payload.durationMinutes
    if (payload.progressRate !== undefined) body.progress_rate = payload.progressRate
    if (payload.completesTodo !== undefined) body.completes_todo = payload.completesTodo
    // Phase 4-α
    if (payload.organizationId !== undefined) body.organization_id = payload.organizationId
    if (payload.orgVisibility !== undefined) body.org_visibility = payload.orgVisibility
    return body
  }

  function buildUpdateBody(payload: UpdateActionMemoPayload) {
    const body: Record<string, unknown> = {}
    if (payload.content !== undefined) body.content = payload.content
    if (payload.memoDate !== undefined) body.memo_date = payload.memoDate
    if (payload.mood !== undefined) body.mood = payload.mood
    if (payload.relatedTodoId !== undefined) body.related_todo_id = payload.relatedTodoId
    if (payload.tagIds !== undefined) body.tag_ids = payload.tagIds
    // Phase 3
    if (payload.category !== undefined) body.category = payload.category
    if (payload.durationMinutes !== undefined) body.duration_minutes = payload.durationMinutes
    if (payload.progressRate !== undefined) body.progress_rate = payload.progressRate
    if (payload.completesTodo !== undefined) body.completes_todo = payload.completesTodo
    // Phase 4-α
    if (payload.organizationId !== undefined) body.organization_id = payload.organizationId
    if (payload.orgVisibility !== undefined) body.org_visibility = payload.orgVisibility
    return body
  }

  /**
   * 共通エラーハンドラ。429 を {@link ActionMemoRateLimitError} に再ラップする。
   * 呼び出し側は {@code error.status === 429} と {@code error.retryAfterSeconds} で
   * トースト表示・自動リトライ等を判断できる。
   */
  function rethrow(error: unknown): never {
    const err = error as {
      response?: { status?: number; headers?: { get?: (k: string) => string | null } }
      message?: string
    }
    if (err?.response?.status === 429) {
      const retryAfter = err.response.headers?.get?.('Retry-After') ?? null
      const seconds = retryAfter ? Number.parseInt(retryAfter, 10) : null
      const wrapped = new Error(
        `Rate limited (Retry-After: ${seconds ?? 'unknown'}s)`,
      ) as ActionMemoRateLimitError
      wrapped.status = 429
      wrapped.retryAfterSeconds = Number.isFinite(seconds) ? seconds : null
      throw wrapped
    }
    throw error
  }

  // === Memo CRUD ===

  async function createMemo(payload: CreateActionMemoPayload): Promise<ActionMemo> {
    try {
      const res = await api<{ data: RawMemo }>(BASE, {
        method: 'POST',
        body: buildCreateBody(payload),
      })
      return normalizeMemo(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  async function fetchMemos(params: ListActionMemoParams = {}): Promise<ActionMemoListResponse> {
    const query = new URLSearchParams()
    if (params.date) query.set('date', params.date)
    if (params.from) query.set('from', params.from)
    if (params.to) query.set('to', params.to)
    if (params.tagId !== undefined) query.set('tag_id', String(params.tagId))
    if (params.cursor) query.set('cursor', params.cursor)
    if (params.limit !== undefined) query.set('limit', String(params.limit))

    try {
      const res = await api<RawListResponse>(`${BASE}?${query.toString()}`)
      return {
        data: (res.data ?? []).map(normalizeMemo),
        nextCursor: res.next_cursor ?? null,
      }
    } catch (error) {
      rethrow(error)
    }
  }

  async function getMemo(id: number): Promise<ActionMemo> {
    try {
      const res = await api<{ data: RawMemo }>(`${BASE}/${id}`)
      return normalizeMemo(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  async function updateMemo(id: number, payload: UpdateActionMemoPayload): Promise<ActionMemo> {
    try {
      const res = await api<{ data: RawMemo }>(`${BASE}/${id}`, {
        method: 'PATCH',
        body: buildUpdateBody(payload),
      })
      return normalizeMemo(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  async function deleteMemo(id: number): Promise<void> {
    try {
      await api(`${BASE}/${id}`, { method: 'DELETE' })
    } catch (error) {
      rethrow(error)
    }
  }

  async function linkTodo(id: number, todoId: number): Promise<ActionMemo> {
    try {
      const res = await api<{ data: RawMemo }>(`${BASE}/${id}/link-todo`, {
        method: 'POST',
        body: { todo_id: todoId },
      })
      return normalizeMemo(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  // === Settings ===

  async function getSettings(): Promise<ActionMemoSettings> {
    try {
      const res = await api<{ data: RawSettings }>(SETTINGS_BASE)
      return normalizeSettings(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  async function updateSettings(payload: Partial<ActionMemoSettings>): Promise<ActionMemoSettings> {
    const body: Record<string, unknown> = {}
    if (payload.moodEnabled !== undefined) body.mood_enabled = payload.moodEnabled
    // Phase 3
    if (payload.defaultPostTeamId !== undefined) body.default_post_team_id = payload.defaultPostTeamId
    if (payload.defaultCategory !== undefined) body.default_category = payload.defaultCategory
    // Phase 4-β
    if (payload.reminderEnabled !== undefined) body.reminder_enabled = payload.reminderEnabled
    if (payload.reminderTime !== undefined) body.reminder_time = payload.reminderTime
    try {
      const res = await api<{ data: RawSettings }>(SETTINGS_BASE, {
        method: 'PATCH',
        body,
      })
      return normalizeSettings(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  // === Phase 5-1: 監査ログ取得 ===

  type RawAuditLog = {
    id: number
    event_type: string
    actor_id: number | null
    created_at: string
    metadata: string | null
  }

  /**
   * メモに紐付く監査ログを取得する（変更履歴折りたたみUI用）。
   *
   * @param memoId 対象メモ ID
   * @returns 最新10件の監査ログ（新しい順）
   */
  async function getMemoAuditLogs(memoId: number): Promise<ActionMemoAuditLog[]> {
    try {
      const res = await api<{ data: RawAuditLog[] }>(`${BASE}/${memoId}/audit-logs`)
      return (res.data ?? []).map((raw: RawAuditLog): ActionMemoAuditLog => ({
        id: raw.id,
        eventType: raw.event_type,
        actorId: raw.actor_id ?? null,
        createdAt: raw.created_at,
        metadata: raw.metadata ?? null,
      }))
    } catch (error) {
      rethrow(error)
    }
  }

  // === Phase 4-β: TODO 差し戻し ===

  async function revertTodoCompletion(memoId: number): Promise<void> {
    try {
      await api(`${BASE}/${memoId}/complete-todo`, { method: 'DELETE' })
    } catch (error) {
      rethrow(error)
    }
  }

  // === Phase 4-β: 管理職ダッシュボード ===

  async function fetchMemberMemos(
    teamId: number,
    memberId: number,
    params: { cursor?: string; limit?: number } = {},
  ): Promise<ActionMemoListResponse> {
    const query = new URLSearchParams()
    if (params.cursor) query.set('cursor', params.cursor)
    if (params.limit !== undefined) query.set('limit', String(params.limit))
    try {
      const res = await api<RawListResponse>(
        `/api/v1/teams/${teamId}/members/${memberId}/action-memos?${query.toString()}`,
      )
      return {
        data: (res.data ?? []).map(normalizeMemo),
        nextCursor: res.next_cursor ?? null,
      }
    } catch (error) {
      rethrow(error)
    }
  }

  // === Publish daily (Phase 2) ===

  /**
   * 当日分（または指定日分）のメモをまとめて PERSONAL タイムラインに投稿する。
   *
   * <p>設計書 §4 §5.4 に基づく「今日を締める」儀式。サーバー側は 0 件の日は 400、
   * 1 分 5 回超の連打は 429、それ以外は 201 Created で {@code timeline_post_id} 等を返す。
   * 同日内の再実行は旧投稿を論理削除してから新規投稿を作り直す（冪等再実行）。</p>
   */
  async function publishDaily(payload: PublishDailyPayload = {}): Promise<PublishDailyResponse> {
    const body: Record<string, unknown> = {}
    if (payload.memoDate !== undefined) body.memo_date = payload.memoDate
    if (payload.extraComment !== undefined) body.extra_comment = payload.extraComment
    try {
      const res = await api<{ data: RawPublishDailyResponse }>(`${BASE}/publish-daily`, {
        method: 'POST',
        body,
      })
      return {
        timelinePostId: res.data.timeline_post_id,
        memoCount: res.data.memo_count,
        memoDate: res.data.memo_date,
      }
    } catch (error) {
      rethrow(error)
    }
  }

  // === Weekly Summary (Phase 3) ===

  /**
   * F06.1 BlogPost API のレスポンス型（週次まとめ取得に必要な最小サブセット）。
   *
   * <p>設計書 §11 #11: 新規 API は作らない。{@code GET /api/v1/blog/posts} の
   * ページネーション付きレスポンスを利用し、タイトルプレフィックスでクライアント側フィルタする。</p>
   */
  type RawBlogPost = {
    id: number
    title: string
    body: string | null
    publishedAt: string | null
    visibility: string | null
  }

  type RawBlogPagedResponse = {
    data: RawBlogPost[]
    meta: {
      page: number
      size: number
      totalElements: number
      totalPages: number
    }
  }

  /** 週次ふりかえりタイトルのプレフィックス */
  const WEEKLY_TITLE_PREFIX = '週次ふりかえり: '

  /**
   * タイトルから対象期間（from / to）を抽出する。
   * 期待フォーマット: "週次ふりかえり: YYYY-MM-DD 〜 YYYY-MM-DD"
   */
  function parsePeriodFromTitle(title: string): { from: string; to: string } {
    const datePattern = /(\d{4}-\d{2}-\d{2})\s*[〜~]\s*(\d{4}-\d{2}-\d{2})/
    const match = title.match(datePattern)
    if (match) {
      return { from: match[1]!, to: match[2]! }
    }
    return { from: '', to: '' }
  }

  function normalizeBlogPostToWeeklySummary(raw: RawBlogPost): WeeklySummary {
    return {
      id: raw.id,
      title: raw.title,
      body: raw.body ?? '',
      publishedAt: raw.publishedAt ?? null,
      period: parsePeriodFromTitle(raw.title),
    }
  }

  /**
   * 週次まとめ一覧を取得する。
   *
   * <p>F06.1 BlogPost API（{@code GET /api/v1/blog/posts?visibility=PRIVATE}）を呼び、
   * タイトルが「週次ふりかえり: 」で始まるブログ記事だけをクライアント側フィルタして返す。</p>
   *
   * @param params.page ページ番号（0始まり）。デフォルト 0
   * @param params.size 1ページあたりの件数。デフォルト 20
   */
  async function fetchWeeklySummaries(params?: {
    page?: number
    size?: number
  }): Promise<WeeklySummaryListResponse> {
    const query = new URLSearchParams()
    query.set('visibility', 'PRIVATE')
    if (params?.page !== undefined) query.set('page', String(params.page))
    if (params?.size !== undefined) query.set('size', String(params.size))

    try {
      const res = await api<RawBlogPagedResponse>(`/api/v1/blog/posts?${query.toString()}`)
      const filtered = (res.data ?? [])
        .filter((post: RawBlogPost) => post.title.startsWith(WEEKLY_TITLE_PREFIX))
        .map(normalizeBlogPostToWeeklySummary)
      return {
        data: filtered,
        page: res.meta?.page ?? 0,
        totalPages: res.meta?.totalPages ?? 1,
      }
    } catch (error) {
      rethrow(error)
    }
  }

  /**
   * 週次まとめ詳細を取得する。
   *
   * <p>{@code GET /api/v1/blog/posts/{slug}} を呼ぶ。
   * 週次まとめブログの slug は Backend が自動生成するため、ID ベースではなく
   * slug ベースで取得する。ただし一覧から ID を持っている場合が多いので、
   * 一覧の body をそのまま使えば追加リクエスト不要。</p>
   */
  async function getWeeklySummary(slug: string): Promise<WeeklySummary> {
    try {
      const res = await api<{ data: RawBlogPost }>(`/api/v1/blog/posts/${slug}`)
      return normalizeBlogPostToWeeklySummary(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  // === Available Teams (Phase 3) ===

  /**
   * チーム投稿先候補一覧を取得する。
   * {@code GET /api/v1/action-memos/available-teams}
   */
  async function fetchAvailableTeams(): Promise<AvailableTeam[]> {
    try {
      const res = await api<{ data: RawAvailableTeam[] }>(`${BASE}/available-teams`)
      return (res.data ?? []).map(normalizeAvailableTeam)
    } catch (error) {
      rethrow(error)
    }
  }

  // === Available Orgs (Phase 5-2) ===

  /**
   * 組織スコープ投稿先候補一覧を取得する。
   * {@code GET /api/v1/action-memos/available-orgs}
   */
  async function fetchAvailableOrgs(): Promise<AvailableOrg[]> {
    try {
      const res = await api<{ data: { id: number; name: string }[] }>(`${BASE}/available-orgs`)
      return (res.data ?? []).map((o) => ({ id: o.id, name: o.name }))
    } catch (error) {
      rethrow(error)
    }
  }

  // === Publish to team (Phase 3) ===

  /**
   * 個別メモをチームタイムラインに投稿する。
   * {@code POST /api/v1/action-memos/{memoId}/publish-to-team}
   */
  async function publishToTeam(memoId: number, payload: PublishToTeamPayload): Promise<void> {
    const body: Record<string, unknown> = {}
    if (payload.teamId !== undefined) body.team_id = payload.teamId
    if (payload.extraComment !== undefined) body.extra_comment = payload.extraComment
    try {
      await api(`${BASE}/${memoId}/publish-to-team`, {
        method: 'POST',
        body,
      })
    } catch (error) {
      rethrow(error)
    }
  }

  /**
   * 今日の WORK メモを一括チーム投稿する。
   * {@code POST /api/v1/action-memos/publish-daily-to-team}
   */
  async function publishDailyToTeam(payload: PublishDailyToTeamPayload = {}): Promise<void> {
    const body: Record<string, unknown> = {}
    if (payload.teamId !== undefined) body.team_id = payload.teamId
    try {
      await api(`${BASE}/publish-daily-to-team`, {
        method: 'POST',
        body,
      })
    } catch (error) {
      rethrow(error)
    }
  }

  // === Tag CRUD (Phase 4) ===

  type RawTagResponse = {
    id: number
    name: string
    color: string | null
    sort_order: number
    deleted: boolean
  }

  function normalizeTagResponse(raw: RawTagResponse): ActionMemoTag {
    return {
      id: raw.id,
      name: raw.name,
      color: raw.color ?? null,
      deleted: raw.deleted === true,
    }
  }

  async function getTags(): Promise<ActionMemoTag[]> {
    try {
      const res = await api<{ data: RawTagResponse[] }>(TAGS_BASE)
      return (res.data ?? []).map(normalizeTagResponse)
    } catch (error) {
      rethrow(error)
    }
  }

  async function createTag(payload: CreateTagPayload): Promise<ActionMemoTag> {
    try {
      const res = await api<{ data: RawTagResponse }>(TAGS_BASE, {
        method: 'POST',
        body: {
          name: payload.name,
          color: payload.color ?? undefined,
        },
      })
      return normalizeTagResponse(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  async function updateTag(id: number, payload: UpdateTagPayload): Promise<ActionMemoTag> {
    try {
      const body: Record<string, unknown> = {}
      if (payload.name !== undefined) body.name = payload.name
      if (payload.color !== undefined) body.color = payload.color
      const res = await api<{ data: RawTagResponse }>(`${TAGS_BASE}/${id}`, {
        method: 'PATCH',
        body,
      })
      return normalizeTagResponse(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  async function deleteTag(id: number): Promise<void> {
    try {
      await api(`${TAGS_BASE}/${id}`, { method: 'DELETE' })
    } catch (error) {
      rethrow(error)
    }
  }

  // === Memo ↔ Tag operations (Phase 4) ===

  async function addTagsToMemo(memoId: number, tagIds: number[]): Promise<void> {
    try {
      await api(`${BASE}/${memoId}/tags`, {
        method: 'POST',
        body: { tag_ids: tagIds },
      })
    } catch (error) {
      rethrow(error)
    }
  }

  async function removeTagFromMemo(memoId: number, tagId: number): Promise<void> {
    try {
      await api(`${BASE}/${memoId}/tags/${tagId}`, { method: 'DELETE' })
    } catch (error) {
      rethrow(error)
    }
  }

  // === Mood stats (Phase 4) ===

  type RawMoodStatsResponse = {
    total: number
    distribution: Record<string, number>
  }

  async function getMoodStats(params: { from: string; to: string }): Promise<MoodStatsResponse> {
    const query = new URLSearchParams()
    query.set('from', params.from)
    query.set('to', params.to)
    try {
      const res = await api<{ data: RawMoodStatsResponse }>(`${BASE}/mood-stats?${query.toString()}`)
      return {
        total: res.data.total,
        distribution: (res.data.distribution ?? {}) as Partial<Record<Mood, number>>,
      }
    } catch (error) {
      rethrow(error)
    }
  }

  return {
    createMemo,
    fetchMemos,
    getMemo,
    updateMemo,
    deleteMemo,
    linkTodo,
    getSettings,
    updateSettings,
    publishDaily,
    fetchWeeklySummaries,
    getWeeklySummary,
    getTags,
    createTag,
    updateTag,
    deleteTag,
    addTagsToMemo,
    removeTagFromMemo,
    getMoodStats,
    // Phase 3
    fetchAvailableTeams,
    publishToTeam,
    publishDailyToTeam,
    // Phase 4-β
    revertTodoCompletion,
    fetchMemberMemos,
    // Phase 5-1
    getMemoAuditLogs,
    // Phase 5-2
    fetchAvailableOrgs,
  }
}
