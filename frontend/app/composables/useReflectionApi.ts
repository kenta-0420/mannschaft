/**
 * F06.5 アクティブリコール学習機能（振り返り）API composable。
 *
 * BE は全エンドポイントを `ApiResponse<T>`（`{ data: T }`）で返す（§7）。
 * 認可は「認証必須＋本人所有のみ」。req/res は生成型（`~/types/reflection` の別名経由）を使う。
 *
 * Phase 3 追加:
 * - listArchiveFolders() → EP #17 GET /archive/folders
 * - searchArchive(params) → EP #18 GET /archive/search
 * - archiveTheme(id) → EP #19 PATCH /themes/{id}/archive
 * - restoreTheme(id) → EP #20 PATCH /themes/{id}/restore
 * - bulkArchive(body) → EP #21 POST /archive/bulk-archive
 * - getTermSuggestion(baseDate?) → EP #22 GET /term-suggestion
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
  ArchiveFolderResponse,
  TermSuggestionResponse,
  BulkArchiveRequest,
  BulkArchiveResult,
  ArchiveSearchParams,
  ReflectionVocabCardsResponse,
  VocabCardsParams,
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

  // ─── Phase 3: アーカイブ＆分類（§12・EP #17〜#22） ──────────────────────

  /** EP #17: アーカイブ済みテーマの学年×学期×教科フォルダ集計。 */
  async function listArchiveFolders() {
    return api<{ data: ArchiveFolderResponse[] }>(`${BASE}/archive/folders`)
  }

  /** EP #18: アーカイブ済みテーマ横断検索（学年/学期/教科/キーワード AND・ページング）。 */
  async function searchArchive(params: ArchiveSearchParams = {}) {
    const qs = new URLSearchParams()
    if (params.academicYear != null) qs.set('academicYear', String(params.academicYear))
    if (params.termLabel != null) qs.set('termLabel', params.termLabel)
    if (params.subjectName != null) qs.set('subjectName', params.subjectName)
    if (params.keyword != null) qs.set('keyword', params.keyword)
    if (params.archived != null) qs.set('archived', String(params.archived))
    if (params.page != null) qs.set('page', String(params.page))
    if (params.size != null) qs.set('size', String(params.size))
    const query = qs.toString() ? `?${qs.toString()}` : ''
    return api<{ data: { content: ReflectionThemeResponse[], totalElements: number, totalPages: number, number: number, size: number } }>(`${BASE}/archive/search${query}`)
  }

  /** EP #19: テーマをアーカイブ（PENDING リマインダーを CANCELLED に）。 */
  async function archiveTheme(themeId: string) {
    return api<{ data: ReflectionThemeResponse }>(`${BASE}/themes/${themeId}/archive`, { method: 'PATCH' })
  }

  /** EP #20: アーカイブ済みテーマを復元（今日ビュー・テーマ一覧に戻す）。 */
  async function restoreTheme(themeId: string) {
    return api<{ data: ReflectionThemeResponse }>(`${BASE}/themes/${themeId}/restore`, { method: 'PATCH' })
  }

  /** EP #21: 学年/学期/教科 条件でアクティブテーマを一括アーカイブ。 */
  async function bulkArchive(body: BulkArchiveRequest) {
    return api<{ data: BulkArchiveResult }>(`${BASE}/archive/bulk-archive`, { method: 'POST', body })
  }

  /**
   * EP #22: 基準日から個人時間割の学年・学期を自動提案。
   * @param baseDate YYYY-MM-DD 形式（省略時はサーバが今日を使用）
   */
  async function getTermSuggestion(baseDate?: string) {
    const qs = baseDate ? `?baseDate=${encodeURIComponent(baseDate)}` : ''
    return api<{ data: TermSuggestionResponse }>(`${BASE}/term-suggestion${qs}`)
  }

  // ─── Phase 4: 期間横断単語帳（EP #23） ───────────────────────────────────

  /** EP #23: 期間内の TERM_CARD カードを横断抽出（recall_attempts 非書込・閲覧専用）。 */
  async function getVocabCards(params: VocabCardsParams) {
    const qs = new URLSearchParams()
    qs.set('from', params.from)
    qs.set('to', params.to)
    if (params.themeId) qs.set('themeId', params.themeId)
    // 複数教科 OR フィルタ（繰り返しパラメータ形式・AC-62）
    if (params.subjects && params.subjects.length > 0) {
      params.subjects.forEach(s => qs.append('subjects', s))
    }
    else if (params.subject) {
      qs.set('subject', params.subject)
    }
    // 複数 sourceType OR フィルタ（AC-65）
    if (params.sourceTypes && params.sourceTypes.length > 0) {
      params.sourceTypes.forEach(t => qs.append('sourceTypes', t))
    }
    else if (params.sourceType) {
      qs.set('sourceType', params.sourceType)
    }
    // shuffle=true の場合はページング不要（AC-63）
    if (params.shuffle) {
      qs.set('shuffle', 'true')
    }
    else {
      if (params.page != null) qs.set('page', String(params.page))
      if (params.size != null) qs.set('size', String(params.size))
    }
    return api<{ data: ReflectionVocabCardsResponse }>(`${BASE}/cards?${qs.toString()}`)
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
    // Phase 3
    listArchiveFolders,
    searchArchive,
    archiveTheme,
    restoreTheme,
    bulkArchive,
    getTermSuggestion,
    // Phase 4
    getVocabCards,
  }
}
