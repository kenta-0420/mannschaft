export type SurveyStatus = 'DRAFT' | 'PUBLISHED' | 'CLOSED'
export type QuestionType = 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'TEXT' | 'RATING' | 'DATE'
/**
 * 結果公開設定。
 *
 * - `CREATOR_ONLY`: 作成者のみ閲覧可
 * - `RESPONDENTS`: 回答者のみ閲覧可
 * - `ALL_MEMBERS`: スコープ内の全メンバーが閲覧可
 * - `AFTER_CLOSE`: アンケートが締め切り（CLOSED）された後にスコープ内の全員が閲覧可
 *
 * NOTE: Backend `ResultsVisibility` enum は別の 4 値
 * (`AFTER_RESPONSE` / `AFTER_CLOSE` / `ADMINS_ONLY` / `VIEWERS_ONLY`) を持つ。
 * 名称統一は別軍議案件 (`project_visibility_enum_unification_pending`) で扱うため、
 * 本フロント側の型は設計書 §権限判定 (docs/features/F05.4_survey_vote.md L1377〜) の
 * 命名を踏襲しつつ、AFTER_CLOSE のみ Backend と共通の値として追加している。
 */
export type ResultsVisibility = 'CREATOR_ONLY' | 'RESPONDENTS' | 'ALL_MEMBERS' | 'AFTER_CLOSE'
export type UnrespondedVisibility = 'HIDDEN' | 'CREATOR_AND_ADMIN' | 'ALL_MEMBERS'

export interface SurveyResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  title: string
  description: string | null
  status: SurveyStatus
  isAnonymous: boolean
  allowMultipleSubmissions: boolean
  resultsVisibility: ResultsVisibility
  unrespondedVisibility: UnrespondedVisibility
  deadline: string | null
  createdBy: { id: number; displayName: string } | null
  responseCount: number
  targetCount: number | null
  hasResponded: boolean
  createdAt: string
  updatedAt: string
}

export interface SurveyQuestion {
  id: number
  questionText: string
  questionType: QuestionType
  isRequired: boolean
  sortOrder: number
  options: SurveyOption[]
}

export interface SurveyOption {
  id: number
  optionText: string
  sortOrder: number
}

export interface SurveyDetailResponse {
  data: SurveyResponse & {
    questions: SurveyQuestion[]
  }
}

export interface SurveyResultSummary {
  questionId: number
  questionText: string
  questionType: QuestionType
  totalResponses: number
  optionResults: Array<{
    optionId: number
    optionText: string
    count: number
    percentage: number
  }>
  textResponses?: string[]
}

export interface CreateSurveyRequest {
  title: string
  description?: string
  isAnonymous?: boolean
  allowMultipleSubmissions?: boolean
  resultsVisibility?: ResultsVisibility
  unrespondedVisibility?: UnrespondedVisibility | null
  deadline?: string
  questions: Array<{
    questionText: string
    questionType: QuestionType
    isRequired?: boolean
    sortOrder: number
    options?: Array<{ optionText: string; sortOrder: number }>
  }>
}

export interface UpdateSurveyRequest {
  title?: string
  description?: string | null
  isAnonymous?: boolean
  allowMultipleSubmissions?: boolean
  resultsVisibility?: ResultsVisibility
  unrespondedVisibility?: UnrespondedVisibility | null
  deadline?: string | null
}

export interface SubmitResponseRequest {
  answers: Array<{
    questionId: number
    optionId?: number
    optionIds?: number[]
    textValue?: string
    ratingValue?: number
    dateValue?: string
  }>
}

export interface RespondentItem {
  userId: number
  displayName: string
  avatarUrl: string | null
  hasResponded: boolean
  respondedAt: string | null
}

export interface RespondentsResponse {
  data: RespondentItem[]
}

/**
 * 督促送信レスポンス（POST /api/v1/surveys/{id}/remind）。
 *
 * Backend は Java DTO を Jackson のデフォルト命名（camelCase）で返すため、
 * 本プロジェクト全体の API レスポンス命名規則に合わせて camelCase で受ける。
 * 設計書 (docs/features/F05.4_survey_vote.md §POST /remind) は snake_case で
 * 例示しているが、これはドキュメント側の不整合であり実装は camelCase が正。
 */
export interface RemindRespondentsResponse {
  data: {
    surveyId: number
    remindedCount: number
    remainingRemindQuota: number
    message: string
  }
}
