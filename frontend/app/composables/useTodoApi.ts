interface TodoListParams {
  status?: string
  priority?: string
  assigneeId?: number
  projectId?: number
  page?: number
  size?: number
  sort?: string
}

interface PagedTodos {
  data: Array<{
    id: number
    scopeType: string
    scopeId: number
    title: string
    description: string | null
    status: string
    priority: string
    dueDate: string | null
    dueTime: string | null
    daysRemaining: number | null
    completedAt: string | null
    completedBy: { id: number; displayName: string } | null
    createdBy: { id: number; displayName: string }
    sortOrder: number
    assignees: Array<{ id: number; userId: number; displayName: string; avatarUrl: string | null }>
    createdAt: string
    updatedAt: string
  }>
  meta: { page: number; size: number; totalElements: number; totalPages: number }
}

interface TodoDetail {
  data: {
    id: number
    scopeType: string
    scopeId: number
    title: string
    description: string | null
    status: string
    priority: string
    dueDate: string | null
    dueTime: string | null
    daysRemaining: number | null
    completedAt: string | null
    completedBy: { id: number; displayName: string } | null
    createdBy: { id: number; displayName: string }
    sortOrder: number
    assignees: Array<{ id: number; userId: number; displayName: string; avatarUrl: string | null }>
    createdAt: string
    updatedAt: string
  }
}

interface CommentList {
  data: Array<{
    id: number
    todoId: number
    userId: number
    displayName: string
    avatarUrl: string | null
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

  async function createPersonalTodo(body: Record<string, unknown>) {
    return api<TodoDetail>('/api/v1/todos', {
      method: 'POST',
      body: { ...body, scopeType: 'PERSONAL' },
    })
  }

  // スコープを問わず使える汎用ステータス変更
  async function changeTodoStatusById(
    scopeType: string,
    scopeId: number | null,
    todoId: number,
    status: string,
  ) {
    if (scopeType === 'PERSONAL' || !scopeId) {
      return api(`/api/v1/todos/${todoId}/status`, { method: 'PATCH', body: { status } })
    }
    const type = scopeType === 'TEAM' ? ('team' as const) : ('organization' as const)
    return api(`${buildBase(type, scopeId)}/todos/${todoId}/status`, {
      method: 'PATCH',
      body: { status },
    })
  }

  // === Team TODO CRUD ===
  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  async function listTodos(
    scopeType: 'team' | 'organization',
    scopeId: number,
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

  async function getTodo(scopeType: 'team' | 'organization', scopeId: number, todoId: number) {
    return api<TodoDetail>(`${buildBase(scopeType, scopeId)}/todos/${todoId}`)
  }

  async function createTodo(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<TodoDetail>(`${buildBase(scopeType, scopeId)}/todos`, { method: 'POST', body })
  }

  async function updateTodo(
    scopeType: 'team' | 'organization',
    scopeId: number,
    todoId: number,
    body: Record<string, unknown>,
  ) {
    return api<TodoDetail>(`${buildBase(scopeType, scopeId)}/todos/${todoId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteTodo(scopeType: 'team' | 'organization', scopeId: number, todoId: number) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}`, { method: 'DELETE' })
  }

  // === Status ===
  async function changeTodoStatus(
    scopeType: 'team' | 'organization',
    scopeId: number,
    todoId: number,
    status: string,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/status`, {
      method: 'PATCH',
      body: { status },
    })
  }

  async function bulkChangeTodoStatus(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
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
    scopeId: number,
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
    scopeId: number,
    todoId: number,
    page: number = 0,
  ) {
    return api<CommentList>(
      `${buildBase(scopeType, scopeId)}/todos/${todoId}/comments?page=${page}&size=20`,
    )
  }

  async function addComment(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
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
    scopeId: number,
    todoId: number,
    commentId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/comments/${commentId}`, {
      method: 'DELETE',
    })
  }

  return {
    getMyTodos,
    createPersonalTodo,
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
  }
}
