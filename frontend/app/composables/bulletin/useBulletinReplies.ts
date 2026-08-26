import type { BulletinReplyResponse } from '~/types/bulletin'

/**
 * 掲示板返信関連 API（グローバル / スコープ別）。
 *
 * - グローバル: `/api/v1/bulletin/threads/{threadId}/replies` ほか
 * - スコープ別: `/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies`
 */
export function useBulletinReplies() {
  const api = useApi()

  // === Replies ===

  /**
   * スレッドの返信一覧を取得する（グローバル方式）。
   * BE: GET /api/v1/bulletin/threads/{threadId}/replies
   * レスポンス: { data: BulletinReplyResponse[], meta: { totalElements, page, size, totalPages } }
   */
  async function getReplies(
    threadId: number,
    params?: { page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    if (params?.page !== undefined) query.set('page', String(params.page))
    if (params?.size !== undefined) query.set('size', String(params.size))
    const qs = query.toString()
    return api<{
      data: BulletinReplyResponse[]
      meta: { totalElements: number; page: number; size: number; totalPages: number }
    }>(`/api/v1/bulletin/threads/${threadId}/replies${qs ? `?${qs}` : ''}`)
  }

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

  // === Scoped Replies ===
  async function getScopedReplies(
    scopeType: string,
    scopeId: string,
    threadId: number,
    params?: { page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    if (params?.page) query.set('page', String(params.page))
    if (params?.size) query.set('size', String(params.size))
    return api<{ data: BulletinReplyResponse[] }>(
      `/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/replies?${query}`,
    )
  }

  async function createScopedReply(
    scopeType: string,
    scopeId: string,
    threadId: number,
    body: string,
  ) {
    return api<{ data: BulletinReplyResponse }>(
      `/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/replies`,
      {
        method: 'POST',
        body: { body },
      },
    )
  }

  async function updateScopedReply(
    scopeType: string,
    scopeId: string,
    threadId: number,
    replyId: number,
    body: string,
  ) {
    return api<{ data: BulletinReplyResponse }>(
      `/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/replies/${replyId}`,
      {
        method: 'PUT',
        body: { body },
      },
    )
  }

  async function deleteScopedReply(
    scopeType: string,
    scopeId: string,
    threadId: number,
    replyId: number,
  ) {
    return api(`/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/replies/${replyId}`, {
      method: 'DELETE',
    })
  }

  return {
    getReplies,
    createReply,
    createNestedReply,
    updateReply,
    deleteReply,
    getScopedReplies,
    createScopedReply,
    updateScopedReply,
    deleteScopedReply,
  }
}
