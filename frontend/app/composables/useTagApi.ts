import type {
  TagResponse,
  PagedTags,
  CreateTagRequest,
  UpdateTagRequest,
} from '~/types/quickMemo'

type TagScope = 'personal' | 'team' | 'organization'

export function useTagApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) query.set(key, String(value))
    }
    return query.toString()
  }

  function basePath(scope: TagScope, scopeId?: number): string {
    if (scope === 'personal') return '/api/v1/me/tags'
    if (scope === 'team') return `/api/v1/teams/${scopeId}/tags`
    return `/api/v1/organizations/${scopeId}/tags`
  }

  // ─── 一覧 ────────────────────────────────────────────────────────────────────

  async function listTags(
    scope: TagScope,
    scopeId?: number,
    params: { page?: number; size?: number } = {},
  ) {
    const qs = buildQuery({ page: 1, size: 50, ...params })
    return api<PagedTags>(`${basePath(scope, scopeId)}?${qs}`)
  }

  // ─── 作成 ────────────────────────────────────────────────────────────────────

  async function createTag(scope: TagScope, scopeId: number | undefined, body: CreateTagRequest) {
    return api<{ data: TagResponse }>(basePath(scope, scopeId), { method: 'POST', body })
  }

  // ─── 更新 ────────────────────────────────────────────────────────────────────

  async function updateTag(
    scope: TagScope,
    scopeId: number | undefined,
    tagId: number,
    body: UpdateTagRequest,
  ) {
    return api<{ data: TagResponse }>(`${basePath(scope, scopeId)}/${tagId}`, {
      method: 'PUT',
      body,
    })
  }

  // ─── 削除 ────────────────────────────────────────────────────────────────────

  async function deleteTag(scope: TagScope, scopeId: number | undefined, tagId: number) {
    return api(`${basePath(scope, scopeId)}/${tagId}`, { method: 'DELETE' })
  }

  return {
    listTags,
    createTag,
    updateTag,
    deleteTag,
  }
}
