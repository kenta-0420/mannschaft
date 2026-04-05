// ===== Reports =====
export interface ReportResponse {
  id: number
  targetType: string
  targetId: number
  reportedBy: number
  scopeType: string
  scopeId: number
  targetUserId: number
  reason: string
  description: string
  contentSnapshot: string | null
  status: string
  reviewedBy: number | null
  reviewedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ResolveReportRequest {
  actionType: string
  note?: string
  freezeUntil?: string
  guidelineSection?: string
}

export interface EscalateRequest {
  reason?: string
  guidelineSection?: string
}

export interface BulkResolveRequest {
  reportIds: number[]
  actionType: string
  note?: string
  guidelineSection?: string
}

export interface ReportStatsResponse {
  pendingCount: number
  reviewingCount: number
  escalatedCount: number
  resolvedCount: number
  dismissedCount: number
  totalCount: number
}

// ===== Report Actions =====
export interface ReportActionResponse {
  id: number
  reportId: number
  actionType: string
  actionBy: number
  note: string
  freezeUntil: string | null
  guidelineSection: string | null
  createdAt: string
}

// ===== Internal Notes =====
export interface InternalNoteResponse {
  id: number
  reportId: number
  authorId: number
  note: string
  createdAt: string
}

export interface CreateInternalNoteRequest {
  note: string
}

// ===== Feedbacks =====
export interface FeedbackResponse {
  id: number
  scopeType: string
  scopeId: number
  category: string
  title: string
  body: string
  isAnonymous: boolean
  submittedBy: number
  status: string
  adminResponse: string | null
  respondedBy: number | null
  respondedAt: string | null
  isPublicResponse: boolean
  voteCount: number
  createdAt: string
  updatedAt: string
}

export interface FeedbackRespondRequest {
  adminResponse: string
  isPublicResponse?: boolean
}

export interface FeedbackStatusRequest {
  status: string
}

// ===== Notification Stats =====
export interface AdminNotificationStatsResponse {
  id: number
  date: string
  channel: string
  sentCount: number
  deliveredCount: number
  failedCount: number
  bounceCount: number
}

// ===== Seals =====
export interface SealResponse {
  id: number
  userId: number
  variant: string
  displayText: string
  svgData: string
  sealHash: string
  generationVersion: number
  createdAt: string
  updatedAt: string
}

// ===== Action Templates =====
export interface ActionTemplateResponse {
  id: number
  name: string
  actionType: string
  reason: string
  templateText: string
  isDefault: boolean
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface CreateActionTemplateRequest {
  name?: string
  actionType: string
  reason?: string
  templateText: string
  isDefault?: boolean
}

// ===== Form Presets =====
export interface FormPresetResponse {
  id: number
  name: string
  description: string
  category: string
  fieldsJson: string
  icon: string
  color: string
  isActive: boolean
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface CreateFormPresetRequest {
  name?: string
  description?: string
  category?: string
  fieldsJson: string
  icon?: string
  color?: string
}

// ===== Warning Re-reviews =====
export interface ReviewReReviewRequest {
  status: string
  reviewNote?: string
}

// ===== User Violations =====
export interface UserViolationHistoryResponse {
  userId: number
  activeWarningCount: number
  activeContentDeleteCount: number
  totalViolationCount: number
  violations: ViolationResponse[]
  yabai: boolean
}

export interface ViolationResponse {
  id: number
  userId: number
  reportId: number
  actionId: number
  violationType: string
  reason: string
  expiresAt: string | null
  isActive: boolean
  createdAt: string
}
