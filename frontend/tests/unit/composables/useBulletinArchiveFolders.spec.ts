/**
 * F05.1: useBulletinArchiveFolders のユニットテスト。
 *
 * テスト対象:
 *  - getFolderTree: スコープセグメント変換（TEAM→teams / ORGANIZATION→organizations）+ パス
 *  - createFolder / updateFolder / deleteFolder: メソッド・ボディ・URL
 *  - getArchiveThreads: folder_id クエリ（未分類=未付与 / 'all' / UUID）
 *  - moveThreadToFolder: PATCH ボディ（null=未分類）
 */
import { describe, it, expect, beforeEach, vi, type MockedFunction } from 'vitest'

// === モック: useApi ===
const mockFetch: MockedFunction<(url: string, opts?: unknown) => Promise<unknown>> = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useBulletinArchiveFolders } = await import(
  '~/composables/bulletin/useBulletinArchiveFolders'
)

describe('useBulletinArchiveFolders', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockFetch.mockResolvedValue({ data: [], meta: {} })
  })

  describe('getFolderTree', () => {
    it('TEAM スコープを teams セグメントに変換してツリーを取得する', async () => {
      const { getFolderTree } = useBulletinArchiveFolders()
      await getFolderTree('TEAM', 42)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/teams/42/bulletin/archive/folders',
      )
    })

    it('ORGANIZATION スコープを organizations セグメントに変換する', async () => {
      const { getFolderTree } = useBulletinArchiveFolders()
      await getFolderTree('ORGANIZATION', 7)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/organizations/7/bulletin/archive/folders',
      )
    })
  })

  describe('createFolder', () => {
    it('POST でフォルダを作成する', async () => {
      const { createFolder } = useBulletinArchiveFolders()
      await createFolder('TEAM', 1, { name: '2025年度', color: '#3B82F6', parentFolderId: null })
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/teams/1/bulletin/archive/folders',
        { method: 'POST', body: { name: '2025年度', color: '#3B82F6', parentFolderId: null } },
      )
    })
  })

  describe('updateFolder', () => {
    it('PUT でフォルダを更新・移動する', async () => {
      const { updateFolder } = useBulletinArchiveFolders()
      await updateFolder('ORGANIZATION', 2, 'uuid-123', { name: '改名', parentFolderId: 'parent-1' })
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/organizations/2/bulletin/archive/folders/uuid-123',
        { method: 'PUT', body: { name: '改名', parentFolderId: 'parent-1' } },
      )
    })
  })

  describe('deleteFolder', () => {
    it('DELETE でフォルダを削除する', async () => {
      const { deleteFolder } = useBulletinArchiveFolders()
      await deleteFolder('TEAM', 3, 'uuid-del')
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/teams/3/bulletin/archive/folders/uuid-del',
        { method: 'DELETE' },
      )
    })
  })

  describe('getArchiveThreads', () => {
    it('folderId 未指定なら folder_id クエリを付けない（未分類）', async () => {
      const { getArchiveThreads } = useBulletinArchiveFolders()
      await getArchiveThreads('TEAM', 1)
      const url = mockFetch.mock.calls[0]?.[0] as string
      expect(url).toContain('/api/v1/teams/1/bulletin/archive/threads?')
      expect(url).not.toContain('folder_id')
      expect(url).toContain('page=0')
      expect(url).toContain('size=20')
    })

    it("folderId='all' なら folder_id=all を付与する", async () => {
      const { getArchiveThreads } = useBulletinArchiveFolders()
      await getArchiveThreads('TEAM', 1, { folderId: 'all' })
      const url = mockFetch.mock.calls[0]?.[0] as string
      expect(url).toContain('folder_id=all')
    })

    it('UUID を folder_id に付与する', async () => {
      const { getArchiveThreads } = useBulletinArchiveFolders()
      await getArchiveThreads('ORGANIZATION', 5, { folderId: 'uuid-abc', page: 2 })
      const url = mockFetch.mock.calls[0]?.[0] as string
      expect(url).toContain('/api/v1/organizations/5/bulletin/archive/threads?')
      expect(url).toContain('folder_id=uuid-abc')
      expect(url).toContain('page=2')
    })
  })

  describe('moveThreadToFolder', () => {
    it('PATCH でスレッドを振り分ける', async () => {
      const { moveThreadToFolder } = useBulletinArchiveFolders()
      await moveThreadToFolder('TEAM', 1, 999, 'uuid-target')
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/teams/1/bulletin/archive/threads/999/folder',
        { method: 'PATCH', body: { archiveFolderId: 'uuid-target' } },
      )
    })

    it('null を渡すと未分類（保管庫直下）へ移動する', async () => {
      const { moveThreadToFolder } = useBulletinArchiveFolders()
      await moveThreadToFolder('TEAM', 1, 999, null)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/teams/1/bulletin/archive/threads/999/folder',
        { method: 'PATCH', body: { archiveFolderId: null } },
      )
    })
  })
})
