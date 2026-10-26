import { defineStore } from 'pinia'
import type {
  ScopeFolder,
  ScopeType,
  CreateFolderRequest,
  UpdateFolderRequest,
  BulkAssignResponse,
  FolderNotificationSummary,
} from '~/types/scopeFolder'

/**
 * F15.3 マイスコープフォルダ統合 UX 用 Pinia ストア。
 *
 * 役割:
 *  - TEAM / ORGANIZATION 別のフォルダ一覧キャッシュ
 *  - 未分類フォルダ（is_default=TRUE）の lazy 取得
 *  - フォルダ CRUD / アイテム追加・削除・一括振り分け
 *  - フォルダ別未読集計のキャッシュ（タブバッジ用）
 *
 * 設計書 F15.3 §7.6 に準拠。Pinia ストアだが `composables/` 配下に置く運用は
 * Nuxt 3 の auto-import に依存しており、設計書 §7.1 の指示通り。
 */

interface ScopeFoldersState {
  myTeamFolders: ScopeFolder[]
  myOrgFolders: ScopeFolder[]
  defaultTeamFolderId: number | null
  defaultOrgFolderId: number | null
  notificationSummaryByFolder: Record<number, number>
  loading: boolean
}

const BASE_PATH = '/api/v1/me/scope-folders'

function isDefaultFolder(folder: ScopeFolder): boolean {
  return folder.isDefault === true
}

