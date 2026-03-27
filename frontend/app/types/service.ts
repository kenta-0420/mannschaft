export type ServiceRecordStatus = 'DRAFT' | 'CONFIRMED'

export interface ServiceRecordResponse {
  id: number
  teamId: number
  targetUserId: number
  targetUser: { id: number; displayName: string; avatarUrl: string | null }
  recordedBy: { id: number; displayName: string } | null
  serviceDate: string
  title: string
  body: string | null
  status: ServiceRecordStatus
  templateId: number | null
  templateName: string | null
  customFields: Array<{ fieldId: number; fieldName: string; fieldType: string; value: string | null }>
  attachments: Array<{ id: number; fileName: string; fileSize: number; url: string }>
  reactionSummary: Record<string, number>
  myReactions: string[]
  createdAt: string
  updatedAt: string
}

export interface ServiceRecordTemplate {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  name: string
  description: string | null
  fields: Array<{ id: number; fieldName: string; fieldType: string; isRequired: boolean; sortOrder: number }>
  createdAt: string
}

export interface ServiceHistorySummary {
  totalRecords: number
  dateRange: { from: string; to: string }
  recordsByMonth: Array<{ month: string; count: number }>
}
