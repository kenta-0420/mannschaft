// F02.9 個人ダッシュボード お気に入りウィジェット — フロントエンド型定義
// 設計書: docs/features/F02.9_favorites_widget.md
//
// バックエンド `FavoriteResponse` は flat 構造で返るが、フロントエンドでは
// UI レンダリングしやすいように `entity` ネスト構造に詰め直して扱う。
// 変換は `useFavoritesApi` 内のヘルパーが担当する。

/** お気に入り対象のエンティティ種別。 */
export type FavoriteEntityType =
  | 'TEAM'
  | 'ORGANIZATION'
  | 'KB_PAGE'
  | 'BLOG_AUTHOR'
  | 'VILLAGE'

/** エンティティの利用可否。削除済みなどで参照不能なら UNAVAILABLE。 */
export type FavoriteEntityStatus = 'AVAILABLE' | 'UNAVAILABLE'

/** お気に入りに紐づくエンティティのメタ情報。 */
export interface FavoriteEntityMeta {
  name: string
  description: string | null
  iconUrl: string | null
  pageUrl: string
  status: FavoriteEntityStatus
  canEdit: boolean
  editableFields: string[]
}

/** お気に入り 1 件分の表示用モデル。 */
export interface UserFavoriteItem {
  favoriteId: string
  entityType: FavoriteEntityType
  entityId: string
  displayOrder: number
  createdAt: string
  entity: FavoriteEntityMeta
}

/** ウィジェット全体の取得レスポンス。 */
export interface UserFavoritesResponse {
  items: UserFavoriteItem[]
  totalCount: number
  maxCount: number
}

/** お気に入り追加リクエスト。 */
export interface AddFavoriteRequest {
  entityType: FavoriteEntityType
  entityId: string
}

/** 並び替えリクエスト。 */
export interface ReorderFavoritesRequest {
  orderedIds: string[]
}

/** 任意エンティティのお気に入り登録状態チェック結果。 */
export interface FavoriteCheckResponse {
  isFavorited: boolean
  favoriteId: string | null
}
