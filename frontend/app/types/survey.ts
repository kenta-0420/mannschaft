export type SurveyStatus = 'DRAFT' | 'PUBLISHED' | 'CLOSED'
export type QuestionType = 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'TEXT' | 'RATING' | 'DATE'
export type ResultsVisibility = 'CREATOR_ONLY' | 'RESPONDENTS' | 'ALL_MEMBERS'
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
