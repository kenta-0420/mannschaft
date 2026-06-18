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
  /**
   * F10.1.1 L1 管理者レンズの ON/OFF（スコープ単位）。
   * key = `${ScopeTabType}:${slug}`（例 `TEAM:dev-team`）, value = 管理者レンズ ON。
   * 設計書 02 §1.2。PII でも DB データでもないため localStorage 同梱のみ（DB 保存しない）。
   */
  adminLens: Record<string, boolean>
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
    /**
     * F10.1.1 L1 管理者レンズの ON/OFF（スコープ単位・設計書 02 §1.2）。
     * key = `${ScopeTabType}:${slug}`, value = 管理者レンズ ON。既定は空（=メンバーレンズ）。
     */
    adminLens: {} as Record<string, boolean>,
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
          if (parsed.adminLens) this.adminLens = parsed.adminLens
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
        adminLens: this.adminLens,
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

        // 選択中スコープの BIGINT→slug マイグレーション + フォールバック処理。
        // scopeId（BIGINT 文字列）と slug（スラッグ）の両方でマッチングを行い、
        // UUID が取得できた場合は localStorage も含めてアップグレードする。
        if (scopeType === 'TEAM') {
          if (this.selectedTeamId === null) {
            // 未選択 → 先頭を選択
            if (first) {
              this.selectedTeamId = first.slug ?? first.scopeId
              this.persistToStorage()
            }
          } else {
            // BIGINT または UUID どちらでもマッチするアイテムを探す
            const matchingItem = result.items.find(
              item => item.scopeId === this.selectedTeamId || item.slug === this.selectedTeamId,
            )
            if (matchingItem) {
              const uuid = matchingItem.slug ?? matchingItem.scopeId
              if (uuid !== this.selectedTeamId) {
                // BIGINT → UUID アップグレード（localStorage にも保存）
                this.selectedTeamId = uuid
                this.persistToStorage()
              }
            } else if (first) {
              // 一覧から消えた（退会・権限喪失）→ 先頭にフォールバック
              this.selectedTeamId = first.slug ?? first.scopeId
              this.persistToStorage()
            }
          }
        } else if (scopeType === 'ORGANIZATION') {
          if (this.selectedOrgId === null) {
            if (first) {
              this.selectedOrgId = first.slug ?? first.scopeId
              this.persistToStorage()
            }
          } else {
            const matchingItem = result.items.find(
              item => item.scopeId === this.selectedOrgId || item.slug === this.selectedOrgId,
            )
            if (matchingItem) {
              const uuid = matchingItem.slug ?? matchingItem.scopeId
              if (uuid !== this.selectedOrgId) {
                this.selectedOrgId = uuid
                this.persistToStorage()
              }
            } else if (first) {
              this.selectedOrgId = first.slug ?? first.scopeId
              this.persistToStorage()
            }
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
     * 管理者レンズの scopeKey を生成する（設計書 02 §1.2）。
     * `${scopeType}:${slug}`（例 `TEAM:dev-team` / `ORGANIZATION:acme`）。
     * slug はスコープ内一意かつ URL 識別子の正準のため、数値 ID を持ち出さない。
     *
     * @param scopeType - TEAM / ORGANIZATION
     * @param slug - スコープの slug
     */
    adminLensKey(scopeType: ScopeTabType, slug: string): string {
      return `${scopeType}:${slug}`
    },

    /**
     * 管理者レンズの ON/OFF を設定して localStorage に保存する。
     *
     * @param scopeType - TEAM / ORGANIZATION
     * @param slug - スコープの slug
     * @param on - 管理者レンズ ON（true）/ メンバーレンズ（false）
     */
    setAdminLens(scopeType: ScopeTabType, slug: string, on: boolean) {
      this.adminLens[this.adminLensKey(scopeType, slug)] = on
      this.persistToStorage()
    },

    /**
     * 管理者レンズが ON かどうかを返す（既定 false = メンバーレンズ）。
     *
     * @param scopeType - TEAM / ORGANIZATION
     * @param slug - スコープの slug
     */
    isAdminLensOn(scopeType: ScopeTabType, slug: string): boolean {
      return this.adminLens[this.adminLensKey(scopeType, slug)] ?? false
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
