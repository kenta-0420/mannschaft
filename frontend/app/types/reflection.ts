/**
 * F06.5 アクティブリコール学習機能（振り返り）の型定義。
 *
 * API 呼出の req/res 型は **生成型を優先**する（`~/types/generated`・BE→FE 境界ルール
 * feedback_fe_be_parallel_api_boundary_after_generated_types）。
 * このファイルは「生成型の薄い別名」と、生成型では `JsonNode`（=構造未定義）になる
 * structured_content / recalled_content の **UI 用構造型** のみを手動で持つ（§2.3 スキーマ）。
 *
 * Phase 3 追加型: openapi.json に Phase 3 フィールドを補完し generate:types 再生成済み。
 * ReflectionThemeResponse / CreateReflectionThemeRequest / UpdateReflectionThemeRequest は
 * 生成型をそのまま参照する（academicYear / termLabel / parentThemeId / archivedAt / clearParent
 * は生成型に含まれる）。
 */
import type { components } from '~/types/generated'

// ===== 生成型の別名（API req/res はこちらを使う） =====

/** テーマレスポンス（Phase 3: academicYear / termLabel / parentThemeId / archivedAt を含む）。 */
export type ReflectionThemeResponse = components['schemas']['ReflectionThemeResponse']
export type ReflectionEntryResponse = components['schemas']['ReflectionEntryResponse']
export type ReflectionTodayResponse = components['schemas']['ReflectionTodayResponse']
export type ReflectionTodayItem = components['schemas']['ReflectionTodayItem']
export type RecallAttemptResponse = components['schemas']['RecallAttemptResponse']
export type ReflectionSettingsResponse = components['schemas']['ReflectionSettingsResponse']
export type MaskedHint = components['schemas']['MaskedHint']

/** テーマ作成リクエスト（Phase 3: academicYear / termLabel / parentThemeId を含む）。 */
export type CreateReflectionThemeRequest = components['schemas']['CreateReflectionThemeRequest']
/** テーマ更新リクエスト（Phase 3: academicYear / termLabel / parentThemeId / clearParent を含む）。 */
export type UpdateReflectionThemeRequest = components['schemas']['UpdateReflectionThemeRequest']
export type UpsertReflectionEntryRequest = components['schemas']['UpsertReflectionEntryRequest']
export type CreateRecallAttemptRequest = components['schemas']['CreateRecallAttemptRequest']
export type ExportToBlogRequest = components['schemas']['ExportToBlogRequest']
export type UpdateReflectionSettingsRequest = components['schemas']['UpdateReflectionSettingsRequest']
export type LinkableSlotResponse = components['schemas']['LinkableSlotResponse']

// ===== Phase 3 専用型（生成型未生成・BE DTO §12.4 から直接写した手動定義） =====

/**
 * アーカイブフォルダ集計レスポンス（EP #17・§12.4）。
 * 学年×学期×教科 GROUP BY 結果。各フィールド null=未設定グループ。
 */
export interface ArchiveFolderResponse {
  /** 学年度。null=未設定グループ。 */
  academicYear: number | null
  /** 学期ラベル。null=未設定グループ。 */
  termLabel: string | null
  /** 科目名。null=科目未紐づけグループ。 */
  subjectName: string | null
  /** フォルダに属するアーカイブ済みテーマ件数。 */
  themeCount: number
}

/**
 * 学年・学期自動提案レスポンス（EP #22・§12.1）。
 * 個人時間割から基準日に有効な学年・学期を提案する。
 */
export interface TermSuggestionResponse {
  /** 提案する学年度。null=提案なし。 */
  academicYear: number | null
  /** 提案する学期ラベル。null=提案なし。 */
  termLabel: string | null
}

/**
 * 一括アーカイブリクエスト（EP #21・§12.4）。
 * 3フィールドすべて null は 400 で拒否。
 */
export interface BulkArchiveRequest {
  /** 一括対象の学年度。null=条件に含めない。 */
  academicYear?: number | null
  /** 一括対象の学期ラベル。null=条件に含めない。 */
  termLabel?: string | null
  /** 一括対象の科目名。null=条件に含めない。 */
  subjectName?: string | null
}

/**
 * 一括アーカイブ結果レスポンス（EP #21・§12.4）。
 */
export interface BulkArchiveResult {
  /** 一括アーカイブしたテーマ件数（0件でも 200 を返す）。 */
  archivedCount: number
}

/** アーカイブ横断検索クエリパラメータ（EP #18・§12.4）。 */
export interface ArchiveSearchParams {
  academicYear?: number | null
  termLabel?: string | null | undefined
  subjectName?: string | null | undefined
  keyword?: string | null | undefined
  archived?: boolean | null
  page?: number
  size?: number
}

// 列挙（生成型に enum がある場合はそちらが優先。値の定数配列は UI 選択肢に使う）
export type ReflectionSourceType = 'SUBJECT' | 'PROJECT' | 'DIARY' | 'FREE'
export type ReflectionLinkedSlotKind = 'TEAM' | 'PERSONAL'
export type RecallSelfRating = 'REMEMBERED' | 'PARTIAL' | 'FORGOT'

export const REFLECTION_SOURCE_TYPES: ReflectionSourceType[] = ['SUBJECT', 'PROJECT', 'DIARY', 'FREE']
export const RECALL_SELF_RATINGS: RecallSelfRating[] = ['REMEMBERED', 'PARTIAL', 'FORGOT']

// ===== structured_content の UI 用構造型（§2.3・生成型は JsonNode ＝構造未定義） =====

/** 小見出し（subsection）。 */
export interface ReflectionSubsection {
  sub_heading: string
  detail: string
  supplement: string
}

/** 中見出し（section）。 */
export interface ReflectionSection {
  heading: string
  subsections: ReflectionSubsection[]
}

/**
 * アウトライン構造化コンテンツ（固定 5 階層・§2.3）。
 *
 * メインテーマ → 中見出し(section) → 小見出し(subsection) → 詳細 → 補足。
 * `free_note` はマスク対象外の自由欄。
 */
export interface ReflectionStructuredContent {
  main_theme: string
  sections: ReflectionSection[]
  free_note: string
}

/** 空の structured_content を生成する。 */
export function emptyStructuredContent(): ReflectionStructuredContent {
  return { main_theme: '', sections: [], free_note: '' }
}

/** 空の subsection を生成する。 */
export function emptySubsection(): ReflectionSubsection {
  return { sub_heading: '', detail: '', supplement: '' }
}

/** 空の section（subsection 1 つ付き）を生成する。 */
export function emptySection(heading = ''): ReflectionSection {
  return { heading, subsections: [emptySubsection()] }
}
