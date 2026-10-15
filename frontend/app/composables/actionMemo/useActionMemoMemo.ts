/**
 * F02.5 行動メモ — Memo CRUD ドメイン。
 *
 * <p>Phase 3 リファクタにて {@code useActionMemoApi.ts} から分離。
 * createMemo / fetchMemos / getMemo / updateMemo / deleteMemo / linkTodo /
 * revertTodoCompletion / getMemoAuditLogs の 8 関数を提供する。</p>
 */
import type {
  ActionMemo,
  ActionMemoAuditLog,
  ActionMemoListResponse,
  CreateActionMemoPayload,
  ListActionMemoParams,
  UpdateActionMemoPayload,
} from '~/types/actionMemo'
import {
  ACTION_MEMO_BASE,
  normalizeMemo,
  rethrow,
  type RawListResponse,
  type RawMemo,
} from './shared/normalize'

export function useActionMemoMemo() {
  const api = useApi()
  const BASE = ACTION_MEMO_BASE

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

  // === Phase 4-β: TODO 差し戻し ===

  async function revertTodoCompletion(memoId: number): Promise<void> {
    try {
      await api(`${BASE}/${memoId}/complete-todo`, { method: 'DELETE' })
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

  return {
    createMemo,
    fetchMemos,
    getMemo,
    updateMemo,
    deleteMemo,
    linkTodo,
    revertTodoCompletion,
    getMemoAuditLogs,
  }
}
