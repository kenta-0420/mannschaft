/** バックエンド共通レスポンス型 */
export interface ApiResponse<T> {
  data: T
  message: string | null
}

/** ページネーション付きレスポンス型 */
export interface PagedResponse<T> {
  data: T[]
  meta: PageMeta
}

export interface PageMeta {
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** エラーレスポンス型 */
export interface ErrorResponse {
  error: string
  message: string
  fieldErrors?: FieldError[]
}

export interface FieldError {
  field: string
  message: string
}
