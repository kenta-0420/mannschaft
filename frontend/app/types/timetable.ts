export type TimetableStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
export type TimetableVisibility = 'MEMBERS_ONLY' | 'PUBLIC'
export type ChangeType = 'REPLACE' | 'CANCEL' | 'ADD' | 'DAY_OFF'
export type WeekPatternType = 'EVERY' | 'A' | 'B'
export type DayOfWeekKey = 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN'

export interface TimetablePeriod {
  id?: number
  periodNumber: number
  label: string
  startTime: string
  endTime: string
  isBreak?: boolean
}

export interface TimetableTerm {
  id: number
  name: string
  startDate: string
  endDate: string
  academicYear?: number
  sortOrder?: number
  scopeType: 'TEAM' | 'ORGANIZATION'
}

export interface Timetable {
  id: number
  teamId: number
  termId: number
  termName: string | null
  name: string
  status: TimetableStatus
  visibility: TimetableVisibility
  effectiveFrom: string
  effectiveUntil: string | null
  weekPatternEnabled: boolean
  weekPatternBaseDate: string | null
  periodOverride: string | null
  notes: string | null
  createdAt: string
}

export interface TimetableSlot {
  id: number
  timetableId: number
  dayOfWeek: string
  periodNumber: number
  weekPattern: WeekPatternType
  subjectName: string
  teacherName: string | null
  roomName: string | null
  color: string | null
  notes: string | null
}

export interface TimetableChange {
  id: number
  timetableId: number
  targetDate: string
  periodNumber: number | null
  changeType: ChangeType
  subjectName: string | null
  teacherName: string | null
  roomName: string | null
  reason: string | null
  notifyMembers: boolean
  createdAt: string
}

/** 週間ビューの1コマ情報（臨時変更情報含む） */
export interface WeeklySlotInfo {
  periodNumber: number
  subjectName: string
  teacherName: string | null
  roomName: string | null
  color: string | null
  notes: string | null
  isChanged: boolean
  originalSubject: string | null
  changeType: ChangeType | null
  changeReason: string | null
}

/** 週間ビューの曜日ごとの情報 */
export interface WeeklyDayInfo {
  date: string
  isDayOff: boolean
  dayOffReason: string | null
  slots: WeeklySlotInfo[]
}

/** 週間ビューレスポンス（バックエンド WeeklyViewResponse に対応） */
export interface WeeklyView {
  timetableId: number
  timetableName: string
  weekStart: string
  weekEnd: string
  weekPatternEnabled: boolean
  currentWeekPattern: WeekPatternType
  periods: TimetablePeriod[]
  /** キー: MON/TUE/WED/THU/FRI/SAT/SUN */
  days: Record<DayOfWeekKey, WeeklyDayInfo>
}
