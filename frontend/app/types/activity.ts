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

/**
 * 活動記録のカスタムフィールド型。
 * バックエンド {@code com.mannschaft.app.activity.FieldType} と一致させる。
 */
export type ActivityFieldType = 'TEXT' | 'NUMBER' | 'DATE' | 'SELECT' | 'CHECKBOX' | 'TEXTAREA'

/**
 * 活動テンプレートのフィールド定義。
 * バックエンド {@code ActivityTemplateResponse.TemplateFieldResponse} に準拠する。
 * - {@code fieldKey}: 活動記録作成時の {@code fieldValues} のキーになる識別子（fieldName/id ではない）
 * - {@code fieldLabel}: 画面表示用ラベル
 * - {@code optionsJson}: SELECT 型の選択肢を表す JSON 文字列（例: {@code ["A","B"]}）
 */
export interface ActivityTemplateField {
  id: number
  fieldKey: string
  fieldLabel: string
  fieldType: ActivityFieldType
  isRequired: boolean
  optionsJson: string | null
  placeholder: string | null
  unit: string | null
  isAggregatable: boolean
  sortOrder: number
}

export interface ActivityTemplate {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  name: string
  description: string | null
  icon: string | null
  color: string | null
  isParticipantRequired: boolean
  defaultVisibility: 'PUBLIC' | 'MEMBERS_ONLY' | null
  sortOrder: number
  fields: ActivityTemplateField[]
  createdAt: string
  updatedAt: string
}

/**
 * 活動記録作成リクエストボディ（手書き）。
 * バックエンド {@code CreateActivityRequest} の body 部分に対応する。
 * scope_type / scope_id はクエリパラメータで送るため body には含めない。
 * NOTE: 作成 API には {@code location} フィールドは存在しない（一覧で返る location は送らない）。
 * visibility は PUBLIC / MEMBERS_ONLY の2値のみ（PRIVATE は無い・未指定時 BE 既定 MEMBERS_ONLY）。
 */
export interface CreateActivityRequestBody {
  templateId: number
  title: string
  /** "yyyy-MM-dd" */
  activityDate: string
  description?: string
  /** "HH:mm" */
  activityTimeStart?: string
  /** "HH:mm" */
  activityTimeEnd?: string
  visibility?: 'PUBLIC' | 'MEMBERS_ONLY'
  /** キーはテンプレの fieldKey。値は fieldType 別の素の型 */
  fieldValues?: Record<string, string | number | boolean>
  participantUserIds?: number[]
  fileIds?: number[]
  scheduleId?: number
  postToTimeline?: boolean
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
