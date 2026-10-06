import type { SearchResponse, Suggestion, SavedSearch, RecentSearch } from '~/types/search'

export function useSearchApi() {
  const api = useApi()

  async function search(params: { q: string }) {
    return api<SearchResponse>(`/api/v1/search?q=${encodeURIComponent(params.q)}`)
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
