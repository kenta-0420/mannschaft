export type TimetableStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
export type ChangeType = 'REPLACE' | 'CANCEL' | 'ADD' | 'DAY_OFF'

export interface TimetablePeriod {
  id: number
  periodNumber: number
  label: string
  startTime: string
  endTime: string
}

export interface TimetableTerm {
  id: number
  name: string
  startDate: string
  endDate: string
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}

export interface Timetable {
  id: number
  teamId: number
  termId: number
  termName: string
  name: string
  status: TimetableStatus
  weekPatternEnabled: boolean
  createdAt: string
  updatedAt: string
}

export interface TimetableSlot {
  id: number
  timetableId: number
  dayOfWeek: number
  periodNumber: number
  subject: string
  teacher: string | null
  room: string | null
  weekPattern: 'EVERY' | 'A' | 'B' | null
  color: string | null
}

export interface TimetableChange {
  id: number
  timetableId: number
  changeDate: string
  changeType: ChangeType
  originalSlotId: number | null
  newSubject: string | null
  newTeacher: string | null
  newRoom: string | null
  reason: string | null
  createdAt: string
}

export interface WeeklyView {
  periods: TimetablePeriod[]
  days: {
    dayOfWeek: number
    date: string
    slots: (TimetableSlot & { change?: TimetableChange })[]
  }[]
}
