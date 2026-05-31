import dayjs from 'dayjs'
import { defineStore } from 'pinia'
import type {
  InboxItem,
  InboxLabel,
  InboxListParams,
  InboxPriority,
  InboxSourceType,
  InboxState,
  InboxStateFilter,
} from '~/types/inbox'

/**
 * F04.11 統合通知インボックス — Pinia ストア。
 *
 * 楽観更新＋失敗ロールバックパターン（useActionMemoStore の createMemo / deleteMemo を手本）。
 * snooze / archive は即 state を書換え → API 失敗で previous にロールバック。
 * TZ 補助は useAuthStore の timezone を借り dayjs().tz(timezone) でプリセット日時計算。
 *
 * 設計書: docs/features/F04.11_notification_inbox/02_api_design.md §3.3 / 04_security_operations.md
 */

export type InboxSnoozePreset = 'in3h' | 'tonight' | 'tomorrowMorning' | 'nextWeek'

interface InboxStoreState {
  /** 一覧（現在タブに対応したアイテム配列）。 */
  items: InboxItem[]
  /** 状態別件数サマリ。 */
  summaryByState: Record<string, number>
  /** 緊急度別件数サマリ。 */
  summaryByPriority: Record<string, number>
  /** 種類別件数サマリ。 */
  summaryBySourceType: Record<string, number>
  /** 現在表示しているタブ。 */
  currentTab: InboxStateFilter
  /** 緊急度フィルタ（複数選択可）。 */
  priorityFilter: InboxPriority[]
  /** 種類フィルタ（複数選択可）。 */
  sourceTypeFilter: InboxSourceType[]
  /** ラベル絞り込み（単一）。 */
  labelFilter: string | null
  /** 現在のページ番号（0始まり）。 */
  page: number
  /** 次ページがあるか。 */
  hasMore: boolean
  /** 概算総件数。 */
  totalEstimated: number
  /** 一覧取得中フラグ。 */
  loading: boolean
  /** サマリ取得中フラグ。 */
  summaryLoading: boolean
  /** 直近のエラー（表示用 i18n キー or メッセージ）。 */
  error: string | null
}

