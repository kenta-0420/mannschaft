export interface ActivityRecordResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  templateId: number | null
  templateName: string | null
  title: string
  activityDate: string
  location: string | null
  description: string | null
  participants: Array<{ userId: number; displayName: string; avatarUrl: string | null }>
  participantCount: number
  customFields: Array<{ fieldId: number; fieldName: string; fieldType: string; value: string | null }>
  isPublic: boolean
  createdBy: { id: number; displayName: string } | null
  createdAt: string
  updatedAt: string
}

export interface ActivityTemplate {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  name: string
  description: string | null
  fields: Array<{ id: number; fieldName: string; fieldType: string; isRequired: boolean; sortOrder: number }>
  isOfficial: boolean
  createdAt: string
}

export interface ActivityComment {
  id: number
  activityId: number
  userId: number
  displayName: string
  avatarUrl: string | null
  body: string
  createdAt: string
  updatedAt: string
}

export interface ActivityStats {
  totalActivities: number
  totalParticipants: number
  averageParticipants: number
  monthlyBreakdown: Array<{ month: string; count: number }>
}

/**
 * 公開活動記録レスポンス
 * GET /api/v1/public/activities/{id} のレスポンス型（認証不要・PUBLIC のみ）
 */
/**
 * 公開活動記録 API レスポンス
 * GET /api/v1/public/activities/{id} が返す ActivityResultEntity のフィールドに準拠する。
 *
 * NOTE: バックエンドは現在 ActivityResultEntity をそのまま返している。
 * 将来的には専用 DTO（participantCount・customFields・teamName 等）を返すエンドポイントに移行する予定。
 */
export interface PublicActivityResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  templateId: number | null
  title: string
  activityDate: string
  activityTimeStart: string | null
  activityTimeEnd: string | null
  location: string | null
  venueId: number | null
  description: string | null
  fieldValues: string
  attachments: string | null
  visibility: 'PUBLIC' | 'MEMBERS_ONLY' | 'PRIVATE'
  scheduleId: number | null
  createdBy: number | null
  createdAt: string
  updatedAt: string
  // 将来拡張フィールド（現在はバックエンドから返らない）
  participantCount?: number
  customFields?: Array<{ fieldId: number; fieldName: string; fieldType: string; value: string | null }>
  imageUrl?: string | null
  organizationName?: string | null
  teamName?: string | null
}
