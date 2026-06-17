import type { TodoStatusLabelInfo } from '~/types/todoStatusLabel'
import type {
  HandoffApiResponse,
  HandoffHistoryResponse,
  HandoffRequest,
} from '~/types/todoHandoff'

interface TodoListParams {
  status?: string
  priority?: string
  assigneeId?: number
  projectId?: number
  page?: number
  size?: number
  sort?: string
}

/** Wave 1 DTO刷新: ネスト構造サブDTO */
interface TodoScopeDto {
  scopeType?: string
  scopeId?: number
  projectId?: number | null
  milestoneId?: number | null
}

interface TodoContentDto {
  title?: string
  description?: string | null
  startDate?: string | null
  progressRate?: number | null
  progressManual?: boolean
  sortOrder?: number
}

interface TodoScheduleDto {
  dueDate?: string | null
  dueTime?: string | null
  daysRemaining?: number | null
  linkedScheduleId?: number | null
}

interface TodoAuditDto {
  createdAt?: string
  updatedAt?: string
  createdBy?: { id: number; displayName: string }
  completedBy?: { id: number; displayName: string } | null
  completedAt?: string | null
}

interface TodoBase {
  id: number
  scope?: TodoScopeDto
  content?: TodoContentDto
  schedule?: TodoScheduleDto
  /** @deprecated 旧フラットフィールド互換 */
  status?: string
  /** F02.3.1 — カスタムステータスラベル情報（バックエンド側で SYSTEM 既定にフォールバック済み） — @deprecated 旧フラットフィールド互換 */
  statusLabel?: TodoStatusLabelInfo | null
  /** @deprecated 旧フラットフィールド互換 */
  priority?: string
  hierarchy?: {
    parentId?: number | null
    depth?: number
    children?: TodoBase[]
    childCount?: number
    descendantCompletedCount?: number
    descendantTotalCount?: number
  }
  audit?: TodoAuditDto
  assignees: Array<{ id: number; userId: number; displayName: string; avatarUrl: string | null }>
}

interface PagedTodos {
  data: TodoBase[]
  meta: { page: number; size: number; totalElements: number; totalPages: number }
}

interface TodoDetail {
  data: TodoBase
}

interface CommentList {
  data: Array<{
    id: number
    todoId: number
    /** BE の CommentResponse は user フィールドにネストして返す（ProjectResponse.UserInfo 準拠） */
    user: {
      id: number
      displayName: string
      avatarUrl?: string | null
    }
    body: string
    createdAt: string
    updatedAt: string
  }>
  meta: { page: number; size: number; totalElements: number; totalPages: number }
}

