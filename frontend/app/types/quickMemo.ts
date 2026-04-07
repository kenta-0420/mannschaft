export type QuickMemoStatus = 'UNSORTED' | 'ARCHIVED' | 'CONVERTED'

export interface QuickMemoReminderSlot {
  slot: number
  scheduledAt: string | null
  sentAt: string | null
}

export interface TagSummary {
  id: number
  name: string
  color: string | null
}

export interface AttachmentSummary {
  id: number
  s3Key: string
  originalFilename: string
  contentType: string
  fileSizeBytes: number
  widthPx: number | null
  heightPx: number | null
  sortOrder: number
}

export interface QuickMemoResponse {
  id: number
  title: string
  body: string | null
  status: QuickMemoStatus
  tags: TagSummary[]
  attachments: AttachmentSummary[]
  reminderUsesDefault: boolean
  reminders: QuickMemoReminderSlot[]
  createdAt: string
  updatedAt: string
}

export interface PagedQuickMemos {
  data: QuickMemoResponse[]
  meta: {
    page: number
    size: number
    total: number
    totalPages: number
    unsortedCount?: number
  }
}

export interface TagResponse {
  id: number
  scopeType: string
  scopeId: number
  name: string
  color: string | null
  usageCount: number
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface PagedTags {
  data: TagResponse[]
  meta: { page: number; size: number; total: number; totalPages: number }
}

export interface ReminderOffset {
  dayOffset: number
  time: string
}

export interface CreateQuickMemoRequest {
  title: string
  body?: string
  tagIds?: number[]
  reminderUsesDefault?: boolean
  reminders?: ReminderOffset[]
}

export interface UpdateQuickMemoRequest {
  title?: string
  body?: string | null
  tagIds?: number[]
  reminderUsesDefault?: boolean
  reminders?: ReminderOffset[]
}

export interface ConvertToTodoRequest {
  priority?: string
  dueDate?: string
  projectId?: number
}

export interface ConvertToTodoResponse {
  memoId: number
  todoId: number
  memoStatus: string
}

export interface PresignRequest {
  filename: string
  contentType: string
  sizeBytes: number
}

export interface PresignResponse {
  uploadId: string
  presignedUrl: string
  expiresAt: string
}

export interface CreateTagRequest {
  name: string
  color?: string
}

export interface UpdateTagRequest {
  name?: string
  color?: string | null
}

export interface UserQuickMemoSettingsResponse {
  userId: number
  reminderEnabled: boolean
  defaultOffset1Days: number | null
  defaultTime1: string | null
  defaultOffset2Days: number | null
  defaultTime2: string | null
  defaultOffset3Days: number | null
  defaultTime3: string | null
  createdAt: string
  updatedAt: string
}

export interface UpdateSettingsRequest {
  reminderEnabled?: boolean
  defaultOffset1Days?: number | null
  defaultTime1?: string | null
  defaultOffset2Days?: number | null
  defaultTime2?: string | null
  defaultOffset3Days?: number | null
  defaultTime3?: string | null
}

export interface VoiceInputConsentResponse {
  hasConsent: boolean
  version: number | null
  consentedAt: string | null
}

export interface VoiceInputConsentRequest {
  version: number
}
