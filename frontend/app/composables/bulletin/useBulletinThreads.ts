import type {
  BulletinThreadResponse,
  BulletinReplyResponse,
  BulletinReader,
  BulletinReadStatus,
  BulletinThreadSearchParams,
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

/**
 * 掲示板スレッド関連 API（グローバル / スコープ別 / 既読状態）。
 *
 * - グローバル: `/api/v1/bulletin/threads`
 * - スコープ別: `/api/v1/{scopeType}/{scopeId}/bulletin/threads`
 * - 既読状態: `/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/read-status`
 */
export function useBulletinThreads() {
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
    return api<{
      data: BulletinThreadResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/bulletin/threads?${qs}`)
  }

  async function getThread(threadId: number) {
    return api<{ data: BulletinThreadResponse & { replies: BulletinReplyResponse[] } }>(
      `/api/v1/bulletin/threads/${threadId}`,
    )
  }

  async function createThread(
    scopeType: string,
    scopeId: number,
    body: Record<string, unknown>,
    files?: File[],
  ) {
    if (files && files.length > 0) {
      const formData = new FormData()
      formData.append('data', JSON.stringify({ ...body, scopeType, scopeId }))
      files.forEach((file) => formData.append('files[]', file))
      return api<{ data: BulletinThreadResponse }>('/api/v1/bulletin/threads', {
        method: 'POST',
        body: formData,
      })
    }
    return api<{ data: BulletinThreadResponse }>('/api/v1/bulletin/threads', {
      method: 'POST',
      body: { ...body, scopeType, scopeId },
    })
  }

  async function updateThread(threadId: number, body: Record<string, unknown>) {
    return api<{ data: BulletinThreadResponse }>(`/api/v1/bulletin/threads/${threadId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteThread(threadId: number) {
    return api(`/api/v1/bulletin/threads/${threadId}`, { method: 'DELETE' })
  }

  async function changePriority(threadId: number, priority: string) {
    return api(`/api/v1/bulletin/threads/${threadId}/priority`, {
      method: 'PATCH',
      body: { priority },
    })
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
    return api(`/api/v1/bulletin/threads/${threadId}/archive`, {
      method: 'PATCH',
      body: { archived },
    })
  }

  async function readAll(scopeType: string, scopeId: number) {
    return api('/api/v1/bulletin/threads/read-all', {
      method: 'POST',
      body: { scopeType, scopeId },
    })
  }

  // === Scoped Threads ===
  async function getScopedThreads(
    scopeType: string,
    scopeId: number,
    params?: { categoryId?: number; page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    if (params?.categoryId) query.set('categoryId', String(params.categoryId))
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{
      data: BulletinThreadResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/${scopeType}/${scopeId}/bulletin/threads?${query}`)
  }

  async function searchScopedThreads(
    scopeType: string,
    scopeId: number,
    params: BulletinThreadSearchParams,
  ) {
    const query = new URLSearchParams()
    query.set('keyword', params.keyword)
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 20))
    return api<{
      data: BulletinThreadResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/${scopeType}/${scopeId}/bulletin/threads/search?${query}`)
  }

  async function createScopedThread(
    scopeType: string,
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: BulletinThreadResponse }>(
      `/api/v1/${scopeType}/${scopeId}/bulletin/threads`,
      { method: 'POST', body },
    )
  }

  async function getScopedThread(scopeType: string, scopeId: number, threadId: number) {
    return api<{ data: BulletinThreadResponse & { replies: BulletinReplyResponse[] } }>(
      `/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}`,
    )
  }

  async function updateScopedThread(
    scopeType: string,
    scopeId: number,
    threadId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: BulletinThreadResponse }>(
      `/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteScopedThread(scopeType: string, scopeId: number, threadId: number) {
    return api(`/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}`, { method: 'DELETE' })
  }

  async function archiveScopedThread(scopeType: string, scopeId: number, threadId: number) {
    return api(`/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/archive`, {
      method: 'POST',
    })
  }

  async function lockScopedThread(scopeType: string, scopeId: number, threadId: number) {
    return api(`/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/lock`, {
      method: 'POST',
    })
  }

  async function pinScopedThread(scopeType: string, scopeId: number, threadId: number) {
    return api(`/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/pin`, {
      method: 'POST',
    })
  }

  // === Scoped Read Status ===
  async function getReadStatus(scopeType: string, scopeId: number, threadId: number) {
    return api<{ data: BulletinReadStatus }>(
      `/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/read-status`,
    )
  }

  async function markReadStatus(scopeType: string, scopeId: number, threadId: number) {
    return api(`/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/read-status`, {
      method: 'POST',
    })
  }

  return {
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
    getScopedThreads,
    searchScopedThreads,
    createScopedThread,
    getScopedThread,
    updateScopedThread,
    deleteScopedThread,
    archiveScopedThread,
    lockScopedThread,
    pinScopedThread,
    getReadStatus,
    markReadStatus,
  }
}
