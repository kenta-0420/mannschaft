import dayjs from 'dayjs'
import { defineStore } from 'pinia'
import type {
  CreateLabelPayload,
  InboxBulkAction,
  InboxBulkItem,
  InboxItem,
  InboxLabel,
  InboxListParams,
  InboxPriority,
  InboxSourceType,
  InboxState,
  InboxStateFilter,
  SuggestedLabel,
  UpdateLabelPayload,
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
  // ─── Phase 2 追加 ───────────────────────────
  /** ユーザーのラベル一覧。 */
  labels: InboxLabel[]
  /** ラベル取得中フラグ。 */
  labelsLoading: boolean
  // bulk 選択モード
  /** bulk 選択モード ON/OFF。 */
  selectionMode: boolean
  /** 選択中のアイテムキー集合（"sourceType:sourceId" 形式）。 */
  selectedKeys: Set<string>
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
    // Phase 2
    labels: [],
    labelsLoading: false,
    selectionMode: false,
    selectedKeys: new Set<string>(),
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
     * groupCount > 1 のとき groupMembers 全員に bulkAction(SNOOZE) を適用。
     * 楽観更新: 即 state を SNOOZED に書換え → API 失敗でロールバック。
     */
    async snooze(
      sourceType: InboxSourceType,
      sourceId: number,
      snoozedUntil: string,
    ): Promise<boolean> {
      const previous = this.items.slice()
      const item = this._findItem(sourceType, sourceId)
      const isGroup = (item?.groupCount ?? 1) > 1

      if (isGroup && item?.groupMembers && item.groupMembers.length > 0) {
        // グループカード: 楽観的に代表カードを除去 → bulkAction で全メンバーへ適用
        this._removeItem(sourceType, sourceId)
        try {
          const api = useInboxApi()
          await api.bulkAction({
            action: 'SNOOZE',
            items: item.groupMembers as InboxBulkItem[],
            snoozedUntil,
          })
          return true
        } catch (error) {
          this.items = previous
          this._handleError(error)
          return false
        }
      }

      // 単独アイテム: 従来の単一 triage
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
     * groupCount > 1 のとき groupMembers 全員を個別 api.unsnooze で解除する。
     * bulk に UNSNOOZE action が未定義のため個別 API を Promise.allSettled で並列実行し、
     * 全結果（成功・失敗問わず）が揃った後に必ず fetchInbox で実サーバ状態へ再同期する。
     * これにより部分失敗時の誤表示（楽観ロールバック後の状態ズレ）を防ぐ。
     * 単独アイテムは従来どおり単一 triage。
     */
    async unsnooze(sourceType: InboxSourceType, sourceId: number): Promise<boolean> {
      const previous = this.items.slice()
      const item = this._findItem(sourceType, sourceId)
      const isGroup = (item?.groupCount ?? 1) > 1

      if (isGroup && item?.groupMembers && item.groupMembers.length > 0) {
        // グループカード: 楽観的に代表カードを除去 → 全メンバーへ個別 unsnooze を並列実行
        this._removeItem(sourceType, sourceId)
        try {
          // Promise.allSettled で部分失敗を許容しながら全メンバーへ unsnooze を実行
          const api = useInboxApi()
          const results = await Promise.allSettled(
            item.groupMembers.map((m) => api.unsnooze(m.sourceType as InboxSourceType, m.sourceId)),
          )
          // 全件失敗の場合はロールバックして再同期
          const allFailed = results.every((r) => r.status === 'rejected')
          if (allFailed) {
            this.items = previous
            this._handleError(results[0] && results[0].status === 'rejected' ? results[0].reason : new Error('unsnooze failed'))
          }
          // 成功・部分成功問わず実サーバ状態へ再同期（誤表示防止）
          await this.fetchInbox()
          return !allFailed
        } catch (error) {
          // Promise.allSettled 自体は reject しないため、ここは fetchInbox の失敗等
          this.items = previous
          this._handleError(error)
          await this.fetchInbox()
          return false
        }
      }

      // 単独アイテム: 従来の単一 triage
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
     * groupCount > 1 のとき groupMembers 全員に bulkAction(ARCHIVE) を適用。
     * 楽観更新: 即 state を ARCHIVED に書換え → API 失敗でロールバック。
     */
    async archive(sourceType: InboxSourceType, sourceId: number): Promise<boolean> {
      const previous = this.items.slice()
      const item = this._findItem(sourceType, sourceId)
      const isGroup = (item?.groupCount ?? 1) > 1

      if (isGroup && item?.groupMembers && item.groupMembers.length > 0) {
        // グループカード: 楽観的に代表カードを除去 → bulkAction で全メンバーへ適用
        this._removeItem(sourceType, sourceId)
        try {
          const api = useInboxApi()
          await api.bulkAction({
            action: 'ARCHIVE',
            items: item.groupMembers as InboxBulkItem[],
          })
          return true
        } catch (error) {
          this.items = previous
          this._handleError(error)
          return false
        }
      }

      // 単独アイテム: 従来の単一 triage
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
     * groupCount > 1 のとき groupMembers 全員に bulkAction(UNARCHIVE) を適用。
     * 楽観更新: 即 state を UNREAD に書換え → API 失敗でロールバック。
     */
    async unarchive(sourceType: InboxSourceType, sourceId: number): Promise<boolean> {
      const previous = this.items.slice()
      const item = this._findItem(sourceType, sourceId)
      const isGroup = (item?.groupCount ?? 1) > 1

      if (isGroup && item?.groupMembers && item.groupMembers.length > 0) {
        // グループカード: 楽観的に代表カードを除去 → bulkAction で全メンバーへ適用
        this._removeItem(sourceType, sourceId)
        try {
          const api = useInboxApi()
          await api.bulkAction({
            action: 'UNARCHIVE',
            items: item.groupMembers as InboxBulkItem[],
          })
          return true
        } catch (error) {
          this.items = previous
          this._handleError(error)
          return false
        }
      }

      // 単独アイテム: 従来の単一 triage
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

    /**
     * ラベル絞り込みを設定して一覧を再取得する。
     */
    async setLabelFilter(labelId: string | null): Promise<void> {
      this.labelFilter = labelId
      await this.fetchInbox()
    },

    // ─────────────────────────────────────────────
    // Phase 2: ラベル CRUD
    // ─────────────────────────────────────────────

    /**
     * ラベル一覧を取得する。
     */
    async fetchLabels(): Promise<void> {
      this.labelsLoading = true
      try {
        const api = useInboxApi()
        const res = await api.getLabels()
        this.labels = res.data
      } catch (error) {
        this._handleError(error)
      } finally {
        this.labelsLoading = false
      }
    },

    /**
     * ラベルを作成する。
     * エラーは握り潰さず呼び出し元へ再throwする（409=重複・422=上限超過を呼び出し元で処理するため）。
     */
    async createLabel(payload: CreateLabelPayload): Promise<InboxLabel> {
      const api = useInboxApi()
      const res = await api.createLabel(payload)
      this.labels.push(res.data)
      return res.data
    },

    /**
     * ラベルを更新する（楽観更新）。
     * エラーは握り潰さず呼び出し元へ再throwする（409=重複を呼び出し元で処理するため）。
     * 失敗時はロールバックしてから再throw。
     */
    async updateLabel(labelId: string, payload: UpdateLabelPayload): Promise<void> {
      const idx = this.labels.findIndex((l) => l.id === labelId)
      const previous = idx >= 0 ? { ...this.labels[idx]! } : null
      if (idx >= 0 && previous) {
        // 楽観更新
        this.labels.splice(idx, 1, {
          ...this.labels[idx]!,
          ...payload,
        })
      }
      try {
        const api = useInboxApi()
        const res = await api.updateLabel(labelId, payload)
        if (idx >= 0) {
          this.labels.splice(idx, 1, res.data)
        }
      } catch (error) {
        // ロールバック後に再throw（呼び出し元で409/422を処理する）
        if (idx >= 0 && previous) {
          this.labels.splice(idx, 1, previous)
        }
        throw error
      }
    },

    /**
     * ラベルを削除する（楽観削除）。
     * エラーは握り潰さず呼び出し元へ再throwする（404等を呼び出し元で処理するため）。
     * 失敗時はロールバックしてから再throw。
     */
    async deleteLabel(labelId: string): Promise<void> {
      const idx = this.labels.findIndex((l) => l.id === labelId)
      const removed = idx >= 0 ? this.labels[idx] : null
      if (idx >= 0) {
        // 楽観削除
        this.labels.splice(idx, 1)
      }
      try {
        const api = useInboxApi()
        await api.deleteLabel(labelId)
      } catch (error) {
        // ロールバック後に再throw
        if (idx >= 0 && removed) {
          this.labels.splice(idx, 0, removed)
        }
        throw error
      }
    },

    // ─────────────────────────────────────────────
    // Phase 2: ラベル付与 / 解除（楽観更新）
    // ─────────────────────────────────────────────

    /**
     * アイテムにラベルを付与する（楽観更新＋ロールバック）。
     */
    async assignLabel(
      sourceType: InboxSourceType,
      sourceId: number,
      labelId: string,
    ): Promise<boolean> {
      const id = `${sourceType}:${sourceId}`
      const idx = this.items.findIndex((item) => item.id === id)
      const previous = this.items.slice()
      const label = this.labels.find((l) => l.id === labelId)

      if (idx >= 0 && label) {
        const item = this.items[idx]!
        if (!item.labels.some((l) => l.id === labelId)) {
          // 楽観追加
          this.items.splice(idx, 1, {
            ...item,
            labels: [...item.labels, label],
          })
        }
      }

      try {
        const api = useInboxApi()
        await api.assignLabel(labelId, sourceType, sourceId)
        return true
      } catch (error) {
        // ロールバック
        this.items = previous
        this._handleError(error)
        return false
      }
    },

    /**
     * アイテムからラベルを解除する（楽観更新＋ロールバック）。
     */
    async unassignLabel(
      sourceType: InboxSourceType,
      sourceId: number,
      labelId: string,
    ): Promise<boolean> {
      const id = `${sourceType}:${sourceId}`
      const idx = this.items.findIndex((item) => item.id === id)
      const previous = this.items.slice()

      if (idx >= 0) {
        const item = this.items[idx]!
        // 楽観削除
        this.items.splice(idx, 1, {
          ...item,
          labels: item.labels.filter((l) => l.id !== labelId),
        })
      }

      try {
        const api = useInboxApi()
        await api.unassignLabel(labelId, sourceType, sourceId)
        return true
      } catch (error) {
        // ロールバック
        this.items = previous
        this._handleError(error)
        return false
      }
    },

    // ─────────────────────────────────────────────
    // Phase 3 (wave3b): 自動ラベリング提案付与
    // ─────────────────────────────────────────────

    /**
     * 提案チップをタップしてラベルを付与する（楽観更新＋ロールバック）。
     *
     * 楽観更新:
     *   1. `suggestedLabels` から該当提案を除去
     *   2. `labels` に仮ラベル（id='') を追加
     *   API 成功: BE 返却 LabelDto で id を確定上書き
     *   API 失敗: ロールバック → _handleError でトースト（呼び出し元が showError を呼ぶ）
     *
     * @param sourceType アイテムのソース種別
     * @param sourceId   アイテムのソース ID
     * @param suggestion 提案オブジェクト（color / suggestionKey）
     * @param labelName  i18n 解決済みの表示名（FE が送る）
     * @returns 成功時 true・失敗時 false
     */
    async suggestApply(
      sourceType: InboxSourceType,
      sourceId: number,
      suggestion: SuggestedLabel,
      labelName: string,
    ): Promise<boolean> {
      const id = `${sourceType}:${sourceId}`
      const idx = this.items.findIndex((item) => item.id === id)
      const previous = this.items.slice()

      if (idx >= 0) {
        const item = this.items[idx]!
        const optimisticLabel: InboxLabel = {
          id: '', // API 成功後に確定
          name: labelName,
          color: suggestion.color,
          icon: null,
          sortOrder: 999,
        }
        // 楽観更新: 提案を除去・仮ラベルを追加
        this.items.splice(idx, 1, {
          ...item,
          labels: [...item.labels, optimisticLabel],
          suggestedLabels: (item.suggestedLabels ?? []).filter(
            (s) => s.suggestionKey !== suggestion.suggestionKey,
          ),
        })
      }

      try {
        const api = useInboxApi()
        const returnedLabel = await api.suggestApply(sourceType, sourceId, labelName, suggestion.color)
        // API 成功: 仮ラベルの id を確定値で上書き
        if (idx >= 0) {
          const item = this.items[idx]!
          this.items.splice(idx, 1, {
            ...item,
            labels: item.labels.map((l) =>
              l.id === '' && l.name === labelName ? returnedLabel : l,
            ),
          })
          // ストアのラベルマスターにも追加（重複なしで）
          if (!this.labels.some((l) => l.id === returnedLabel.id)) {
            this.labels.push(returnedLabel)
          }
        }
        return true
      } catch (error) {
        // ロールバック
        this.items = previous
        this._handleError(error)
        return false
      }
    },

    // ─────────────────────────────────────────────
    // Phase 2: bulk 選択モード
    // ─────────────────────────────────────────────

    /**
     * bulk 選択モードのトグル。モード終了時は選択をクリア。
     */
    toggleSelectionMode(): void {
      this.selectionMode = !this.selectionMode
      if (!this.selectionMode) {
        this.selectedKeys = new Set<string>()
      }
    },

    /**
     * アイテムの選択状態をトグルする（"sourceType:sourceId" キー）。
     */
    toggleSelect(key: string): void {
      const next = new Set(this.selectedKeys)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      this.selectedKeys = next
    },

    /**
     * 全選択をクリアする。
     */
    clearSelection(): void {
      this.selectedKeys = new Set<string>()
    },

    /**
     * 選択アイテムに対して一括操作を実行する。
     * 成功時: { processed, skipped } を返し選択をクリア・一覧再取得。
     * 失敗時: null を返す。
     */
    async runBulk(
      action: InboxBulkAction,
      options?: { snoozedUntil?: string; labelId?: string },
    ): Promise<{ processed: number; skipped: number } | null> {
      if (this.selectedKeys.size === 0) return null

      const items: InboxBulkItem[] = []
      for (const key of this.selectedKeys) {
        const colonIdx = key.indexOf(':')
        if (colonIdx > 0) {
          const sourceType = key.slice(0, colonIdx) as InboxSourceType
          const sourceId = Number(key.slice(colonIdx + 1))
          items.push({ sourceType, sourceId })
        }
      }

      try {
        const api = useInboxApi()
        const res = await api.bulkAction({
          action,
          items,
          snoozedUntil: options?.snoozedUntil,
          labelId: options?.labelId,
        })
        this.clearSelection()
        // 操作後に一覧を再取得して状態を正規化
        await this.fetchInbox()
        return res.data
      } catch (error) {
        this._handleError(error)
        return null
      }
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

    /**
     * items 配列から指定アイテムを検索して返す（見つからない場合は undefined）。
     */
    _findItem(sourceType: InboxSourceType, sourceId: number): InboxItem | undefined {
      const id = `${sourceType}:${sourceId}`
      return this.items.find((item) => item.id === id)
    },

    /**
     * items 配列から指定アイテムを除去する（楽観的グループ除去用）。
     */
    _removeItem(sourceType: InboxSourceType, sourceId: number): void {
      const id = `${sourceType}:${sourceId}`
      const idx = this.items.findIndex((item) => item.id === id)
      if (idx >= 0) {
        this.items.splice(idx, 1)
      }
    },

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
export type { InboxItem, InboxLabel, InboxBulkAction, InboxBulkItem, SuggestedLabel }
