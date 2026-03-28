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

export interface SearchResponse {
  data: {
    results: SearchResult[]
    typeCounts: Record<ContentType, number>
    query: string
    timedOutTypes: ContentType[]
    zeroResultsHelp?: {
      didYouMean: string | null
      broaderQuery: string | null
    }
  }
  meta: {
    total: number
    page: number
    perPage: number
    totalPages?: number
    viewMode: SearchViewMode
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
