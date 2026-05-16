import { computed, readonly, ref } from 'vue'
import type {
  AddFavoriteRequest,
  FavoriteEntityType,
  ReorderFavoritesRequest,
  UserFavoriteItem,
} from '~/types/favorite'

// useFavoritesApi - お気に入りAPI Composable (F02.9)
//
// バックエンドAPI仕様:
//   GET    /api/v1/me/favorites          → ApiResponse<List<FavoriteResponse>>
//   POST   /api/v1/me/favorites          → ApiResponse<FavoriteResponse> (201)
//   GET    /api/v1/me/favorites/{id}     → ApiResponse<FavoriteResponse>
//   DELETE /api/v1/me/favorites/{id}     → 204
//   PATCH  /api/v1/me/favorites/reorder  → 204 (body: { orderedIds: UUID[] })
//
// バックエンド FavoriteResponse フィールド (flat):
//   id: string (UUID)
//   entityType: string ("TEAM" 等)
//   entityId: string
//   displayOrder: number
//   displayName: string | null
//   iconUrl: string | null
//   pageUrl: string | null
//   canEdit: boolean
//   available: boolean
//   createdAt: string (ISO)
//
// フロントエンドでは `UserFavoriteItem` の `entity` ネスト構造に変換して扱う。

/** バックエンド FavoriteResponse の生フィールド型。 */
interface FavoriteResponseDto {
  id: string
  entityType: string
  entityId: string
  displayOrder: number
  displayName: string | null
  iconUrl: string | null
  pageUrl: string | null
  canEdit: boolean
  available: boolean
  createdAt: string
}

/** Spring Boot の `ApiResponse<T>` ラッパー。 */
interface ApiResponseWrapper<T> {
  data: T
}

/** バックエンド `FavoriteResponse` を `UserFavoriteItem` に変換する。 */
function mapBackendToFrontend(dto: FavoriteResponseDto): UserFavoriteItem {
  return {
    favoriteId: dto.id,
    entityType: dto.entityType as FavoriteEntityType,
    entityId: dto.entityId,
    displayOrder: dto.displayOrder,
    createdAt: dto.createdAt,
    entity: {
      // displayName が null の場合は空文字を入れて UI 側で fallback できるようにする
      name: dto.displayName ?? '',
      // バックエンドに description は存在しないため null 固定
      description: null,
      iconUrl: dto.iconUrl,
      // pageUrl が null（unavailable 時）は空文字。UI 側でリンク無効化する想定
      pageUrl: dto.pageUrl ?? '',
      status: dto.available ? 'AVAILABLE' : 'UNAVAILABLE',
      canEdit: dto.canEdit,
      // バックエンドに editableFields は存在しないため空配列固定
      editableFields: [],
    },
  }
}

export function useFavoritesApi() {
  const api = useApi()

  const items = ref<UserFavoriteItem[]>([])
  const totalCount = computed(() => items.value.length)
  const isLoading = ref(false)
  const error = ref<unknown>(null)

  /** 一覧取得しキャッシュを更新する。 */
  async function fetchFavorites(): Promise<UserFavoriteItem[]> {
    isLoading.value = true
    error.value = null
    try {
      const res = await api<ApiResponseWrapper<FavoriteResponseDto[]>>('/api/v1/me/favorites')
      const mapped = (res.data ?? []).map(mapBackendToFrontend)
      items.value = mapped
      return mapped
    } catch (e) {
      error.value = e
      throw e
    } finally {
      isLoading.value = false
    }
  }

  /** お気に入りを追加し、キャッシュ先頭に挿入する。 */
  async function addFavorite(
    entityType: FavoriteEntityType,
    entityId: string,
  ): Promise<UserFavoriteItem> {
    const body: AddFavoriteRequest = { entityType, entityId }
    const res = await api<ApiResponseWrapper<FavoriteResponseDto>>('/api/v1/me/favorites', {
      method: 'POST',
      body,
    })
    const created = mapBackendToFrontend(res.data)
    // バックエンドは displayOrder=0 の先頭挿入仕様なのでフロントも同様にキャッシュ更新
    items.value = [created, ...items.value]
    return created
  }

  /** お気に入りを削除し、キャッシュからも除外する。 */
  async function removeFavorite(favoriteId: string): Promise<void> {
    await api(`/api/v1/me/favorites/${favoriteId}`, { method: 'DELETE' })
    items.value = items.value.filter((it) => it.favoriteId !== favoriteId)
  }

  /** 並び替えを保存し、キャッシュも並べ直す。 */
  async function reorderFavorites(orderedIds: string[]): Promise<void> {
    const body: ReorderFavoritesRequest = { orderedIds }
    await api('/api/v1/me/favorites/reorder', {
      method: 'PATCH',
      body,
    })
    // 与えられた順序に従ってキャッシュを並べ直す。orderedIds に含まれない項目は末尾に保持
    const indexMap = new Map<string, number>()
    orderedIds.forEach((id, i) => indexMap.set(id, i))
    const ordered = [...items.value].sort((a, b) => {
      const ai = indexMap.get(a.favoriteId) ?? Number.MAX_SAFE_INTEGER
      const bi = indexMap.get(b.favoriteId) ?? Number.MAX_SAFE_INTEGER
      return ai - bi
    })
    items.value = ordered
  }

  /** お気に入り 1 件を取得する（キャッシュは更新しない）。 */
  async function getFavoriteById(favoriteId: string): Promise<UserFavoriteItem> {
    const res = await api<ApiResponseWrapper<FavoriteResponseDto>>(
      `/api/v1/me/favorites/${favoriteId}`,
    )
    return mapBackendToFrontend(res.data)
  }

  return {
    items: readonly(items),
    totalCount,
    isLoading: readonly(isLoading),
    error: readonly(error),
    fetchFavorites,
    addFavorite,
    removeFavorite,
    reorderFavorites,
    getFavoriteById,
  }
}
