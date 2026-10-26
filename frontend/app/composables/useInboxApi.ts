import type {
  CreateLabelPayload,
  InboxBulkPayload,
  InboxBulkResponse,
  InboxLabel,
  InboxLabelListResponse,
  InboxLabelResponse,
  InboxListParams,
  InboxListResponse,
  InboxPriority,
  InboxSourceType,
  InboxSuggestionKey,
  InboxSummary,
  InboxTriageResponse,
  UpdateLabelPayload,
} from '~/types/inbox'

/**
 * F04.11 統合通知インボックス API composable。
 *
 * 手本: useNotificationApi.ts（useApi() + buildQuery パターン）。
 * priority[] / sourceType[] は配列クエリに対応（URLSearchParams.append ループ）。
 * 設計書: docs/features/F04.11_notification_inbox/02_api_design.md §3
 */
export function useInboxApi() {
  const api = useApi()

  /**
   * クエリ文字列を構築する。
   * 配列型（priority / sourceType）は複数の同名パラメータとして append する。
   * スカラー値は undefined / null をスキップして set する。
   */
  function buildQuery(params: InboxListParams): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value === undefined || value === null) continue
      if (Array.isArray(value)) {
        for (const item of value) {
          query.append(key, String(item))
        }
      } else {
        query.set(key, String(value))
      }
    }
    return query.toString()
  }

  /**
   * インボックス一覧を取得する。
   * state 省略時は BE デフォルト（INBOX）が適用される。
   */
  async function getInbox(params: InboxListParams = {}): Promise<InboxListResponse> {
    const qs = buildQuery(params)
    return api<InboxListResponse>(`/api/v1/inbox?${qs}`)
  }

  /**
   * 状態別・緊急度別・種類別の件数サマリを取得する（タブ/バッジ用）。
   */
  async function getSummary(): Promise<InboxSummary> {
    return api<InboxSummary>('/api/v1/inbox/summary')
  }

  /**
   * 指定アイテムをスヌーズする。
   * snoozedUntil は ISO-8601 文字列（フロント側でプリセット日時を計算して渡す）。
   */
  async function snooze(
    sourceType: InboxSourceType,
    sourceId: number,
    snoozedUntil: string,
  ): Promise<InboxTriageResponse> {
    return api<InboxTriageResponse>('/api/v1/inbox/snooze', {
      method: 'POST',
      body: { sourceType, sourceId, snoozedUntil },
    })
  }

  /**
   * スヌーズを解除して受信箱へ戻す。
   */
  async function unsnooze(
    sourceType: InboxSourceType,
    sourceId: number,
  ): Promise<InboxTriageResponse> {
    return api<InboxTriageResponse>('/api/v1/inbox/unsnooze', {
      method: 'POST',
      body: { sourceType, sourceId },
    })
  }

  /**
   * アイテムを保管庫へ退避する。
   */
  async function archive(
    sourceType: InboxSourceType,
    sourceId: number,
  ): Promise<InboxTriageResponse> {
    return api<InboxTriageResponse>('/api/v1/inbox/archive', {
      method: 'POST',
      body: { sourceType, sourceId },
    })
  }

  /**
   * 保管庫から受信箱へ戻す。
   */
  async function unarchive(
    sourceType: InboxSourceType,
    sourceId: number,
  ): Promise<InboxTriageResponse> {
    return api<InboxTriageResponse>('/api/v1/inbox/unarchive', {
      method: 'POST',
      body: { sourceType, sourceId },
    })
  }

  // ─────────────────────────────────────────────
  // Phase 2: ラベル CRUD
  // ─────────────────────────────────────────────

  /**
   * ユーザーのラベル一覧を取得する。
   */
  async function getLabels(): Promise<InboxLabelListResponse> {
    return api<InboxLabelListResponse>('/api/v1/inbox/labels')
  }

  /**
   * ラベルを作成する（201）。
   */
  async function createLabel(payload: CreateLabelPayload): Promise<InboxLabelResponse> {
    return api<InboxLabelResponse>('/api/v1/inbox/labels', {
      method: 'POST',
      body: payload,
    })
  }

  /**
   * ラベルを更新する。
   */
  async function updateLabel(labelId: string, payload: UpdateLabelPayload): Promise<InboxLabelResponse> {
    return api<InboxLabelResponse>(`/api/v1/inbox/labels/${labelId}`, {
      method: 'PUT',
      body: payload,
    })
  }

  /**
   * ラベルを削除する（204）。
   */
  async function deleteLabel(labelId: string): Promise<void> {
    await api<unknown>(`/api/v1/inbox/labels/${labelId}`, {
      method: 'DELETE',
    })
  }

  /**
   * アイテムにラベルを付与する。
   */
  async function assignLabel(
    labelId: string,
    sourceType: InboxSourceType,
    sourceId: number,
  ): Promise<InboxLabel> {
    return api<InboxLabel>(`/api/v1/inbox/labels/${labelId}/assign`, {
      method: 'POST',
      body: { sourceType, sourceId },
    })
  }

  /**
   * アイテムからラベルを解除する。
   */
  async function unassignLabel(
    labelId: string,
    sourceType: InboxSourceType,
    sourceId: number,
  ): Promise<void> {
    await api<unknown>(`/api/v1/inbox/labels/${labelId}/assign`, {
      method: 'DELETE',
      body: { sourceType, sourceId },
    })
  }

  // ─────────────────────────────────────────────
  // Phase 2: bulk 操作
  // ─────────────────────────────────────────────

  /**
   * 複数アイテムに対して一括操作を行う。
   */
  async function bulkAction(payload: InboxBulkPayload): Promise<InboxBulkResponse> {
    return api<InboxBulkResponse>('/api/v1/inbox/bulk', {
      method: 'POST',
      body: payload,
    })
  }

  // ─────────────────────────────────────────────
  // Phase 3 (wave3b): 自動ラベリング提案付与
  // ─────────────────────────────────────────────

  /**
   * 提案ラベルを find-or-create しアイテムに付与する（冪等・200 で LabelDto を返す）。
   * name は FE が i18n 解決した表示名を送る（BE は表示名を持たない）。
   * 上限超過時は 4xx（INBOX_LABEL_LIMIT_EXCEEDED / INBOX_LABEL_PER_ITEM_EXCEEDED）が返る。
   */
  async function suggestApply(
    sourceType: InboxSourceType,
    sourceId: number,
    name: string,
    color: string,
  ): Promise<InboxLabel> {
    return api<InboxLabel>('/api/v1/inbox/labels/suggest-apply', {
      method: 'POST',
      body: { name, color, sourceType, sourceId },
    })
  }

  return {
    getInbox,
    getSummary,
    snooze,
    unsnooze,
    archive,
    unarchive,
    getLabels,
    createLabel,
    updateLabel,
    deleteLabel,
    assignLabel,
    unassignLabel,
    bulkAction,
    suggestApply,
  }
}

