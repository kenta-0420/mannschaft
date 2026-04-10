export type EventCategoryColor = string

export interface EventCategory {
  id: number
  name: string
  color: EventCategoryColor
  icon: string | null
  isDayOffCategory: boolean
  sortOrder: number | null
  scope: 'TEAM' | 'ORGANIZATION'
}

export interface AnnualEventCategory {
  id: number
  name: string
  color: EventCategoryColor
  icon: string | null
}

export interface AnnualEvent {
  id: number
  title: string
  startAt: string
  endAt: string | null
  allDay: boolean
  eventType: 'PRACTICE' | 'MATCH' | 'EVENT' | 'MEETING' | 'OTHER'
  eventCategory: AnnualEventCategory | null
  status: string
  sourceScheduleId: number | null
}

export interface AnnualViewMonth {
  month: string
  events: AnnualEvent[]
}

export interface AnnualEventViewResponse {
  academicYear: number
  yearStart: string
  yearEnd: string
  categories: EventCategory[]
  months: AnnualViewMonth[]
  totalEvents: number
}

export interface CopyConflict {
  type: string
  existingScheduleId: number
  existingTitle: string
}

export interface CopyPreviewItem {
  sourceScheduleId: number
  title: string
  sourceStartAt: string
  sourceEndAt: string | null
  suggestedStartAt: string
  suggestedEndAt: string | null
  dateShiftNote: string
  eventCategory: AnnualEventCategory | null
  allDay: boolean
  conflict: CopyConflict | null
}

export interface CopyPreview {
  sourceYear: number
  targetYear: number
  dateShiftMode: string
  items: CopyPreviewItem[]
  totalCopyable: number
  totalWithConflicts: number
}

export interface CopyExecuteItem {
  sourceScheduleId: number
  targetStartAt: string
  targetEndAt: string | null
  include: boolean
}

export interface CopyExecuteRequest {
  sourceYear: number
  targetYear: number
  dateShiftMode?: string
  items: CopyExecuteItem[]
}

export interface CopyExecuteResponse {
  copyLogId: number
  totalCopied: number
  totalSkipped: number
  createdScheduleIds: number[]
}

export interface CopyLog {
  id: number
  sourceAcademicYear: number
  targetAcademicYear: number
  totalCopied: number
  totalSkipped: number
  dateShiftMode: string
  executedBy: number | null
  createdAt: string
}
