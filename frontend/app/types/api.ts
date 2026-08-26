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

/**
 * Spring Data `Page<T>` をそのまま JSON 露出したレスポンス型。
 *
 * BE が `ApiResponse<Page<T>>` を返すエンドポイント専用。
 * 生成型（`types/generated`）の `PageJoinRequestResponse` 等 23 スキーマは
 * `content` の要素型が違うだけで全て同一形状であり、本型はその総称にあたる。
 *
 * 注意:
 *   - 上の {@link PagedResponse} は BE の独自 `PagedResponse` DTO（`{data, meta}`）用で別物。混同しないこと。
 *   - `app/types/public.ts` にも同名の `SpringPage<T>` があるが、あちらは
 *     「認証済み API の型と共用しない」Defense in Depth 境界の内側に意図的に隔離されているため統合しない。
 */
export interface SpringPage<T> {
  content: T[]
  empty: boolean
  first: boolean
  last: boolean
  number: number
  numberOfElements: number
  pageable: SpringPageable
  size: number
  sort: SpringSort
  totalElements: number
  totalPages: number
}

/** {@link SpringPage} の `pageable`（Spring の `PageableObject`）。 */
export interface SpringPageable {
  offset: number
  pageNumber: number
  pageSize: number
  paged: boolean
  sort: SpringSort
  unpaged: boolean
}

/** {@link SpringPage} の `sort`（Spring の `SortObject`）。 */
export interface SpringSort {
  empty: boolean
  sorted: boolean
  unsorted: boolean
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
