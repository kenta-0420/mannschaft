import type { PageMeta } from '~/types/api'

// ===== Announcements =====
export interface AnnouncementResponse {
  id: number
  title: string
  body: string
  priority: string
  targetScope: string
  isPinned: boolean
  publishedAt: string | null
  expiresAt: string | null
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface CreateAnnouncementRequest {
  title: string
  body: string
  priority?: string
  targetScope?: string
  isPinned?: boolean
  expiresAt?: string
}

export interface UpdateAnnouncementRequest {
  title?: string
  body?: string
  priority?: string
  targetScope?: string
  isPinned?: boolean
  expiresAt?: string
}

// ===== Feature Flags =====
export interface FeatureFlagResponse {
  id: number
  flagKey: string
  isEnabled: boolean
  description: string
  updatedBy: number
  createdAt: string
  updatedAt: string
}

// ===== Maintenance Schedules =====
export interface MaintenanceScheduleResponse {
  id: number
  title: string
  message: string
  mode: string
  startsAt: string
  endsAt: string
  status: string
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface CreateMaintenanceScheduleRequest {
  title?: string
  message?: string
  mode?: string
  startsAt: string
  endsAt: string
}

export interface UpdateMaintenanceScheduleRequest {
  title?: string
  message?: string
  mode?: string
  startsAt?: string
  endsAt?: string
}

// ===== Templates =====
export interface TemplateFieldValueResponse {
  fieldId: number
  fieldName: string
  defaultValue: string
}

export interface SystemTemplateResponse {
  id: number
  name: string
  titleTemplate: string
  noteTemplate: string
  defaultDurationMinutes: number
  sortOrder: number
  scope: string
  teamId: number | null
  organizationId: number | null
  customFieldValues: TemplateFieldValueResponse[]
}

export interface CreateTemplateRequest {
  name?: string
  templateJson: string
}

// ===== Modules =====
export interface LevelAvailabilityResponse {
  level: string
  isAvailable: boolean
  note: string
}

export interface ModuleSummaryResponse {
  id: number
  name: string
  slug: string
  moduleType: string
}

export interface ModuleResponse {
  id: number
  name: string
  slug: string
  description: string
  moduleType: string
  moduleNumber: number
  requiresPaidPlan: boolean
  trialDays: number
  isActive: boolean
  levelAvailability: LevelAvailabilityResponse[]
  recommendations: ModuleSummaryResponse[]
}

/** PATCH /api/v1/system-admin/modules/{id}/paid-plan のリクエスト */
export interface UpdateModulePaidPlanRequest {
  requiresPaidPlan: boolean
}

/** PATCH /api/v1/system-admin/modules/{id}/active のリクエスト */
export interface UpdateModuleActiveRequest {
  isActive: boolean
}

// ===== Batch Logs =====
export interface BatchJobLogResponse {
  id: number
  jobName: string
  status: string
  startedAt: string
  finishedAt: string | null
  processedCount: number
  errorMessage: string | null
  createdAt: string
}

// ===== F10.X 第二陣: 汎用バッチキック API =====
/** バッチ直近実行ステータス（{@code @SchedulerLock} の SKIPPED を含む） */
export type BatchEndpointLastStatus = 'SUCCESS' | 'FAILED' | 'RUNNING' | 'SKIPPED'

/** バッチ起動応答ステータス */
export type BatchTriggerStatus = 'ACCEPTED' | 'COMPLETED' | 'FAILED' | 'LOCKED'

/** 登録済みバッチエンドポイントの概要 DTO（GET /api/v1/system-admin/batch） */
export interface BatchEndpointSummary {
  name: string
  description: string
  schedulerLockName: string | null
  /** 直近実行ステータス（履歴が無ければ null） */
  lastStatus: BatchEndpointLastStatus | null
  /** 直近実行の開始時刻（履歴が無ければ null） */
  lastStartedAt: string | null
}

/** バッチ直近実行状況応答（GET /api/v1/system-admin/batch/{name}/status） */
export interface BatchStatusResponse {
  name: string
  lastJobLog: BatchJobLogResponse | null
}

/** バッチ起動応答（POST /api/v1/system-admin/batch/{name}/trigger） */
export interface BatchTriggerResponse {
  name: string
  status: BatchTriggerStatus
  jobLogId: number | null
  message: string
}

// ===== Notification Stats =====
export interface NotificationStatsResponse {
  id: number
  date: string
  channel: string
  sentCount: number
  deliveredCount: number
  failedCount: number
  bounceCount: number
}

// ===== Moderation Dashboard =====
export interface ModerationDashboardResponse {
  pendingReportsCount: number
  pendingAppealsCount: number
  pendingReReviewsCount: number
  escalatedReReviewsCount: number
  pendingUnflagRequestsCount: number
  activeViolationsCount: number
  yabaiUsersCount: number
}

// ===== Moderation Settings =====
export interface ModerationSettingsResponse {
  id: number
  settingKey: string
  settingValue: string
  description: string
  updatedBy: number
  updatedAt: string
}

// ===== Moderation Templates =====
export interface ModerationTemplateResponse {
  id: number
  name: string
  actionType: string
  reason: string
  templateText: string
  language: string
  isDefault: boolean
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface CreateModerationTemplateRequest {
  name?: string
  actionType?: string
  reason?: string
  templateText: string
  language?: string
  isDefault?: boolean
}

// ===== Affiliate Configs =====
export interface AffiliateConfigResponse {
  id: number
  provider: string
  tagId: string
  placement: string
  description: string
  bannerImageUrl: string
  bannerWidth: number
  bannerHeight: number
  altText: string
  isActive: boolean
  activeFrom: string | null
  activeUntil: string | null
  displayPriority: number
  createdAt: string
  updatedAt: string
}

export interface CreateAffiliateConfigRequest {
  provider?: string
  tagId?: string
  placement?: string
  description?: string
  bannerImageUrl?: string
  bannerWidth?: number
  bannerHeight?: number
  altText?: string
  activeFrom?: string
  activeUntil?: string
  displayPriority: number
}

// ===== Tournament Presets =====
export interface TournamentPresetResponse {
  id: number
  category: string
  name: string
  description: string
  icon: string
  color: string
  isParticipantRequired: boolean
  defaultVisibility: string
  fieldsJson: string
  isActive: boolean
  createdAt: string
  updatedAt: string
}

// ===== Safety Checks =====
export interface SafetyPresetResponse {
  id: number
  body: string
  sortOrder: number
  isActive: boolean
  createdAt: string
}

// system-admin 専用の安否確認テンプレート（safety.ts の SafetyTemplateResponse とは別物）
export interface SystemAdminSafetyTemplateResponse {
  id: number
  scopeType: string
  scopeId: string
  templateName: string
  title: string
  message: string
  reminderIntervalMinutes: number
  isSystemDefault: boolean
  sortOrder: number
  createdBy: number
  createdAt: string
}

// ===== Template Wallpapers =====
export interface WallpaperResponse {
  id: number
  templateSlug: string
  name: string
  imageUrl: string
  thumbnailUrl: string
  category: string
  sortOrder: number
  active: boolean
}

export interface CreateWallpaperRequest {
  templateSlug?: string
  name?: string
  imageUrl?: string
  thumbnailUrl?: string
  category?: string
  sortOrder?: number
}

// ===== Activity Template Presets =====
export interface ActivityTemplatePresetResponse {
  id: number
  category: string
  name: string
  description: string
  icon: string
  color: string
  isParticipantRequired: boolean
  defaultVisibility: string
  fieldsJson: string
  isActive: boolean
  createdAt: string
  updatedAt: string
}

// ===== Warning Re-reviews =====
// WarningReReviewResponse と YabaiUnflagResponse は warning.ts で定義（重複を避けるため再エクスポート）
export type { WarningReReviewResponse, YabaiUnflagResponse } from './warning'

// ReviewReReviewRequest は admin-report.ts で定義（重複を避けるため再エクスポート）
export type { ReviewReReviewRequest } from './admin-report'

export interface ReviewUnflagRequest {
  status: string
  reviewNote?: string
}

// ===== User Violations =====
// ViolationResponse と UserViolationHistoryResponse は admin-report.ts で定義（重複を避けるため再エクスポート）
export type { ViolationResponse, UserViolationHistoryResponse } from './admin-report'

// ===== Organization Entity =====
export interface OrganizationEntity {
  id: number
  createdAt: string
  updatedAt: string
  name: string
  nameKana: string
  nickname1: string | null
  nickname2: string | null
  orgType: 'SCHOOL' | 'COMPANY' | 'NPO' | 'COMMUNITY' | 'GOVERNMENT' | 'OTHER'
  parentOrganizationId: number | null
  prefecture: string
  city: string
  visibility: 'PUBLIC' | 'PRIVATE'
  hierarchyVisibility: 'NONE' | 'BASIC' | 'FULL'
  supporterEnabled: boolean
  version: number
  archivedAt: string | null
  deletedAt: string | null
}

export interface PagedResponse<T> {
  data: T[]
  meta: PageMeta
}

// ===== Error Reports (F12.5) =====
export interface ErrorReportResponse {
  id: number
  errorMessage: string
  stackTrace: string
  pageUrl: string
  userAgent: string
  userComment: string
  userId: number | null
  organizationId: number | null
  requestId: string
  ipAddress: string
  occurredAt: string
  status: string
  severity: string
  resolvedBy: number | null
  resolvedAt: string | null
  adminNote: string
  latestUserComment: string
  errorHash: string
  occurrenceCount: number
  affectedUserCount: number
  firstOccurredAt: string
  lastOccurredAt: string
  createdAt: string
  updatedAt: string
}

export interface ErrorReportStatsResponse {
  totalNew: number
  totalInvestigating: number
  totalReopened: number
  totalToday: number
  topErrors: Array<{
    errorHash: string
    errorMessage: string
    pageUrl: string
    occurrenceCount: number
    affectedUserCount: number
    lastOccurredAt: string
  }>
}

// ===== Beta Restriction (F00.6) =====
export interface BetaRestrictionConfigResponse {
  isEnabled: boolean
  maxTeamId: number | null
  maxOrgId: number | null
  updatedAt: string
}

export interface UpdateBetaRestrictionRequest {
  isEnabled: boolean
  maxTeamId: number | null
  maxOrgId: number | null
}

// ===== GDPR パージ状況 (Phase E) =====
/** GDPR パージ処理の状態 */
export type GdprPurgeStatus = 'PENDING' | 'SUCCESS'

/** GDPR パージ対象ドメイン名 */
export type GdprPurgeDomain = 'role' | 'team' | 'payment' | 'chart' | 'proxy' | 'errorreport'

/** GDPR パージ状況の 1 行（ユーザー × ドメイン） */
export interface GdprPurgeStatusRow {
  userId: number
  emailHash: string
  domainName: string
  status: GdprPurgeStatus
  attemptedAt: string
  completedAt: string | null
  isAlert: boolean
  /** Phase F 追加: これまでの retry 試行回数 */
  retryCount: number
  /** Phase F 追加: 最後に retry を実行した日時（未実行なら null） */
  lastRetriedAt: string | null
}

/** Phase F: ドメイン単位の GDPR パージ手動 retry 結果 */
export interface GdprPurgeRetryResult {
  succeeded: boolean
  domainName: string
  newStatus: GdprPurgeStatus
  retryCount: number
  message: string
}

/** ドメイン別サマリー */
export interface GdprPurgeDomainSummary {
  domain: string
  pendingCount: number
  successCount: number
}

/** GDPR パージ状況サマリー（GET /api/v1/system-admin/gdpr/purge-status/summary） */
export interface GdprPurgeSummaryData {
  totalPending: number
  totalSuccess: number
  alertCount: number
  byDomain: GdprPurgeDomainSummary[]
}

/** ページネーション付き GDPR パージ状況レスポンス */
export interface GdprPurgeStatusPage {
  content: GdprPurgeStatusRow[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** GET /api/v1/system-admin/gdpr/purge-status のクエリパラメータ */
export interface GdprPurgeStatusQuery {
  status?: string
  domain?: string
  dateFrom?: string
  dateTo?: string
  page?: number
  size?: number
}

// ===== セキュリティスキャン状態 =====

/** OWASP Dependency-Check スキャンの結論ステータス */
export type SecurityScanConclusion = 'SUCCESS' | 'FAILURE' | 'IN_PROGRESS' | 'UNKNOWN'

/** セキュリティスキャン状態レスポンス（GET /api/v1/system-admin/security-scan/status） */
export interface SecurityScanStatusResponse {
  /** スキャン結論（"SUCCESS" | "FAILURE" | "IN_PROGRESS" | "UNKNOWN"） */
  conclusion: SecurityScanConclusion
  /** GitHub Actions の実行 URL（null の場合は取得失敗） */
  runUrl: string | null
  /** 最終実行日時 ISO 8601 文字列（null の場合は実行履歴なし） */
  runAt: string | null
}
