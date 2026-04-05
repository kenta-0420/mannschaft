export type EventCategoryColor = string

export interface EventCategory {
  id: number
  name: string
  color: EventCategoryColor
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  isInherited: boolean
}

export interface AnnualEvent {
  id: number
  title: string
  startDate: string
  endDate: string | null
  eventType: 'PRACTICE' | 'MATCH' | 'EVENT' | 'MEETING' | 'OTHER'
  categoryId: number | null
  categoryName: string | null
  categoryColor: string | null
  status: string
  termLabel: string | null
}

export interface AnnualViewMonth {
  month: string
  events: AnnualEvent[]
}

export interface CopyPreview {
  sourceYear: number
  targetYear: number
  events: { original: AnnualEvent; adjustedDate: string }[]
}
