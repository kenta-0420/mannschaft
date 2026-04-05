export type EventCategory = 'AUTH' | 'MEMBER' | 'CONTENT' | 'ADMIN' | 'SYSTEM'

export interface AuditLog {
  id: number
  userId: number
  userName: string
  targetUserId: number | null
  targetUserName: string | null
  teamId: number | null
  organizationId: number | null
  eventType: string
  eventCategory: EventCategory
  ipAddress: string
  userAgent: string | null
  metadata: Record<string, unknown> | null
  createdAt: string
}

export interface AuditLogParams {
  userId?: number
  targetUserId?: number
  eventType?: string
  eventCategory?: EventCategory
  from?: string
  to?: string
  page?: number
  size?: number
}