/** priority 文字列を i18n キーに変換するヘルパー。 */
export function priorityI18nKey(priority: InboxPriority): string {
  const map: Record<InboxPriority, string> = {
    URGENT: 'inbox.priority.urgent',
    HIGH: 'inbox.priority.high',
    NORMAL: 'inbox.priority.normal',
    LOW: 'inbox.priority.low',
  }
  return map[priority]
}

/** sourceType 文字列を i18n キーに変換するヘルパー。 */
export function sourceTypeI18nKey(sourceType: InboxSourceType): string {
  const map: Record<InboxSourceType, string> = {
    NOTIFICATION: 'inbox.source.notification',
    ANNOUNCEMENT: 'inbox.source.announcement',
    MENTION: 'inbox.source.mention',
    CONFIRMABLE: 'inbox.source.confirmable',
    TODO_DUE: 'inbox.source.todoDue',
  }
  return map[sourceType]
}

/** sourceType に対応する PrimeIcons クラスを返すヘルパー。 */
export function sourceTypeIcon(sourceType: InboxSourceType): string {
  const map: Record<InboxSourceType, string> = {
    NOTIFICATION: 'pi pi-bell',
    ANNOUNCEMENT: 'pi pi-megaphone',
    MENTION: 'pi pi-at',
    CONFIRMABLE: 'pi pi-check-circle',
    TODO_DUE: 'pi pi-clock',
  }
  return map[sourceType]
}

/** priority に対応する severity / CSS クラスを返すヘルパー。 */
export function prioritySeverity(priority: InboxPriority): string {
  const map: Record<InboxPriority, string> = {
    URGENT: 'danger',
    HIGH: 'warn',
    NORMAL: 'info',
    LOW: 'secondary',
  }
  return map[priority]
}

/**
 * suggestionKey (UPPER_SNAKE) を i18n キー (camel) に変換するヘルパー。
 * 例: 'REPLY_NEEDED' → 'inbox.suggestion.replyNeeded'
 *
 * BE の SuggestionKey 列挙値と i18n キーの対応は**ここ1箇所**で管理する（直書き禁止）。
 */
export function suggestionKeyI18nKey(key: InboxSuggestionKey): string {
  const map: Record<InboxSuggestionKey, string> = {
    REPLY_NEEDED: 'inbox.suggestion.replyNeeded',
    ACTION_NEEDED: 'inbox.suggestion.actionNeeded',
    URGENT: 'inbox.suggestion.urgent',
    READ_LATER: 'inbox.suggestion.readLater',
  }
  return map[key]
}
