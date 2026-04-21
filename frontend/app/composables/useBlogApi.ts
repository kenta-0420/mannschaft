import type { BlogPostResponse, BlogReactionResponse, BlogTag, BlogSeries, BlogRevision } from '~/types/cms'

interface BlogSettings {
  displayName: string | null
  bio: string | null
  avatarUrl: string | null
  theme: string | null
}

interface BlogShareResponse {
  id: number
  platform: string
  sharedAt: string
}

export function useBlogApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) query.set(key, String(value))
    }
    return query.toString()
  }

  // === Public / Admin Blog Posts ===
  async function getPosts(params: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api<{
      data: BlogPostResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/blog/posts?${qs}`)
  }

  async function getPost(slug: string) {
    return api<{ data: BlogPostResponse }>(`/api/v1/blog/posts/${slug}`)
  }

  async function getFeed(params?: Record<string, unknown>) {
    const qs = buildQuery(params || {})
    return api<{ data: BlogPostResponse[]; meta: Record<string, unknown> }>(
      `/api/v1/blog/feed?${qs}`,
    )
  }

  async function createPost(body: Record<string, unknown>) {
    return api<{ data: BlogPostResponse }>('/api/v1/blog/posts', { method: 'POST', body })
  }

  async function updatePost(postId: number, body: Record<string, unknown>) {
    return api<{ data: BlogPostResponse }>(`/api/v1/blog/posts/${postId}`, { method: 'PUT', body })
  }

  async function deletePost(postId: number) {
    return api(`/api/v1/blog/posts/${postId}`, { method: 'DELETE' })
  }

  async function changePublishStatus(postId: number, status: string) {
    return api(`/api/v1/blog/posts/${postId}/publish`, { method: 'PATCH', body: { status } })
  }

  async function bulkUpdatePosts(body: Record<string, unknown>) {
    return api('/api/v1/blog/posts/bulk', { method: 'PATCH', body })
  }

  async function duplicatePost(postId: number) {
    return api<{ data: BlogPostResponse }>(`/api/v1/blog/posts/${postId}/duplicate`, {
      method: 'POST',
    })
  }

  async function createPreviewToken(postId: number) {
    return api<{ data: { token: string } }>(`/api/v1/blog/posts/${postId}/preview-token`, {
      method: 'POST',
    })
  }

  async function deletePreviewToken(postId: number) {
    return api(`/api/v1/blog/posts/${postId}/preview-token`, { method: 'DELETE' })
  }

  // === Revisions ===
  async function getRevisions(postId: number) {
    return api<{ data: BlogRevision[] }>(`/api/v1/blog/posts/${postId}/revisions`)
  }

  async function restoreRevision(postId: number, revisionId: number) {
    return api<{ data: BlogPostResponse }>(
      `/api/v1/blog/posts/${postId}/revisions/${revisionId}/restore`,
      { method: 'POST' },
    )
  }

  async function autoSave(postId: number, body: Record<string, unknown>) {
    return api(`/api/v1/blog/posts/${postId}/auto-save`, { method: 'PATCH', body })
  }

  // === Tags ===
  async function getTags(params?: Record<string, unknown>) {
    const qs = buildQuery(params || {})
    return api<{ data: BlogTag[] }>(`/api/v1/blog/tags?${qs}`)
  }

  async function createTag(body: { name: string }) {
    return api<{ data: BlogTag }>('/api/v1/blog/tags', { method: 'POST', body })
  }

  async function updateTag(tagId: number, body: { name: string }) {
    return api<{ data: BlogTag }>(`/api/v1/blog/tags/${tagId}`, { method: 'PUT', body })
  }

  async function deleteTag(tagId: number) {
    return api(`/api/v1/blog/tags/${tagId}`, { method: 'DELETE' })
  }

  // === Series ===
  async function getSeries(params?: Record<string, unknown>) {
    const qs = buildQuery(params || {})
    return api<{ data: BlogSeries[] }>(`/api/v1/blog/series?${qs}`)
  }

  async function createSeries(body: Record<string, unknown>) {
    return api<{ data: BlogSeries }>('/api/v1/blog/series', { method: 'POST', body })
  }

  async function updateSeries(seriesId: number, body: Record<string, unknown>) {
    return api<{ data: BlogSeries }>(`/api/v1/blog/series/${seriesId}`, { method: 'PUT', body })
  }

  async function deleteSeries(seriesId: number) {
    return api(`/api/v1/blog/series/${seriesId}`, { method: 'DELETE' })
  }

  // === User Blog (me) ===
  async function getMyPosts(params?: Record<string, unknown>) {
    const qs = buildQuery(params || {})
    return api<{ data: BlogPostResponse[]; meta: Record<string, unknown> }>(
      `/api/v1/users/me/blog/posts?${qs}`,
    )
  }

  async function getMyPost(postId: number) {
    return api<{ data: BlogPostResponse }>(`/api/v1/users/me/blog/posts/${postId}`)
  }

  async function createMyPost(body: Record<string, unknown>) {
    return api<{ data: BlogPostResponse }>('/api/v1/users/me/blog/posts', { method: 'POST', body })
  }

  async function updateMyPost(postId: number, body: Record<string, unknown>) {
    return api<{ data: BlogPostResponse }>(`/api/v1/users/me/blog/posts/${postId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteMyPost(postId: number) {
    return api(`/api/v1/users/me/blog/posts/${postId}`, { method: 'DELETE' })
  }

  async function publishMyPost(postId: number, body?: Record<string, unknown>) {
    return api(`/api/v1/users/me/blog/posts/${postId}/publish`, { method: 'PATCH', body })
  }

  async function selfReviewPost(postId: number, body: Record<string, unknown>) {
    return api(`/api/v1/users/me/blog/posts/${postId}/self-review`, { method: 'PATCH', body })
  }

  async function sharePost(postId: number, body: Record<string, unknown>) {
    return api<{ data: BlogShareResponse }>(`/api/v1/users/me/blog/posts/${postId}/share`, {
      method: 'POST',
      body,
    })
  }

  async function deleteShare(postId: number, shareId: number) {
    return api(`/api/v1/users/me/blog/posts/${postId}/shares/${shareId}`, { method: 'DELETE' })
  }

  // === User Blog Settings ===
  async function getMyBlogSettings() {
    return api<{ data: BlogSettings }>('/api/v1/users/me/blog/settings')
  }

  async function updateMyBlogSettings(body: Partial<BlogSettings>) {
    return api<{ data: BlogSettings }>('/api/v1/users/me/blog/settings', { method: 'PUT', body })
  }

  // === User Blog Posts (public) ===
  async function getUserPosts(userId: number, params?: Record<string, unknown>) {
    const qs = buildQuery(params || {})
    return api<{ data: BlogPostResponse[]; meta: Record<string, unknown> }>(
      `/api/v1/users/${userId}/blog/posts?${qs}`,
    )
  }

  async function getUserPost(userId: number, slug: string) {
    return api<{ data: BlogPostResponse }>(`/api/v1/users/${userId}/blog/posts/${slug}`)
  }

  // === Blog Reactions (みたよ！) ===
  async function addMitayo(postId: number) {
    return api<{ data: BlogReactionResponse }>(`/api/v1/blog/posts/${postId}/reactions`, {
      method: 'POST',
    })
  }

  async function removeMitayo(postId: number) {
    return api<{ data: BlogReactionResponse }>(`/api/v1/blog/posts/${postId}/reactions`, {
      method: 'DELETE',
    })
  }

  return {
    getPosts,
    getPost,
    getFeed,
    createPost,
    updatePost,
    deletePost,
    changePublishStatus,
    bulkUpdatePosts,
    duplicatePost,
    createPreviewToken,
    deletePreviewToken,
    getRevisions,
    restoreRevision,
    autoSave,
    getTags,
    createTag,
    updateTag,
    deleteTag,
    getSeries,
    createSeries,
    updateSeries,
    deleteSeries,
    getMyPosts,
    getMyPost,
    createMyPost,
    updateMyPost,
    deleteMyPost,
    publishMyPost,
    selfReviewPost,
    sharePost,
    deleteShare,
    getMyBlogSettings,
    updateMyBlogSettings,
    getUserPosts,
    getUserPost,
    addMitayo,
    removeMitayo,
  }
}
