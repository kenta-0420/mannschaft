export type CirculationStatus = 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'

export interface CirculationRecipient {
  userId: number
  displayName: string
  avatarUrl: string | null
  groupOrder: number
  stampStatus: 'PENDING' | 'STAMPED' | 'SKIPPED'
  stampedAt: string | null
  readAt: string | null
  comment: string | null
  delegatedBy: { id: number; displayName: string } | null
}

export interface CirculationAttachment {
  id: number
  fileName: string
  fileSize: number
  mimeType: string
  url: string
}

export interface CirculationResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  title: string
  body: string | null
  status: CirculationStatus
  createdBy: { id: number; displayName: string; avatarUrl: string | null }
  stampDisplayStyle: string
  deadline: string | null
  recipientCount: number
  stampedCount: number
  attachments: CirculationAttachment[]
  createdAt: string
  updatedAt: string
}

export interface CirculationDetailResponse {
  data: CirculationResponse & {
    recipients: CirculationRecipient[]
  }
}

export interface CreateCirculationRequest {
  title: string
  body?: string
  stampDisplayStyle?: string
  deadline?: string
  recipientGroups: Array<{
    groupOrder: number
    userIds: number[]
  }>
}

export interface UpdateCirculationRequest {
  title?: string
  body?: string
  priority?: string
  dueDate?: string
  reminderEnabled?: boolean
  reminderIntervalHours?: number
  stampDisplayStyle?: string
}

export interface CirculationComment {
  id: number
  documentId: number
  userId: number
  displayName: string
  avatarUrl: string | null
  body: string
  createdAt: string
  updatedAt: string
}

export interface CirculationStampRequest {
  sealId: number
  targetType: string
  targetId: number
  stampDocumentHash?: string
}

export interface CirculationStatsResponse {
  totalDocuments: number
  inProgress: number
  completed: number
  overdueCount: number
}

export interface AddRecipientsRequest {
  recipients: Array<{
    userId: number
    groupOrder?: number
  }>
}

export interface CreateAttachmentRequest {
  attachmentType: string
  fileKey?: string
  originalFilename?: string
  fileSize?: number
  mimeType?: string
}

/**
 * F13 Phase 5-a: 回覧板添付ファイル presign-upload リクエスト型。
 * サーバー側で新統一パス命名規則に従った fileKey を生成してもらう。
 */
export interface CirculationAttachmentPresignRequest {
  fileName: string
  contentType: string
  fileSize: number
}

/**
 * F13 Phase 5-a: 回覧板添付ファイル presign-upload レスポンス型。
 * uploadUrl を使って R2 に直接 PUT し、完了後に fileKey を addAttachment API に渡す。
 */
export interface CirculationAttachmentPresignResponse {
  uploadUrl: string
  fileKey: string
  expiresInSeconds: number
}
