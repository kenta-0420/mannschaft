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