export const useInboxStore = defineStore('inbox', {
  state: (): InboxStoreState => ({
    items: [],
    summaryByState: {},
    summaryByPriority: {},
    summaryBySourceType: {},
    currentTab: 'INBOX',
    priorityFilter: [],
    sourceTypeFilter: [],
    labelFilter: null,
    page: 0,
    hasMore: false,
    totalEstimated: 0,
    loading: false,
    summaryLoading: false,
    error: null,
  }),

  getters: {
    /** 受信箱の件数（タブバッジ用）。 */
    inboxCount: (state): number => state.summaryByState['INBOX'] ?? 0,
    /** スヌーズ中の件数。 */
    snoozedCount: (state): number => state.summaryByState['SNOOZED'] ?? 0,
    /** 保管庫の件数。 */
    archivedCount: (state): number => state.summaryByState['ARCHIVED'] ?? 0,
  },

  actions: {
    // ─────────────────────────────────────────────
    // フェッチ系
    // ─────────────────────────────────────────────

    /**
     * 一覧を取得する（初回 or タブ切替時）。
     * page=0 でリセット。
     */
    async fetchInbox(params?: Partial<InboxListParams>): Promise<void> {
      this.loading = true
      this.error = null
      this.page = 0
      try {
        const api = useInboxApi()
        const res = await api.getInbox({
          state: this.currentTab,
          priority: this.priorityFilter.length > 0 ? this.priorityFilter : undefined,
          sourceType: this.sourceTypeFilter.length > 0 ? this.sourceTypeFilter : undefined,
          labelId: this.labelFilter ?? undefined,
          page: 0,
          size: 20,
          ...params,
        })
        this.items = res.data.items
        this.page = res.data.page
        this.hasMore = res.data.hasMore
        this.totalEstimated = res.data.totalEstimated
      } catch (error) {
        this._handleError(error)
      } finally {
        this.loading = false
      }
    },

    /**
     * 次ページを追記（「もっと読む」ボタン用）。
     */
    async fetchMore(): Promise<void> {
      if (!this.hasMore || this.loading) return
      this.loading = true
      this.error = null
      const nextPage = this.page + 1
      try {
        const api = useInboxApi()
        const res = await api.getInbox({
          state: this.currentTab,
          priority: this.priorityFilter.length > 0 ? this.priorityFilter : undefined,
          sourceType: this.sourceTypeFilter.length > 0 ? this.sourceTypeFilter : undefined,
          labelId: this.labelFilter ?? undefined,
          page: nextPage,
          size: 20,
        })
        this.items.push(...res.data.items)
        this.page = res.data.page
        this.hasMore = res.data.hasMore
        this.totalEstimated = res.data.totalEstimated
      } catch (error) {
        this._handleError(error)
      } finally {
        this.loading = false
      }
    },

    /**
     * 件数サマリを取得する（タブバッジ用）。
     */
    async fetchSummary(): Promise<void> {
      this.summaryLoading = true
      try {
        const api = useInboxApi()
        const res = await api.getSummary()
        this.summaryByState = res.data.byState
        this.summaryByPriority = res.data.byPriority
        this.summaryBySourceType = res.data.bySourceType
      } catch (error) {
        this._handleError(error)
      } finally {
        this.summaryLoading = false
      }
    },

    // ─────────────────────────────────────────────
    // triage 操作（楽観更新＋ロールバック）
    // ─────────────────────────────────────────────

    /**
     * スヌーズ。
     * 即アイテムの state を SNOOZED / snoozedUntil に書換え → API 失敗でロールバック。
     */
    async snooze(
      sourceType: InboxSourceType,
      sourceId: number,
      snoozedUntil: string,
    ): Promise<boolean> {
      const previous = this.items.slice()
      this._updateItemState(sourceType, sourceId, 'SNOOZED', snoozedUntil)
      try {
        const api = useInboxApi()
        const res = await api.snooze(sourceType, sourceId, snoozedUntil)
        // BE 返却値で上書き（正規化済みデータで確定）
        this._replaceItem(res.data)
        return true
      } catch (error) {
        this.items = previous
        this._handleError(error)
        return false
      }
    },

    /**
     * スヌーズ解除。
     */
    async unsnooze(sourceType: InboxSourceType, sourceId: number): Promise<boolean> {
      const previous = this.items.slice()
      this._updateItemState(sourceType, sourceId, 'UNREAD', null)
      try {
        const api = useInboxApi()
        const res = await api.unsnooze(sourceType, sourceId)
        this._replaceItem(res.data)
        return true
      } catch (error) {
        this.items = previous
        this._handleError(error)
        return false
      }
    },

    /**
     * アーカイブ。
     */
    async archive(sourceType: InboxSourceType, sourceId: number): Promise<boolean> {
      const previous = this.items.slice()
      this._updateItemState(sourceType, sourceId, 'ARCHIVED', null)
      try {
        const api = useInboxApi()
        const res = await api.archive(sourceType, sourceId)
        this._replaceItem(res.data)
        return true
      } catch (error) {
        this.items = previous
        this._handleError(error)
        return false
      }
    },

    /**
     * アーカイブ解除。
     */
    async unarchive(sourceType: InboxSourceType, sourceId: number): Promise<boolean> {
      const previous = this.items.slice()
      this._updateItemState(sourceType, sourceId, 'UNREAD', null)
      try {
        const api = useInboxApi()
        const res = await api.unarchive(sourceType, sourceId)
        this._replaceItem(res.data)
        return true
      } catch (error) {
        this.items = previous
        this._handleError(error)
        return false
      }
    },

    // ─────────────────────────────────────────────
    // フィルタ・タブ切替
    // ─────────────────────────────────────────────

    /**
     * タブを切替えて一覧を再取得する。
     */
    async switchTab(tab: InboxStateFilter): Promise<void> {
      if (this.currentTab === tab) return
      this.currentTab = tab
      await this.fetchInbox()
    },

    /**
     * 緊急度フィルタを設定して一覧を再取得する。
     */
    async setPriorityFilter(priorities: InboxPriority[]): Promise<void> {
      this.priorityFilter = priorities
      await this.fetchInbox()
    },

    /**
     * 種類フィルタを設定して一覧を再取得する。
     */
    async setSourceTypeFilter(sourceTypes: InboxSourceType[]): Promise<void> {
      this.sourceTypeFilter = sourceTypes
      await this.fetchInbox()
    },

    // ─────────────────────────────────────────────
    // スヌーズプリセット計算
    // ─────────────────────────────────────────────

    /**
     * スヌーズプリセットから ISO-8601 文字列（ユーザーTZ適用）を計算する。
     * useActionMemoStore.today() と同じパターンで authStore からタイムゾーンを取得する。
     */
    computeSnoozeUntil(preset: InboxSnoozePreset): string {
      const authStore = useAuthStore()
      const tz = authStore.user?.timezone ?? 'Asia/Tokyo'
      const now = dayjs().tz(tz)

      switch (preset) {
        case 'in3h':
          return now.add(3, 'hour').toISOString()
        case 'tonight': {
          const tonight = now.hour(21).minute(0).second(0).millisecond(0)
          // 21時を過ぎている場合は翌日21時
          return (now.hour() >= 21 ? tonight.add(1, 'day') : tonight).toISOString()
        }
        case 'tomorrowMorning':
          return now.add(1, 'day').hour(9).minute(0).second(0).millisecond(0).toISOString()
        case 'nextWeek': {
          // 翌月曜09:00
          const daysUntilMonday = (8 - now.day()) % 7 || 7
          return now
            .add(daysUntilMonday, 'day')
            .hour(9)
            .minute(0)
            .second(0)
            .millisecond(0)
            .toISOString()
        }
      }
    },

    // ─────────────────────────────────────────────
    // 内部ユーティリティ
    // ─────────────────────────────────────────────

    /** items 配列内のアイテムの state / snoozedUntil を更新する（楽観更新用）。 */
    _updateItemState(
      sourceType: InboxSourceType,
      sourceId: number,
      state: InboxState,
      snoozedUntil: string | null,
    ): void {
      const id = `${sourceType}:${sourceId}`
      const idx = this.items.findIndex((item) => item.id === id)
      if (idx >= 0) {
        const existing = this.items[idx]
        if (existing) {
          this.items.splice(idx, 1, { ...existing, state, snoozedUntil })
        }
      }
    },

    /** BE 返却のアイテムで items 配列内を上書きする。 */
    _replaceItem(updated: InboxItem): void {
      const idx = this.items.findIndex((item) => item.id === updated.id)
      if (idx >= 0) {
        this.items.splice(idx, 1, updated)
      }
    },

    /**
     * fetch 系エラーを state.error に設定する。
     * エラーメッセージは汎用 i18n キーを使用。
     */
    _handleError(error: unknown): void {
      const err = error as {
        status?: number
        response?: { status?: number }
      }
      const status = err?.status ?? err?.response?.status
      if (status === 401 || status === 403) {
        this.error = 'common.error.unauthorized'
      } else if (status === 404) {
        this.error = 'common.error.notFound'
      } else {
        this.error = 'common.error.unknown'
      }
    },
  },
})

/** 型再エクスポート（呼び出し側の便宜のため） */
export type { InboxItem, InboxLabel }
