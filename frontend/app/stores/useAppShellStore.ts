import { defineStore } from 'pinia'

const STORAGE_KEY = 'app-shell'

interface AppShellState {
  /** 個人ページでのユーザー手動選択（永続化対象）。true=レール(68px) / false=展開(260px) */
  userCollapsed: boolean
  /** スコープページ（チーム/組織）がセットする自動レール要求。Phase2でteam.vue/organization.vueから結線 */
  forceRail: boolean
  /**
   * スコープページ上での一時的な手動展開（Phase2）。永続化しない・スコープ再入場のたびリセット。
   * forceRail=true の間だけ意味を持つ（isRail のガード式を参照）。
   */
  scopeExpanded: boolean
  /** モバイルドロワーの開閉状態（永続化しない・都度リセット） */
  mobileDrawerOpen: boolean
}

/**
 * サイドバー化 Phase1/2: グローバルサイドバーの開閉状態を管理する Pinia ストア。
 *
 * 優先順位: スコープページの一時展開(scopeExpanded) ＞ スコープページの自動レール(forceRail)
 * ＞ 個人ページの手動記憶(userCollapsed)。
 * （sidebar-prototype.html の isRail() 挙動と同一。マスター御裁可済みの仕様）
 */
export const useAppShellStore = defineStore('appShell', {
  state: (): AppShellState => ({
    userCollapsed: false,
    forceRail: false,
    scopeExpanded: false,
    mobileDrawerOpen: false,
  }),

  getters: {
    /**
     * レール（68px折りたたみ）表示にすべきか。
     * forceRail が false のときは scopeExpanded を無視する（個人ページに scopeExpanded の
     * 残留値が漏れて誤展開させないためのガード。setForceRail が出入りの都度リセットもする）。
     */
    isRail: (state): boolean => {
      if (state.forceRail) return !state.scopeExpanded
      return state.userCollapsed
    },
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

    /**
     * スコープページ（チーム/組織）からの自動レール要求。永続化しない一時状態。
     * true/false どちらのセットでも scopeExpanded を既定（false=自動レール）へ戻す
     * （「チーム画面へ入るたび自動収縮に戻す」「ルート遷移でリセット」仕様）。
     */
    setForceRail(force: boolean) {
      this.forceRail = force
      this.scopeExpanded = false
    },

    /** スコープページ上でのヘッダーパネルボタン操作。一時展開を反転する（記憶しない） */
    toggleScopeExpanded() {
      this.scopeExpanded = !this.scopeExpanded
    },

    setScopeExpanded(expanded: boolean) {
      this.scopeExpanded = expanded
    },

    /**
     * ヘッダーのパネルトグルボタンから呼ぶ統一エントリ。
     * forceRail 中（スコープページ）は一時展開の切替、それ以外は個人ページの手動記憶を切り替える。
     */
    togglePanel() {
      if (this.forceRail) {
        this.toggleScopeExpanded()
      } else {
        this.toggleUserCollapsed()
      }
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
