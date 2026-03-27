import type {
  TimelineFeedResponse,
  TimelinePostDetailResponse,
  TimelinePostResponse,
  TimelineEditHistory,
  ReactionDetail,
  TimelineScopeType,
} from '~/types/timeline'

interface FeedParams {
  scopeType: TimelineScopeType
  scopeId?: number
  cursor?: number
  limit?: number
  feed?: 'all' | 'following'
}

interface SearchParams {
  scopeType: TimelineScopeType
  scopeId?: number
  q: string
  cursor?: number
  limit?: number
}

export function useTimelineApi() {
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

  // === Feed ===
  async function getFeed(params: FeedParams) {
    const qs = buildQuery({
      scope_type: params.scopeType,
      scope_id: params.scopeId,
      cursor: params.cursor,
      limit: params.limit,
      feed: params.feed,
    })
    return api<TimelineFeedResponse>(`/api/v1/timeline?${qs}`)
  }

  async function getMyTimeline(cursor?: number, limit?: number) {
    const qs = buildQuery({ cursor, limit })
    return api<TimelineFeedResponse>(`/api/v1/timeline/my?${qs}`)
  }

  async function searchPosts(params: SearchParams) {
    const qs = buildQuery({
      scope_type: params.scopeType,
      scope_id: params.scopeId,
      q: params.q,
      cursor: params.cursor,
      limit: params.limit,
    })
    return api<TimelineFeedResponse>(`/api/v1/timeline/search?${qs}`)
  }

  // === CRUD ===
  async function getPost(postId: number) {
    return api<TimelinePostDetailResponse>(`/api/v1/timeline/${postId}`)
  }

  async function createPost(formData: FormData) {
    return api<{ data: TimelinePostResponse }>('/api/v1/timeline', {
      method: 'POST',
      body: formData,
    })
  }

  async function updatePost(postId: number, body: Record<string, unknown>) {
    return api<{ data: TimelinePostResponse }>(`/api/v1/timeline/${postId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deletePost(postId: number) {
    return api(`/api/v1/timeline/${postId}`, { method: 'DELETE' })
  }

  // === Replies ===
  async function createReply(postId: number, content: string) {
    return api<{ data: TimelinePostResponse }>(`/api/v1/timeline/${postId}/replies`, {
      method: 'POST',
      body: { content },
    })
  }

  async function getReplies(postId: number, cursor?: number, limit?: number) {
    const qs = buildQuery({ cursor, limit })
    return api<TimelineFeedResponse>(`/api/v1/timeline/${postId}/replies?${qs}`)
  }

  // === Reactions ===
  async function addReaction(postId: number, emoji: string) {
    return api(`/api/v1/timeline/${postId}/reactions`, {
      method: 'POST',
      body: { emoji },
    })
  }

  async function removeReaction(postId: number, emoji: string) {
    return api(`/api/v1/timeline/${postId}/reactions/${encodeURIComponent(emoji)}`, {
      method: 'DELETE',
    })
  }

  async function getReactions(postId: number) {
    return api<{ data: ReactionDetail[] }>(`/api/v1/timeline/${postId}/reactions`)
  }

  // === Pin ===
  async function togglePin(postId: number, pinned: boolean) {
    return api(`/api/v1/timeline/${postId}/pin`, {
      method: 'PATCH',
      body: { pinned },
    })
  }

  // === Bookmark ===
  async function addBookmark(postId: number) {
    return api(`/api/v1/timeline/${postId}/bookmark`, { method: 'POST' })
  }

  async function removeBookmark(postId: number) {
    return api(`/api/v1/timeline/${postId}/bookmark`, { method: 'DELETE' })
  }

  async function getBookmarks(cursor?: number, limit?: number) {
    const qs = buildQuery({ cursor, limit })
    return api<TimelineFeedResponse>(`/api/v1/timeline/bookmarks?${qs}`)
  }

  // === Repost ===
  async function repost(postId: number, content?: string) {
    return api<{ data: TimelinePostResponse }>(`/api/v1/timeline/${postId}/repost`, {
      method: 'POST',
      body: content ? { content } : {},
    })
  }

  async function removeRepost(postId: number) {
    return api(`/api/v1/timeline/${postId}/repost`, { method: 'DELETE' })
  }

  // === Poll ===
  async function votePoll(postId: number, optionId: number) {
    return api(`/api/v1/timeline/${postId}/poll/vote`, {
      method: 'POST',
      body: { optionId },
    })
  }

  async function removeVote(postId: number) {
    return api(`/api/v1/timeline/${postId}/poll/vote`, { method: 'DELETE' })
  }

  // === Drafts / Scheduled ===
  async function getDrafts(cursor?: number) {
    const qs = buildQuery({ cursor })
    return api<TimelineFeedResponse>(`/api/v1/timeline/drafts?${qs}`)
  }

  async function getScheduledPosts(cursor?: number) {
    const qs = buildQuery({ cursor })
    return api<TimelineFeedResponse>(`/api/v1/timeline/scheduled?${qs}`)
  }

  // === Edit history ===
  async function getEditHistory(postId: number) {
    return api<{ data: TimelineEditHistory[] }>(`/api/v1/timeline/${postId}/edits`)
  }

  // === Read ===
  async function markAsRead(postId: number) {
    return api(`/api/v1/timeline/${postId}/read`, { method: 'PUT' })
  }

  return {
    getFeed,
    getMyTimeline,
    searchPosts,
    getPost,
    createPost,
    updatePost,
    deletePost,
    createReply,
    getReplies,
    addReaction,
    removeReaction,
    getReactions,
    togglePin,
    addBookmark,
    removeBookmark,
    getBookmarks,
    repost,
    removeRepost,
    votePoll,
    removeVote,
    getDrafts,
    getScheduledPosts,
    getEditHistory,
    markAsRead,
  }
}
