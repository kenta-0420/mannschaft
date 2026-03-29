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

export interface SafetyTemplateResponse {
  id: number
  scopeType: string
  scopeId: number
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
export interface WarningReReviewResponse {
  id: number
  userId: number
  reportId: number
  actionId: number
  reason: string
  status: string
  adminReviewedBy: number | null
  adminReviewNote: string | null
  adminReviewedAt: string | null
  escalationReason: string | null
  systemAdminReviewedBy: number | null
  systemAdminReviewNote: string | null
  systemAdminReviewedAt: string | null
  createdAt: string
}

export interface ReviewReReviewRequest {
  status: string
  reviewNote?: string
}

// ===== Yabai Unflag =====
export interface YabaiUnflagResponse {
  id: number
  userId: number
  reason: string
  status: string
  reviewedBy: number | null
  reviewNote: string | null
  reviewedAt: string | null
  nextEligibleAt: string | null
  createdAt: string
}

export interface ReviewUnflagRequest {
  status: string
  reviewNote?: string
}

// ===== User Violations =====
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

export interface UserViolationHistoryResponse {
  userId: number
  activeWarningCount: number
  activeContentDeleteCount: number
  totalViolationCount: number
  violations: ViolationResponse[]
  yabai: boolean
}

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
