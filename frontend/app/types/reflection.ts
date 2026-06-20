/**
 * F06.5 アクティブリコール学習機能（振り返り）の型定義。
 *
 * API 呼出の req/res 型は **生成型を優先**する（`~/types/generated`・BE→FE 境界ルール
 * feedback_fe_be_parallel_api_boundary_after_generated_types）。
 * このファイルは「生成型の薄い別名」と、生成型では `JsonNode`（=構造未定義）になる
 * structured_content / recalled_content の **UI 用構造型** のみを手動で持つ（§2.3 スキーマ）。
 */
import type { components } from '~/types/generated'

// ===== 生成型の別名（API req/res はこちらを使う） =====

export type ReflectionThemeResponse = components['schemas']['ReflectionThemeResponse']
export type ReflectionEntryResponse = components['schemas']['ReflectionEntryResponse']
export type ReflectionTodayResponse = components['schemas']['ReflectionTodayResponse']
export type ReflectionTodayItem = components['schemas']['ReflectionTodayItem']
export type RecallAttemptResponse = components['schemas']['RecallAttemptResponse']
export type ReflectionSettingsResponse = components['schemas']['ReflectionSettingsResponse']
export type MaskedHint = components['schemas']['MaskedHint']

export type CreateReflectionThemeRequest = components['schemas']['CreateReflectionThemeRequest']
export type UpdateReflectionThemeRequest = components['schemas']['UpdateReflectionThemeRequest']
export type UpsertReflectionEntryRequest = components['schemas']['UpsertReflectionEntryRequest']
export type CreateRecallAttemptRequest = components['schemas']['CreateRecallAttemptRequest']
export type ExportToBlogRequest = components['schemas']['ExportToBlogRequest']
export type UpdateReflectionSettingsRequest = components['schemas']['UpdateReflectionSettingsRequest']

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
