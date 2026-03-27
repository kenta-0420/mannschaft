import type {
  BulletinCategory,
  BulletinThreadResponse,
  BulletinReplyResponse,
  BulletinReader,
} from '~/types/bulletin'

interface ThreadListParams {
  scopeType: string
  scopeId: number
  categoryId?: number
  priority?: string
  isArchived?: boolean
  search?: string
  page?: number
  size?: number
}

export function useBulletinApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) {
        query.set(key, String(value))
      }
    }
    return query.toString()
  }

  // === Categories ===
  async function getCategories(scopeType: string, scopeId: number) {
    const qs = buildQuery({ scope_type: scopeType, scope_id: scopeId })
    return api<{ data: BulletinCategory[] }>(`/api/v1/bulletin/categories?${qs}`)
  }

  async function createCategory(body: Record<string, unknown>) {
    return api<{ data: BulletinCategory }>('/api/v1/bulletin/categories', { method: 'POST', body })
  }

  async function updateCategory(categoryId: number, body: Record<string, unknown>) {
    return api<{ data: BulletinCategory }>(`/api/v1/bulletin/categories/${categoryId}`, { method: 'PUT', body })
  }

  async function deleteCategory(categoryId: number) {
    return api(`/api/v1/bulletin/categories/${categoryId}`, { method: 'DELETE' })
  }

  // === Threads ===
  async function getThreads(params: ThreadListParams) {
    const qs = buildQuery({
      scope_type: params.scopeType,
      scope_id: params.scopeId,
      category_id: params.categoryId,
      priority: params.priority,
      is_archived: params.isArchived,
      search: params.search,
      page: params.page ?? 0,
      size: params.size ?? 20,
    })
    return api<{ data: BulletinThreadResponse[]; meta: { page: number; size: number; totalElements: number; totalPages: number } }>(`/api/v1/bulletin/threads?${qs}`)
  }

  async function getThread(threadId: number) {
    return api<{ data: BulletinThreadResponse & { replies: BulletinReplyResponse[] } }>(`/api/v1/bulletin/threads/${threadId}`)
  }

  async function createThread(scopeType: string, scopeId: number, body: Record<string, unknown>) {
    return api<{ data: BulletinThreadResponse }>('/api/v1/bulletin/threads', {
      method: 'POST',
      body: { ...body, scopeType, scopeId },
    })
  }

  async function updateThread(threadId: number, body: Record<string, unknown>) {
    return api<{ data: BulletinThreadResponse }>(`/api/v1/bulletin/threads/${threadId}`, { method: 'PUT', body })
  }

  async function deleteThread(threadId: number) {
    return api(`/api/v1/bulletin/threads/${threadId}`, { method: 'DELETE' })
  }

  async function changePriority(threadId: number, priority: string) {
    return api(`/api/v1/bulletin/threads/${threadId}/priority`, { method: 'PATCH', body: { priority } })
  }

  async function markRead(threadId: number) {
    return api(`/api/v1/bulletin/threads/${threadId}/read`, { method: 'POST' })
  }

  async function getReaders(threadId: number, filter?: 'unread') {
    const qs = filter ? `?filter=${filter}` : ''
    return api<{ data: BulletinReader[] }>(`/api/v1/bulletin/threads/${threadId}/readers${qs}`)
  }

  async function togglePin(threadId: number, pinned: boolean) {
    return api(`/api/v1/bulletin/threads/${threadId}/pin`, { method: 'PATCH', body: { pinned } })
  }

  async function toggleLock(threadId: number, locked: boolean) {
    return api(`/api/v1/bulletin/threads/${threadId}/lock`, { method: 'PATCH', body: { locked } })
  }

  async function toggleArchive(threadId: number, archived: boolean) {
    return api(`/api/v1/bulletin/threads/${threadId}/archive`, { method: 'PATCH', body: { archived } })
  }

  async function readAll(scopeType: string, scopeId: number) {
    return api('/api/v1/bulletin/threads/read-all', { method: 'POST', body: { scopeType, scopeId } })
  }

  // === Replies ===
  async function createReply(threadId: number, body: string) {
    return api<{ data: BulletinReplyResponse }>(`/api/v1/bulletin/threads/${threadId}/replies`, {
      method: 'POST',
      body: { body },
    })
  }

  async function createNestedReply(replyId: number, body: string) {
    return api<{ data: BulletinReplyResponse }>(`/api/v1/bulletin/replies/${replyId}/replies`, {
      method: 'POST',
      body: { body },
    })
  }

  async function updateReply(replyId: number, body: string) {
    return api<{ data: BulletinReplyResponse }>(`/api/v1/bulletin/replies/${replyId}`, {
      method: 'PUT',
      body: { body },
    })
  }

  async function deleteReply(replyId: number) {
    return api(`/api/v1/bulletin/replies/${replyId}`, { method: 'DELETE' })
  }

  // === Reactions ===
  async function addReaction(targetType: 'thread' | 'reply', targetId: number, emoji: string) {
    return api(`/api/v1/bulletin/${targetType}/${targetId}/reactions`, {
      method: 'POST',
      body: { emoji },
    })
  }

  async function removeReaction(targetType: 'thread' | 'reply', targetId: number, emoji: string) {
    return api(`/api/v1/bulletin/${targetType}/${targetId}/reactions/${encodeURIComponent(emoji)}`, {
      method: 'DELETE',
    })
  }

  return {
    getCategories,
    createCategory,
    updateCategory,
    deleteCategory,
    getThreads,
    getThread,
    createThread,
    updateThread,
    deleteThread,
    changePriority,
    markRead,
    getReaders,
    togglePin,
    toggleLock,
    toggleArchive,
    readAll,
    createReply,
    createNestedReply,
    updateReply,
    deleteReply,
    addReaction,
    removeReaction,
  }
}
