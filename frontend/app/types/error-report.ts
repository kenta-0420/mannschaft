/**
 * F12.5 Phase 2 — エラーレポート（システム管理者向け）型定義。
 */

import type { PageMeta } from '~/types/api'

// ===== Enum 系 =====

export type WorkflowStage =
  | 'INVESTIGATION_STARTED'
  | 'ROOT_CAUSE_IDENTIFIED'
  | 'FIX_IN_PROGRESS'
  | 'TEST_COMPLETED'
  | 'RELEASED'

export type ErrorReportStatus = 'NEW' | 'INVESTIGATING' | 'RESOLVED' | 'REOPENED' | 'IGNORED'

export type ErrorReportSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'

export type ActivityType =
  | 'STATUS_CHANGED'
  | 'WORKFLOW_CHANGED'
  | 'ASSIGNEE_CHANGED'
  | 'SEVERITY_CHANGED'
  | 'COMMENT_ADDED'
  | 'AI_ANALYZED'
  | 'GITHUB_ISSUE_CREATED'
  | 'REOPENED'
  | 'RESOLVED'

// ===== レスポンス型 =====

export interface ErrorReportAiAnalysisSummary {
  id: number
  estimatedCause: string | null
  fixProposal: string | null
  impactAssessment: string | null
  suggestedFiles: string | null
  createdAt: string
}

export interface ErrorReportDetail {
  id: number
  errorMessage: string
  stackTrace: string | null
  pageUrl: string
  userAgent: string | null
  userComment: string | null
  userId: number | null
  organizationId: number | null
  requestId: string | null
  ipAddress: string | null
  occurredAt: string
  status: ErrorReportStatus
  severity: ErrorReportSeverity
  resolvedBy: number | null
  resolvedAt: string | null
  adminNote: string | null
  latestUserComment: string | null
  errorHash: string
  occurrenceCount: number
  affectedUserCount: number
  firstOccurredAt: string
  lastOccurredAt: string
  createdAt: string
  updatedAt: string

  // F12.5 Phase 2 追加
  workflowStage: WorkflowStage | null
  assigneeId: number | null
  assigneeName: string | null
  githubIssueUrl: string | null
  lastAiAnalysisAt: string | null
  latestAiAnalysis: ErrorReportAiAnalysisSummary | null

  // F10.6 Phase 10-δ 追加
  /** SLA 対応期限。severity=LOW は NULL。 */
  slaDueAt: string | null
}

export interface TimelineItem {
  type: 'OCCURRENCE' | 'ACTIVITY'
  occurredAt: string
  // OCCURRENCE 用
  pageUrl?: string | null
  userId?: number | null
  userAgent?: string | null
  // ACTIVITY 用
  activityType?: ActivityType | null
  actorId?: number | null
  actorName?: string | null
  systemActor?: boolean
  content?: string | null
  metadata?: Record<string, unknown> | null
}

export interface TimelineResponse {
  items: TimelineItem[]
  hasMore: boolean
  nextCursor: string | null
}

// ===== リクエスト型 =====

export interface ListParams {
  status?: ErrorReportStatus
  severity?: ErrorReportSeverity
  from?: string
  to?: string
  page?: number
  size?: number
  sort?: string
  /** F10.6 Phase 10-δ — SLA超過のみ表示フィルタ。 */
  overdueOnly?: boolean
}

export interface ListResponse {
  data: ErrorReportDetail[]
  meta: PageMeta
}

// ===== F12.5 Phase 2-C — AI 分析関連 =====

export interface AiAnalysisResponse {
  id: number
  errorReportId: number
  modelName: string
  promptTokens: number
  completionTokens: number
  estimatedCause: string | null
  fixProposal: string | null
  impactAssessment: string | null
  suggestedFiles: string[]
  status: 'SUCCESS' | 'FAILED'
  errorMessage: string | null
  createdBy: number | null
  createdByName: string | null
  createdAt: string
}

export interface AiAnalysisListResponse {
  data: AiAnalysisResponse[]
  meta: PageMeta
}

// ===== F12.5 Phase 2-D — GitHub Issue / Config =====

export interface GitHubIssueCreateResponse {
  url: string
}

export interface ErrorReportConfig {
  githubEnabled: boolean
  aiEnabled: boolean
  aiModel: string
  aiMonthlyBudgetJpy: number
}

// ===== F12.5 Phase 2-E — Kanban =====

/**
 * Kanban カラムの key。"NULL" は未着手カラムを表す。
 */
export type KanbanStageKey = 'NULL' | WorkflowStage

export interface KanbanCard {
  id: number
  errorMessage: string
  severity: ErrorReportSeverity
  status: ErrorReportStatus
  occurrenceCount: number
  affectedUserCount: number
  lastOccurredAt: string
  assigneeId: number | null
  assigneeName: string | null
  pageUrl: string
  hasGithubIssue: boolean
  hasAiAnalysis: boolean
}

export interface KanbanColumn {
  stageKey: KanbanStageKey
  totalCount: number
  cards: KanbanCard[]
  hasMore: boolean
}

export interface KanbanResponse {
  columns: KanbanColumn[]
}

// ===== F10.6 Phase 10-δ — 担当者候補 =====

export interface AssignableUser {
  id: number
  displayName: string
  profileImageUrl: string | null
}
