import type { BlogPostResponse, BlogTag, BlogSeries, BlogRevision } from '~/types/cms'

export function useBlogApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) query.set(key, String(value))
    }
    return query.toString()
  }

  async function getPosts(params: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api<{ data: BlogPostResponse[]; meta: { page: number; size: number; totalElements: number; totalPages: number } }>(`/api/v1/blog/posts?${qs}`)
  }

  async function getPost(slug: string) {
    return api<{ data: BlogPostResponse }>(`/api/v1/blog/posts/${slug}`)
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

  async function getTags(scopeType?: string, scopeId?: number) {
    const qs = buildQuery({ scope_type: scopeType, scope_id: scopeId })
    return api<{ data: BlogTag[] }>(`/api/v1/blog/tags?${qs}`)
  }

  async function getSeries(scopeType?: string, scopeId?: number) {
    const qs = buildQuery({ scope_type: scopeType, scope_id: scopeId })
    return api<{ data: BlogSeries[] }>(`/api/v1/blog/series?${qs}`)
  }

  async function getRevisions(postId: number) {
    return api<{ data: BlogRevision[] }>(`/api/v1/blog/posts/${postId}/revisions`)
  }

  async function autoSave(postId: number, body: Record<string, unknown>) {
    return api(`/api/v1/blog/posts/${postId}/auto-save`, { method: 'PATCH', body })
  }

  return { getPosts, getPost, createPost, updatePost, deletePost, changePublishStatus, getTags, getSeries, getRevisions, autoSave }
}
