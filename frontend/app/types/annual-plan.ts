export type EventCategoryColor = string

/** バックエンド EventCategoryResponse に対応 */
export interface EventCategory {
  id: number
  name: string
  color: EventCategoryColor
  icon: string | null
  isDayOffCategory: boolean | null
  sortOrder: number | null
  /** "TEAM" または "ORGANIZATION" */
  scope: 'TEAM' | 'ORGANIZATION'
}

/** バックエンド AnnualEventItem に対応 */
export interface AnnualEvent {
  id: number
  title: string
  startAt: string
  endAt: string | null
  allDay: boolean
  eventType: 'PRACTICE' | 'MATCH' | 'EVENT' | 'MEETING' | 'OTHER'
  eventCategory: EventCategory | null
  status: string
  sourceScheduleId: number | null
}

/** バックエンド MonthEvents に対応 */
export interface AnnualViewMonth {
  month: string
  events: AnnualEvent[]
}

/** バックエンド AnnualEventViewResponse に対応 */
export interface AnnualEventViewResponse {
  academicYear: number
  yearStart: string
  yearEnd: string
  categories: EventCategory[]
  months: AnnualViewMonth[]
  totalEvents: number
}

/** バックエンド CopyConflict に対応 */
export interface CopyConflict {
  type: string
  existingScheduleId: number
  existingTitle: string
}

/** バックエンド CopyPreviewItem に対応 */
export interface CopyPreviewItem {
  sourceScheduleId: number
  title: string
  sourceStartAt: string
  sourceEndAt: string | null
  suggestedStartAt: string
  suggestedEndAt: string | null
  dateShiftNote: string | null
  eventCategory: EventCategory | null
  allDay: boolean
  conflict: CopyConflict | null
}

/** バックエンド CopyPreviewResponse に対応 */
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

/** バックエンド ExecuteCopyRequest.CopyItem に対応 */
export interface ExecuteCopyItem {
  sourceScheduleId: number
  targetStartAt: string | null
  targetEndAt: string | null
  include: boolean
}