export const useScopeFoldersStore = defineStore('scopeFolders', {
  state: (): ScopeFoldersState => ({
    myTeamFolders: [],
    myOrgFolders: [],
    defaultTeamFolderId: null,
    defaultOrgFolderId: null,
    notificationSummaryByFolder: {},
    loading: false,
  }),

  getters: {
    /** scopeType に応じたフォルダ配列を返すヘルパー。 */
    foldersFor: (state) => (scopeType: ScopeType): ScopeFolder[] => {
      return scopeType === 'TEAM' ? state.myTeamFolders : state.myOrgFolders
    },

    /** 「未分類」以外のユーザー作成フォルダ。 */
    customFoldersFor: (state) => (scopeType: ScopeType): ScopeFolder[] => {
      const list = scopeType === 'TEAM' ? state.myTeamFolders : state.myOrgFolders
      return list.filter(f => !isDefaultFolder(f))
    },

    /** scopeType の「未分類」フォルダ。 */
    defaultFolderFor: (state) => (scopeType: ScopeType): ScopeFolder | null => {
      const list = scopeType === 'TEAM' ? state.myTeamFolders : state.myOrgFolders
      return list.find(isDefaultFolder) ?? null
    },

    /** folderId の未読件数を取得（未集計なら 0）。 */
    unreadCountOf: (state) => (folderId: number): number => {
      return state.notificationSummaryByFolder[folderId] ?? 0
    },
  },

  actions: {
    /**
     * 指定 scopeType のフォルダ一覧をフェッチして state に反映する。
     */
    async fetchAll(scopeType: ScopeType): Promise<void> {
      this.loading = true
      try {
        const api = useApi()
        const res = await api<{ data: ScopeFolder[] }>(
          `${BASE_PATH}?scopeType=${scopeType}`,
        )
        if (scopeType === 'TEAM') {
          this.myTeamFolders = res.data
          this.defaultTeamFolderId = res.data.find(isDefaultFolder)?.id ?? null
        }
        else {
          this.myOrgFolders = res.data
          this.defaultOrgFolderId = res.data.find(isDefaultFolder)?.id ?? null
        }
      }
      finally {
        this.loading = false
      }
    },

    /**
     * 未分類フォルダを取得する（Backend が無ければ lazy 生成）。
     * 取得後、state のフォルダ配列にもマージする。
     */
    async fetchDefault(scopeType: ScopeType): Promise<ScopeFolder> {
      const api = useApi()
      const res = await api<{ data: ScopeFolder }>(
        `${BASE_PATH}/default?scopeType=${scopeType}`,
      )
      const folder = res.data
      this.upsertFolder(scopeType, folder)
      if (scopeType === 'TEAM') {
        this.defaultTeamFolderId = folder.id
      }
      else {
        this.defaultOrgFolderId = folder.id
      }
      return folder
    },

    /**
     * フォルダ新規作成。
     */
    async create(
      scopeType: ScopeType,
      payload: CreateFolderRequest,
    ): Promise<ScopeFolder> {
      const api = useApi()
      const res = await api<{ data: ScopeFolder }>(
        `${BASE_PATH}?scopeType=${scopeType}`,
        { method: 'POST', body: payload },
      )
      this.upsertFolder(scopeType, res.data)
      return res.data
    },

    /**
     * フォルダ更新。未分類フォルダ（isDefault=true）は弾く。
     */
    async update(
      scopeType: ScopeType,
      folderId: number,
      payload: UpdateFolderRequest,
    ): Promise<ScopeFolder> {
      const target = this.findFolder(scopeType, folderId)
      if (target && isDefaultFolder(target)) {
        throw new Error('scopeFolder.error.defaultImmutable')
      }
      const api = useApi()
      const res = await api<{ data: ScopeFolder }>(`${BASE_PATH}/${folderId}`, {
        method: 'PUT',
        body: payload,
      })
      this.upsertFolder(scopeType, res.data)
      return res.data
    },

    /**
     * フォルダ削除。未分類フォルダは弾く。
     */
    async delete(scopeType: ScopeType, folderId: number): Promise<void> {
      const target = this.findFolder(scopeType, folderId)
      if (target && isDefaultFolder(target)) {
        throw new Error('scopeFolder.error.defaultImmutable')
      }
      const api = useApi()
      await api(`${BASE_PATH}/${folderId}`, { method: 'DELETE' })
      this.removeFolder(scopeType, folderId)
    },

    /**
     * フォルダにアイテムを追加。レスポンスのフォルダで state を更新する。
     */
    async addItem(
      scopeType: ScopeType,
      folderId: number,
      scopeId: string,
    ): Promise<void> {
      const api = useApi()
      const res = await api<{ data: ScopeFolder }>(
        `${BASE_PATH}/${folderId}/items`,
        { method: 'POST', body: { scopeId } },
      )
      this.upsertFolder(scopeType, res.data)
      // 別フォルダに属していた場合は移動されるため、他のフォルダから取り除く
      this.removeScopeIdFromOtherFolders(scopeType, folderId, scopeId)
    },

    /**
     * フォルダからアイテムを削除。
     */
    async removeItem(
      scopeType: ScopeType,
      folderId: number,
      scopeId: string,
    ): Promise<void> {
      const api = useApi()
      await api(`${BASE_PATH}/${folderId}/items/${scopeId}`, { method: 'DELETE' })
      const folder = this.findFolder(scopeType, folderId)
      if (folder) {
        folder.itemScopeIds = folder.itemScopeIds.filter(id => id !== scopeId)
      }
    },

    /**
     * 一括振り分け。POST /api/v1/me/scope-folders/items/bulk-assign（F15.3 新規）。
     */
    async bulkAssign(
      folderId: number,
      scopeIds: string[],
      scopeType: ScopeType,
    ): Promise<BulkAssignResponse> {
      const api = useApi()
      const res = await api<{ data: BulkAssignResponse }>(
        `${BASE_PATH}/items/bulk-assign`,
        {
          method: 'POST',
          body: { folderId, scopeIds, scopeType },
        },
      )
      // 振り分け後は最新フォルダ一覧を再取得（楽観的更新ではなく一貫性優先）
      await this.fetchAll(scopeType)
      return res.data
    },

    /**
     * フォルダ別未読集計の取得（F15.3 新規）。
     */
    async refreshNotificationSummary(scopeType: ScopeType): Promise<void> {
      const api = useApi()
      const res = await api<{ data: FolderNotificationSummary[] }>(
        `${BASE_PATH}/notifications/summary?scopeType=${scopeType}`,
      )
      const next: Record<number, number> = { ...this.notificationSummaryByFolder }
      for (const item of res.data) {
        next[item.folderId] = item.unreadCount
      }
      this.notificationSummaryByFolder = next
    },

    /** state リセット（ログアウト時など）。 */
    clear() {
      this.myTeamFolders = []
      this.myOrgFolders = []
      this.defaultTeamFolderId = null
      this.defaultOrgFolderId = null
      this.notificationSummaryByFolder = {}
    },

    // === 内部ヘルパー ===

    findFolder(scopeType: ScopeType, folderId: number): ScopeFolder | undefined {
      const list = scopeType === 'TEAM' ? this.myTeamFolders : this.myOrgFolders
      return list.find(f => f.id === folderId)
    },

    upsertFolder(scopeType: ScopeType, folder: ScopeFolder) {
      const list = scopeType === 'TEAM' ? this.myTeamFolders : this.myOrgFolders
      const idx = list.findIndex(f => f.id === folder.id)
      if (idx >= 0) {
        list[idx] = folder
      }
      else {
        list.push(folder)
      }
      // sortOrder 昇順で安定ソート（未分類は sortOrder=9999 で末尾固定）
      list.sort((a, b) => a.sortOrder - b.sortOrder)
    },

    removeFolder(scopeType: ScopeType, folderId: number) {
      if (scopeType === 'TEAM') {
        this.myTeamFolders = this.myTeamFolders.filter(f => f.id !== folderId)
      }
      else {
        this.myOrgFolders = this.myOrgFolders.filter(f => f.id !== folderId)
      }
    },

    removeScopeIdFromOtherFolders(
      scopeType: ScopeType,
      keepFolderId: number,
      scopeId: string,
    ) {
      const list = scopeType === 'TEAM' ? this.myTeamFolders : this.myOrgFolders
      for (const folder of list) {
        if (folder.id === keepFolderId) continue
        folder.itemScopeIds = folder.itemScopeIds.filter(id => id !== scopeId)
      }
    },
  },
})
