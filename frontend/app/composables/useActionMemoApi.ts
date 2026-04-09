import type {
  ActionMemo,
  ActionMemoListResponse,
  ActionMemoRateLimitError,
  ActionMemoSettings,
  ActionMemoTag,
  CreateActionMemoPayload,
  ListActionMemoParams,
  Mood,
  UpdateActionMemoPayload,
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
 * <p><b>Phase 1 スコープ</b>: CRUD + link-todo + 設定。
 * publish-daily / Tag CRUD は Phase 2-4 で別途追加する。</p>
 */
export function useActionMemoApi() {
  const api = useApi()
  const BASE = '/api/v1/action-memos'
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
  }

  type RawSettings = {
    mood_enabled: boolean
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
    }
  }

  function normalizeSettings(raw: RawSettings): ActionMemoSettings {
    return { moodEnabled: raw.mood_enabled }
  }

  function buildCreateBody(payload: CreateActionMemoPayload) {
    return {
      content: payload.content,
      memo_date: payload.memoDate ?? undefined,
      mood: payload.mood ?? undefined,
      related_todo_id: payload.relatedTodoId ?? undefined,
      tag_ids: payload.tagIds ?? undefined,
    }
  }

  function buildUpdateBody(payload: UpdateActionMemoPayload) {
    const body: Record<string, unknown> = {}
    if (payload.content !== undefined) body.content = payload.content
    if (payload.memoDate !== undefined) body.memo_date = payload.memoDate
    if (payload.mood !== undefined) body.mood = payload.mood
    if (payload.relatedTodoId !== undefined) body.related_todo_id = payload.relatedTodoId
    if (payload.tagIds !== undefined) body.tag_ids = payload.tagIds
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

  async function updateSettings(payload: { moodEnabled: boolean }): Promise<ActionMemoSettings> {
    try {
      const res = await api<{ data: RawSettings }>(SETTINGS_BASE, {
        method: 'PATCH',
        body: { mood_enabled: payload.moodEnabled },
      })
      return normalizeSettings(res.data)
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
  }
}
