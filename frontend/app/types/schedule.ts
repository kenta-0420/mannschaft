export type ScheduleScopeType = 'TEAM' | 'ORGANIZATION'
export type ScheduleStatus = 'DRAFT' | 'PUBLISHED' | 'CANCELLED'
export type AttendanceStatus = 'YES' | 'NO' | 'MAYBE' | 'PENDING'
export type RecurrenceType = 'NONE' | 'DAILY' | 'WEEKLY' | 'MONTHLY'
export type EditScope = 'THIS_ONLY' | 'THIS_AND_FOLLOWING' | 'ALL'

export interface ScheduleResponse {
  id: number
  scopeType: ScheduleScopeType
  scopeId: number
  title: string
  description: string | null
  location: string | null
  startAt: string
  endAt: string
  allDay: boolean
  status: ScheduleStatus
  recurrenceType: RecurrenceType
  recurrenceRule: string | null
  recurrenceGroupId: number | null
  categoryId: number | null
  categoryName: string | null
  categoryColor: string | null
  responseDeadline: string | null
  createdBy: { id: number; displayName: string }
  attendanceStats: {
    yes: number
    no: number
    maybe: number
    pending: number
    total: number
  } | null
  myAttendance: AttendanceStatus | null
  createdAt: string
  updatedAt: string
}

export interface PersonalScheduleResponse {
  id: number
  title: string
  description: string | null
  location: string | null
  startAt: string
  endAt: string
  allDay: boolean
  color: string | null
  reminders: number[]
  createdAt: string
  updatedAt: string
}

export interface CreateScheduleRequest {
  title: string
  description?: string
  location?: string
  startAt: string
  endAt: string
  allDay?: boolean
  categoryId?: number
  recurrenceType?: RecurrenceType
  recurrenceRule?: string
  responseDeadline?: string
}

export interface UpdateScheduleRequest {
  title?: string
  description?: string
  location?: string
  startAt?: string
  endAt?: string
  allDay?: boolean
  categoryId?: number
  responseDeadline?: string
  editScope?: EditScope
}

export interface CreatePersonalScheduleRequest {
  title: string
  description?: string
  location?: string
  startAt: string
  endAt: string
  allDay?: boolean
  color?: string
  reminders?: number[]
}

export interface AttendanceResponse {
  userId: number
  displayName: string
  avatarUrl: string | null
  status: AttendanceStatus
  comment: string | null
  respondedAt: string | null
}

export interface RespondAttendanceRequest {
  status: AttendanceStatus
  comment?: string
}

export interface EventCategoryResponse {
  id: number
  name: string
  color: string
  sortOrder: number
}

export interface CalendarMonthView {
  year: number
  month: number
  events: Array<{
    id: number
    title: string
    startAt: string
    endAt: string
    allDay: boolean
    color: string | null
    scopeType: string
    isPersonal: boolean
  }>
}

// === Annual Schedule ===
export interface AnnualScheduleParams {
  academicYear?: number
  categoryId?: number
  eventType?: string
  termStartDate?: string
  termEndDate?: string
}

export interface AnnualCopyPreviewParams {
  sourceYear: number
  targetYear: number
  dateShiftMode?: string
  categoryId?: number
}

export interface ExecuteCopyRequest {
  sourceYear: number
  targetYear: number
  dateShiftMode?: string
  items?: Array<{ id: number; targetDate?: string }>
}

// === Bulk Attendance ===
export interface BulkAttendanceItem {
  userId: number
  status: AttendanceStatus
  comment?: string
}

export interface BulkAttendanceRequest {
  attendances: BulkAttendanceItem[]
}

// === Cross Invite ===
export interface CrossInviteRequest {
  targetType: string
  targetId: number
  message?: string
}

// === Performance ===
export interface PerformanceRecordEntry {
  userId: number
  value: string | number
  note?: string
}

export interface ScheduleBulkRecordRequest {
  template?: string
  entries?: PerformanceRecordEntry[]
}

// === Schedule Invitation ===
export interface ScheduleInvitationResponse {
  id: number
  scheduleId: number
  scheduleTitle: string
  inviterTeamId: number
  inviterTeamName: string
  message: string | null
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CONFIRMED'
  createdAt: string
}

// === Schedule Stats ===
export interface ScheduleStatsResponse {
  scheduleId: number
  totalInvited: number
  responded: number
  yes: number
  no: number
  maybe: number
  pending: number
}
