export interface CoinTossResponse {
  id: number
  mode: string
  question: string | null
  options: string[]
  resultIndex: number
  result: string
  sharedToChat: boolean
  createdAt: string
}

export interface CoinTossRequest {
  mode?: string
  options?: string[]
  question?: string
}

export interface CursorMeta {
  nextCursor: string | null
  hasNext: boolean
  limit: number
}