export function useTodoApi() {
  const api = useApi()

  // === Personal TODO ===
  async function getMyTodos() {
    return api<{ data: PagedTodos['data'] }>('/api/v1/todos/my')
  }

  /**
   * 個人 TODO 詳細取得（F02.3.1 Phase 1b で追加）
   */
  async function getPersonalTodo(todoId: number) {
    return api<TodoDetail>(`/api/v1/todos/${todoId}`)
  }

  async function createPersonalTodo(body: Record<string, unknown>) {
    return api<TodoDetail>('/api/v1/todos', {
      method: 'POST',
      body: { ...body, scopeType: 'PERSONAL' },
    })
  }

  async function updatePersonalTodo(todoId: number, body: Record<string, unknown>) {
    return api<TodoDetail>(`/api/v1/todos/${todoId}`, { method: 'PUT', body })
  }

  async function deletePersonalTodo(todoId: number) {
    return api(`/api/v1/todos/${todoId}`, { method: 'DELETE' })
  }

  /**
   * スコープを問わず使える汎用ステータス変更
   *
   * F02.3.1 でリクエストボディが拡張され、`status` / `statusLabelId` のいずれか（または両方）を指定可能。
   * 後方互換のため、status のみの呼び出しも引き続きサポート。
   */
  async function changeTodoStatusById(
    scopeType: string,
    scopeId: string | null,
    todoId: number,
    payload: { status?: string; statusLabelId?: number } | string,
  ) {
    const body =
      typeof payload === 'string' ? { status: payload } : payload
    if (scopeType === 'PERSONAL' || !scopeId) {
      return api(`/api/v1/todos/${todoId}/status`, { method: 'PATCH', body })
    }
    const type = scopeType === 'TEAM' ? ('team' as const) : ('organization' as const)
    return api(`${buildBase(type, scopeId)}/todos/${todoId}/status`, {
      method: 'PATCH',
      body,
    })
  }

  // === Team TODO CRUD ===
  function buildBase(scopeType: 'team' | 'organization', scopeId: string) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  async function listTodos(
    scopeType: 'team' | 'organization',
    scopeId: string,
    params?: TodoListParams,
  ) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    if (params?.priority) query.set('priority', params.priority)
    if (params?.assigneeId) query.set('assigneeId', String(params.assigneeId))
    if (params?.projectId) query.set('projectId', String(params.projectId))
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    if (params?.sort) query.set('sort', params.sort)
    return api<PagedTodos>(`${buildBase(scopeType, scopeId)}/todos?${query}`)
  }

  async function getTodo(scopeType: 'team' | 'organization', scopeId: string, todoId: number) {
    return api<TodoDetail>(`${buildBase(scopeType, scopeId)}/todos/${todoId}`)
  }

  async function createTodo(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<TodoDetail>(`${buildBase(scopeType, scopeId)}/todos`, { method: 'POST', body })
  }

  async function updateTodo(
    scopeType: 'team' | 'organization',
    scopeId: string,
    todoId: number,
    body: Record<string, unknown>,
  ) {
    return api<TodoDetail>(`${buildBase(scopeType, scopeId)}/todos/${todoId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteTodo(scopeType: 'team' | 'organization', scopeId: string, todoId: number) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}`, { method: 'DELETE' })
  }

  // === Status ===
  /**
   * チーム / 組織 TODO のステータス変更
   *
   * F02.3.1 でリクエストボディが `{ status?, statusLabelId? }` に拡張。
   * 後方互換のため、status のみの呼び出しも従来通り受け付ける。
   */
  async function changeTodoStatus(
    scopeType: 'team' | 'organization',
    scopeId: string,
    todoId: number,
    payload: { status?: string; statusLabelId?: number } | string,
  ) {
    const body =
      typeof payload === 'string' ? { status: payload } : payload
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/status`, {
      method: 'PATCH',
      body,
    })
  }

  async function bulkChangeTodoStatus(
    scopeType: 'team' | 'organization',
    scopeId: string,
    todoIds: number[],
    status: string,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/bulk-status`, {
      method: 'PATCH',
      body: { todoIds, status },
    })
  }

  // === Assignees ===
  async function addAssignee(
    scopeType: 'team' | 'organization',
    scopeId: string,
    todoId: number,
    userId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/assignees`, {
      method: 'POST',
      body: { userId },
    })
  }

  async function removeAssignee(
    scopeType: 'team' | 'organization',
    scopeId: string,
    todoId: number,
    userId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/assignees/${userId}`, {
      method: 'DELETE',
    })
  }

  // === Comments ===
  async function getComments(
    scopeType: 'team' | 'organization',
    scopeId: string,
    todoId: number,
    page: number = 0,
  ) {
    return api<CommentList>(
      `${buildBase(scopeType, scopeId)}/todos/${todoId}/comments?page=${page}&size=20`,
    )
  }

  async function addComment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    todoId: number,
    body: string,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/comments`, {
      method: 'POST',
      body: { body },
    })
  }

  async function updateComment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    todoId: number,
    commentId: number,
    body: string,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/comments/${commentId}`, {
      method: 'PUT',
      body: { body },
    })
  }

  async function deleteComment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    todoId: number,
    commentId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/comments/${commentId}`, {
      method: 'DELETE',
    })
  }

  // === Handoff (キャッチボール) — F02.3.1 Phase 2 ===
  /**
   * チーム/組織 TODO を別メンバーへ渡す。
   * 個人 TODO では呼び出してはいけない（バックエンドが 400 を返す）。
   */
  async function handoff(
    scopeType: 'team' | 'organization',
    scopeId: string,
    todoId: number,
    body: HandoffRequest,
  ) {
    return api<HandoffApiResponse>(
      `${buildBase(scopeType, scopeId)}/todos/${todoId}/handoff`,
      { method: 'POST', body },
    )
  }

  /**
   * チーム/組織 TODO のキャッチボール履歴を新しい順で取得する。
   */
  async function getHandoffHistory(
    scopeType: 'team' | 'organization',
    scopeId: string,
    todoId: number,
  ) {
    return api<HandoffHistoryResponse>(
      `${buildBase(scopeType, scopeId)}/todos/${todoId}/handoffs`,
    )
  }

  return {
    getMyTodos,
    getPersonalTodo,
    createPersonalTodo,
    updatePersonalTodo,
    deletePersonalTodo,
    changeTodoStatusById,
    listTodos,
    getTodo,
    createTodo,
    updateTodo,
    deleteTodo,
    changeTodoStatus,
    bulkChangeTodoStatus,
    addAssignee,
    removeAssignee,
    getComments,
    addComment,
    updateComment,
    deleteComment,
    handoff,
    getHandoffHistory,
  }
}
