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
  /** BE の PagedResponse.PageMeta#total に対応（正式フィールド名）*/
  total?: number
  /** 旧互換フィールド名。BE の PagedResponse は total を送信するが型の互換性のため残存 */
  totalElements: number
  totalPages: number
}

/** カーソルページネーション型 */
export interface CursorMeta {
  nextCursor: string | null
  hasNext: boolean
  limit: number
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
