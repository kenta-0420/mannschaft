import type { components } from '~/types/generated'

export type ContentType = 'POST' | 'MESSAGE' | 'THREAD' | 'ARTICLE' | 'FILE' | 'USER' | 'TEAM' | 'ORGANIZATION' | 'ACTIVITY'
export type SearchAction = 'LIKE' | 'BOOKMARK' | 'MARK_READ' | 'DOWNLOAD' | 'SEND_DM'
export type SearchViewMode = 'OVERVIEW' | 'DETAIL'

export interface SearchResult {
  type: ContentType
  id: number
  title: string | null
  snippet: string
  highlights: Record<string, [number, number][]>
  relevance: number | null
  author?: { id: number; displayName: string; avatarUrl: string }
  scope?: { type: string; id: number; name: string }
  createdAt: string
  url: string
  actions: SearchAction[]
}

export type GlobalSearchType = 'schedules' | 'events' | 'reservations' | 'shifts' | 'safetyChecks' | 'queues' | 'teams' | 'organizations' | 'users'

// 現行 DTO は各種別の表示名と ID を返す。Map<String, Object> の値型は生成型で表現できない。
export interface GlobalSearchResult {
  id: number
  title?: string
  location?: string
  venueName?: string
  purpose?: string
  ticketNumber?: string
  guestName?: string
  name?: string
  fullName?: string
}

export interface SearchResponse {
  data: Required<Pick<components['schemas']['SearchResultResponse'], 'query' | 'executionTimeMs'>> & {
    results: Record<GlobalSearchType, GlobalSearchResult[]>
    counts: Record<GlobalSearchType, number>
  }
}

export interface Suggestion {
  text: string
  type: 'KEYWORD' | 'USER' | 'TEAM' | 'ORGANIZATION'
  userId?: number
  teamId?: number
  avatarUrl?: string
}

export interface SavedSearch {
  id: number
  name: string
  query: string
  filters: Record<string, string>
  createdAt: string
}

export interface RecentSearch {
  id: number
  query: string
  searchedAt: string
}
