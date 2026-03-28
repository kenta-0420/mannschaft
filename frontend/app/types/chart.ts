export type ChartStatus = 'DRAFT' | 'FINALIZED'

export interface Chart {
  id: number
  teamId: number
  clientUserId: number | null
  clientName: string
  staffUserId: number
  staffName: string
  visitDate: string
  status: ChartStatus
  chiefComplaint: string | null
  notes: string | null
  nextVisitRecommendation: string | null
  isPinned: boolean
  sections: ChartSectionConfig
  photos: ChartPhoto[]
  version: number
  createdAt: string
  updatedAt: string
}

export interface ChartSectionConfig {
  bodyChart: boolean
  colorRecipe: boolean
  beforeAfter: boolean
  questionnaire: boolean
  consent: boolean
}

export interface ChartPhoto {
  id: number
  chartId: number
  photoType: 'BEFORE' | 'AFTER' | 'GENERAL'
  photoUrl: string
  caption: string | null
  sortOrder: number
  createdAt: string
}

export interface ChartTemplate {
  id: number
  teamId: number
  name: string
  sections: ChartSectionConfig
  questionnaireJson: QuestionnaireItem[] | null
  createdAt: string
}

export interface QuestionnaireItem {
  question: string
  type: 'TEXT' | 'SELECT' | 'CHECKBOX'
  options?: string[]
  required: boolean
}

export interface CreateChartRequest {
  clientUserId?: number
  clientName: string
  visitDate: string
  chiefComplaint?: string
  notes?: string
  nextVisitRecommendation?: string
  sections: ChartSectionConfig
  templateId?: number
}

export interface ChartListParams {
  page?: number
  size?: number
  staffId?: number
  clientName?: string
  dateFrom?: string
  dateTo?: string
}
