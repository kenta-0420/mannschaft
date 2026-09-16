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

/** 監査ログの絞り込み条件（ページング方式に依存しない部分）。 */
export interface AuditLogFilterParams {
  userId?: number
  targetUserId?: number
  eventType?: string
  eventCategory?: EventCategory
  from?: string
  to?: string
}

/**
 * オフセットページング用のパラメータ。
 *
 * `/api/v1/admin/audit-logs`（`PagedResponse`）専用。
 */
export interface AuditLogParams extends AuditLogFilterParams {
  page?: number
  size?: number
}

/**
 * カーソルページング用のパラメータ。
 *
 * チーム・組織の監査ログ（`CursorPagedResponse`）専用。BE は `page` / `size` を読まず
 * `cursor` / `limit` のみを見るため、オフセット用パラメータを渡しても効かない（CMP-260912-1823）。
 */
export interface AuditLogCursorParams extends AuditLogFilterParams {
  cursor?: string
  limit?: number
}
