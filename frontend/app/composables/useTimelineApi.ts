import type {
  TimelineFeedResponse,
  TimelinePostDetailResponse,
  TimelinePostResponse,
  TimelineEditHistory,
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
    return api<TimelineFeedResponse>(`/api/v1/timeline/feed?${qs}`)
  }

  async function getMyTimeline(cursor?: number, limit?: number) {
    const qs = buildQuery({ cursor, limit })
    return api<TimelineFeedResponse>(`/api/v1/timeline/my?${qs}`)
  }

  async function getUserPosts(userId: number, params?: Record<string, unknown>) {
    const qs = buildQuery(params || {})
    return api<TimelineFeedResponse>(`/api/v1/timeline/users/${userId}/posts?${qs}`)
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
    return api<TimelinePostDetailResponse>(`/api/v1/timeline/posts/${postId}`)
  }

  async function createPost(formData: FormData) {
    return api<{ data: TimelinePostResponse }>('/api/v1/timeline/posts', {
      method: 'POST',
      body: formData,
    })
  }

  async function updatePost(postId: number, body: Record<string, unknown>) {
    return api<{ data: TimelinePostResponse }>(`/api/v1/timeline/posts/${postId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deletePost(postId: number) {
    return api(`/api/v1/timeline/posts/${postId}`, { method: 'DELETE' })
  }

  // === Replies ===
  async function createReply(postId: number, content: string) {
    return api<{ data: TimelinePostResponse }>(`/api/v1/timeline/posts/${postId}/replies`, {
      method: 'POST',
      body: { content },
    })
  }

  async function getReplies(postId: number, cursor?: number, limit?: number) {
    const qs = buildQuery({ cursor, limit })
    return api<TimelineFeedResponse>(`/api/v1/timeline/posts/${postId}/replies?${qs}`)
  }

  // === Reactions (みたよ！) ===
  async function addReaction(postId: number) {
    return api<{ data: { timelinePostId: number; mitayo: boolean; mitayoCount: number } }>(
      `/api/v1/timeline/posts/${postId}/reactions`,
      { method: 'POST' },
    )
  }

  async function removeReaction(postId: number) {
    return api<{ data: { timelinePostId: number; mitayo: boolean; mitayoCount: number } }>(
      `/api/v1/timeline/posts/${postId}/reactions`,
      { method: 'DELETE' },
    )
  }

  // === Pin ===
  async function pinPost(postId: number) {
    return api(`/api/v1/timeline/posts/${postId}/pin`, { method: 'POST' })
  }

  async function getPinnedPosts() {
    return api<TimelineFeedResponse>('/api/v1/timeline/pinned')
  }

  // === Bookmark ===
  async function addBookmark(postId: number) {
    return api(`/api/v1/timeline/bookmarks/${postId}`, { method: 'POST' })
  }

  async function removeBookmark(postId: number) {
    return api(`/api/v1/timeline/bookmarks/${postId}`, { method: 'DELETE' })
  }

  async function getBookmarks(cursor?: number, limit?: number) {
    const qs = buildQuery({ cursor, limit })
    return api<TimelineFeedResponse>(`/api/v1/timeline/bookmarks?${qs}`)
  }

  // === Mutes ===
  async function getMutes() {
    return api('/api/v1/timeline/mutes')
  }

  async function addMute(body: Record<string, unknown>) {
    return api('/api/v1/timeline/mutes', { method: 'POST', body })
  }

  async function removeMute(body: Record<string, unknown>) {
    return api('/api/v1/timeline/mutes', { method: 'DELETE', body })
  }

  // === Poll ===
  async function getPoll(postId: number) {
    return api(`/api/v1/timeline/posts/${postId}/poll`)
  }

  async function votePoll(postId: number, optionId: number) {
    return api(`/api/v1/timeline/posts/${postId}/poll/vote`, {
      method: 'POST',
      body: { optionId },
    })
  }

  // === Repost ===
  async function repost(postId: number, content?: string) {
    return api<{ data: TimelinePostResponse }>(`/api/v1/timeline/posts/${postId}/repost`, {
      method: 'POST',
      body: content ? { content } : {},
    })
  }

  async function removeRepost(postId: number) {
    return api(`/api/v1/timeline/posts/${postId}/repost`, { method: 'DELETE' })
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
    return api<{ data: TimelineEditHistory[] }>(`/api/v1/timeline/posts/${postId}/edits`)
  }

  // === Read ===
  async function markAsRead(postId: number) {
    return api(`/api/v1/timeline/posts/${postId}/read`, { method: 'PUT' })
  }

  // === Timeline Digest ===
  async function getDigests(params?: Record<string, unknown>) {
    const qs = buildQuery(params || {})
    return api(`/api/v1/timeline-digest?${qs}`)
  }

  async function getDigest(id: number) {
    return api(`/api/v1/timeline-digest/${id}`)
  }

  async function updateDigest(id: number, body: Record<string, unknown>) {
    return api(`/api/v1/timeline-digest/${id}`, { method: 'PATCH', body })
  }

  async function deleteDigest(id: number) {
    return api(`/api/v1/timeline-digest/${id}`, { method: 'DELETE' })
  }

  async function generateDigest(body?: Record<string, unknown>) {
    return api('/api/v1/timeline-digest/generate', { method: 'POST', body })
  }

  async function publishDigest(id: number) {
    return api(`/api/v1/timeline-digest/${id}/publish`, { method: 'POST' })
  }

  async function regenerateDigest(id: number) {
    return api(`/api/v1/timeline-digest/${id}/regenerate`, { method: 'POST' })
  }

  async function getDigestConfig() {
    return api('/api/v1/timeline-digest/config')
  }

  async function updateDigestConfig(body: Record<string, unknown>) {
    return api('/api/v1/timeline-digest/config', { method: 'PUT', body })
  }

  async function deleteDigestConfig() {
    return api('/api/v1/timeline-digest/config', { method: 'DELETE' })
  }

  return {
    getFeed,
    getMyTimeline,
    getUserPosts,
    searchPosts,
    getPost,
    createPost,
    updatePost,
    deletePost,
    createReply,
    getReplies,
    addReaction,
    removeReaction,
    pinPost,
    getPinnedPosts,
    addBookmark,
    removeBookmark,
    getBookmarks,
    getMutes,
    addMute,
    removeMute,
    getPoll,
    votePoll,
    repost,
    removeRepost,
    getDrafts,
    getScheduledPosts,
    getEditHistory,
    markAsRead,
    getDigests,
    getDigest,
    updateDigest,
    deleteDigest,
    generateDigest,
    publishDigest,
    regenerateDigest,
    getDigestConfig,
    updateDigestConfig,
    deleteDigestConfig,
  }
}
