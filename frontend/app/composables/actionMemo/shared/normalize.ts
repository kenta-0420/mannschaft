/**
 * F02.5 行動メモ API クライアント — 共通正規化ユーティリティ。
 *
 * <p>Backend は一部フィールドを {@code @JsonProperty} でスネークケース表現にしているため
 * （{@code memo_date}, {@code tag_ids}, {@code related_todo_id}, {@code timeline_post_id},
 * {@code created_at}, {@code updated_at}, {@code next_cursor}, {@code mood_enabled}）、
 * このモジュールでキャメルケース ⇔ スネークケースを明示的に変換する。</p>
 *
 * <p>レートリミット 429 応答は {@link ActionMemoRateLimitError} に再ラップして
 * {@code Retry-After} ヘッダーの秒数を呼び出し側へ伝える。</p>
 *
 * <p>Phase 3 リファクタにて {@code useActionMemoApi.ts} から共通部分を抽出。
 * 7 ドメイン composable から共通利用される。</p>
 */
import type {
  ActionMemo,
  ActionMemoCategory,
  ActionMemoRateLimitError,
  ActionMemoSettings,
  ActionMemoTag,
  AvailableTeam,
  Mood,
  OrgVisibility,
} from '~/types/actionMemo'

// === Raw 型（Backend スネークケース表現） ===

export type RawTag = {
  id: number
  name: string
  color: string | null
  deleted?: boolean
}

export type RawMemo = {
  id: number
  content: string
  mood: Mood | null
  memo_date: string
  related_todo_id: number | null
  timeline_post_id: number | null
  tags: RawTag[] | null
  created_at: string
  updated_at?: string
  // Phase 3
  category?: string | null
  duration_minutes?: number | null
  progress_rate?: number | null
  completes_todo?: boolean | null
  posted_team_id?: number | null
  // Phase 4-α
  organization_id?: number | null
  org_visibility?: string | null
}

export type RawSettings = {
  mood_enabled: boolean
  // Phase 3
  default_post_team_id?: number | null
  default_category?: string | null
  // Phase 4-β
  reminder_enabled?: boolean
  reminder_time?: string | null
}

export type RawAvailableTeam = {
  id: number
  name: string
  is_default: boolean
}

export type RawListResponse = {
  data: RawMemo[]
  next_cursor: string | null
}

// === Normalizers ===

export function normalizeTag(raw: RawTag): ActionMemoTag {
  return {
    id: raw.id,
    name: raw.name,
    color: raw.color ?? null,
    deleted: raw.deleted === true,
  }
}

export function normalizeMemo(raw: RawMemo): ActionMemo {
  return {
    id: raw.id,
    memoDate: raw.memo_date,
    content: raw.content,
    mood: raw.mood ?? null,
    relatedTodoId: raw.related_todo_id ?? null,
    timelinePostId: raw.timeline_post_id ?? null,
    tags: (raw.tags ?? []).map(normalizeTag),
    createdAt: raw.created_at,
    updatedAt: raw.updated_at,
    // Phase 3
    category: (raw.category as ActionMemoCategory) ?? 'OTHER',
    durationMinutes: raw.duration_minutes ?? null,
    progressRate: raw.progress_rate ?? null,
    completesTodo: raw.completes_todo ?? false,
    postedTeamId: raw.posted_team_id ?? null,
    // Phase 4-α
    organizationId: raw.organization_id ?? null,
    orgVisibility: (raw.org_visibility as OrgVisibility) ?? null,
  }
}

export function normalizeSettings(raw: RawSettings): ActionMemoSettings {
  return {
    moodEnabled: raw.mood_enabled,
    // Phase 3
    defaultPostTeamId: raw.default_post_team_id ?? null,
    defaultCategory: (raw.default_category as ActionMemoCategory) ?? 'OTHER',
    // Phase 4-β
    reminderEnabled: raw.reminder_enabled ?? false,
    reminderTime: raw.reminder_time ?? null,
  }
}

export function normalizeAvailableTeam(raw: RawAvailableTeam): AvailableTeam {
  return {
    id: raw.id,
    name: raw.name,
    isDefault: raw.is_default === true,
  }
}

/**
 * 共通エラーハンドラ。429 を {@link ActionMemoRateLimitError} に再ラップする。
 * 呼び出し側は {@code error.status === 429} と {@code error.retryAfterSeconds} で
 * トースト表示・自動リトライ等を判断できる。
 */
export function rethrow(error: unknown): never {
  const err = error as {
    response?: { status?: number; headers?: { get?: (k: string) => string | null } }
    message?: string
  }
  if (err?.response?.status === 429) {
    const retryAfter = err.response.headers?.get?.('Retry-After') ?? null
    const seconds = retryAfter ? Number.parseInt(retryAfter, 10) : null
    const wrapped = new Error(
      `Rate limited (Retry-After: ${seconds ?? 'unknown'}s)`,
    ) as ActionMemoRateLimitError
    wrapped.status = 429
    wrapped.retryAfterSeconds = Number.isFinite(seconds) ? seconds : null
    throw wrapped
  }
  throw error
}

// === API ベースパス定数 ===

export const ACTION_MEMO_BASE = '/api/v1/action-memos'
export const ACTION_MEMO_TAGS_BASE = '/api/v1/action-memo-tags'
export const ACTION_MEMO_SETTINGS_BASE = '/api/v1/action-memo-settings'
