/**
 * F15.3: useScopeFoldersStore のユニットテスト。
 *
 * テスト対象:
 *  - fetchAll: API レスポンスを scopeType 別 state へ反映
 *  - fetchDefault: 未分類フォルダの lazy 取得・state 更新
 *  - create / update / delete: フォルダ CRUD と is_default 防御
 *  - addItem / removeItem: アイテム操作
 *  - bulkAssign: 新規エンドポイント呼び出し
 *  - refreshNotificationSummary: 集計 state 更新
 *  - getters: foldersFor / customFoldersFor / defaultFolderFor / unreadCountOf
 */
import { describe, it, expect, beforeEach, vi, type MockedFunction } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import type { ScopeFolder } from '~/types/scopeFolder'

// === モック: useApi ===
const mockFetch: MockedFunction<(url: string, opts?: unknown) => Promise<unknown>> = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useScopeFoldersStore } = await import('~/composables/useScopeFoldersStore')

function makeFolder(overrides: Partial<ScopeFolder> = {}): ScopeFolder {
  return {
    id: 1,
    name: 'デフォルト',
    color: '#3B82F6',
    icon: null,
    isDefault: false,
    sortOrder: 0,
    itemScopeIds: [],
    ...overrides,
  }
}

describe('useScopeFoldersStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockFetch.mockReset()
  })

  describe('fetchAll', () => {
    it('TEAM のフォルダ一覧を取得し state に反映する', async () => {
      const folders: ScopeFolder[] = [
        makeFolder({ id: 1, name: '部活' }),
        makeFolder({ id: 99, name: '未分類', isDefault: true, sortOrder: 9999 }),
      ]
      mockFetch.mockResolvedValueOnce({ data: folders })

      const store = useScopeFoldersStore()
      await store.fetchAll('TEAM')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/me/scope-folders?scopeType=TEAM',
      )
      expect(store.myTeamFolders).toEqual(folders)
      expect(store.defaultTeamFolderId).toBe(99)
    })

    it('ORGANIZATION 取得時に myOrgFolders へ反映する', async () => {
      const folders: ScopeFolder[] = [makeFolder({ id: 5, name: '法人' })]
      mockFetch.mockResolvedValueOnce({ data: folders })

      const store = useScopeFoldersStore()
      await store.fetchAll('ORGANIZATION')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/me/scope-folders?scopeType=ORGANIZATION',
      )
      expect(store.myOrgFolders).toEqual(folders)
      expect(store.defaultOrgFolderId).toBeNull()
    })
  })

  describe('fetchDefault', () => {
    it('lazy 生成エンドポイントを呼びフォルダを反映する', async () => {
      const folder = makeFolder({ id: 42, isDefault: true, name: '未分類' })
      mockFetch.mockResolvedValueOnce({ data: folder })

      const store = useScopeFoldersStore()
      const result = await store.fetchDefault('TEAM')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/me/scope-folders/default?scopeType=TEAM',
      )
      expect(result).toEqual(folder)
      expect(store.defaultTeamFolderId).toBe(42)
      expect(store.myTeamFolders).toContainEqual(folder)
    })
  })

  describe('create / update / delete', () => {
    it('create は POST し state へ追加する', async () => {
      const folder = makeFolder({ id: 10, name: '新規' })
      mockFetch.mockResolvedValueOnce({ data: folder })

      const store = useScopeFoldersStore()
      const result = await store.create('TEAM', { name: '新規', color: '#3B82F6' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/me/scope-folders?scopeType=TEAM',
        { method: 'POST', body: { name: '新規', color: '#3B82F6' } },
      )
      expect(result).toEqual(folder)
      expect(store.myTeamFolders).toContainEqual(folder)
    })

    it('update は PUT し state を更新する', async () => {
      const orig = makeFolder({ id: 10, name: '旧' })
      const updated = makeFolder({ id: 10, name: '新' })
      mockFetch.mockResolvedValueOnce({ data: updated })

      const store = useScopeFoldersStore()
      store.myTeamFolders = [orig]

      const result = await store.update('TEAM', 10, { name: '新' })

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/scope-folders/10', {
        method: 'PUT',
        body: { name: '新' },
      })
      expect(result).toEqual(updated)
      expect(store.myTeamFolders[0]?.name).toBe('新')
    })

    it('update は未分類フォルダ（isDefault=true）を弾く', async () => {
      const store = useScopeFoldersStore()
      store.myTeamFolders = [makeFolder({ id: 99, isDefault: true })]

      await expect(store.update('TEAM', 99, { name: '改名' })).rejects.toThrow(
        'scopeFolder.error.defaultImmutable',
      )
      expect(mockFetch).not.toHaveBeenCalled()
    })

    it('delete は DELETE し state から取り除く', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const store = useScopeFoldersStore()
      store.myTeamFolders = [makeFolder({ id: 10 })]

      await store.delete('TEAM', 10)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/scope-folders/10', {
        method: 'DELETE',
      })
      expect(store.myTeamFolders).toHaveLength(0)
    })

    it('delete は未分類フォルダを弾く', async () => {
      const store = useScopeFoldersStore()
      store.myTeamFolders = [makeFolder({ id: 99, isDefault: true })]

      await expect(store.delete('TEAM', 99)).rejects.toThrow(
        'scopeFolder.error.defaultImmutable',
      )
      expect(mockFetch).not.toHaveBeenCalled()
    })
  })

  describe('addItem / removeItem', () => {
    it('addItem は POST し他フォルダから scopeId を取り除く', async () => {
      const folderA = makeFolder({ id: 1, itemScopeIds: ['101'] })
      const folderB = makeFolder({ id: 2, itemScopeIds: [] })
      // レスポンスは追加後の folderB
      const updatedB = makeFolder({ id: 2, itemScopeIds: ['101'] })
      mockFetch.mockResolvedValueOnce({ data: updatedB })

      const store = useScopeFoldersStore()
      store.myTeamFolders = [folderA, folderB]

      await store.addItem('TEAM', 2, '101')

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/scope-folders/2/items', {
        method: 'POST',
        body: { scopeId: '101' },
      })
      const a = store.myTeamFolders.find(f => f.id === 1)
      const b = store.myTeamFolders.find(f => f.id === 2)
      expect(a?.itemScopeIds).not.toContain('101')
      expect(b?.itemScopeIds).toContain('101')
    })

    it('removeItem は DELETE し state から取り除く', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const folder = makeFolder({ id: 1, itemScopeIds: ['101', '102'] })
      const store = useScopeFoldersStore()
      store.myTeamFolders = [folder]

      await store.removeItem('TEAM', 1, '101')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/me/scope-folders/1/items/101',
        { method: 'DELETE' },
      )
      expect(store.myTeamFolders[0]?.itemScopeIds).toEqual(['102'])
    })
  })

  describe('bulkAssign', () => {
    it('bulk-assign エンドポイントを呼び結果を返す', async () => {
      const bulkResp = { assignedCount: 3, skippedCount: 0, errors: [] }
      mockFetch.mockResolvedValueOnce({ data: bulkResp })
      // bulkAssign は完了後に fetchAll を呼ぶ
      mockFetch.mockResolvedValueOnce({ data: [] })

      const store = useScopeFoldersStore()
      const result = await store.bulkAssign(1, ['101', '102', '103'], 'TEAM')

      expect(mockFetch).toHaveBeenNthCalledWith(
        1,
        '/api/v1/me/scope-folders/items/bulk-assign',
        {
          method: 'POST',
          body: { folderId: 1, scopeIds: ['101', '102', '103'], scopeType: 'TEAM' },
        },
      )
      expect(result).toEqual(bulkResp)
    })
  })

  describe('refreshNotificationSummary', () => {
    it('summary エンドポイントを呼び notificationSummaryByFolder へ反映する', async () => {
      mockFetch.mockResolvedValueOnce({
        data: [
          { folderId: 1, unreadCount: 5 },
          { folderId: 2, unreadCount: 0 },
        ],
      })

      const store = useScopeFoldersStore()
      await store.refreshNotificationSummary('TEAM')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/me/scope-folders/notifications/summary?scopeType=TEAM',
      )
      expect(store.notificationSummaryByFolder).toEqual({ 1: 5, 2: 0 })
    })
  })

  describe('getters', () => {
    it('foldersFor は scopeType に応じた配列を返す', () => {
      const store = useScopeFoldersStore()
      const t = makeFolder({ id: 1 })
      const o = makeFolder({ id: 2 })
      store.myTeamFolders = [t]
      store.myOrgFolders = [o]

      expect(store.foldersFor('TEAM')).toEqual([t])
      expect(store.foldersFor('ORGANIZATION')).toEqual([o])
    })

    it('customFoldersFor は未分類を除外する', () => {
      const store = useScopeFoldersStore()
      const custom = makeFolder({ id: 1, isDefault: false })
      const def = makeFolder({ id: 99, isDefault: true })
      store.myTeamFolders = [custom, def]

      expect(store.customFoldersFor('TEAM')).toEqual([custom])
    })

    it('defaultFolderFor は未分類フォルダを返す', () => {
      const store = useScopeFoldersStore()
      const def = makeFolder({ id: 99, isDefault: true })
      store.myTeamFolders = [makeFolder({ id: 1 }), def]

      expect(store.defaultFolderFor('TEAM')).toEqual(def)
    })

    it('defaultFolderFor は未分類が無い場合 null を返す', () => {
      const store = useScopeFoldersStore()
      store.myTeamFolders = [makeFolder({ id: 1 })]

      expect(store.defaultFolderFor('TEAM')).toBeNull()
    })

    it('unreadCountOf は未集計フォルダで 0 を返す', () => {
      const store = useScopeFoldersStore()
      store.notificationSummaryByFolder = { 1: 5 }

      expect(store.unreadCountOf(1)).toBe(5)
      expect(store.unreadCountOf(99)).toBe(0)
    })
  })

  describe('clear', () => {
    it('全 state を初期化する', () => {
      const store = useScopeFoldersStore()
      store.myTeamFolders = [makeFolder({ id: 1 })]
      store.myOrgFolders = [makeFolder({ id: 2 })]
      store.defaultTeamFolderId = 1
      store.defaultOrgFolderId = 2
      store.notificationSummaryByFolder = { 1: 5 }

      store.clear()

      expect(store.myTeamFolders).toEqual([])
      expect(store.myOrgFolders).toEqual([])
      expect(store.defaultTeamFolderId).toBeNull()
      expect(store.defaultOrgFolderId).toBeNull()
      expect(store.notificationSummaryByFolder).toEqual({})
    })
  })
})
