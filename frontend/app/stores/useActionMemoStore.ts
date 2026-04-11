import { defineStore } from 'pinia'
import type {
  ActionMemo,
  ActionMemoSettings,
  ActionMemoTag,
  CreateActionMemoPayload,
  CreateTagPayload,
  Mood,
  MoodStatsResponse,
  PublishDailyPayload,
  PublishDailyResponse,
  UpdateActionMemoPayload,
  UpdateTagPayload,
  WeeklySummary,
} from '~/types/actionMemo'

/**
 * F02.5 行動メモ Pinia ストア。
 *
 * <p>UX 上の核となる原則は次の通り（設計書 §1, §11）:</p>
 * <ul>
 *   <li>楽観的 UI: 送信前にローカルに追加し、API 失敗時のみロールバック</li>
 *   <li>下書き自動保存: 入力中の content を {@code localStorage} に保存し、送信成功でクリア</li>
 *   <li>mood は設定 OFF のユーザーに対しては server 側で silent に NULL 化される</li>
 * </ul>
 */

interface ActionMemoStoreState {
  /** 表示中のメモ一覧（通常は当日分）。新しいものが配列の先頭 */
  memos: ActionMemo[]
  settings: ActionMemoSettings
  /** Phase 4: ユーザー所有のタグ一覧 */
  tags: ActionMemoTag[]
  /** Phase 4: 気分集計データ */
  moodStats: MoodStatsResponse | null
  loading: boolean
  /** 直近のエラー（429 / 400 等）。i18n キーまたはメッセージ */
  error: string | null
  /**
   * lastError は機械可読な分類用。
   * - "RATE_LIMIT" / "DAILY_LIMIT" / "FUTURE_DATE" / "TODO_NOT_FOUND" / "UNKNOWN" のいずれか
   */
  lastError: ActionMemoErrorCode | null
  /** オフラインキュー件数（UI バナー表示用）。online イベントや手動 flush で変動 */
  offlineQueueCount: number
  /** 現在オフラインかどうか（{@code navigator.onLine === false}）。SSR 時は false */
  isOffline: boolean
  /** Phase 3: 週次まとめ一覧 */
  weeklySummaries: WeeklySummary[]
  /** Phase 3: 週次まとめ一覧の読み込み中フラグ */
  weeklyLoading: boolean
  /** Phase 3: 週次まとめ一覧のエラー（i18n キー） */
  weeklyError: string | null
  /** Phase 3: 週次まとめの現在ページ（0始まり） */
  weeklyPage: number
  /** Phase 3: 週次まとめの総ページ数 */
  weeklyTotalPages: number
}

export type ActionMemoErrorCode =
  | 'RATE_LIMIT'
  | 'DAILY_LIMIT'
  | 'FUTURE_DATE'
  | 'TODO_NOT_FOUND'
  | 'UNKNOWN'

const DRAFT_KEY_PREFIX = 'action-memo-draft-'

function draftKey(userId: number | string): string {
  return `${DRAFT_KEY_PREFIX}${userId}`
}

/** 楽観的 UI の一時 ID。負値で本物の ID と被らないようにする */
let _tempIdCounter = -1
function nextTempId(): number {
  return _tempIdCounter--
}

/**
 * 投稿日時。サーバー側で書き換えられるためクライアント計算は最小限。
 * 楽観的 UI 用にローカル ISO 文字列を生成。
 */
function nowIso(): string {
  return new Date().toISOString().replace('Z', '').slice(0, 19)
}

/**
 * JST の今日（YYYY-MM-DD）。サーバー側の判定基準と合わせる。
 */
function todayJst(): string {
  const now = new Date()
  // UTC → JST (+9h)
  const jst = new Date(now.getTime() + 9 * 60 * 60 * 1000)
  return jst.toISOString().slice(0, 10)
}

