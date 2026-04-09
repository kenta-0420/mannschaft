import { defineStore } from 'pinia'
import type {
  ActionMemo,
  ActionMemoSettings,
  ActionMemoTag,
  CreateActionMemoPayload,
  Mood,
  UpdateActionMemoPayload,
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
  /** Phase 4 でタグ管理を実装する際に利用。Phase 1 では空配列のまま */
  tags: ActionMemoTag[]
  loading: boolean
  /** 直近のエラー（429 / 400 等）。i18n キーまたはメッセージ */
  error: string | null
  /**
   * lastError は機械可読な分類用。
   * - "RATE_LIMIT" / "DAILY_LIMIT" / "FUTURE_DATE" / "TODO_NOT_FOUND" / "UNKNOWN" のいずれか
   */
  lastError: ActionMemoErrorCode | null
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
    loading: false,
    error: null,
    lastError: null,
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
     *   <li>API 呼び出し → 成功時に本物の ID で置換</li>
     *   <li>失敗時は一時 ID を削除し error を設定</li>
     * </ol>
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
