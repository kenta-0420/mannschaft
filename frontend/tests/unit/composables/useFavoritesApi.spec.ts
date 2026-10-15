import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F02.9 useFavoritesApi ユニットテスト。
 *
 * <p>お気に入りウィジェット用 Composable の動作を検証する。
 * バックエンドの flat レスポンスを `UserFavoriteItem`（entity ネスト構造）に
 * 変換するロジック、キャッシュ更新、エラー処理を主な観点とする。</p>
 *
 * モック方針:
 *  - `useApi` を vi.mock でスタブ化し、mockFetch（関数）を差し込む。
 *
 * テストケース一覧:
 *  FAV-API-001: fetchFavorites — flat レスポンスを entity ネスト構造に変換しキャッシュへ格納
 *  FAV-API-002: fetchFavorites — エラー時に error ref にセットされ items は空のまま
 *  FAV-API-003: addFavorite — キャッシュ先頭に挿入され totalCount が +1 される
 *  FAV-API-004: removeFavorite — 指定 favoriteId をキャッシュから除外し totalCount が -1
 *  FAV-API-005: reorderFavorites — 指定順序にキャッシュが並び替わる
 *  FAV-API-006: mapBackendToFrontend — available=true → status=AVAILABLE 変換
 *  FAV-API-007: mapBackendToFrontend — available=false → status=UNAVAILABLE 変換
 *  FAV-API-008: mapBackendToFrontend — description は null・editableFields は空配列
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useFavoritesApi } = await import('~/composables/useFavoritesApi')

interface BackendFavoriteResponse {
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

function makeBackendFav(overrides: Partial<BackendFavoriteResponse> = {}): BackendFavoriteResponse {
  return {
    id: 'fav-001',
    entityType: 'TEAM',
    entityId: '123',
    displayOrder: 0,
    displayName: 'Test Team',
    iconUrl: null,
    pageUrl: '/teams/123',
    canEdit: true,
    available: true,
    createdAt: '2026-05-15T00:00:00Z',
    ...overrides,
  }
}

describe('useFavoritesApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  describe('fetchFavorites()', () => {
    it('FAV-API-001: flat レスポンスを entity ネスト構造に変換しキャッシュへ格納', async () => {
      const backendItems: BackendFavoriteResponse[] = [
        makeBackendFav({ id: 'fav-a', displayName: 'A' }),
        makeBackendFav({ id: 'fav-b', displayName: 'B', entityType: 'ORGANIZATION' }),
      ]
      mockFetch.mockResolvedValueOnce({ data: backendItems })
      const api = useFavoritesApi()

      const result = await api.fetchFavorites()

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/favorites')
      expect(result).toHaveLength(2)
      expect(api.items.value).toHaveLength(2)
      expect(api.items.value[0]).toMatchObject({
        favoriteId: 'fav-a',
        entityType: 'TEAM',
        entity: { name: 'A', status: 'AVAILABLE' },
      })
      expect(api.items.value[1]).toMatchObject({
        favoriteId: 'fav-b',
        entityType: 'ORGANIZATION',
      })
    })

    it('FAV-API-002: エラー時に error ref にセットされ items は空のまま', async () => {
      const err = new Error('network failure')
      mockFetch.mockRejectedValueOnce(err)
      const api = useFavoritesApi()

      await expect(api.fetchFavorites()).rejects.toThrow('network failure')
      expect(api.error.value).toBe(err)
      expect(api.items.value).toHaveLength(0)
    })
  })

  describe('addFavorite()', () => {
    it('FAV-API-003: キャッシュ先頭に挿入され totalCount が +1', async () => {
      // 既存 1 件をプリロード
      mockFetch.mockResolvedValueOnce({ data: [makeBackendFav({ id: 'fav-existing' })] })
      const api = useFavoritesApi()
      await api.fetchFavorites()
      expect(api.totalCount.value).toBe(1)

      // 追加
      mockFetch.mockResolvedValueOnce({
        data: makeBackendFav({ id: 'fav-new', displayName: 'Added' }),
      })
      const created = await api.addFavorite('TEAM', '999')

      expect(mockFetch).toHaveBeenLastCalledWith('/api/v1/me/favorites', {
        method: 'POST',
        body: { entityType: 'TEAM', entityId: '999' },
      })
      expect(created.favoriteId).toBe('fav-new')
      expect(api.totalCount.value).toBe(2)
      expect(api.items.value[0]?.favoriteId).toBe('fav-new')
      expect(api.items.value[1]?.favoriteId).toBe('fav-existing')
    })
  })

  describe('removeFavorite()', () => {
    it('FAV-API-004: 指定 favoriteId をキャッシュから除外し totalCount が -1', async () => {
      mockFetch.mockResolvedValueOnce({
        data: [
          makeBackendFav({ id: 'fav-keep' }),
          makeBackendFav({ id: 'fav-drop' }),
        ],
      })
      const api = useFavoritesApi()
      await api.fetchFavorites()
      expect(api.totalCount.value).toBe(2)

      mockFetch.mockResolvedValueOnce(undefined)
      await api.removeFavorite('fav-drop')

      expect(mockFetch).toHaveBeenLastCalledWith('/api/v1/me/favorites/fav-drop', {
        method: 'DELETE',
      })
      expect(api.totalCount.value).toBe(1)
      expect(api.items.value[0]?.favoriteId).toBe('fav-keep')
    })
  })

  describe('reorderFavorites()', () => {
    it('FAV-API-005: 指定順序にキャッシュが並び替わる', async () => {
      mockFetch.mockResolvedValueOnce({
        data: [
          makeBackendFav({ id: 'fav-1', displayOrder: 0 }),
          makeBackendFav({ id: 'fav-2', displayOrder: 1 }),
          makeBackendFav({ id: 'fav-3', displayOrder: 2 }),
        ],
      })
      const api = useFavoritesApi()
      await api.fetchFavorites()

      mockFetch.mockResolvedValueOnce(undefined)
      await api.reorderFavorites(['fav-3', 'fav-1', 'fav-2'])

      expect(mockFetch).toHaveBeenLastCalledWith('/api/v1/me/favorites/reorder', {
        method: 'PATCH',
        body: { orderedIds: ['fav-3', 'fav-1', 'fav-2'] },
      })
      expect(api.items.value.map((it) => it.favoriteId)).toEqual([
        'fav-3',
        'fav-1',
        'fav-2',
      ])
    })
  })

  describe('mapBackendToFrontend（変換ロジック）', () => {
    it('FAV-API-006: available=true → status=AVAILABLE に変換される', async () => {
      mockFetch.mockResolvedValueOnce({
        data: [makeBackendFav({ available: true })],
      })
      const api = useFavoritesApi()
      await api.fetchFavorites()

      expect(api.items.value[0]?.entity.status).toBe('AVAILABLE')
    })

    it('FAV-API-007: available=false → status=UNAVAILABLE に変換される', async () => {
      mockFetch.mockResolvedValueOnce({
        data: [makeBackendFav({ available: false })],
      })
      const api = useFavoritesApi()
      await api.fetchFavorites()

      expect(api.items.value[0]?.entity.status).toBe('UNAVAILABLE')
    })

    it('FAV-API-008: description は null、editableFields は空配列で固定変換される', async () => {
      mockFetch.mockResolvedValueOnce({
        data: [makeBackendFav()],
      })
      const api = useFavoritesApi()
      await api.fetchFavorites()

      const entity = api.items.value[0]?.entity
      expect(entity?.description).toBeNull()
      expect(entity?.editableFields).toEqual([])
    })
  })
})