export const useActionMemoStore = defineStore('actionMemo', {
  state: (): ActionMemoStoreState => ({
    memos: [],
    settings: { moodEnabled: false },
    tags: [],
    moodStats: null,
    loading: false,
    error: null,
    lastError: null,
    offlineQueueCount: 0,
    isOffline: false,
    weeklySummaries: [],
    weeklyLoading: false,
    weeklyError: null,
    weeklyPage: 0,
    weeklyTotalPages: 0,
  }),

  getters: {
    /** 当日のメモのみ抽出（日付で絞る簡易ヘルパー） */
    currentDayMemos:
      (state) =>
      (date: string): ActionMemo[] =>
        state.memos.filter((m) => m.memoDate === date),

    isMoodEnabled: (state): boolean => state.settings.moodEnabled,
  },

  actions: {
    /**
     * 楽観的 UI でメモを作成する。
     *
     * <ol>
     *   <li>一時 ID で memos の先頭に追加</li>
     *   <li>オンライン時: API 呼び出し → 成功時に本物の ID で置換 / 失敗時はロールバック</li>
     *   <li>オフライン時 ({@code navigator.onLine === false}): IndexedDB にキューイングし、
     *       楽観的 UI のまま維持。online イベントで自動 flush を試みる</li>
     * </ol>
     *
     * <p>オフライン時でも {@link ActionMemo#id} は負数の仮 ID が設定され、同期完了時に
     * {@link useOfflineQueue#flushQueue} 経由で本物の ID に置き換わる。</p>
     */
    async createMemo(payload: CreateActionMemoPayload): Promise<ActionMemo | null> {
      const tempId = nextTempId()
      const optimistic: ActionMemo = {
        id: tempId,
        memoDate: payload.memoDate ?? todayJst(),
        content: payload.content,
        // mood_enabled = false の場合、サーバー側で silent NULL 化されるため
        // ローカルでも同様に NULL にしておく
        mood: this.settings.moodEnabled ? (payload.mood ?? null) : null,
        relatedTodoId: payload.relatedTodoId ?? null,
        timelinePostId: null,
        tags: [],
        createdAt: nowIso(),
      }
      this.memos.unshift(optimistic)
      this.error = null
      this.lastError = null

      // オフラインキュー判定: navigator.onLine が false ならキューに積む
      if (this._isOffline()) {
        try {
          const queue = useOfflineQueue()
          await queue.enqueue(payload, tempId)
          this.offlineQueueCount = await queue.count()
          this.isOffline = true
          // 下書きはクリアする（キューに積んだ時点で「ユーザーの入力」は成立）
          this.clearDraft(this._currentUserIdOrAnon())
          // 仮 ID のまま楽観的 UI を返す
          return optimistic
        } catch (error) {
          // キューイングにも失敗したらロールバック
          const idx = this.memos.findIndex((m) => m.id === tempId)
          if (idx >= 0) this.memos.splice(idx, 1)
          this._handleError(error)
          return null
        }
      }

      try {
        const api = useActionMemoApi()
        const created = await api.createMemo(payload)
        // 一時 ID を本物に置き換え
        const idx = this.memos.findIndex((m) => m.id === tempId)
        if (idx >= 0) {
          this.memos.splice(idx, 1, created)
        } else {
          this.memos.unshift(created)
        }
        // 送信成功 → 下書きクリア
        this.clearDraft(this._currentUserIdOrAnon())
        return created
      } catch (error) {
        // ロールバック
        const idx = this.memos.findIndex((m) => m.id === tempId)
        if (idx >= 0) this.memos.splice(idx, 1)
        this._handleError(error)
        return null
      }
    },

    /**
     * オフラインキューを順次送信する。
     *
     * <p>成功した項目ごとに仮 ID を本物の ID に置き換える。{@code navigator.onLine} が
     * {@code true} の場合にのみ呼ばれる想定（online イベントや手動同期ボタン）。</p>
     *
     * @returns 成功件数
     */
    async flushOfflineQueue(): Promise<number> {
      const queue = useOfflineQueue()
      const api = useActionMemoApi()
      const results = await queue.flushQueue(async (payload) => {
        try {
          const created = await api.createMemo(payload)
          return created
        } catch (error) {
          this._handleError(error)
          return null
        }
      })
      // 仮 ID を本物の ID に置換するため、一度だけ fetchMemos で再取得する
      // （flushQueue の返す createdId だけでは、並列送信された別デバイスのメモや
      //  同日の並び順を正しく反映できないため）
      if (results.length > 0) {
        const targetDate = results[0]?.queued.payload.memoDate ?? todayJst()
        await this.fetchMemosForDate(targetDate)
      }
      this.offlineQueueCount = await queue.count()
      this.isOffline = this._isOffline()
      return results.length
    },

    /**
     * オフラインキュー件数を再計算する（UI バナー更新用）。
     */
    async refreshOfflineQueueCount(): Promise<void> {
      const queue = useOfflineQueue()
      this.offlineQueueCount = await queue.count()
      this.isOffline = this._isOffline()
    },

    /**
     * 指定日のメモ一覧を取得する。
     */
    async fetchMemosForDate(date: string): Promise<void> {
      this.loading = true
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        const res = await api.fetchMemos({ date, limit: 200 })
        this.memos = res.data
      } catch (error) {
        this._handleError(error)
      } finally {
        this.loading = false
      }
    },

    /**
     * メモを部分更新する。
     */
    async updateMemo(id: number, payload: UpdateActionMemoPayload): Promise<ActionMemo | null> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        const updated = await api.updateMemo(id, payload)
        const idx = this.memos.findIndex((m) => m.id === id)
        if (idx >= 0) this.memos.splice(idx, 1, updated)
        return updated
      } catch (error) {
        this._handleError(error)
        return null
      }
    },

    /**
     * メモを論理削除する。楽観的に list から取り除く。
     */
    async deleteMemo(id: number): Promise<boolean> {
      const previous = this.memos.slice()
      this.memos = this.memos.filter((m) => m.id !== id)
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        await api.deleteMemo(id)
        return true
      } catch (error) {
        // ロールバック
        this.memos = previous
        this._handleError(error)
        return false
      }
    },

    /**
     * 設定を取得する。レコード未作成のユーザーはデフォルト値が返る。
     */
    async fetchSettings(): Promise<void> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        this.settings = await api.getSettings()
      } catch (error) {
        this._handleError(error)
      }
    },

    /**
     * 設定を更新する（UPSERT）。
     */
    async updateSettings(patch: { moodEnabled: boolean }): Promise<void> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        this.settings = await api.updateSettings(patch)
      } catch (error) {
        this._handleError(error)
      }
    },

    /**
     * 終業時まとめ投稿を実行する。
     *
     * <p>設計書 §5.4 の「今日を締める」儀式。成功時は対象日のメモ一覧を再取得し、
     * {@code timelinePostId} を反映する。失敗時は呼び出し側（closing.vue）で
     * トースト表示できるようエラーを再 throw する。</p>
     *
     * @param payload {@code memoDate} / {@code extraComment} 任意
     */
    async publishDaily(payload: PublishDailyPayload = {}): Promise<PublishDailyResponse> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        const response = await api.publishDaily(payload)
        // 各メモの timeline_post_id を反映するため対象日を再取得する
        const targetDate = payload.memoDate ?? response.memoDate ?? todayJst()
        await this.fetchMemosForDate(targetDate)
        return response
      } catch (error) {
        this._handleError(error)
        throw error
      }
    },

    // === Tag CRUD (Phase 4) ===

    /**
     * 自分のタグ一覧を取得する（論理削除済みは含まない）。
     */
    async fetchTags(): Promise<void> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        this.tags = await api.getTags()
      } catch (error) {
        this._handleError(error)
      }
    },

    /**
     * タグを作成する。
     */
    async createTag(payload: CreateTagPayload): Promise<ActionMemoTag | null> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        const created = await api.createTag(payload)
        this.tags.push(created)
        return created
      } catch (error) {
        this._handleError(error)
        return null
      }
    },

    /**
     * タグを更新する（名前・色）。
     */
    async updateTag(tagId: number, payload: UpdateTagPayload): Promise<ActionMemoTag | null> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        const updated = await api.updateTag(tagId, payload)
        const idx = this.tags.findIndex((t) => t.id === tagId)
        if (idx >= 0) this.tags.splice(idx, 1, updated)
        return updated
      } catch (error) {
        this._handleError(error)
        return null
      }
    },

    /**
     * タグを論理削除する。復活機能なし（§11 #9）。
     */
    async deleteTag(tagId: number): Promise<boolean> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        await api.deleteTag(tagId)
        this.tags = this.tags.filter((t) => t.id !== tagId)
        return true
      } catch (error) {
        this._handleError(error)
        return false
      }
    },

    /**
     * メモにタグを追加する（複数可）。
     */
    async addTagsToMemo(memoId: number, tagIds: number[]): Promise<boolean> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        await api.addTagsToMemo(memoId, tagIds)
        return true
      } catch (error) {
        this._handleError(error)
        return false
      }
    },

    /**
     * メモからタグを除去する。
     */
    async removeTagFromMemo(memoId: number, tagId: number): Promise<boolean> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        await api.removeTagFromMemo(memoId, tagId)
        return true
      } catch (error) {
        this._handleError(error)
        return false
      }
    },

    // === Mood stats (Phase 4) ===

    /**
     * 期間内の気分分布を取得する。
     */
    async fetchMoodStats(from: string, to: string): Promise<void> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        this.moodStats = await api.getMoodStats({ from, to })
      } catch (error) {
        this._handleError(error)
      }
    },

    // === Weekly Summary (Phase 3) ===

    /**
     * 週次まとめ一覧を取得する。
     *
     * <p>F06.1 BlogPost API を流用し、タイトルプレフィックス「週次ふりかえり: 」で
     * クライアント側フィルタする（設計書 §11 #11）。</p>
     *
     * @param page ページ番号（0始まり）。省略時は 0
     */
    async fetchWeeklySummaries(page = 0): Promise<void> {
      this.weeklyLoading = true
      this.weeklyError = null
      try {
        const api = useActionMemoApi()
        const res = await api.fetchWeeklySummaries({ page, size: 20 })
        if (page === 0) {
          this.weeklySummaries = res.data
        } else {
          this.weeklySummaries = [...this.weeklySummaries, ...res.data]
        }
        this.weeklyPage = res.page
        this.weeklyTotalPages = res.totalPages
      } catch {
        this.weeklyError = 'action_memo.weekly.error'
      } finally {
        this.weeklyLoading = false
      }
    },

    // === Draft ===

    /** localStorage から下書きを復元 */
    loadDraft(userId: number | string): string {
      if (typeof window === 'undefined') return ''
      try {
        return localStorage.getItem(draftKey(userId)) ?? ''
      } catch {
        return ''
      }
    },

    /** localStorage に下書きを保存 */
    saveDraft(userId: number | string, content: string): void {
      if (typeof window === 'undefined') return
      try {
        if (content && content.length > 0) {
          localStorage.setItem(draftKey(userId), content)
        } else {
          localStorage.removeItem(draftKey(userId))
        }
      } catch {
        // Storage quota or disabled — silently ignore
      }
    },

    /** 下書きをクリア */
    clearDraft(userId: number | string): void {
      if (typeof window === 'undefined') return
      try {
        localStorage.removeItem(draftKey(userId))
      } catch {
        // ignore
      }
    },

    // === 内部ユーティリティ ===

    _currentUserIdOrAnon(): number | string {
      try {
        const auth = useAuthStore()
        return auth.user?.id ?? 'anon'
      } catch {
        return 'anon'
      }
    },

    /**
     * {@code navigator.onLine === false} の判定。SSR 時は false（オンライン扱い）。
     */
    _isOffline(): boolean {
      if (typeof navigator === 'undefined') return false
      return navigator.onLine === false
    },

    /**
     * fetch 系エラーを {@link ActionMemoErrorCode} に分類して state に書き出す。
     * 429 は {@link ActionMemoRateLimitError} 由来、それ以外は HTTP status / メッセージで判定。
     */
    _handleError(error: unknown): void {
      const err = error as {
        status?: number
        response?: { status?: number; _data?: { message?: string; code?: string } }
        message?: string
      }
      const status = err?.status ?? err?.response?.status
      const message = err?.response?._data?.message ?? err?.message ?? 'unknown'

      if (status === 429) {
        this.lastError = 'RATE_LIMIT'
        this.error = 'action_memo.error.rate_limit'
        return
      }
      if (status === 400) {
        if (/200/.test(message) || /daily/i.test(message) || /上限/.test(message)) {
          this.lastError = 'DAILY_LIMIT'
          this.error = 'action_memo.error.daily_limit'
          return
        }
        if (/future/i.test(message) || /未来/.test(message)) {
          this.lastError = 'FUTURE_DATE'
          this.error = 'action_memo.error.future_date'
          return
        }
      }
      if (status === 404) {
        this.lastError = 'TODO_NOT_FOUND'
        this.error = 'action_memo.error.todo_not_found'
        return
      }
      this.lastError = 'UNKNOWN'
      this.error = 'action_memo.error.save_failed'
    },
  },
})

// === エクスポート（テスト用にユーティリティを公開） ===
export const __actionMemoInternals = {
  draftKey,
  todayJst,
}

/** 型再エクスポート（呼び出し側の便宜のため） */
export type { ActionMemo, ActionMemoSettings, Mood }
