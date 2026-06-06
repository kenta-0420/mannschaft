/**
 * F22.1 横スワイプ・スコープダッシュボード — Pinia ストア
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.4
 * 手本: frontend/app/stores/useNavSettingsStore.ts（localStorage 楽観更新 + サーバー同期パターン）
 */
import { defineStore } from 'pinia'
import type { ScopeTabType, ScopeTabPage } from '~/types/dashboard-scope'

/** アクティブパネルの型 */
export type ActivePanel = 'PERSONAL' | 'TEAM' | 'ORGANIZATION'

/** 表示順エントリ */
export interface TabOrderEntry {
  scopeId: string
  sortOrder: number
}

/** localStorage に永続化するステートのシリアライズ形式 */
interface PersistedState {
  activePanel: ActivePanel
  teamTabPage: number
  orgTabPage: number
  activeFolderId: number | null
  selectedTeamId: string | null
  selectedOrgId: string | null
  tabOrders: Record<ScopeTabType, TabOrderEntry[]>
}

const STORAGE_KEY = 'scope-dashboard'

/** デフォルト表示順（空配列 = サーバー順に従う） */
const defaultTabOrders = (): Record<ScopeTabType, TabOrderEntry[]> => ({
  TEAM: [],
  ORGANIZATION: [],
})

