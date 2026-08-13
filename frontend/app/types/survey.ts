import type { components } from '~/types/generated'

export type SurveyStatus = 'DRAFT' | 'PUBLISHED' | 'CLOSED' | 'ARCHIVED'
export type QuestionType = 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'TEXT' | 'RATING' | 'DATE'
/**
 * 結果公開設定（FE ドメイン名）。
 *
 * - `CREATOR_ONLY`: 作成者・管理者のみ閲覧可
 * - `RESPONDENTS`: 回答した人だけが閲覧可
 * - `ALL_MEMBERS`: 締切前も含め、配信対象スコープの所属者が中間集計を閲覧可
 * - `AFTER_CLOSE`: 締め切り（CLOSED）後にスコープ所属者が閲覧可
 * - `VIEWERS_ONLY`: `survey_result_viewers` の名簿で指定された閲覧者のみ閲覧可
 *
 * Backend `ResultsVisibility` enum とは **1:1 に対応する**（Issue #2617-1,2）。
 * かつて FE 4 値 → BE 4 値の写像に 2 対 1 の畳み込み（`ALL_MEMBERS`/`CREATOR_ONLY` → `ADMINS_ONLY`、
 * `VIEWERS_ONLY`/`ADMINS_ONLY` → `CREATOR_ONLY`）があり、読み込んで更新すると値が化ける
 * 往復損失を起こしていた。BE に `ALWAYS` が実装された（Issue #2635）ため写像を全単射に直した。
 *
 * | FE             | BE               |
 * |----------------|------------------|
 * | `CREATOR_ONLY` | `ADMINS_ONLY`    |
 * | `RESPONDENTS`  | `AFTER_RESPONSE` |
 * | `ALL_MEMBERS`  | `ALWAYS`         |
 * | `AFTER_CLOSE`  | `AFTER_CLOSE`    |
 * | `VIEWERS_ONLY` | `VIEWERS_ONLY`   |
 */
export type ResultsVisibility =
  | 'CREATOR_ONLY'
  | 'RESPONDENTS'
  | 'ALL_MEMBERS'
  | 'AFTER_CLOSE'
  | 'VIEWERS_ONLY'
export type UnrespondedVisibility = 'HIDDEN' | 'CREATOR_AND_ADMIN' | 'ALL_MEMBERS'
/** 配信モード。BE `DistributionMode` enum と同値。 */
export type DistributionMode = 'ALL' | 'TARGETED'

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
  /** FE ドメイン値。BE 値は useSurveyApi の翻訳層で {@link ResultsVisibility} へ写してある。 */
  resultsVisibility: ResultsVisibility
  unrespondedVisibility: UnrespondedVisibility
}
export interface SurveyDistributionDto {
  distributionMode: DistributionMode | null
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
  /**
   * 回答締切。BE `CreateSurveyRequest.expiresAt` と同名・同義（オフセット付き ISO 文字列）。
   * かつて `deadline` という BE に存在しない名前で送っており、締切が黙って捨てられていた。
   */
  expiresAt?: string
  /**
   * 配信モード。BE は `@NotBlank`（`ALL` / `TARGETED`）。
   * 省略時は useSurveyApi が `ALL` を補う。
   */
  distributionMode?: DistributionMode
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
  /** 回答締切。BE `UpdateSurveyRequest.expiresAt` と同名・同義。 */
  expiresAt?: string | null
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
 * BE `ResultsVisibility` enum の値。生成型（`docs/openapi.json` 由来）が正準。
 * FE 側の {@link ResultsVisibility} とは名前が異なるだけで 1:1 に対応する。
 */
export type BeResultsVisibility = NonNullable<
  components['schemas']['SurveyPolicyDto']['resultsVisibility']
>

/**
 * BE survey の生形。
 * policy.resultsVisibility だけが BE enum 値（`AFTER_RESPONSE` 等）で来るため、
 * その 1 キーを {@link BeResultsVisibility} に差し替えて受ける。
 */
export type SurveyResponseWire = Omit<SurveyResponse, 'policy'> & {
  policy: Omit<SurveyPolicyDto, 'resultsVisibility'> & {
    resultsVisibility: BeResultsVisibility
  }
}

/**
 * BE 詳細レスポンス全体。
 *
 * Issue #2635 で `SurveyDetailResponse` がフラット化され、`data.survey` の入れ子が消えて
 * `SurveyResponse` の 9 フィールドが `data` 直下に並び `questions` が加わった。
 * 対象は作成 POST・詳細 GET・複製 POST の 3 経路（一覧・更新・公開・締切・延長は
 * 従来どおりフラットな `SurveyResponse`）。
 */
export interface SurveyDetailWire {
  data: SurveyResponseWire & {
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
