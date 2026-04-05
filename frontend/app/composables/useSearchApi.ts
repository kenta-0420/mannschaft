import type { SearchResponse, Suggestion, SavedSearch, RecentSearch, ContentType } from '~/types/search'

export function useSearchApi() {
  const api = useApi()

  async function search(params: {
    q: string
    type?: ContentType
    scopeType?: string
    scopeId?: number
    dateFrom?: string
    dateTo?: string
    page?: number
    perPage?: number
  }) {
    const query = new URLSearchParams()
    query.set('q', params.q)
    if (params.type) query.set('type', params.type)
    if (params.scopeType) query.set('scopeType', params.scopeType)
    if (params.scopeId != null) query.set('scopeId', String(params.scopeId))
    if (params.dateFrom) query.set('dateFrom', params.dateFrom)
    if (params.dateTo) query.set('dateTo', params.dateTo)
    if (params.page != null) query.set('page', String(params.page))
    if (params.perPage != null) query.set('perPage', String(params.perPage))
    const res = await api<SearchResponse>(`/api/v1/search?${query.toString()}`)
    return res
  }

  async function suggestions(q: string) {
    const res = await api<{ data: Suggestion[] }>(`/api/v1/search/suggestions?q=${encodeURIComponent(q)}`)
    return res.data
  }

  async function listRecent() {
    const res = await api<{ data: RecentSearch[] }>('/api/v1/search/recent')
    return res.data
  }

  async function clearRecent() {
    await api('/api/v1/search/recent', { method: 'DELETE' })
  }

  async function deleteRecent(id: number) {
    await api(`/api/v1/search/recent/${id}`, { method: 'DELETE' })
  }

  async function listSaved() {
    const res = await api<{ data: SavedSearch[] }>('/api/v1/search/saved')
    return res.data
  }

  async function saveSearch(name: string, query: string, filters: Record<string, string>) {
    const res = await api<{ data: SavedSearch }>('/api/v1/search/saved', {
      method: 'POST',
      body: { name, query, filters },
    })
    return res.data
  }

  async function deleteSaved(id: number) {
    await api(`/api/v1/search/saved/${id}`, { method: 'DELETE' })
  }

  return { search, suggestions, listRecent, clearRecent, deleteRecent, listSaved, saveSearch, deleteSaved }
}