export const useScopeDashboardStore = defineStore('scopeDashboard', {
  state: () => ({
    /** アクティブパネル（個人/チーム/組織）*/
    activePanel: 'PERSONAL' as ActivePanel,
    /** チームタグのページ番号（0 始まり）*/
    teamTabPage: 0,
    /** 組織タグのページ番号（0 始まり）*/
    orgTabPage: 0,
    /** フォルダフィルタ（F15.3 フォルダ ID / null = すべて。my_scope_folders.id は数値）*/
    activeFolderId: null as number | null,
    /** チームパネルで選択中のチーム ID（UUID string）*/
    selectedTeamId: null as string | null,
    /** 組織パネルで選択中の組織 ID（UUID string）*/
    selectedOrgId: null as string | null,
    /** 表示順（楽観更新用。PUT /order 成功まで先行反映）*/
    tabOrders: defaultTabOrders() as Record<ScopeTabType, TabOrderEntry[]>,
    /** タグページデータキャッシュ（scopeType → ScopeTabPage）*/
    tabPages: {} as Partial<Record<ScopeTabType, ScopeTabPage>>,
    /** 初期ロード完了フラグ */
    loaded: false,
    /**
     * 直近のエラー i18n キー（null = エラーなし）。
     * UI（Wave 3 のコンポーネント）が `$t(lastError)` で表示する。
     * store 層では生文言を持たない（i18n 直書き禁止・UI 層で翻訳）。
     */
    lastError: null as string | null,
  }),

  actions: {
    /**
     * localStorage から復元する（同期・即時）。
     * プラグインの起動直後に呼ぶことでチラつきを防ぐ。
     */
    loadFromStorage() {
      if (!import.meta.client) return
      try {
        const saved = localStorage.getItem(STORAGE_KEY)
        if (saved) {
          const parsed = JSON.parse(saved) as Partial<PersistedState>
          if (parsed.activePanel) this.activePanel = parsed.activePanel
          if (parsed.teamTabPage !== undefined) this.teamTabPage = parsed.teamTabPage
          if (parsed.orgTabPage !== undefined) this.orgTabPage = parsed.orgTabPage
          if (parsed.activeFolderId !== undefined) this.activeFolderId = parsed.activeFolderId
          if (parsed.selectedTeamId !== undefined) this.selectedTeamId = parsed.selectedTeamId
          if (parsed.selectedOrgId !== undefined) this.selectedOrgId = parsed.selectedOrgId
          if (parsed.tabOrders) this.tabOrders = parsed.tabOrders
        }
      } catch {
        // localStorage 読み取り失敗は無視（デフォルト値で継続）
      }
    },

    /**
     * 現在のステートを localStorage に保存する。
     * 変更のたびに呼ぶ。
     */
    persistToStorage() {
      if (!import.meta.client) return
      const toSave: PersistedState = {
        activePanel: this.activePanel,
        teamTabPage: this.teamTabPage,
        orgTabPage: this.orgTabPage,
        activeFolderId: this.activeFolderId,
        selectedTeamId: this.selectedTeamId,
        selectedOrgId: this.selectedOrgId,
        tabOrders: this.tabOrders,
      }
      localStorage.setItem(STORAGE_KEY, JSON.stringify(toSave))
    },

    /**
     * サーバーからタグ一覧を取得してキャッシュに保存する。
     * 認証済みのバックグラウンド同期に使用する。
     *
     * @param scopeType - TEAM / ORGANIZATION
     * @param page - 0 始まりのページ番号
     */
    async loadTabs(scopeType: ScopeTabType, page = 0) {
      try {
        const { getScopeTabs } = useScopeTabApi()
        const result = await getScopeTabs(scopeType, page, this.activeFolderId ?? undefined)
        this.tabPages[scopeType] = result
        this.lastError = null

        // 先頭スコープ（存在すれば）。undefined ガードで noUncheckedIndexedAccess に適合。
        const first = result.items[0]

        // 先頭スコープを自動選択（未選択の場合のみ）
        if (first) {
          if (scopeType === 'TEAM' && this.selectedTeamId === null) {
            this.selectedTeamId = first.scopeId
          } else if (scopeType === 'ORGANIZATION' && this.selectedOrgId === null) {
            this.selectedOrgId = first.scopeId
          }
        }

        // 選択中スコープが一覧から消えた場合は先頭へフォールバック（退会・権限喪失対応）
        if (scopeType === 'TEAM' && this.selectedTeamId !== null) {
          const exists = result.items.some(item => item.scopeId === this.selectedTeamId)
          if (!exists && first) {
            this.selectedTeamId = first.scopeId
            this.persistToStorage()
          }
        } else if (scopeType === 'ORGANIZATION' && this.selectedOrgId !== null) {
          const exists = result.items.some(item => item.scopeId === this.selectedOrgId)
          if (!exists && first) {
            this.selectedOrgId = first.scopeId
            this.persistToStorage()
          }
        }

        this.loaded = true
      } catch (e) {
        // エラーは握りつぶさない。ログを残し、i18n キーをエラー状態に保持して
        // （UI 層が $t で表示）localStorage の最後の状態で継続する。
        console.error('[scopeDashboard] loadTabs failed', e)
        this.lastError = 'scopeDashboard.tagBar.loadError'
        this.loaded = true
      }
    },

    /**
     * タグ表示順を楽観更新する。
     * PUT /scope-tabs/order を呼び、失敗時にロールバックする。
     *
     * @param scopeType - TEAM / ORGANIZATION
     * @param orders - 新しい表示順の配列
     */
    async reorder(scopeType: ScopeTabType, orders: TabOrderEntry[]) {
      // 楽観更新
      const prev = [...(this.tabOrders[scopeType] ?? [])]
      this.tabOrders[scopeType] = orders
      this.persistToStorage()

      try {
        const { updateOrder } = useScopeTabApi()
        await updateOrder({ scopeType, orders })
        this.lastError = null
      } catch (e) {
        // ロールバック。エラーは握りつぶさず、ログ + i18n キーを状態に保持（UI 層が $t 表示）。
        this.tabOrders[scopeType] = prev
        this.persistToStorage()
        console.error('[scopeDashboard] reorder failed', e)
        this.lastError = 'scopeDashboard.orderDialog.saveError'
      }
    },

    /**
     * アクティブパネルを変更して localStorage に保存する。
     *
     * @param panel - 'PERSONAL' | 'TEAM' | 'ORGANIZATION'
     */
    setActivePanel(panel: ActivePanel) {
      this.activePanel = panel
      this.persistToStorage()
    },

    /**
     * フォルダフィルタを変更してページを 0 にリセット後、タグを再取得する。
     * フォルダ切替でページが残ると空表示になるため必ずリセットする（設計書 §3.5）。
     *
     * @param folderId - フォルダ ID（null = すべて。my_scope_folders.id は数値）
     */
    async setFolder(folderId: number | null) {
      this.activeFolderId = folderId
      this.teamTabPage = 0
      this.orgTabPage = 0
      this.persistToStorage()
      // アクティブパネルに応じて再取得
      const scopeType: ScopeTabType = this.activePanel === 'ORGANIZATION' ? 'ORGANIZATION' : 'TEAM'
      await this.loadTabs(scopeType, 0)
    },
  },
})
