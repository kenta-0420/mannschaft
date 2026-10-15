// 年次更新キャンペーン
export type ResidenceState = 'SELF_RESIDING' | 'RENTED_OUT' | 'LONG_TERM_ABSENT' | 'VACANT' | 'UNRESPONDED'

export interface AnnualReviewResponse {
  id: string
  organizationId: number
  targetYear: number
  title: string
  deadlineDate: string
  responseCount: number
  status: string // 'OPEN' | 'CLOSED'
  closedAt: string | null
  createdAt: string
}

export interface AnnualReviewResponseItem {
  id: string
  annualReviewId: string
  residentRegistryId: number
  residenceState: ResidenceState
  respondedAt: string
  createdAt: string
}

export interface AnnualReviewCreateRequest {
  targetYear: number
  title: string
  deadlineDate: string
}

export interface AnnualReviewResponseSubmitRequest {
  residenceState: ResidenceState
}

// アクティビティスナップショット
export interface ActivitySnapshotItem {
  id: string
  organizationId: number
  residentRegistryId: number
  subjectUserId: number
  snapshotDate: string
  activityScoreTotal: number
  activityBreakdownJson: string | null
  createdAt: string
}

// ダッシュボード
export interface ResidenceStatusDashboard {
  organizationId: number
  totalResidents: number
  highRiskCount: number
  midRiskCount: number
  lowRiskCount: number
  unresponsiveCount: number
  openAnnualReviewCount: number
  generatedAt: string
}

// 見守り委員訪問記録
export type ContactResult = 'MET' | 'NO_RESPONSE' | 'MAILBOX_ABNORMAL' | 'METER_ABNORMAL' | 'NEIGHBOR_INFO' | 'REFUSED' | 'OTHER'

export interface MonitoringVisitResponse {
  id: string
  organizationId: number
  committeeId: number
  residentRegistryId: number
  dwellingUnitId: number
  subjectUserId: number
  visitorUserId: number
  visitedAt: string
  contactResult: ContactResult
  considerationMemo: string | null
  nextVisitRecommendedAt: string | null
  consentCovenantId: string | null
  createdAt: string
}

export interface MonitoringVisitCreateRequest {
  committeeId: number
  residentRegistryId: number
  dwellingUnitId: number
  subjectUserId: number
  visitorUserId: number
  visitedAt: string
  contactResult: ContactResult
  considerationMemo?: string | null
  nextVisitRecommendedAt?: string | null
  consentCovenantId?: string | null
}

// 横展開安否確認
export interface OrgWideSafetyCheckResponse {
  id: string
  organizationId: number
  safetyCheckId: number | null
  triggeredBy: number
  triggeredAt: string
  triggerReason: string
  closedAt: string | null
  createdAt: string
}
