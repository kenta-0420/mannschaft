import { defineStore } from 'pinia'

const STORAGE_KEY = 'app-shell'

interface AppShellState {
  /** 個人ページでのユーザー手動選択（永続化対象）。true=レール(68px) / false=展開(260px) */
  userCollapsed: boolean
  /** スコープページ（チーム/組織）がセットする自動レール要求。Phase2で結線・今回は仕組みのみ */
  forceRail: boolean
  /** モバイルドロワーの開閉状態（永続化しない・都度リセット） */
  mobileDrawerOpen: boolean
}

/**
 * サイドバー化 Phase1: グローバルサイドバーの開閉状態を管理する Pinia ストア。
 *
 * 優先順位: スコープページの自動レール(forceRail) ＞ 個人ページの手動記憶(userCollapsed)。
 * （sidebar-prototype.html の isRail() 挙動と同一。マスター御裁可済みの仕様）
 */
export const useAppShellStore = defineStore('appShell', {
  state: (): AppShellState => ({
    userCollapsed: false,
    forceRail: false,
    mobileDrawerOpen: false,
  }),

  getters: {
    /** レール（68px折りたたみ）表示にすべきか。スコープ自動レールが個人の記憶より優先 */
    isRail: (state): boolean => state.userCollapsed || state.forceRail,
  },

  actions: {
    /** 個人ページでの手動トグル。永続化する */
    toggleUserCollapsed() {
      this.userCollapsed = !this.userCollapsed
      this.persistToStorage()
    },

    setUserCollapsed(collapsed: boolean) {
      this.userCollapsed = collapsed
      this.persistToStorage()
    },

    /** スコープページ（チーム/組織）からの自動レール要求。永続化しない一時状態 */
    setForceRail(force: boolean) {
      this.forceRail = force
    },

    openMobileDrawer() {
      this.mobileDrawerOpen = true
    },

    closeMobileDrawer() {
      this.mobileDrawerOpen = false
    },

    toggleMobileDrawer() {
      this.mobileDrawerOpen = !this.mobileDrawerOpen
    },

    /**
     * localStorage から userCollapsed のみ復元する。
     * 壊れた JSON・想定外の型は既定値（展開 = false）にフォールバックする。
     */
    loadFromStorage() {
      if (!import.meta.client) return
      try {
        const saved = localStorage.getItem(STORAGE_KEY)
        if (!saved) return
        const parsed = JSON.parse(saved) as { userCollapsed?: unknown }
        this.userCollapsed = typeof parsed.userCollapsed === 'boolean' ? parsed.userCollapsed : false
      } catch {
        // 壊れた JSON は既定値（展開）にフォールバック
        this.userCollapsed = false
      }
    },

    /** userCollapsed のみを localStorage['app-shell'] に永続化する */
    persistToStorage() {
      if (!import.meta.client) return
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ userCollapsed: this.userCollapsed }))
    },
  },
})
