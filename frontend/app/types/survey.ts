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

// === Survey Response ===
export interface SurveyScopeDto {
  scopeType: string
  scopeId: string
}
export interface SurveyContentDto {
  title: string
  description: string | null
}
export interface SurveyPolicyDto {
  isAnonymous: boolean
  allowMultipleSubmissions: boolean
  resultsVisibility: string
  unrespondedVisibility: string
}
export interface SurveyDistributionDto {
  distributionMode: string | null
  autoPostToTimeline: boolean | null
  seriesId: string | null
  remindBeforeHours: string | null
  manualRemindCount: number | null
}
export interface SurveyScheduleDto {
  startsAt: string | null
  expiresAt: string | null
  publishedAt: string | null
  closedAt: string | null
}
export interface SurveyStatsDto {
  responseCount: number
  targetCount: number | null
}
export interface SurveyAuditDto {
  version: number
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface SurveyResponse {
  id: number
  status: SurveyStatus
  scope: SurveyScopeDto
  content: SurveyContentDto
  policy: SurveyPolicyDto
  distribution: SurveyDistributionDto
  schedule: SurveyScheduleDto
  stats: SurveyStatsDto
  audit: SurveyAuditDto
  hasResponded?: boolean
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
    hasResponded?: boolean
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
  /**
   * F05.4 (B) 組織→参加チーム配信: チーム別内訳（by_team）集計を有効にするか。
   * 既定 false。組織スコープのアンケートでのみ意味を持つ。匿名（isAnonymous=true）との併用は
   * BE 側で 400（SURVEY_023）になるため UI で相互排他にする。
   * 生成型 components['schemas']['CreateSurveyRequest'].teamBreakdownEnabled と同値。
   */
  teamBreakdownEnabled?: boolean
  /**
   * 設問リスト。下書き作成時は省略可（BE 側で @NotEmpty なし）。
   * 設問なし＝DRAFT として保存し、後から設問を追加して公開する二段フロー用。
   */
  questions?: Array<{
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
  fullName: string
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

// === Backend wire 型（実 BE が返す入れ子・enum 値の生形）===
//
// 詳細/回答取得の実 BE レスポンスは FE consumer が期待するフラット形と複数次元でズレるため、
// useSurveyApi の翻訳層（アダプタ）が「wire 形 → フラット形」へ変換する。
// 画面（pages/surveys/[surveyId].vue）と SurveyResponseForm.vue は無改修のまま、
// 下記 wire 型は「BE が実際に返す形」を表現する受け皿として用いる。

/** BE questionType enum（実 BE 値）。FE の {@link QuestionType} とは SCALE/FREE_TEXT が異なる。 */
export type BeQuestionType = 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'FREE_TEXT' | 'SCALE'

/** BE 設問選択肢の生形（options[]）。 */
export interface SurveyOptionWire {
  id: number
  questionId: number
  optionText: string
  displayOrder: number
}

/** BE 設問の生形（content/scaleConfig が入れ子）。 */
export interface SurveyQuestionWire {
  id: number
  surveyId: number
  questionType: BeQuestionType
  content: {
    questionText: string
    isRequired: boolean
    displayOrder: number
    maxSelections: number | null
  }
  scaleConfig: {
    scaleMin: number | null
    scaleMax: number | null
    scaleMinLabel: string | null
    scaleMaxLabel: string | null
  }
  createdAt?: string
  options: SurveyOptionWire[]
}

/**
 * BE survey の生形。
 * policy.resultsVisibility のみ BE enum 値（AFTER_RESPONSE 等）で来るため、
 * {@link SurveyResponse} の policy を string で受けられるよう緩める。
 */
export type SurveyResponseWire = Omit<SurveyResponse, 'policy'> & {
  policy: Omit<SurveyPolicyDto, 'resultsVisibility'> & { resultsVisibility: string }
}

/** BE 詳細レスポンス全体（survey と questions が data 配下で分離）。 */
export interface SurveyDetailWire {
  data: {
    survey: SurveyResponseWire
    questions: SurveyQuestionWire[]
  }
}

/** BE「自分の回答」1 行の生形。questionId 単位の行配列で返る。 */
export interface SurveyResponseRowWire {
  id: number
  surveyId: number
  questionId: number
  userId: number
  optionId: number | null
  textResponse: string | null
  createdAt?: string
}

/** BE「自分の回答」レスポンス全体（行配列）。 */
export interface MyResponseWire {
  data: SurveyResponseRowWire[]
}
