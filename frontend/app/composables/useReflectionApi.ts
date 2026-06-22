/**
 * F06.5 アクティブリコール学習機能（振り返り）API composable。
 *
 * BE は全エンドポイントを `ApiResponse<T>`（`{ data: T }`）で返す（§7）。
 * 認可は「認証必須＋本人所有のみ」。req/res は生成型（`~/types/reflection` の別名経由）を使う。
 */
import type {
  ReflectionThemeResponse,
  ReflectionEntryResponse,
  ReflectionTodayResponse,
  RecallAttemptResponse,
  ReflectionSettingsResponse,
  CreateReflectionThemeRequest,
  UpdateReflectionThemeRequest,
  UpsertReflectionEntryRequest,
  CreateRecallAttemptRequest,
  ExportToBlogRequest,
  UpdateReflectionSettingsRequest,
  LinkableSlotResponse,
} from '~/types/reflection'
import type { BlogPostResponse } from '~/types/cms'

const BASE = '/api/v1/me/reflections'

export function useReflectionApi() {
  const api = useApi()

  // ─── テーマ（§7 #1〜#5） ───────────────────────────────────────────────

  async function listThemes() {
    return api<{ data: ReflectionThemeResponse[] }>(`${BASE}/themes`)
  }

  async function getTheme(themeId: string) {
    return api<{ data: ReflectionThemeResponse }>(`${BASE}/themes/${themeId}`)
  }

  async function createTheme(body: CreateReflectionThemeRequest) {
    return api<{ data: ReflectionThemeResponse }>(`${BASE}/themes`, { method: 'POST', body })
  }

  async function updateTheme(themeId: string, body: UpdateReflectionThemeRequest) {
    return api<{ data: ReflectionThemeResponse }>(`${BASE}/themes/${themeId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteTheme(themeId: string) {
    return api(`${BASE}/themes/${themeId}`, { method: 'DELETE' })
  }

  // ─── エントリ（§7 #6〜#9） ─────────────────────────────────────────────

  async function listEntries(themeId: string) {
    return api<{ data: ReflectionEntryResponse[] }>(`${BASE}/themes/${themeId}/entries`)
  }

  async function getEntry(entryId: string) {
    return api<{ data: ReflectionEntryResponse }>(`${BASE}/entries/${entryId}`)
  }

  /** エントリ upsert（(theme,target_date) 一意・楽観排他 409・§7 #7）。 */
  async function upsertEntry(body: UpsertReflectionEntryRequest) {
    return api<{ data: ReflectionEntryResponse }>(`${BASE}/entries`, { method: 'PUT', body })
  }

  async function deleteEntry(entryId: string) {
    return api(`${BASE}/entries/${entryId}`, { method: 'DELETE' })
  }

  // ─── 想起テスト（§7 #10〜#11） ─────────────────────────────────────────

  /** 想起テスト保存＝開示（revealed_at 記録・original 返却・§7 #10）。 */
  async function recordRecall(entryId: string, body: CreateRecallAttemptRequest) {
    return api<{ data: ReflectionEntryResponse }>(`${BASE}/entries/${entryId}/recall`, {
      method: 'POST',
      body,
    })
  }

  async function listRecalls(entryId: string) {
    return api<{ data: RecallAttemptResponse[] }>(`${BASE}/entries/${entryId}/recalls`)
  }

  // ─── 今日の振り返りビュー（§7 #12） ────────────────────────────────────

  async function getToday(date?: string) {
    const qs = date ? `?date=${encodeURIComponent(date)}` : ''
    return api<{ data: ReflectionTodayResponse }>(`${BASE}/today${qs}`)
  }

  // ─── 科目紐づけ候補（§11.3） ────────────────────────────────────────────

  /** 週全体のコマを科目単位で重複排除した紐づけ候補一覧（AC-30）。 */
  async function listLinkableSlots() {
    return api<{ data: LinkableSlotResponse[] }>(`${BASE}/linkable-slots`)
  }

  // ─── ブログ輸出（§7 #13） ──────────────────────────────────────────────

  async function exportToBlog(entryId: string, body: ExportToBlogRequest) {
    return api<{ data: BlogPostResponse }>(`${BASE}/entries/${entryId}/export-to-blog`, {
      method: 'POST',
      body,
    })
  }

  // ─── 通知設定（§7 #14〜#15） ───────────────────────────────────────────

  async function getSettings() {
    return api<{ data: ReflectionSettingsResponse }>(`${BASE}/settings`)
  }

  async function updateSettings(body: UpdateReflectionSettingsRequest) {
    return api<{ data: ReflectionSettingsResponse }>(`${BASE}/settings`, { method: 'PUT', body })
  }

  return {
    listThemes,
    getTheme,
    createTheme,
    updateTheme,
    deleteTheme,
    listEntries,
    getEntry,
    upsertEntry,
    deleteEntry,
    recordRecall,
    listRecalls,
    getToday,
    listLinkableSlots,
    exportToBlog,
    getSettings,
    updateSettings,
  }
}
