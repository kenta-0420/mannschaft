export type ReportStatus = 'PENDING' | 'REVIEWING' | 'RESOLVED' | 'DISMISSED'
export type ViolationSeverity = 'WARNING' | 'MUTE' | 'SUSPENSION' | 'BAN'

export interface ContentReportResponse {
  id: number
  reporterUserId: number
  reporterName: string
  targetType: 'TIMELINE_POST' | 'CHAT_MESSAGE' | 'BLOG_POST' | 'BULLETIN_THREAD' | 'SOCIAL_PROFILE'
  targetId: number
  targetPreview: string | null
  reason: string
  category: string
  status: ReportStatus
  reviewedBy: { id: number; displayName: string } | null
  reviewNote: string | null
  actionTaken: string | null
  createdAt: string
  updatedAt: string
}

export interface UserViolation {
  id: number
  userId: number
  displayName: string
  severity: ViolationSeverity
  reason: string
  reportId: number | null
  expiresAt: string | null
  isActive: boolean
  createdBy: { id: number; displayName: string }
  createdAt: string
}

export interface ModerationAppeal {
  id: number
  violationId: number
  userId: number
  displayName: string
  appealText: string
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED'
  reviewNote: string | null
  createdAt: string
}

export interface AuditLogResponse {
  id: number
  actorId: number
  actorName: string
  action: string
  targetType: string
  targetId: number | null
  details: Record<string, unknown> | null
  ipAddress: string | null
  createdAt: string
}
